#!/usr/bin/env bash
# Guards the guard: proves no static initialiser in the native library can execute an
# instruction an ARMv8.0 CPU lacks. See native_init_audit.py for why that matters and what
# the audit can and cannot see.
#
# Separate from check.sh because it needs the NDK's llvm tools, which a machine can be
# missing while still building the app perfectly well. check.sh calls it when it can and
# says so when it cannot, rather than failing the build gate over a toolchain path.
set -euo pipefail
# shellcheck source=_gradle.sh
. "$(dirname "$0")/_gradle.sh"
cd "$(dirname "$0")/../.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  echo "== native-init: SKIPPED (no ANDROID_HOME/ANDROID_SDK_ROOT) =="
  exit 0
fi

# The NDK the build actually used is not recorded anywhere readable, so take the highest
# installed version. A mismatch would disassemble the same ELF with a different objdump,
# which changes nothing this script reads.
NDK_DIR="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$NDK_DIR" ]; then
  echo "== native-init: SKIPPED (no NDK under $SDK/ndk) =="
  exit 0
fi

TOOLS="$(ls -d "$NDK_DIR"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1 || true)"
READELF="$TOOLS/llvm-readelf"
OBJDUMP="$TOOLS/llvm-objdump"
[ -x "$READELF" ] || READELF="$READELF.exe"
[ -x "$OBJDUMP" ] || OBJDUMP="$OBJDUMP.exe"
if [ ! -x "$READELF" ] || [ ! -x "$OBJDUMP" ]; then
  echo "== native-init: SKIPPED (no llvm-readelf/llvm-objdump under $NDK_DIR) =="
  exit 0
fi

# The unstripped object, not the packaged one: the audit walks a call graph, and the
# library shipped in the APK has had the symbol names it needs stripped out.
echo "== native-init: locating the unstripped arm64 library =="
find_lib() {
  find src/Harken.Android/app/build/intermediates/cxx -path '*arm64-v8a*' \
    -name 'libharken_whisper_jni.so' 2>/dev/null | sort | tail -1
}
LIB="$(find_lib)"
if [ -z "$LIB" ]; then
  echo "== native-init: no native build found, building it =="
  (cd src/Harken.Android && "$GRADLEW" assembleRelease)
  LIB="$(find_lib)"
fi
if [ -z "$LIB" ]; then
  echo "native-init: no libharken_whisper_jni.so after a release build" >&2
  exit 1
fi
echo "   $LIB"

echo "== native-init: walking .init_array =="
python .claude/scripts/native_init_audit.py "$READELF" "$OBJDUMP" "$LIB"

echo "== native-init: OK =="
