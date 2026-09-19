#!/usr/bin/env bash
# Guards the guard: proves no static initialiser in the native library can execute an
# instruction an ARMv8.0 CPU lacks. See native_init_audit.py for why that matters and what
# the audit can and cannot see.
#
# A missing SDK, NDK or llvm toolchain fails here rather than skipping. This app has native
# code (externalNativeBuild in app/build.gradle.kts, arm64-v8a only), so a machine missing
# any of those cannot produce an APK at all: check.sh dies in assembleDebug twenty lines
# before it reaches this script, which is exactly what happened on 2026-09-18 when the SDK
# was deleted out from under it. Anything that reaches here therefore has the toolchain, and
# "cannot find it" means this script's own path resolution is wrong. That has to fail loudly:
# a gate that green-lights itself when it cannot run is not a gate.
set -euo pipefail
# shellcheck source=_gradle.sh
. "$(dirname "$0")/_gradle.sh"
cd "$(dirname "$0")/../.."

# ANDROID_HOME/ANDROID_SDK_ROOT is the whole of SDK resolution in this repo: local.properties
# is deliberately absent (src/Harken.Android/.gitignore, ARC-020), so if Gradle found an SDK
# then one of these is set.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  echo "native-init: no ANDROID_HOME/ANDROID_SDK_ROOT, yet the native build needs one" >&2
  exit 1
fi

# The NDK the build actually used is not recorded anywhere readable, so take the highest
# installed version. A mismatch would disassemble the same ELF with a different objdump,
# which changes nothing this script reads.
NDK_DIR="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$NDK_DIR" ]; then
  echo "native-init: no NDK under $SDK/ndk, yet the library was just built with one" >&2
  exit 1
fi

TOOLS="$(ls -d "$NDK_DIR"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1 || true)"
READELF="$TOOLS/llvm-readelf"
OBJDUMP="$TOOLS/llvm-objdump"
[ -x "$READELF" ] || READELF="$READELF.exe"
[ -x "$OBJDUMP" ] || OBJDUMP="$OBJDUMP.exe"
if [ ! -x "$READELF" ] || [ ! -x "$OBJDUMP" ]; then
  echo "native-init: no llvm-readelf/llvm-objdump under $NDK_DIR" >&2
  exit 1
fi

# The unstripped object, not the packaged one: the audit walks a call graph, and the
# library shipped in the APK has had the symbol names it needs stripped out.
#
# RelWithDebInfo by name, because a Debug library sits under intermediates/cxx too and
# taking whichever path sorted last picked the right one only because "R" follows "D".
# Auditing the debug object would print OK about something other than what ships. The two
# carry identical flags today -- CMakeLists.txt sets HARKEN_KERNEL_OPTIONS unconditionally,
# not per variant -- so this is the claim being true on purpose rather than by alphabet, and
# it stays true if those flags ever do become variant-conditional.
echo "== native-init: locating the unstripped arm64 library =="
find_lib() {
  find src/Harken.Android/app/build/intermediates/cxx -path '*RelWithDebInfo*' \
    -path '*arm64-v8a*' -name 'libharken_whisper_jni.so' 2>/dev/null | sort | tail -1
}
LIB="$(find_lib)"
if [ -z "$LIB" ]; then
  echo "== native-init: no native build found, building it =="
  (cd src/Harken.Android && "$GRADLEW" assembleRelease)
  LIB="$(find_lib)"
fi
if [ -z "$LIB" ]; then
  echo "native-init: no RelWithDebInfo libharken_whisper_jni.so after a release build" >&2
  exit 1
fi
echo "   $LIB"

echo "== native-init: walking .init_array =="
python .claude/scripts/native_init_audit.py "$READELF" "$OBJDUMP" "$LIB"

echo "== native-init: OK =="
