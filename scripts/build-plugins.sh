#!/usr/bin/env bash
#
# Builds plugin addons into .cgp files. Templates are built by
# scripts/build-templates.sh; each script builds only its own kind (ADFA-6252).
#
# Usage:
#   ./scripts/build-plugins.sh                           # every plugin, against the committed libs/
#   ./scripts/build-plugins.sh Random-XKCD               # only these (directory name or path, any case)
#   ./scripts/build-plugins.sh --ref stage               # refresh libs/ from CodeOnTheGo@stage first
#   ./scripts/build-plugins.sh --local ../CodeOnTheGo    # refresh libs/ from a local checkout first
#   ./scripts/build-plugins.sh --out dist                # also copy each release .cgp to dist/<slug>.cgp
#
# --ref and --local go to scripts/update-libs.sh. Without either, the jars already
# in libs/ are used and libs_revision comes from PLUGIN_LIBS_REVISION if the caller
# exported it (Publish addons does).
#
# A name that is a template is ignored, so one name can go to both build scripts.
# An unknown name fails. With no name, every plugin is built, including ones
# skip.txt holds out of the gallery: a libs refresh must prove they still compile.
#
# Bash 3.2 compatible on purpose: macOS ships it. No mapfile.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_ARGS=()
OUT=""
NAMES=()

while [ $# -gt 0 ]; do
    case "$1" in
        --ref|--local)
            [ -n "${2:-}" ] || { echo "Error: $1 requires an argument." >&2; exit 1; }
            LIBS_ARGS+=("$1" "$2")
            shift 2
            ;;
        --out)
            [ -n "${2:-}" ] || { echo "Error: --out requires a directory." >&2; exit 1; }
            OUT="$2"
            shift 2
            ;;
        -h|--help)
            sed -n '2,21p' "$0"
            exit 0
            ;;
        -*)
            echo "Error: unknown argument: $1" >&2
            exit 1
            ;;
        *)
            NAMES+=("$1")
            shift
            ;;
    esac
done

# Resolve before refreshing libs/, so a run that names only templates does not
# clone and build CodeOnTheGo for nothing. ${NAMES[@]+...} because bash 3.2
# treats an empty array as unset under set -u.
list="$(uv run --directory "$REPO_ROOT/tools/addons" addons --root "$REPO_ROOT" \
        discover --include-skipped --kind plugin ${NAMES[@]+"${NAMES[@]}"})"
if [ -z "$list" ]; then
    if [ "${#NAMES[@]}" -gt 0 ]; then
        echo "No plugin among: ${NAMES[*]}. Nothing to build."
        exit 0
    fi
    echo "Error: 'addons discover' found no plugins. Check that uv works and that tools/addons/skip.txt does not exclude everything." >&2
    exit 1
fi

if [ "${#LIBS_ARGS[@]}" -gt 0 ]; then
    "$REPO_ROOT/scripts/update-libs.sh" "${LIBS_ARGS[@]}"
    if [ -s "$REPO_ROOT/.cache/libs-revision" ]; then
        PLUGIN_LIBS_REVISION="$(cat "$REPO_ROOT/.cache/libs-revision")"
        export PLUGIN_LIBS_REVISION
    else
        unset PLUGIN_LIBS_REVISION
    fi
fi

[ -z "$OUT" ] || mkdir -p "$OUT"
for plugin in $list; do
    echo ""
    echo "→ $plugin"
    (
        cd "$REPO_ROOT/$plugin"
        # Newer plugins drop their per-plugin wrapper and use the repo-root
        # gradlew; fall back to it when no local gradlew exists.
        gradlew="./gradlew"
        [ -x "$gradlew" ] || gradlew="$REPO_ROOT/gradlew"
        # Two invocations, not one: downloadAssets declares an output inside
        # src/main/assets, which Gradle rejects as an undeclared dependency of
        # mergeReleaseAssets when both run together.
        if grep -q 'downloadAssets' build.gradle.kts; then
            "$gradlew" --console=plain downloadAssets
        fi
        "$gradlew" --console=plain assemblePlugin
    )
    "$REPO_ROOT/scripts/verify-provenance.sh" "$plugin"
    if [ -n "$OUT" ]; then
        src=""
        for f in "$REPO_ROOT/$plugin"/build/plugin/*.cgp; do
            case "$f" in *-debug.cgp) ;; *) [ -f "$f" ] && src="$f" && break ;; esac
        done
        [ -n "$src" ] || { echo "Error: no release .cgp under $plugin/build/plugin/" >&2; exit 1; }
        slug="$(basename "$plugin" | tr '[:upper:]' '[:lower:]')"
        cp "$src" "$OUT/$slug.cgp"
    fi
done
echo ""
echo "All plugins built successfully."
