#!/usr/bin/env bash
#
# Rebuilds plugin-api.jar and gradle-plugin.jar from the CodeOnTheGo repo
# and refreshes this repo's libs/ folder. It builds no addon: to refresh the
# jars and then build plugins against them, run scripts/build-plugins.sh with
# --ref or --local, which calls this script first. Templates never need it.
#
# Usage:
#   ./scripts/update-libs.sh                      # clone/pull github.com/appdevforall/CodeOnTheGo into .cache/, build from stage
#   ./scripts/update-libs.sh --ref main           # build from a different branch or tag
#   ./scripts/update-libs.sh --local ../CodeOnTheGo  # use an existing local checkout instead of cloning
#
set -euo pipefail

REPO_URL="https://github.com/appdevforall/CodeOnTheGo.git"
DEFAULT_REF="stage"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$REPO_ROOT/libs"
CACHE_DIR="$REPO_ROOT/.cache/CodeOnTheGo"

LOCAL_PATH=""
REF="$DEFAULT_REF"

while [ $# -gt 0 ]; do
    case "$1" in
        --local)
            LOCAL_PATH="${2:-}"
            if [ -z "$LOCAL_PATH" ]; then
                echo "Error: --local requires a path argument." >&2
                exit 1
            fi
            shift 2
            ;;
        --ref)
            REF="${2:-}"
            if [ -z "$REF" ]; then
                echo "Error: --ref requires a branch or tag argument." >&2
                exit 1
            fi
            shift 2
            ;;
        -h|--help)
            sed -n '2,11p' "$0"
            exit 0
            ;;
        *)
            echo "Error: unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [ -n "$LOCAL_PATH" ]; then
    if [ ! -d "$LOCAL_PATH" ]; then
        echo "Error: --local path does not exist: $LOCAL_PATH" >&2
        exit 1
    fi
    CODEONTHEGO_PATH="$(cd "$LOCAL_PATH" && pwd)"
    echo "Using local CodeOnTheGo checkout: $CODEONTHEGO_PATH"
else
    mkdir -p "$(dirname "$CACHE_DIR")"
    if [ -d "$CACHE_DIR/.git" ]; then
        echo "Updating cached CodeOnTheGo checkout at $CACHE_DIR..."
        git -C "$CACHE_DIR" fetch --prune origin
    else
        echo "Cloning $REPO_URL into $CACHE_DIR (first run — this may take a while)..."
        git clone --filter=blob:none "$REPO_URL" "$CACHE_DIR"
    fi
    echo "Checking out ref: $REF"
    git -C "$CACHE_DIR" checkout "$REF"
    git -C "$CACHE_DIR" reset --hard "origin/$REF" 2>/dev/null || true
    CODEONTHEGO_PATH="$CACHE_DIR"
fi

if [ ! -x "$CODEONTHEGO_PATH/gradlew" ]; then
    echo "Error: $CODEONTHEGO_PATH does not contain an executable gradlew." >&2
    exit 1
fi

echo "Building plugin-api jar in $CODEONTHEGO_PATH..."
(cd "$CODEONTHEGO_PATH" && ./gradlew --console=plain :plugin-api:createPluginApiJar)

# plugin-builder lives in its own self-contained Gradle build under plugin-api/plugin-builder/.
# Despite the file ultimately landing in libs/ as gradle-plugin.jar, it is the plugin-builder
# module's output — the Gradle plugin (id: com.itsaky.androidide.plugins.build) that each
# example plugin applies. The CodeOnTheGo gradle-plugin/ module is unrelated.
echo "Building plugin-builder jar in $CODEONTHEGO_PATH/plugin-api/plugin-builder..."
"$CODEONTHEGO_PATH/gradlew" -p "$CODEONTHEGO_PATH/plugin-api/plugin-builder" --console=plain jar

PLUGIN_API_SRC="$(ls "$CODEONTHEGO_PATH"/plugin-api/build/libs/plugin-api-*.jar 2>/dev/null | head -n1 || true)"
PLUGIN_BUILDER_SRC="$(ls "$CODEONTHEGO_PATH"/plugin-api/plugin-builder/build/libs/plugin-builder-*.jar 2>/dev/null | head -n1 || true)"

if [ -z "$PLUGIN_API_SRC" ] || [ ! -f "$PLUGIN_API_SRC" ]; then
    echo "Error: expected plugin-api jar not found under $CODEONTHEGO_PATH/plugin-api/build/libs/" >&2
    exit 1
fi
if [ -z "$PLUGIN_BUILDER_SRC" ] || [ ! -f "$PLUGIN_BUILDER_SRC" ]; then
    echo "Error: expected plugin-builder jar not found under $CODEONTHEGO_PATH/plugin-api/plugin-builder/build/libs/" >&2
    exit 1
fi

mkdir -p "$LIBS_DIR"
cp "$PLUGIN_API_SRC"     "$LIBS_DIR/plugin-api.jar"
cp "$PLUGIN_BUILDER_SRC" "$LIBS_DIR/gradle-plugin.jar"

# libs/ holds five jars but this script rebuilds only two of them. The other
# three (common, eventbus-events, idetooltips) are NOT produced by the two
# Gradle tasks above, and their true source in CodeOnTheGo is not known here.
# Do not guess: a name search finds composite-builds/build-logic/common.jar,
# which is a different 21 KB artifact, and copying it over the IDE's 355 KB
# common.jar would break every plugin that uses it. Report the gap instead,
# so stale jars are visible rather than silent. These jars now ship inside
# every source tarball, so the drift matters.
for jar in common eventbus-events idetooltips; do
    if [ -f "$LIBS_DIR/${jar}.jar" ]; then
        echo "NOT REFRESHED: libs/${jar}.jar (last changed $(date -r "$LIBS_DIR/${jar}.jar" '+%Y-%m-%d'))" >&2
    fi
done

CODEONTHEGO_SHA="$(git -C "$CODEONTHEGO_PATH" rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo ""
echo "Updated libs/ from CodeOnTheGo@$CODEONTHEGO_SHA"
printf "  %-20s %s\n" "plugin-api.jar"    "$(du -h "$LIBS_DIR/plugin-api.jar" | cut -f1)"
printf "  %-20s %s\n" "gradle-plugin.jar" "$(du -h "$LIBS_DIR/gradle-plugin.jar" | cut -f1)"

# The plugin builder records this in each .cgp's assets/cgp-build.properties as
# libs_revision. It cannot resolve the value itself -- the CodeOnTheGo checkout is
# outside the plugin build -- so without this a released plugin's own revision does
# not identify the plugin-api/gradle-plugin jars it was compiled against.
# scripts/build-plugins.sh reads this file and exports PLUGIN_LIBS_REVISION; a file
# rather than an export, because an export cannot reach the parent process. Not
# written when the sha is unknown, so the field is omitted rather than recorded as
# a guess. Resolved at 12 characters, the length the builder records `revision` at.
LIBS_REVISION_FILE="$REPO_ROOT/.cache/libs-revision"
rm -f "$LIBS_REVISION_FILE"
LIBS_REVISION="$(git -C "$CODEONTHEGO_PATH" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
if [ "$LIBS_REVISION" != "unknown" ]; then
    mkdir -p "$(dirname "$LIBS_REVISION_FILE")"
    echo "$LIBS_REVISION" > "$LIBS_REVISION_FILE"
fi
