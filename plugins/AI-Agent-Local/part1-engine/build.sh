#!/bin/bash
# build.sh: build the Part 1 engine for arm64-v8a with the installed Android NDK (ADFA-6876 job P1-03).
# usage: ./build.sh [OUTDIR]        (default OUTDIR = out/arm64-v8a)
# output: OUTDIR/libpart1_membw.so, OUTDIR/libpart1_gpubw.so and the compiler logs OUTDIR/logs/build-membw.log, OUTDIR/logs/build-gpubw.log (nothing is written outside OUTDIR; raw/ is not touched)
# environment: NDK=/path/to/ndk (default: newest under $ANDROID_NDK_HOME, $ANDROID_HOME/ndk, $ANDROID_SDK_ROOT/ndk, ~/Android/Sdk/ndk, /opt/android-sdk/ndk)
#              MEMBW_MARCH (default armv8-a; the kit-arm64 set used armv8.2-a+fp16, see DIFF-FROM-KIT.md)
#              WARN (default "-Wall -Wextra -Wformat=2 -Wshadow"): warning flags
# Exit codes: 0 built; 2 no NDK found; 3 compiler missing in the NDK; 4 a compile or link step failed (build.sh runs no ELF check: tools/elf-facts.sh does).
set -u
HERE=$(cd "$(dirname "$0")" && pwd); OUT=${1:-$HERE/out/arm64-v8a}; API=33
VERSION=$(tr -d '[:space:]' < "$HERE/VERSION")
find_ndk() {
  if [ -n "${NDK:-}" ]; then echo "$NDK"; return; fi
  local d c
  for d in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do [ -n "$d" ] && [ -f "$d/source.properties" ] && { echo "$d"; return; }; done
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk; do
    [ -n "$d" ] || continue; c=$(ls -d "$d"/ndk/*/ 2>/dev/null | sort -V | tail -1); [ -n "$c" ] && { echo "${c%/}"; return; }
  done
}
NDK=$(find_ndk)
if [ -z "$NDK" ] || [ ! -f "$NDK/source.properties" ]; then
  echo "build.sh: no Android NDK is installed (looked at NDK, ANDROID_NDK_HOME, ANDROID_HOME/ndk, ~/Android/Sdk/ndk, /opt/android-sdk/ndk). Install one (r26 or later) and run again. Nothing was built." >&2; exit 2
fi
NDKVER=$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties")
echo "NDK: $NDK  version $NDKVER  (API level $API, arm64-v8a only, engine part1-engine/$VERSION)"
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin; CC=$TC/aarch64-linux-android$API-clang
[ -x "$CC" ] || { echo "build.sh: compiler not found: $CC" >&2; exit 3; }
"$CC" --version | head -1
WARN=${WARN:-"-Wall -Wextra -Wformat=2 -Wshadow"}; MARCH=${MEMBW_MARCH:-armv8-a}
# 16 KB page alignment of the load segments (Android 15+ 16 KB devices); position-independent executable; no dependency beyond libc, libm, libdl (+ libvulkan for gpubw)
COMMON="-O2 -fPIE -pie -DP1_ENGINE_VERSION=\"$VERSION\" -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=sha1 $WARN"
mkdir -p "$OUT/logs" && OUT=$(cd "$OUT" && pwd); rc=0
echo "== libpart1_membw.so"
( cd "$HERE/src" && "$CC" $COMMON -march=$MARCH -fno-tree-vectorize -o "$OUT/libpart1_membw.so" membw.c -lm ) 2>&1 | tee "$OUT/logs/build-membw.log"; [ ${PIPESTATUS[0]} -eq 0 ] || rc=4
echo "== libpart1_gpubw.so"
( cd "$HERE/src/gpubw" && "$CC" $COMMON -o "$OUT/libpart1_gpubw.so" gpubw.c -lvulkan -lm ) 2>&1 | tee "$OUT/logs/build-gpubw.log"; [ ${PIPESTATUS[0]} -eq 0 ] || rc=4
[ $rc -eq 0 ] && { chmod 0755 "$OUT"/libpart1_*.so; ls -l "$OUT"; sha256sum "$OUT"/libpart1_*.so; echo "built $OUT"; } || echo "build.sh: compile failed" >&2
exit $rc
