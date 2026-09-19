"""Proves no static initialiser can execute an ARMv8.2-only instruction.

ggml and whisper are compiled with `-march=armv8.2-a+fp16+dotprod` (ARC-070), so the
compiler may emit `udot`, `sdot` and fp16 arithmetic anywhere in those objects. A CPU
without those extensions dies on SIGILL rather than failing a check.

`OnDeviceTranscriber`'s guard reads HWCAP and refuses before any kernel runs, and that is
only safe while nothing ARMv8.2-only runs *before* the guard. Static initialisers do: the
dynamic linker walks `.init_array` during `System.loadLibrary`, which is long before any
Kotlin code can ask about the CPU. One `_GLOBAL__sub_I_*` that touches a vectorised path
would kill the process at load with the guard never reached, and no test would see it
because every machine this is built and tested on has the extensions.

So: disassemble the library, walk the call graph from the real `.init_array` entries, and
assert that nothing reachable contains one of those instructions.

Limits, which are the reason this prints its numbers rather than just passing:
  - Direct `bl` edges only. An indirect call through a function pointer is invisible here,
    so the reachable set is a lower bound. Reachable functions containing `blr`/`br` are
    counted and named for exactly that reason.
  - Reachability is not execution: a function can be reachable and never called.
  - This audits one built artefact, not the source. It is only true of the .so it read.
"""

import collections
import re
import subprocess
import sys

# `fmla v0.8h` and friends: any FP mnemonic on a half-precision vector arrangement.
FP16_VECTOR = re.compile(r"\tf[a-z0-9]+\t.*\.(8h|4h)")
# `fcvt h0, s0`: half-precision scalar registers.
FP16_SCALAR = re.compile(r"\tf[a-z0-9]+\t+h[0-9]")
DOT_PRODUCT = re.compile(r"\t(udot|sdot)\t")
DIRECT_CALL = re.compile(r"\tbl\t\S+\s+<([^>]+)>")
INDIRECT_CALL = re.compile(r"\t(blr|br)\t")
FUNCTION_HEADER = re.compile(r"^([0-9a-f]+) <(.+)>:")
# readelf -r, no symbol name: offset, info, type, then the addend that is the address.
RELATIVE_RELOCATION = re.compile(r"^([0-9a-f]+)\s+\S+\s+R_AARCH64_RELATIVE\s+([0-9a-f]+)")
INIT_ARRAY_SECTION = re.compile(r"\.init_array\s+INIT_ARRAY\s+([0-9a-f]+)\s+\S+\s+([0-9a-f]+)")


def run(tool, *args):
    out = subprocess.run([tool, *args], capture_output=True, text=True, errors="replace")
    if out.returncode != 0:
        sys.exit(f"{tool} failed: {out.stderr.strip()[:400]}")
    return out.stdout


def init_array_targets(readelf, lib):
    """The addresses the dynamic linker will call, read off the ELF rather than guessed.

    Hardcoding `_GLOBAL__sub_I_whisper.cpp` and friends would pass forever after a
    whisper.cpp bump introduced a fifth initialiser under a new name, which is precisely
    the change this is meant to catch.
    """
    sections = run(readelf, "-S", lib)
    found = INIT_ARRAY_SECTION.search(sections)
    if not found:
        # No initialisers at all is a pass, but a silent one would hide a broken parse.
        sys.exit("could not find a .init_array section header; parsing of readelf -S has drifted")
    start = int(found.group(1), 16)
    end = start + int(found.group(2), 16)

    targets = []
    for line in run(readelf, "-r", lib).splitlines():
        match = RELATIVE_RELOCATION.match(line.strip())
        if not match:
            continue
        slot = int(match.group(1), 16)
        if start <= slot < end:
            targets.append(int(match.group(2), 16))
    if not targets:
        sys.exit(f".init_array spans {end - start} bytes but no relocations fill it; parse is wrong")
    return targets


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: native_init_audit.py <llvm-readelf> <llvm-objdump> <library.so>")
    readelf, objdump, lib = sys.argv[1:]

    targets = init_array_targets(readelf, lib)

    names_by_address = {}
    calls = collections.defaultdict(set)
    indirect = collections.Counter()
    uses_armv82 = set()
    total = 0

    current = None
    for line in run(objdump, "-d", lib).splitlines(keepends=True):
        header = FUNCTION_HEADER.match(line)
        if header:
            current = header.group(2)
            names_by_address[int(header.group(1), 16)] = current
            total += 1
            continue
        if current is None:
            continue
        if DOT_PRODUCT.search(line) or FP16_VECTOR.search(line) or FP16_SCALAR.search(line):
            uses_armv82.add(current)
        call = DIRECT_CALL.search(line)
        if call:
            calls[current].add(call.group(1).split("@")[0])
        if INDIRECT_CALL.search(line):
            indirect[current] += 1

    roots = []
    for address in targets:
        name = names_by_address.get(address)
        if name is None:
            sys.exit(f".init_array entry {address:#x} matches no disassembled function")
        roots.append(name)

    reachable, queue = set(), list(roots)
    while queue:
        function = queue.pop()
        if function in reachable:
            continue
        reachable.add(function)
        queue.extend(calls.get(function, ()))

    print(f"   .init_array entries: {len(roots)}")
    for root in roots:
        print(f"     root: {root}")
    print(f"   functions disassembled: {total}")
    print(f"   functions using ARMv8.2-only instructions: {len(uses_armv82)}")
    print(f"   functions reachable from .init_array (direct calls only): {len(reachable)}")

    with_indirect = sorted(f for f in reachable if indirect[f])
    print(f"   reachable functions containing indirect branches: {len(with_indirect)}")
    for function in with_indirect:
        print(f"     ? {function} ({indirect[function]})")

    hits = sorted(uses_armv82 & reachable)
    if hits:
        print(f"   REACHABLE AND ARMv8.2-ONLY: {len(hits)}")
        for hit in hits:
            print(f"     !! {hit}")
        sys.exit(
            "a static initialiser can reach an ARMv8.2-only instruction: the CPU guard in "
            "OnDeviceTranscriber runs too late to stop it, and an ARMv8.0 device will SIGILL "
            "inside System.loadLibrary. See ARC-070."
        )
    print("   no static initialiser reaches an ARMv8.2-only instruction")


if __name__ == "__main__":
    main()
