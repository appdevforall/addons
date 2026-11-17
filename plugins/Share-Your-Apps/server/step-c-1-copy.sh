#!/bin/sh
# step-c-1-copy.sh: step C, script 1 of 6. Copies project B's sound files into this project, unchanged.
#
# Run:   sh "/storage/emulated/0/CodeOnTheGoProjects/Share Your Apps Server Combined/step-c-1-copy.sh"
# Undo:  sh "/storage/emulated/0/CodeOnTheGoProjects/Share Your Apps Server Combined/step-c-1-copy.sh" undo
#        (puts back the files it replaced, deletes the files it added)
#
# Copied from project-b-reference (only read, never changed) to the same place here:
#   app/src/main/java/org/appdevforall/shareyourapps/server/
#     SoundPayload.kt SoundDsp.kt SoundModem.kt SoundDecoder.kt SoundIo.kt SoundLog.kt WifiJoin.kt  (new here)
#     ImportActivity.kt                                                                          (replaces A's)
#   SoundPayloadTest.kt SoundModemTest.kt SoundLogTest.kt SoundLevelTest.kt (found by name under app/src/test)
#   help/build_help_db.py (replaces A's: B's copy has all of A's MCP help, plus the sound help)
#
# Before changing anything it checks: this file ends with its END line (it wasn't cut off); this is the
# Combined folder; it hasn't run before; every source file exists, exactly once.
# Then it backs up what it replaces (step-c-backup/1), copies, and checks that every copy's SHA-256 equals
# its source's. If a check fails once copying has started, it undoes itself. Checksums: step-c-checksums.txt.
# At the end it lists the files that still differ from project B, for the agent.
#
# The project won't build after this script alone: run step-c-2-import.sh too first.
# Plain sh, no bash.

set -u
NAME="step-c-1-copy.sh"
STEP=1

# ---- Not cut off: the last line of this file must be its END line.
if [ "$(tail -n 1 "$0" 2>/dev/null)" != "# END OF $NAME" ]; then
  echo "STOPPED: $0 is incomplete: its last line isn't \"# END OF $NAME\"."
  echo "Nothing was changed. Ask the agent to write it again."
  exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd)" || { echo "STOPPED: couldn't find this script's folder. Nothing was changed."; exit 2; }
cd "$HERE" || exit 2

fail() { echo "STOPPED: $*"; echo "Nothing was changed."; exit 1; }

REF="project-b-reference"
SRC="app/src/main/java/org/appdevforall/shareyourapps/server"
BK="step-c-backup/$STEP"
SUMS="step-c-checksums.txt"

# ---- Undo: put back what was replaced, delete what was added, forget this step's checksums.
undo() {
  [ -d "$BK" ] || { echo "Nothing to undo: $BK doesn't exist."; return 1; }
  UNDO_OK=yes
  if [ -f "$BK/replaced.txt" ]; then
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      if cp "$BK/files/$f" "$f"; then echo "  put back: $f"
      else echo "  COULDN'T PUT BACK: $f (its copy is in $BK/files)"; UNDO_OK=no; fi
    done < "$BK/replaced.txt"
  fi
  if [ -f "$BK/added.txt" ]; then
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      if [ -e "$f" ]; then rm -f "$f" && echo "  removed: $f"; fi
    done < "$BK/added.txt"
  fi
  if [ -f "$SUMS" ]; then grep -v "^step$STEP " "$SUMS" > "$SUMS.tmp"; mv "$SUMS.tmp" "$SUMS"; fi
  if [ "$UNDO_OK" = yes ]; then
    mv "$BK" "$BK.undone-$(date +%Y%m%d-%H%M%S)"
    echo "Undone."
    return 0
  fi
  echo "NOT fully undone: see the lines above. The backup stays in $BK."
  return 1
}

case "${1:-}" in
  "") ;;
  undo)
    for n in 2 3 4 5 6; do
      if [ -d "step-c-backup/$n" ]; then
        echo "STOPPED: step C script $n has run since this one. Undo it first (its script, with undo). Nothing was changed."
        exit 1
      fi
    done
    echo "Undoing $NAME..."
    undo; exit $? ;;
  *) fail "unknown option \"$1\". Run it with nothing, or with undo." ;;
esac

# ---- Checks. Nothing is changed until all of them pass.
[ -d "$REF/app/src" ] || fail "$REF/app/src isn't here. Is this the Combined folder made by make-combined.sh?"
[ -f "$SRC/McpService.kt" ] || fail "$SRC/McpService.kt isn't here, so this isn't project A's code."
[ -e "$BK" ] && fail "this script has already run ($BK exists). To run it again, undo it first: sh $NAME undo"

PY=""
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
fi
if command -v sha256sum >/dev/null 2>&1; then SHA=sha256sum
elif command -v toybox >/dev/null 2>&1 && toybox sha256sum "$0" >/dev/null 2>&1; then SHA="toybox sha256sum"
elif [ -n "$PY" ]; then SHA=py
else SHA=""
fi
[ -n "$SHA" ] || fail "can't work out SHA-256 checksums here: no sha256sum, toybox or Python."
sha() {
  if [ "$SHA" = py ]; then
    "$PY" -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
  else
    $SHA "$1" | cut -d ' ' -f 1
  fi
}

PLAN=""
for f in SoundPayload.kt SoundDsp.kt SoundModem.kt SoundDecoder.kt SoundIo.kt SoundLog.kt WifiJoin.kt ImportActivity.kt; do
  PLAN="$PLAN $SRC/$f"
done
for t in SoundPayloadTest.kt SoundModemTest.kt SoundLogTest.kt SoundLevelTest.kt; do
  found="$(find "$REF/app/src/test" -type f -name "$t" 2>/dev/null)"
  n="$(printf '%s\n' "$found" | grep -c .)"
  [ "$n" = "1" ] || fail "expected 1 file named $t under $REF/app/src/test, found $n."
  case "$found" in *" "*) fail "the path of $t has a space in it: $found" ;; esac
  PLAN="$PLAN ${found#"$REF"/}"
done
PLAN="$PLAN help/build_help_db.py"
for p in $PLAN; do
  [ -f "$REF/$p" ] || fail "$REF/$p is missing."
done
echo "Checks passed: $(echo $PLAN | wc -w | tr -d ' ') files to copy."

# ---- Back up everything it will replace, and check each backup.
mkdir -p "$BK/files" || fail "couldn't make $BK."
: > "$BK/replaced.txt"; : > "$BK/added.txt"; : > "$BK/sums.txt"
for p in $PLAN; do
  if [ -f "$p" ]; then
    { mkdir -p "$BK/files/$(dirname "$p")" && cp "$p" "$BK/files/$p"; } || { rm -rf "$BK"; fail "couldn't back up $p."; }
    [ "$(sha "$p")" = "$(sha "$BK/files/$p")" ] || { rm -rf "$BK"; fail "the backup of $p doesn't match it."; }
    echo "$p" >> "$BK/replaced.txt"
  else
    echo "$p" >> "$BK/added.txt"
  fi
done
echo "Backed up to $BK."

# ---- Copy, and check every copy against its source.
echo "Copying from $REF..."
BAD=""
for p in $PLAN; do
  { mkdir -p "$(dirname "$p")" && cp "$REF/$p" "$p"; } || { BAD="$p (the copy failed)"; break; }
  a="$(sha "$REF/$p")"; b="$(sha "$p")"
  if [ -z "$a" ] || [ "$a" != "$b" ]; then BAD="$p (its SHA-256 doesn't match the source's)"; break; fi
  if grep -Fqx "$p" "$BK/replaced.txt"; then what="replaced"; else what="added   "; fi
  echo "  $what $p  ($(wc -c < "$p" | tr -d ' ') bytes, sha256 $(printf '%s' "$a" | cut -c1-12)...)"
  echo "step$STEP $a $p" >> "$BK/sums.txt"
done
if [ -n "$BAD" ]; then
  echo "FAILED: $BAD. Undoing what this script did..."
  undo
  exit 1
fi
cat "$BK/sums.txt" >> "$SUMS"

# ---- For the agent: what still differs from project B. "known" = merged by hand, or A's kept on purpose.
echo
echo "Files that still differ from project B:"
KNOWN=" ImportJob.kt ShareActivity.kt ImportService.kt MainActivity.kt AndroidManifest.xml HostService.kt StoreLog.kt Compressor.kt AuthTest.kt build_help_db.sh "
find "$REF/app/src" "$REF/help" -type f | sort | while IFS= read -r f; do
  rel="${f#"$REF"/}"
  if [ ! -f "$rel" ]; then echo "  CHECK  only in B: $rel"; continue; fi
  [ "$(sha "$f")" = "$(sha "$rel")" ] && continue
  case "$KNOWN" in
    *" $(basename "$rel") "*) echo "  known  differs:   $rel" ;;
    *) echo "  CHECK  differs:   $rel" ;;
  esac
done

echo
echo "Done: $(grep -c . "$BK/replaced.txt") replaced, $(grep -c . "$BK/added.txt") added. Every copy matches its source."
echo "Backup: $BK   Checksums: $SUMS"
echo "Python for scripts 2 to 6: ${PY:-NOT FOUND}"
echo "Don't build yet: the project compiles only after step-c-2-import.sh. Paste all of this output to the agent."
exit 0
# END OF step-c-1-copy.sh
