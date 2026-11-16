#!/usr/bin/env bash
#
# ADFA-6810 measurement aid. Builds evict_cache.c for arm64 with the NDK, pushes it to the
# connected device, and runs it against the given file to force the next model load to be cold.
#
# Not part of the plugin: this never runs as part of assemblePlugin and nothing here reaches the
# .cgp. It exists purely so Phase 1/2 of ADFA-6810's measurement plan can repeat a cold-cache
# model load without root or a reboot between runs.
#
# Usage:
#   scripts/evict-page-cache.sh <device-path-to-model-file> [run-as-package]
#
#   <device-path-to-model-file>  path on the device, e.g. /sdcard/Download/model.gguf or
#                                 /data/user/0/com.itsaky.androidide/files/home/model.gguf
#   [run-as-package]             when the file sits under an app's private storage, the plain
#                                 shell user can't open it -- pass the app id (e.g.
#                                 com.itsaky.androidide) to run the evictor via `run-as`. Omit it
#                                 for a file under /sdcard, which the shell can read directly.
#
# Requirements: ANDROID_HOME points at an SDK with an installed NDK; a connected device.

set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_HOME:?set ANDROID_HOME (see addons CLAUDE.md / plugins/README.md)}"

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <device-path-to-model-file> [run-as-package]" >&2
  exit 2
fi

MODEL_PATH="$1"
RUN_AS_PKG="${2:-}"

NDK_DIR=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)
if [[ -z "$NDK_DIR" ]]; then
  echo "no NDK found under $ANDROID_HOME/ndk" >&2
  exit 1
fi
CLANG="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"

BIN_LOCAL="$(mktemp -t evict_cache.XXXXXX)"
trap 'rm -f "$BIN_LOCAL"' EXIT

echo "==> Building evict_cache for arm64-v8a ($NDK_DIR)"
"$CLANG" -O2 -Wall -static -o "$BIN_LOCAL" evict_cache.c

DEVICE_BIN=/data/local/tmp/evict_cache
echo "==> Pushing to $DEVICE_BIN"
adb push "$BIN_LOCAL" "$DEVICE_BIN" >/dev/null
adb shell chmod 755 "$DEVICE_BIN"

if [[ -n "$RUN_AS_PKG" ]]; then
  # SELinux denies `runas_app` execute on shell_data_file (/data/local/tmp), so a binary pushed
  # there can be read as the app's uid but not run directly as it -- copy it into the app's own
  # storage first, which relabels it app_data_file, then exec that copy.
  echo "==> Copying into $RUN_AS_PKG's storage (direct exec from /data/local/tmp is denied by SELinux)"
  adb shell "run-as $RUN_AS_PKG sh -c 'cat $DEVICE_BIN > files/evict_cache && chmod 755 files/evict_cache'"
  echo "==> Running as $RUN_AS_PKG against $MODEL_PATH"
  # adb joins its arguments into one remote command line, so the path is quoted for the device shell.
  adb shell "run-as $RUN_AS_PKG files/evict_cache '$MODEL_PATH'"
else
  echo "==> Running against $MODEL_PATH"
  adb shell "$DEVICE_BIN '$MODEL_PATH'"
fi
