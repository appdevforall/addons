#!/usr/bin/env bash
#
# Asserts the build-provenance record inside the release .cgp of each plugin
# directory given (repo-relative, e.g. plugins/AI-Core).
#
# The plugin builder writes assets/cgp-build.properties into every .cgp (see the
# Build provenance section of CLAUDE.md). Nothing in the Gradle build fails when
# that record is missing, truncated or unreadable — the archive still assembles
# either way — so a builder or asset-packaging regression would ship
# provenance-less artifacts and only be noticed once a crash report could no
# longer be traced to a commit. Every workflow that builds a .cgp calls this,
# including the one that publishes to R2: the artifacts users install are the
# ones the record matters most for.
#
# Usage:
#   ./scripts/verify-provenance.sh plugins/AI-Core [plugins/...]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v unzip >/dev/null 2>&1; then
    echo "Error: unzip is required to verify the provenance record inside each built .cgp." >&2
    exit 1
fi

REQUIRED_PROVENANCE_KEYS=(name version variant revision revision_source timestamp timestamp_source)

# A template addon ships a .cgt, not a .cgp (ADFA-6252). Its record sits at the archive
# root rather than under assets/ (a .cgt has no assets/ directory), and the key set differs:
# there is no Android build `variant`, and no `libs_revision` because a template compiles
# against nothing. Everything else -- the required-key sweep, and the unknown/+dirty/wall-clock
# warnings -- means the same for either artifact.
REQUIRED_CGT_PROVENANCE_KEYS=(revision revision_source timestamp timestamp_source addon_id version)
CGT_PROVENANCE_ENTRY="cgt-build.properties"

verify_cgt_provenance() {
    local addon="$1" cgt props key value revision revision_source timestamp_source

    cgt="$REPO_ROOT/$addon/build/plugin/$(basename "$addon" | tr '[:upper:]' '[:lower:]').cgt"
    if [ ! -f "$cgt" ]; then
        # The publish workflow builds into dist/, not into the addon directory, so accept
        # either. Naming both paths matters: "no .cgt" otherwise reads as a build failure
        # when it is only a different output directory.
        cgt="$REPO_ROOT/dist/$(basename "$addon" | tr '[:upper:]' '[:lower:]').cgt"
    fi
    if [ ! -f "$cgt" ]; then
        echo "Error: $addon produced no .cgt under build/plugin/ or dist/." >&2
        return 1
    fi

    if ! props="$(unzip -p "$cgt" "$CGT_PROVENANCE_ENTRY" 2>/dev/null)" || [ -z "$props" ]; then
        echo "Error: $(basename "$cgt") does not contain $CGT_PROVENANCE_ENTRY." >&2
        echo "       scripts/build-cgt.sh either no longer writes the provenance record or no" >&2
        echo "       longer includes it in the archive." >&2
        return 1
    fi

    for key in "${REQUIRED_CGT_PROVENANCE_KEYS[@]}"; do
        value="$(printf '%s\n' "$props" | sed -n "s/^${key}=//p" | head -n1)"
        if [ -z "$value" ]; then
            echo "Error: $(basename "$cgt") provenance record has no '$key' value." >&2
            printf '%s\n' "$props" | sed 's/^/       /' >&2
            return 1
        fi
    done

    revision="$(printf '%s\n' "$props" | sed -n 's/^revision=//p' | head -n1)"
    revision_source="$(printf '%s\n' "$props" | sed -n 's/^revision_source=//p' | head -n1)"
    timestamp_source="$(printf '%s\n' "$props" | sed -n 's/^timestamp_source=//p' | head -n1)"

    if [ "$revision" = "unknown" ] || [ "$revision_source" = "none" ]; then
        echo "Warning: $addon recorded revision=unknown — the .cgt cannot be traced to a commit." >&2
    fi
    case "$revision" in
        *+dirty)
            echo "Warning: $addon was built from a dirty $addon/ directory, recorded as '$revision'." >&2
            ;;
    esac
    if [ "$timestamp_source" = "wall-clock" ]; then
        echo "Warning: $addon stamped a wall-clock timestamp — this .cgt is not reproducible." >&2
    fi

    echo "  provenance: revision=$revision ($revision_source) timestamp_source=$timestamp_source"
}

verify_provenance() {
    local plugin="$1"
    local cgp props key value revision revision_source timestamp_source recorded_libs
    local -a cgps=()
    while IFS= read -r line; do
        cgps+=("$line")
    # A pluginName rename leaves the old artifact behind — the builder copies to
    # <pluginName>.cgp and never removes siblings — so picking one of several
    # would verify whichever sorts first, possibly a stale build.
    done < <(ls "$REPO_ROOT/$plugin"/build/plugin/*.cgp 2>/dev/null | grep -v -- '-debug\.cgp$' || true)

    if [ "${#cgps[@]}" -eq 0 ]; then
        echo "Error: $plugin assembled no release .cgp under build/plugin/." >&2
        return 1
    fi
    if [ "${#cgps[@]}" -gt 1 ]; then
        echo "Error: $plugin has ${#cgps[@]} release .cgp files under build/plugin/; delete the stale ones and rebuild." >&2
        printf '       %s\n' "${cgps[@]}" >&2
        return 1
    fi
    cgp="${cgps[0]}"

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

    # libs_revision only exists because the caller exported PLUGIN_LIBS_REVISION,
    # so a mismatch means the export stopped reaching the build and every artifact
    # this run publishes has lost the pairing to the jars it compiled against.
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

if [ "$#" -eq 0 ]; then
    echo "Usage: $0 <plugin-dir> [plugin-dir...]" >&2
    exit 1
fi

for plugin in "$@"; do
    # templates.json is the same marker discover.py keys on, so the two agree on
    # what a template is without either restating the rule.
    if [ -f "$REPO_ROOT/$plugin/templates.json" ]; then
        verify_cgt_provenance "$plugin"
    else
        verify_provenance "$plugin"
    fi
done
