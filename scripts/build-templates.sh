#!/usr/bin/env bash
#
# Builds template addons into .cgt bundles. Plugins are built by
# scripts/build-plugins.sh; each script builds only its own kind (ADFA-6252).
# A template needs no CodeOnTheGo jars and no Gradle.
#
# Usage:
#   ./scripts/build-templates.sh                         # every template, into dist/<slug>.cgt
#   ./scripts/build-templates.sh Flutter-Starter-Kit       # only these (directory name or path, any case)
#
# A name that is a plugin is ignored, so one name can go to both build scripts.
# An unknown name fails. With no name, every template is built, including ones
# skip.txt holds out of the gallery.
#
# Bash 3.2 compatible on purpose: macOS ships it.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Fixed: verify-provenance.sh looks for the bundle in dist/.
OUT="$REPO_ROOT/dist"
NAMES=()

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help)
            sed -n '2,15p' "$0"
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

list="$(uv run --directory "$REPO_ROOT/tools/addons" addons --root "$REPO_ROOT" \
        discover --include-skipped --kind template ${NAMES[@]+"${NAMES[@]}"})"
if [ -z "$list" ]; then
    echo "No template to build."
    exit 0
fi

for dir in $list; do
    echo ""
    echo "→ $dir"
    "$REPO_ROOT/scripts/build-cgt.sh" "$REPO_ROOT/$dir" "$OUT"
    "$REPO_ROOT/scripts/verify-provenance.sh" "$dir"
done
echo ""
echo "All templates built successfully."
