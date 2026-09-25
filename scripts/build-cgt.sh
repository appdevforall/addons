#!/usr/bin/env bash
#
# build-cgt.sh (ADFA-6252)
#
# Zips a template addon directory into a .cgt bundle.
#
# Usage:
#   ./scripts/build-cgt.sh <addon-dir> <output-dir>
#
# Output:
#   <output-dir>/<slug>.cgt        where <slug> is the addon directory name, lowercased
#
# WHY AN ALLOW LIST: the addon directory holds gallery metadata (addon.json, the HTML page,
# the icons, README.md) beside the template tree, and none of that belongs in a user-facing
# download. An exclude list would fail open -- a metadata file added later would ship until
# somebody remembered to extend the list. templates.json already names every directory that
# belongs in the archive, so the file list is derived from it. A file that is not listed can
# never be packaged, and a path in templates.json that does not exist on disk fails the build
# instead of being skipped in silence by the IDE.
#
# WHY STORED AND 1980: entries are stored (-0) and every mtime is pinned, so one commit always
# produces one archive, byte for byte. Compression happens at the HTTP layer -- Cloudflare
# serves the gallery. This mirrors dev-assets' core.cgt build; see templates/cgt-templates.md.
#
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <addon-dir> <output-dir>" >&2
    exit 1
fi

ADDON_DIR="$1"
OUTPUT_DIR="$2"

for tool in jq zip; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Error: $tool is required to build a .cgt." >&2
        exit 1
    fi
done

if [[ ! -d "$ADDON_DIR" ]]; then
    echo "Error: '$ADDON_DIR' is not a directory." >&2
    exit 1
fi

if [[ ! -f "$ADDON_DIR/templates.json" ]]; then
    echo "Error: '$ADDON_DIR/templates.json' is missing, so this is not a template addon." >&2
    exit 1
fi

ADDON_NAME="$(basename "$(cd "$ADDON_DIR" && pwd)")"
SLUG="$(printf '%s' "$ADDON_NAME" | tr '[:upper:]' '[:lower:]')"

mkdir -p "$OUTPUT_DIR"
OUT="$(cd "$OUTPUT_DIR" && pwd)"
OUT_CGT="$OUT/$SLUG.cgt"
rm -f "$OUT_CGT"

# The provenance record. Written into the addon directory because it belongs at the archive
# root, and gitignored there. The timestamp is the committer date of the revision, never the
# wall clock: a clock reading would change on every build and end the determinism above.
write_provenance() {
    local dir="$1" revision revision_source timestamp timestamp_source addon_id version

    revision=""
    revision_source="none"
    for var in PLUGIN_VCS_REVISION GITHUB_SHA CI_COMMIT_SHA GIT_COMMIT; do
        if [[ -n "${!var:-}" ]]; then
            revision="${!var}"
            revision_source="env:$var"
            break
        fi
    done
    if [[ -z "$revision" ]] && command -v git >/dev/null 2>&1 \
        && git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        revision="$(git -C "$dir" rev-parse HEAD)"
        revision_source="git"
    fi
    revision="${revision:0:12}"
    [[ -n "$revision" ]] || revision="unknown"

    # Scoped to this addon's own directory, so a change elsewhere in the tree -- a libs/
    # refresh, another addon -- does not mark this artifact dirty.
    # The pathspec is "." and not "$dir": with -C the pathspec already resolves against that
    # directory, so passing the path again looked for <dir>/<dir>, matched nothing, and no
    # build was ever reported dirty.
    if command -v git >/dev/null 2>&1 && git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        if [[ -n "$(git -C "$dir" status --porcelain -- . 2>/dev/null)" ]]; then
            revision="${revision}+dirty"
        fi
    fi

    timestamp=""
    timestamp_source="wall-clock"
    if [[ "$revision_source" != "none" ]] && command -v git >/dev/null 2>&1 \
        && git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        timestamp="$(git -C "$dir" show -s --format=%cd --date=format-local:%Y-%m-%dT%H:%M:%SZ HEAD 2>/dev/null || true)"
        [[ -n "$timestamp" ]] && timestamp_source="git"
    fi
    if [[ -z "$timestamp" ]]; then
        timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    fi

    addon_id="$(jq -r '.template.id // ""' "$dir/addon.json" 2>/dev/null || true)"
    version="$(jq -r '.template.version // ""' "$dir/addon.json" 2>/dev/null || true)"

    cat > "$dir/cgt-build.properties" <<EOF
revision=$revision
revision_source=$revision_source
timestamp=$timestamp
timestamp_source=$timestamp_source
addon_id=$addon_id
version=$version
EOF
}

write_provenance "$ADDON_DIR"

export TZ=UTC
(
    cd "$ADDON_DIR"

    # Fails loudly when templates.json names a path that is not on disk: find exits non-zero
    # and set -e ends the build. The IDE would skip such an entry without an error.
    mapfile -t paths < <(jq -r '.templates[].path' templates.json)
    if [[ "${#paths[@]}" -eq 0 ]]; then
        echo "Error: templates.json lists no templates." >&2
        exit 1
    fi

    find cgt-build.properties templates.json "${paths[@]}" -type f \
        -exec touch -t 198001010000 {} +
    find cgt-build.properties templates.json "${paths[@]}" -type f \
        | LC_ALL=C sort \
        | zip -0 -D -X -q "$OUT_CGT" -@
)

echo "Wrote $OUT_CGT ($(wc -c < "$OUT_CGT" | tr -d ' ') bytes)"
