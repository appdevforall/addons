#!/bin/sh
# step-c-3-share.sh: step C, script 3 of 6. Adds project B's Play sound / Stop sound to project A's ShareActivity.kt.
#
# Run:   sh "/storage/emulated/0/CodeOnTheGoProjects/Local Store Server Combined/step-c-3-share.sh"
# Undo:  sh "/storage/emulated/0/CodeOnTheGoProjects/Local Store Server Combined/step-c-3-share.sh" undo
#
# ShareActivity.kt (A's file, patched with B's sound code, copied from project B unchanged):
#   1. The class description mentions Play sound.
#   2. Fields: player and playButton.
#   3. onDestroy stops the tones and sends the "played" line first, while still connected to the server.
#   4. show(): the tones stop once the share leaves OPEN (a phone connected, or the share ended).
#   5. showCode(): the Play sound button under the code.
#   6. The sound functions: soundButton, playSound, logSound, stopSound, soundPayload, toast.
# A's joining of a share under way, ownShare and Cancel are unchanged.
#
# Same checks as script 2: not cut off; script 2 has run, this one hasn't; every text to replace is there exactly
# once; { } and ( ) stay balanced. Backup in step-c-backup/3; written through a temporary file, read back, SHA-256
# compared; on any failure the file is put back. Checksums: step-c-checksums.txt. Plain sh, no bash; needs Python.

set -u
NAME="step-c-3-share.sh"
STEP=3

# ---- Not cut off: the last line of this file must be its END line.
if [ "$(tail -n 1 "$0" 2>/dev/null)" != "# END OF $NAME" ]; then
  echo "STOPPED: $0 is incomplete: its last line isn't \"# END OF $NAME\"."
  echo "Nothing was changed. Ask the agent to write it again."
  exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd)" || { echo "STOPPED: couldn't find this script's folder. Nothing was changed."; exit 2; }
cd "$HERE" || exit 2

fail() { echo "STOPPED: $*"; echo "Nothing was changed."; exit 1; }

BK="step-c-backup/$STEP"
SUMS="step-c-checksums.txt"

undo() {
  [ -d "$BK" ] || { echo "Nothing to undo: $BK doesn't exist."; return 1; }
  UNDO_OK=yes
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if cp "$BK/files/$f" "$f"; then echo "  put back: $f"
    else echo "  COULDN'T PUT BACK: $f (its copy is in $BK/files)"; UNDO_OK=no; fi
  done < "$BK/replaced.txt"
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
    for n in 4 5 6; do
      if [ -d "step-c-backup/$n" ]; then
        echo "STOPPED: step C script $n has run since this one. Undo it first (its script, with undo). Nothing was changed."
        exit 1
      fi
    done
    echo "Undoing $NAME..."
    undo; exit $? ;;
  *) fail "unknown option \"$1\". Run it with nothing, or with undo." ;;
esac

[ -d "step-c-backup/2" ] || fail "step-c-2-import.sh hasn't run yet (step-c-backup/2 isn't here). Run it first."
[ -e "$BK" ] && fail "this script has already run ($BK exists). To run it again, undo it first: sh $NAME undo"

PY=""
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
fi
[ -n "$PY" ] || fail "neither python3 nor python was found."

"$PY" - "$STEP" "$BK" "$SUMS" <<'PYEOF'
import hashlib, os, shutil, sys
step, bk, sums_path = sys.argv[1], sys.argv[2], sys.argv[3]
SRC = "app/src/main/java/org/appdevforall/localstore/server/"
P = []
def patch(f, what, old, new): P.append((SRC + f, what, old, new))

patch("ShareActivity.kt", "class description mentions Play sound", r''' * share it opened itself; Cancel closes either kind. Allow and Deny are only ever sent from here, by a person.
 * The screen stays on while it's open, since a server paused for a dark screen can't share.
 */''', r''' * share it opened itself; Cancel closes either kind. Allow and Deny are only ever sent from here, by a person.
 * The screen stays on while it's open, since a server paused for a dark screen can't share. Play sound plays the
 * code as tones, for a phone that taps Listen; they stop when the share moves on or the screen closes.
 */''')

patch("ShareActivity.kt", "fields: player and playButton", r'''    private var ownShare = false
    private var lastState = ""
''', r'''    private var ownShare = false
    private var lastState = ""
    /** Play sound: the player while the code plays, and its button, which reads Stop sound meanwhile. */
    private var player: SoundIo.Player? = null
    private var playButton: Button? = null
''')

patch("ShareActivity.kt", "onDestroy stops the tones and logs first", r'''    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
''', r'''    override fun onDestroy() {
        // Nothing plays off the screen: leaving it, or rotating, stops the tones. The "played" line is sent now,
        // while the connection to the server is still open, rather than when the player thread ends.
        val wasPlaying = player != null
        stopSound()
        if (wasPlaying) logPlayedNow?.invoke()
        logPlayedNow = null
        main.removeCallbacksAndMessages(null)
''')

patch("ShareActivity.kt", "show(): tones stop once the share leaves OPEN", r'''        if (key == lastState) return
        lastState = key
        val store = b.getString(Ipc.KEY_SHARE_STORE).orEmpty()''', r'''        if (key == lastState) return
        lastState = key
        // The other phone has connected, or the share ended: the tones have done their job.
        if (state != "OPEN") stopSound()
        val store = b.getString(Ipc.KEY_SHARE_STORE).orEmpty()''')

patch("ShareActivity.kt", "showCode(): Play sound button", r'''            codeRow(code),
            text("Both phones must be on the same Wi-Fi network or hotspot.''', r'''            codeRow(code),
            soundButton(code, link),
            text("Both phones must be on the same Wi-Fi network or hotspot.''')

patch("ShareActivity.kt", "the sound functions (from project B)", r'''    private fun codeRow(code: String) = copyRow(code, 30f, clipLabel = "Share code", description = "Copy the code",
        copied = "Code copied", sensitive = true)
''', r'''    private fun codeRow(code: String) = copyRow(code, 30f, clipLabel = "Share code", description = "Copy the code",
        copied = "Code copied", sensitive = true)

    // ---- Play sound ----
    /** Counts players started and stopped, so a finished player's callback can't reset a newer one's button. */
    private var soundRun = 0

    /** Play sound, or Stop sound while the tones play. */
    private fun soundButton(code: String, link: String): Button =
        button(if (player != null) "Stop sound" else "Play sound") { if (player != null) stopSound() else playSound(code, link) }
            .help("share.sound").also { playButton = it }

    /**
     * Logs the playing player's "played" line at once, as stopped early. onDestroy calls it while the connection to
     * the server is still open; the player's own callback, coming later, then logs nothing.
     */
    private var logPlayedNow: (() -> Unit)? = null

    private fun playSound(code: String, link: String) {
        val payload = soundPayload(code, link) ?: return toast("Couldn't turn this code into sound.")
        val low = SoundIo.volumeLow(this)
        if (low) toast("The media volume is low: turn it up so the other phone hears the tones.")
        val run = ++soundRun
        var logged = false
        lateinit var p: SoundIo.Player
        p = SoundIo.Player(payload.encode()) { error ->
            if (run == soundRun) { player = null; playButton?.text = "Play sound" }
            if (error != null) toast(error)
            if (!logged) { logged = true; logSound(SoundLog.played(p.rounds, p.stoppedEarly, low, payload.ownHotspot, error)) }
        }
        logPlayedNow = { if (!logged) { logged = true; logSound(SoundLog.played(p.rounds, true, low, payload.ownHotspot, null)) } }
        player = p.start()
        playButton?.text = "Stop sound"
    }

    /** The server process owns the log file while it serves, so the line goes to it, over the share's connection. */
    private fun logSound(message: String) {
        val line = SoundLog.line(message)
        android.util.Log.i("LocalStoreSound", line)
        send(Ipc.MSG_LOG, Bundle().apply { putString(Ipc.KEY_LINE, line) })
    }

    private fun stopSound() {
        soundRun++
        player?.stop(); player = null
        playButton?.text = "Play sound"
    }

    /**
     * What the tones carry: the code and the server's address from the share link; on this phone's own hotspot,
     * also its name and password. On other Wi-Fi, neither name nor password (Hotspot.info is null there).
     */
    private fun soundPayload(code: String, link: String): SoundPayload? {
        val normalized = ShareCodes.normalize(code) ?: return null
        val parsed = ShareLink.parse(link)
        val address = parsed?.let { try { java.net.InetAddress.getByName(it.first) as? java.net.Inet4Address } catch (e: Exception) { null } }
        val port = if (address != null && parsed != null) parsed.second else 0
        val hotspot = Hotspot.info
        fun fits(s: String) = s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size <= SoundPayload.MAX_FIELD
        val ssid = hotspot?.ssid?.takeIf { it != "?" && fits(it) }
        val password = hotspot?.password?.takeIf { ssid != null && fits(it) }
        return try { SoundPayload(normalized, if (port > 0) address else null, port, hotspot != null, ssid, password) }
        catch (e: IllegalArgumentException) { null }
    }

    private fun toast(t: String) { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() }
''')

def stop(msg):
    print("STOPPED: " + msg)
    print("Nothing was changed.")
    sys.exit(1)
def sha(b): return hashlib.sha256(b).hexdigest()
def bal(t): return (t.count("{") - t.count("}"), t.count("(") - t.count(")"))

files = []
for p in P:
    if p[0] not in files: files.append(p[0])
raw, out = {}, {}
for f in files:
    if not os.path.isfile(f): stop(f + " isn't here.")
    with open(f, "rb") as h: raw[f] = h.read()
    t = raw[f].decode("utf-8")
    if "SoundIo.Player" in t: stop(f + " already has sound code in it. Has this script, or a hand edit, been applied already?")
    for i, (g, what, old, new) in enumerate(P, 1):
        if g != f: continue
        if old == new: stop("patch %d (%s) changes nothing: the script is wrong." % (i, what))
        n = t.count(old)
        if n != 1: stop("patch %d (%s): its text to replace is in %s %d times, not once. Has the file changed?" % (i, what, f, n))
        if bal(old) != bal(new): stop("patch %d (%s) would unbalance { } or ( ): the script is wrong." % (i, what))
        t = t.replace(old, new)
    out[f] = t.encode("utf-8")
for i, (f, what, old, new) in enumerate(P, 1):
    c = out[f].decode("utf-8").count(new)
    if c != 1: stop("patch %d (%s): after patching, its new text is in %s %d times, not once." % (i, what, f, c))
for f in files:
    if bal(raw[f].decode("utf-8")) != bal(out[f].decode("utf-8")): stop(f + ": { } or ( ) would no longer balance as before.")
# The sound code must match project B's word for word: compare each function body with B's file.
ref = "project-b-reference/" + SRC + "ShareActivity.kt"
if os.path.isfile(ref):
    with open(ref, "rb") as h: b_text = h.read().decode("utf-8")
    for marker in ("    private fun playSound(", "    private fun logSound(", "    private fun stopSound(", "    private fun soundPayload("):
        mine = out[files[0]].decode("utf-8"); i, j = mine.find(marker), b_text.find(marker)
        if i < 0 or j < 0: stop("couldn't find %s in both files." % marker.strip())
        end_m, end_b = mine.find("\n    }\n", i), b_text.find("\n    }\n", j)
        if mine[i:end_m] != b_text[j:end_b]: stop("%s doesn't match project B's word for word: the script is wrong." % marker.strip())
    print("The sound functions match project B's word for word.")
print("Checks passed: %d patches to %d file." % (len(P), len(files)))

try:
    os.makedirs(os.path.join(bk, "files"))
    with open(os.path.join(bk, "replaced.txt"), "w") as h:
        for f in files:
            dst = os.path.join(bk, "files", f)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as o: o.write(raw[f])
            with open(dst, "rb") as o:
                if sha(o.read()) != sha(raw[f]): raise IOError("the backup of %s doesn't match it" % f)
            h.write(f + "\n")
except Exception as e:
    shutil.rmtree(bk, ignore_errors=True)
    stop("couldn't back up: %s." % e)
print("Backed up to %s." % bk)

try:
    for f in files:
        tmp = f + ".step-c.tmp"
        with open(tmp, "wb") as o: o.write(out[f])
        os.replace(tmp, f)
        with open(f, "rb") as o:
            if sha(o.read()) != sha(out[f]): raise IOError("%s doesn't read back as written" % f)
except Exception as e:
    for f in files:
        try:
            with open(f, "wb") as o: o.write(raw[f])
        except Exception: print("  COULDN'T PUT BACK %s: its copy is in %s/files" % (f, bk))
    shutil.rmtree(bk, ignore_errors=True)
    print("FAILED: %s. Every file was put back as it was." % e)
    sys.exit(1)

with open(sums_path, "a") as h:
    for f in files: h.write("step%s %s %s\n" % (step, sha(out[f]), f))
for f in files:
    n = sum(1 for p in P if p[0] == f)
    print("  patched %s: %d changes, %d -> %d bytes, sha256 %s..." % (f, n, len(raw[f]), len(out[f]), sha(out[f])[:12]))
for i, (f, what, _, _) in enumerate(P, 1):
    print("    %d. %s: %s" % (i, os.path.basename(f), what))
print("Every file reads back as written.")
PYEOF
RC=$?
[ "$RC" = 0 ] || exit "$RC"

echo
echo "Backup: $BK   Checksums: $SUMS"
echo "Next: a Standard Run. Then, if you like, a quick check on the phone: start the server, tap Share this store...,"
echo "then Play sound / Stop sound. Paste all of this output, and the build result, to the agent."
exit 0
# END OF step-c-3-share.sh
