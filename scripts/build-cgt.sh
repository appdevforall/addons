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
# belongs in the archive, so the file list is derived from it. A path it names that is not on
# disk fails the build, rather than being skipped in silence by the IDE.
#
# WHY TRACKED FILES: within those directories the list comes from git, not from find. find
# sweeps up whatever is sitting there -- a .DS_Store, a .dart_tool/ and pubspec.lock after
# someone ran a generated project -- and ships it to users. check-toolchain.yml only rejects
# junk that was *committed*, so nothing else catches it. Selecting by commit is also what makes
# the determinism below worth anything. tarball.py selects the same way, for the same reason.
#
# WHY A STAGING DIRECTORY: pinning mtimes is part of the reproducibility contract, but doing it
# in place would rewrite timestamps across the caller's working tree. Everything is copied into
# a temp directory first, so a build has no side effects on the checkout.
#
# WHY STORED AND 1980: entries are stored (-0) and every mtime is pinned, so one commit always
# produces one archive, byte for byte. Compression happens at the HTTP layer -- Cloudflare
# serves the gallery. This mirrors dev-assets' core.cgt build; see templates/README.md.
#
# LIBRARIES: a templates.json entry may carry a "libraries" list. Each item downloads "src"
# (an https URL) into the directory "dst", taken relative to that template's own directory, under
# the file name the URL ends in. So { "path": "FlutterBasic", "libraries": [{ "src":
# "https://host/lib1.so", "dst": "app/lib" }] } ships FlutterBasic/app/lib/lib1.so. When
# "sha256" is given the download must match it or the build fails. Without it the build still
# succeeds but warns, because an unpinned download is the one input git does not fix, and the
# byte-for-byte guarantee below then holds only as long as the server keeps serving the same
# bytes. A library may not land on a tracked file: the commit and the download would disagree
# about what that path holds, so the build refuses rather than pick one in silence.
#
# Bash 3.2 compatible on purpose: macOS ships it, and a community contributor building a
# bundle locally is the main audience. No mapfile, no associative arrays.
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

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/build-cgt.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

# ---------------------------------------------------------------------------
# The provenance record
# ---------------------------------------------------------------------------
# Written into the staging directory, so it lands at the archive root without ever
# touching the addon directory. The timestamp is the committer date of the recorded
# revision, never the wall clock: a clock reading changes on every build and would
# end the determinism above.
write_provenance() {
    local dir="$1" out="$2" revision revision_source timestamp timestamp_source
    local addon_id version var value

    revision=""
    revision_source="none"
    for var in PLUGIN_VCS_REVISION GITHUB_SHA CI_COMMIT_SHA GIT_COMMIT; do
        eval "value=\${$var:-}"
        if [[ -n "$value" ]]; then
            revision="$value"
            revision_source="env:$var"
            break
        fi
    done
    if [[ -z "$revision" ]] && command -v git >/dev/null 2>&1 \
        && git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        revision="$(git -C "$dir" rev-parse HEAD)"
        revision_source="git"
    fi

    # Resolve the date before truncating, so the sha stays usable as a git argument.
    timestamp=""
    timestamp_source="wall-clock"
    if [[ -n "$revision" ]] && command -v git >/dev/null 2>&1 \
        && git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        # The date of the *recorded* revision, not of HEAD: on a pull request GitHub
        # checks out a merge commit, so GITHUB_SHA and HEAD are different commits and
        # the record would pair one with the other's date.
        #
        # TZ=UTC is set on this command rather than inherited. --date=format-local
        # renders in the caller's zone while the format string hard-codes a Z, so
        # without it a developer in PDT records a time seven hours off and labels it
        # UTC, and two machines in different zones disagree about one commit.
        timestamp="$(TZ=UTC git -C "$dir" show -s --format=%cd \
            --date=format-local:%Y-%m-%dT%H:%M:%SZ "$revision" 2>/dev/null || true)"
        [[ -n "$timestamp" ]] && timestamp_source="git"
    fi
    if [[ -z "$timestamp" ]]; then
        timestamp="$(TZ=UTC date -u +%Y-%m-%dT%H:%M:%SZ)"
    fi

    revision="${revision:0:12}"
    [[ -n "$revision" ]] || revision="unknown"

    # Scoped to this addon's own directory, so a change elsewhere in the tree -- a
    # libs/ refresh, another addon -- does not mark this artifact dirty. The pathspec
    # is "." and not "$dir": with -C it already resolves against that directory.
    if command -v git >/dev/null 2>&1 && git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        if [[ -n "$(git -C "$dir" status --porcelain -- . 2>/dev/null)" ]]; then
            revision="${revision}+dirty"
        fi
    fi

    addon_id="$(jq -r '.template.id // ""' "$dir/addon.json" 2>/dev/null || true)"
    version="$(jq -r '.template.version // ""' "$dir/addon.json" 2>/dev/null || true)"

    cat > "$out" <<EOF
revision=$revision
revision_source=$revision_source
timestamp=$timestamp
timestamp_source=$timestamp_source
addon_id=$addon_id
version=$version
EOF
}

# ---------------------------------------------------------------------------
# Select the files
# ---------------------------------------------------------------------------
cd "$ADDON_DIR"

paths=()
while IFS= read -r line; do
    [[ -n "$line" ]] && paths+=("$line")
done < <(jq -r '.templates[].path' templates.json)

if [[ "${#paths[@]}" -eq 0 ]]; then
    echo "Error: templates.json lists no templates." >&2
    exit 1
fi

for p in "${paths[@]}"; do
    if [[ ! -d "$p" ]]; then
        echo "Error: templates.json names '$p', which is not a directory." >&2
        exit 1
    fi
done

LIST="$STAGE/.filelist"
if git rev-parse --git-dir >/dev/null 2>&1; then
    git ls-files -z -- templates.json "${paths[@]}" > "$LIST"
else
    # No checkout to ask. Provenance already records revision=unknown here.
    find templates.json "${paths[@]}" -type f -print0 > "$LIST"
fi

if [[ ! -s "$LIST" ]]; then
    echo "Error: no files found under the paths templates.json lists." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Stage, pin, zip
# ---------------------------------------------------------------------------
TREE="$STAGE/tree"
mkdir -p "$TREE"
# -0 throughout, so a path with a space or a newline survives the round trip.
# The destination travels in the environment: with `sh -c '...' _` the file names
# arrive as "$@", so there is no room for it in the argument list.
TREE="$TREE" xargs -0 -n 50 sh -c 'for f; do
    mkdir -p "$TREE/$(dirname "$f")"
    cp "$f" "$TREE/$f"
done' _ < "$LIST"

# ---------------------------------------------------------------------------
# Fetch the libraries
# ---------------------------------------------------------------------------
# One row per library: template path, src, dst, sha256 (empty when absent). The separator is
# the ASCII unit separator, not a tab: tab is whitespace to `read`, so an empty middle field
# would collapse and shift the ones after it.
# Validation runs over every row before the first download, so a typo in the last entry
# does not cost a slow fetch of the first.
LIBS="$STAGE/.libraries"
if ! jq -r '
    .templates[]
    | .path as $p
    | (.libraries // [])
    | if type == "array" then .[] else error("\($p): \"libraries\" must be a list") end
    | if type == "object" then . else error("\($p): a \"libraries\" item must be an object") end
    | [$p, (.src // ""), (.dst // ""), (.sha256 // "")]
    | map(tostring)
    | if any(.[]; any(explode[]; . < 32)) then error("\($p): a \"libraries\" value holds a control character") else . end
    | join("\u001f")' templates.json > "$LIBS"; then
    echo "Error: templates.json has a malformed \"libraries\" entry." >&2
    exit 1
fi

if [[ -s "$LIBS" ]]; then
    if ! command -v curl >/dev/null 2>&1; then
        echo "Error: curl is required to fetch the libraries templates.json lists." >&2
        exit 1
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
    elif command -v shasum >/dev/null 2>&1; then
        sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
    else
        echo "Error: sha256sum or shasum is required to verify libraries." >&2
        exit 1
    fi

    US="$(printf '\037')"
    while IFS="$US" read -r tpl src dst sha; do
        where="templates.json: library in '$tpl'"
        if [[ -z "$src" || -z "$dst" ]]; then
            echo "Error: $where needs both \"src\" and \"dst\"." >&2
            exit 1
        fi
        case "$src" in
            https://*) ;;
            *) echo "Error: $where has src '$src', which is not an https URL." >&2; exit 1 ;;
        esac
        # dst becomes part of a zip entry name: keep it inside the template directory.
        case "/${dst%/}/" in
            */../*|*/./*|*//*) echo "Error: $where has dst '$dst', which must be a relative path inside the template, with no '.' or '..'." >&2; exit 1 ;;
        esac
        if [[ -n "$sha" ]] && ! printf '%s' "$sha" | grep -Eq '^[0-9a-fA-F]{64}$'; then
            echo "Error: $where has sha256 '$sha', which is not 64 hex digits." >&2
            exit 1
        fi
        name="${src%%[?#]*}"
        name="${name##*/}"
        if [[ -z "$name" || "$name" == "." || "$name" == ".." ]]; then
            echo "Error: $where has src '$src', which does not end in a file name." >&2
            exit 1
        fi
    done < "$LIBS"

    FETCH="$STAGE/.fetch"
    mkdir -p "$FETCH"
    n=0
    while IFS="$US" read -r tpl src dst sha; do
        n=$((n + 1))
        name="${src%%[?#]*}"
        name="${name##*/}"
        dst="${dst%/}"
        rel="$tpl/$dst/$name"
        # Checked against the staged tree, so it covers tracked files and earlier libraries.
        if [[ -e "$TREE/$rel" ]]; then
            echo "Error: library '$src' would overwrite '$rel', which is already in the template." >&2
            exit 1
        fi

        tmp="$FETCH/$n"
        echo "Fetching $src -> $rel" >&2
        if ! curl -fsSL --proto '=https' --tlsv1.2 --retry 3 -o "$tmp" "$src"; then
            echo "Error: could not download '$src'." >&2
            exit 1
        fi

        if [[ -n "$sha" ]]; then
            want="$(printf '%s' "$sha" | tr '[:upper:]' '[:lower:]')"
            got="$(sha256_of "$tmp")"
            if [[ "$got" != "$want" ]]; then
                echo "Error: '$src' has sha256 $got, but templates.json expects $want." >&2
                exit 1
            fi
        else
            echo "Warning: '$src' has no sha256 in templates.json, so it is not verified and the build is not reproducible." >&2
        fi

        mkdir -p "$TREE/$tpl/$dst"
        mv "$tmp" "$TREE/$rel"
    done < "$LIBS"
fi

write_provenance "$PWD" "$TREE/cgt-build.properties"

cd "$TREE"
find . -type f -exec touch -t 198001010000 {} +
find . -type f | sed 's#^\./##' | LC_ALL=C sort | zip -0 -D -X -q "$OUT_CGT" -@

echo "Wrote $OUT_CGT ($(wc -c < "$OUT_CGT" | tr -d ' ') bytes)"
