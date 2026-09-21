#!/usr/bin/env bash
#
# Regenerate the prebuilt llama.cpp AAR consumed by the ai-agent-local plugin.
#
# You only need this after bumping the llama.cpp submodule (i.e. when your fork
# is updated). A normal plugin build does NOT use this script — it consumes the
# committed AAR directly.
#
# Requirements: the llama.cpp submodule and an Android NDK/CMake toolchain
# (ANDROID_HOME / sdk.dir configured, NDK installed).

set -euo pipefail

# Run from the plugin's project root regardless of where it's invoked. The
# wrapper lives at the repo root, two levels up, so gradle is ../../gradlew.
cd "$(dirname "$0")/.."

# Destinations must match the paths build.gradle.kts declares as dependencies.
AAR_DST="libs/v8/llama-v8-release.aar"
AAR_SRC="llama-impl/build/outputs/aar/llama-impl-release.aar"
API_DST="libs/llama-api.jar"
API_SRC="llama-api/build/libs/llama-api.jar"

echo "==> Initializing the llama.cpp submodule (source for the native build)"
git submodule update --init --recursive

echo "==> Building :llama-impl (native lib) and :llama-api (interface jar)"
../../gradlew :llama-impl:assembleRelease :llama-api:jar

# Fail loudly rather than copying a stale artifact from a previous run.
for src in "$AAR_SRC" "$API_SRC"; do
	if [ ! -f "$src" ]; then
		echo "error: expected build output is missing: $src" >&2
		exit 1
	fi
done

echo "==> Copying artifacts into libs/"
mkdir -p "$(dirname "$AAR_DST")" "$(dirname "$API_DST")"
cp "$AAR_SRC" "$AAR_DST"
cp "$API_SRC" "$API_DST"

REV="$(git -C subprojects/llama.cpp rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "==> Done. Regenerated from llama.cpp @ $REV"
echo "    Review and commit: $AAR_DST, $API_DST"
