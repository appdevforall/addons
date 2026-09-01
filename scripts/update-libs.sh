#!/usr/bin/env bash
#
# Rebuilds plugin-api.jar and gradle-plugin.jar from the CodeOnTheGo repo
# and refreshes this repo's libs/ folder.
#
# Usage:
#   ./scripts/update-libs.sh                      # clone/pull github.com/appdevforall/CodeOnTheGo into .cache/, build from stage
#   ./scripts/update-libs.sh --ref main           # build from a different branch or tag
#   ./scripts/update-libs.sh --local ../CodeOnTheGo  # use an existing local checkout instead of cloning
#   ./scripts/update-libs.sh --plugin random-xkcd # refresh libs, then build only this one plugin
#
set -euo pipefail

REPO_URL="https://github.com/appdevforall/CodeOnTheGo.git"
DEFAULT_REF="stage"
PLUGIN_BUILDER_ID="com.itsaky.androidide.plugins.build"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$REPO_ROOT/libs"
CACHE_DIR="$REPO_ROOT/.cache/CodeOnTheGo"

LOCAL_PATH=""
REF="$DEFAULT_REF"
ONLY_PLUGIN=""

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
        --plugin)
            ONLY_PLUGIN="${2:-}"
            if [ -z "$ONLY_PLUGIN" ]; then
                echo "Error: --plugin requires a plugin name argument." >&2
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
# not identify the plugin-api/gradle-plugin jars it was compiled against. Left unset
# when the sha is unknown, so the field is omitted rather than recorded as a guess.
# Re-resolved at 12 characters rather than reusing $CODEONTHEGO_SHA, which is abbreviated
# to git's default length: the builder records `revision` at 12, and two differently
# shaped shas in one properties file are needlessly hard to compare at a glance.
LIBS_REVISION="$(git -C "$CODEONTHEGO_PATH" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
if [ "$LIBS_REVISION" != "unknown" ]; then
    export PLUGIN_LIBS_REVISION="$LIBS_REVISION"
fi

# One discovery rule for the whole repository. The tool applies the skip
# list in tools/addons/skip.txt. Do not use mapfile here: macOS ships
# bash 3.2, which does not have it.
PLUGINS=()
while IFS= read -r line; do
    PLUGINS+=("$line")
# --include-skipped on purpose: a libs refresh must prove every module still
# compiles, including ones held out of the gallery. main built these too, and
# losing that check would let a jar change break them silently.
done < <(uv run --directory "$REPO_ROOT/tools/addons" addons --root "$REPO_ROOT" \
         discover --include-skipped)

if [ "${#PLUGINS[@]}" -eq 0 ]; then
    echo "Error: no addons discovered. 'addons discover' returned nothing -- check that uv works and that tools/addons/skip.txt is not excluding everything." >&2
    exit 1
fi

if [ -n "$ONLY_PLUGIN" ]; then
    # keep the resolved repo-relative path: the build loop cds into it, and
    # the bare name the caller gave us is no longer a directory
    match=""
    for p in "${PLUGINS[@]}"; do
        if [ "$p" = "$ONLY_PLUGIN" ] || [ "${p##*/}" = "$ONLY_PLUGIN" ]; then
            match="$p"
            break
        fi
    done
    if [ -z "$match" ]; then
        echo "Error: requested plugin '$ONLY_PLUGIN' is not a buildable example plugin." >&2
        echo "Available plugins: ${PLUGINS[*]}" >&2
        exit 1
    fi
    PLUGINS=("$match")
fi

if ! command -v unzip >/dev/null 2>&1; then
    echo "Error: unzip is required to verify the provenance record inside each built .cgp." >&2
    exit 1
fi

# The plugin builder writes assets/cgp-build.properties into every .cgp (see the
# Build provenance section of CLAUDE.md). Nothing in the Gradle build fails when
# that record is missing, truncated or unreadable — the archive still assembles
# either way — so a builder or asset-packaging regression would ship provenance-less
# artifacts and only be noticed once a crash report could no longer be traced to
# a commit. Assert the record here: both release workflows drive their builds
# through this script, so this is the one chokepoint that covers all of them.
REQUIRED_PROVENANCE_KEYS=(name version variant revision revision_source timestamp timestamp_source)

verify_provenance() {
    local plugin="$1"
    local cgp props key value revision revision_source timestamp_source recorded_libs
    cgp="$(ls "$REPO_ROOT/$plugin"/build/plugin/*.cgp 2>/dev/null | grep -v -- '-debug\.cgp$' | head -n1 || true)"
    if [ -z "$cgp" ]; then
        echo "Error: $plugin assembled no release .cgp under build/plugin/." >&2
        return 1
    fi

    if ! props="$(unzip -p "$cgp" assets/cgp-build.properties 2>/dev/null)" || [ -z "$props" ]; then
        echo "Error: $(basename "$cgp") does not contain assets/cgp-build.properties." >&2
        echo "       The builder in libs/gradle-plugin.jar either no longer generates the" >&2
        echo "       provenance record or no longer packages it as an asset." >&2
        return 1
    fi

    for key in "${REQUIRED_PROVENANCE_KEYS[@]}"; do
        value="$(printf '%s\n' "$props" | sed -n "s/^${key}=//p" | head -n1)"
        if [ -z "$value" ]; then
            echo "Error: $(basename "$cgp") provenance record has no '$key' value." >&2
            printf '%s\n' "$props" | sed 's/^/       /' >&2
            return 1
        fi
    done

    revision="$(printf '%s\n' "$props" | sed -n 's/^revision=//p' | head -n1)"
    revision_source="$(printf '%s\n' "$props" | sed -n 's/^revision_source=//p' | head -n1)"
    timestamp_source="$(printf '%s\n' "$props" | sed -n 's/^timestamp_source=//p' | head -n1)"
    recorded_libs="$(printf '%s\n' "$props" | sed -n 's/^libs_revision=//p' | head -n1)"

    # libs_revision only exists because this script exports PLUGIN_LIBS_REVISION
    # above, so a mismatch means the export stopped reaching the build and every
    # artifact this run publishes has lost the pairing to the jars it compiled
    # against.
    if [ -n "${PLUGIN_LIBS_REVISION:-}" ] && [ "$recorded_libs" != "$PLUGIN_LIBS_REVISION" ]; then
        echo "Error: $(basename "$cgp") recorded libs_revision='$recorded_libs', expected '$PLUGIN_LIBS_REVISION'." >&2
        return 1
    fi

    # Warnings, not errors: both are legitimate outside CI (a checkout with no
    # .git, a machine with no git binary), but in a workflow run they mean the
    # artifact cannot be traced back or reproduced.
    if [ "$revision" = "unknown" ] || [ "$revision_source" = "none" ]; then
        echo "Warning: $plugin recorded revision=unknown — the .cgp cannot be traced to a commit." >&2
    fi
    case "$revision" in
        *+dirty)
            echo "Warning: $plugin was built from a dirty $plugin/ directory, recorded as '$revision'." >&2
            echo "         Build-time downloads must land on gitignored paths, never on tracked files." >&2
            ;;
    esac
    if [ "$timestamp_source" = "wall-clock" ]; then
        echo "Warning: $plugin stamped a wall-clock timestamp — this .cgp is not reproducible." >&2
    fi

    echo "  provenance: revision=$revision ($revision_source) libs_revision=${recorded_libs:-<unset>} timestamp_source=$timestamp_source"
}

echo ""
echo "Discovered example plugins: ${PLUGINS[*]}"
echo "Building all example plugins against the refreshed libs..."
for plugin in "${PLUGINS[@]}"; do
    echo ""
    echo "→ $plugin"
    (
        cd "$REPO_ROOT/$plugin"
        # Newer plugins (e.g. flutter-template) drop their per-plugin wrapper and
        # use the repo-root gradlew; fall back to it when no local gradlew exists.
        gradlew="./gradlew"
        [ -x "$gradlew" ] || gradlew="$REPO_ROOT/gradlew"
        if grep -q 'downloadAssets' build.gradle.kts; then
            "$gradlew" --console=plain downloadAssets
        fi
        "$gradlew" --console=plain assemblePlugin
    )
    verify_provenance "$plugin"
done
echo ""
echo "All plugins built successfully."