#!/bin/sh
# step-c-5-soundimport.sh: step C, script 5 of 6. Adds the new file SoundImport.kt: hearing a share code for an MCP
# client (import_listen), with no screen. Script 6 adds the import_listen tool that uses it.
#
# Run:   sh "/storage/emulated/0/CodeOnTheGoProjects/Local Store Server Combined/step-c-5-soundimport.sh"
# Undo:  sh "/storage/emulated/0/CodeOnTheGoProjects/Local Store Server Combined/step-c-5-soundimport.sh" undo
#        (deletes SoundImport.kt)
#
# SoundImport.kt follows the import screen's Listen (project B), which stays unchanged:
#   listen up to 30 s -> heard: is the owner's phone reachable? -> yes, or no hotspot password: the import starts
#   (ImportJob, byMcp) -> else on Android 10+ WifiJoin asks to join the owner's hotspot (a person taps Connect)
#   -> Android 9, or no tap: "person needed" (a person uses Listen on the import screen). Never the password.
#
# Before writing it checks: this file ends with its END line; script 4 has run; this one hasn't; SoundImport.kt
# doesn't exist yet; every function it calls is in the project's files; its { } and ( ) balance; it ends with its
# own END line. Then it writes through a temporary file, reads it back and compares SHA-256. Backup record in
# step-c-backup/5; checksums in step-c-checksums.txt. Plain sh, no bash; needs Python.

set -u
NAME="step-c-5-soundimport.sh"
STEP=5

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
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if [ -e "$f" ]; then rm -f "$f" && echo "  removed: $f"; fi
  done < "$BK/added.txt"
  if [ -f "$SUMS" ]; then grep -v "^step$STEP " "$SUMS" > "$SUMS.tmp"; mv "$SUMS.tmp" "$SUMS"; fi
  mv "$BK" "$BK.undone-$(date +%Y%m%d-%H%M%S)"
  echo "Undone."
  return 0
}

case "${1:-}" in
  "") ;;
  undo)
    if [ -d "step-c-backup/6" ]; then
      echo "STOPPED: step C script 6 has run since this one. Undo it first (its script, with undo). Nothing was changed."
      exit 1
    fi
    echo "Undoing $NAME..."
    undo; exit $? ;;
  *) fail "unknown option \"$1\". Run it with nothing, or with undo." ;;
esac

[ -d "step-c-backup/4" ] || fail "step-c-4-mic.sh hasn't run yet (step-c-backup/4 isn't here). Run it first."
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
TARGET = SRC + "SoundImport.kt"

KT = r'''package org.appdevforall.localstore.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Hearing a share code for an MCP client (import_listen), with no screen: the same steps as the import screen's
 * Listen (project B), which this leaves unchanged. Main process only; the listener's and WifiJoin's answers run on
 * the main thread.
 *
 * 1. Listens for up to 30 s (SoundIo.MAX_MS), while McpService runs with the microphone type.
 * 2. Heard: can this phone reach the owner's phone on its Wi-Fi (WifiJoin.reachable, about 2 s)?
 * 3. Reachable, or the tones carry no hotspot password: the import starts, as import_start would (byMcp).
 * 4. Otherwise, on Android 10 and later, WifiJoin asks Android to join the owner's own hotspot: a person taps
 *    Connect within 30 s. Joined: the import starts on that network, which is let go once the import ends.
 * 5. Android 9, or no tap: a person is needed, who uses Listen on the import screen; it shows the hotspot's name
 *    and password for joining by hand.
 *
 * The hotspot's password is never put in the state, the client's answers or the log.
 */
object SoundImport {
    enum class Phase { IDLE, LISTENING, CHECKING, JOINING, IMPORT_STARTED, NOTHING_HEARD, PERSON_NEEDED, FAILED, STOPPED }

    /** What import_listen reports. [ssid]: the owner's network name, from the tones; never its password. */
    class State(val phase: Phase, val message: String, val secondsLeft: Long = 0, val signal: Double = 0.0,
                val code: String? = null, val ssid: String? = null)

    private val main = Handler(Looper.getMainLooper())
    @Volatile var state = State(Phase.IDLE, "Not listening."); private set
    @Volatile private var listener: SoundIo.Listener? = null
    /** Counts listens started and stopped, so a late answer about an older one is ignored. */
    @Volatile private var run = 0

    /** Listening, checking the network, or waiting for a person's Connect tap. */
    val busy: Boolean get() = state.phase in setOf(Phase.LISTENING, Phase.CHECKING, Phase.JOINING)

    /**
     * Starts listening. Returns null once started, or why not: "person needed: ..." when only a person can fix it.
     * The caller checks first that this phone isn't serving, as import_start does.
     */
    @Synchronized
    fun start(context: Context, phoneName: String): String? {
        val app = context.applicationContext
        if (!SoundIo.canListen(app)) {
            SoundLog.write(app, SoundLog.microphoneRefused())
            return "person needed: the app isn't allowed to use the microphone. Ask a person to open Local Store Server, " +
                "turn MCP control off and on again, and allow the microphone."
        }
        if (busy) return "Already listening: follow it with import_status, or stop it with import_cancel."
        if (ImportJob.snapshot.busy) return "An import is already running. Follow it with import_status, or end it with import_cancel."
        val r = ++run
        var strongest = 0.0
        var loudest = SoundDsp.METER_FLOOR_DB
        state = State(Phase.LISTENING, "Listening. Ask the owner to tap Play sound on their share screen.", SoundIo.MAX_MS / 1000)
        SoundLog.write(app, "listening for a share code, for an MCP client")
        listener = SoundIo.Listener(app,
            level = { v ->
                if (r == run) {
                    strongest = maxOf(strongest, v.signal)
                    loudest = maxOf(loudest, v.db)
                    state = State(Phase.LISTENING, hearing(v.signal), (v.msLeft + 999) / 1000, v.signal)
                }
            },
            heard = { p, how -> if (r == run) { listener = null; onHeard(app, r, p, how, phoneName) } },
            ended = { end, why ->
                if (r == run) {
                    listener = null
                    val seconds = SoundIo.MAX_MS / 1000
                    when (end) {
                        SoundIo.End.NOTHING_HEARD -> {
                            SoundLog.write(app, SoundLog.nothingHeard(seconds, strongest))
                            // Only silence: Android may be blocking the microphone while the app is off screen.
                            state = if (loudest <= SoundDsp.METER_FLOOR_DB)
                                State(Phase.PERSON_NEEDED, "The microphone heard only silence for $seconds s. Android may block it while the app " +
                                    "is off screen: ask a person to open Local Store Server, then call import_listen again.")
                            else State(Phase.NOTHING_HEARD, "Didn't hear the code in $seconds s. The phones may need to be closer, or the owner's " +
                                "media volume higher. Call import_listen again while the owner's phone plays its sound.")
                        }
                        SoundIo.End.NO_MICROPHONE -> {
                            SoundLog.write(app, SoundLog.listenFailed(why))
                            state = State(Phase.PERSON_NEEDED, "$why Ask a person to open Local Store Server, then call import_listen again.")
                        }
                        else -> {
                            SoundLog.write(app, SoundLog.listenFailed(why))
                            state = State(Phase.FAILED, why)
                        }
                    }
                }
            },
        ).start()
        return null
    }

    private fun hearing(signal: Double) = "Listening. " + when {
        signal < 0.15 -> "Nothing heard yet."
        signal < SoundDecoder.THRESHOLD -> "Hearing something faint: the phones may need to be closer."
        else -> "Hearing the tones."
    }

    /** A code was heard: first, whether this phone can already reach the owner's phone, off the main thread. */
    private fun onHeard(app: Context, r: Int, p: SoundPayload, how: String, name: String) {
        SoundLog.write(app, SoundLog.heard(p, how)) // never the password
        val address = p.address?.let { InetSocketAddress(it, p.port) }
        state = State(Phase.CHECKING, "Heard the code ${ShareCodes.display(p.code)}. Checking whether this phone can reach the owner's phone.",
            code = p.code, ssid = p.ssid)
        thread(name = "sound-reach-mcp") {
            val ok = WifiJoin.reachable(app, p.code, address)
            main.post { if (r == run) afterReach(app, r, p, name, address, ok) }
        }
    }

    /** As the import screen does: import at once; or join the owner's own hotspot first; or a person is needed. */
    private fun afterReach(app: Context, r: Int, p: SoundPayload, name: String, address: InetSocketAddress?, reachable: Boolean) {
        val ssid = p.ssid
        val password = p.password
        if (reachable || ssid == null || password == null) {
            val hint = if (!reachable && ssid != null) " The owner's phone is on the Wi-Fi network $ssid: if the import can't find it, " +
                "a person must join that network, then call import_start with the code." else ""
            startImport(app, p, name, address, null, hint)
            return
        }
        if (!WifiJoin.canJoin) {
            SoundLog.write(app, "the owner's hotspot $ssid must be joined by hand (Android 9)")
            state = byHand(p, "This phone runs Android 9, which can't join the owner's hotspot $ssid by itself.")
            return
        }
        val tapSeconds = WifiJoin.CONNECT_TAP_MS / 1000
        SoundLog.write(app, "joining the owner's hotspot $ssid, for an MCP client")
        state = State(Phase.JOINING, "Joining the owner's hotspot $ssid. A person must tap Connect when Android asks, within $tapSeconds s.",
            code = p.code, ssid = ssid)
        WifiJoin.join(app, ssid, password) { network ->
            if (r != run) return@join
            if (network != null) {
                SoundLog.write(app, "joined the owner's hotspot $ssid")
                startImport(app, p, name, address, network, "")
                // After start: the import is busy by now, so the hotspot is let go once it ends; at once if it didn't start.
                if (ImportJob.snapshot.busy) WifiJoin.holdUntilImportEnds() else WifiJoin.release()
            } else {
                SoundLog.write(app, "didn't join the owner's hotspot $ssid: no Connect tap within $tapSeconds s, or refused")
                state = byHand(p, "Nobody tapped Connect within $tapSeconds s, so this phone didn't join the owner's hotspot $ssid.")
            }
        }
    }

    /** A person must join the owner's hotspot. The client never gets its password: the import screen shows it. */
    private fun byHand(p: SoundPayload, why: String) = State(Phase.PERSON_NEEDED, "$why A person must open Local Store Server, " +
        "tap Import a store, then Listen while the owner's phone plays its sound again, and follow the screen: it joins the " +
        "hotspot, or shows its name and password.", code = p.code, ssid = p.ssid)

    private fun startImport(app: Context, p: SoundPayload, name: String, address: InetSocketAddress?, network: android.net.Network?, hint: String) {
        val why = ImportJob.start(app, p.code, name, address, network = network, byMcp = true)
        state = when {
            why == null -> State(Phase.IMPORT_STARTED, "Heard the code ${ShareCodes.display(p.code)}, and the import has started: " +
                "follow it with import_status.$hint", code = p.code, ssid = p.ssid)
            why.startsWith("person needed: ") -> State(Phase.PERSON_NEEDED, why.removePrefix("person needed: "), code = p.code, ssid = p.ssid)
            else -> State(Phase.FAILED, why, code = p.code, ssid = p.ssid)
        }
    }

    /** Stops listening, checking or joining; an import already started carries on (import_cancel ends that). */
    @Synchronized
    fun stop(context: Context): Boolean {
        if (!busy) return false
        val joining = state.phase == Phase.JOINING
        run++
        listener?.stop(); listener = null
        // A join still being asked for is let go, on the main thread, where WifiJoin runs.
        if (joining) main.post { WifiJoin.release() }
        SoundLog.write(context.applicationContext, SoundLog.stopped() + ": by an MCP client")
        state = State(Phase.STOPPED, "Stopped.")
        return true
    }

    /** import_listen's and import_status's report. Never the hotspot's password. */
    fun status(): JSONObject {
        val s = state
        val out = JSONObject().put("phase", s.phase.name.lowercase()).put("message", s.message)
        if (s.phase == Phase.LISTENING) out.put("seconds_left", s.secondsLeft).put("hearing", when {
            s.signal < 0.15 -> "nothing"
            s.signal < SoundDecoder.THRESHOLD -> "faint"
            else -> "tones"
        })
        s.code?.let { out.put("code", ShareCodes.display(it)) }
        s.ssid?.let { out.put("owner_network", it) }
        if (s.phase == Phase.PERSON_NEEDED) out.put("person_needed", s.message)
        return out
    }
}
// END OF SoundImport.kt
'''

# What SoundImport.kt calls, which must be in the project as it is now: (file, text), "*" = any file in SRC.
NEEDS = [
    ("SoundIo.kt", "fun canListen("), ("SoundIo.kt", "class Listener("), ("SoundIo.kt", "const val MAX_MS"),
    ("SoundIo.kt", "enum class End { STOPPED, NOTHING_HEARD, NO_MICROPHONE, FAILED }"),
    ("WifiJoin.kt", "fun reachable(context: Context, code: String, address: InetSocketAddress?)"),
    ("WifiJoin.kt", "fun join(context: Context, ssid: String, password: String, done: (Network?) -> Unit)"),
    ("WifiJoin.kt", "fun holdUntilImportEnds()"), ("WifiJoin.kt", "fun release()"),
    ("WifiJoin.kt", "val canJoin"), ("WifiJoin.kt", "const val CONNECT_TAP_MS"),
    ("ImportJob.kt", "network: android.net.Network? = null, byMcp: Boolean = false): String?"),
    ("SoundLog.kt", "fun heard("), ("SoundLog.kt", "fun nothingHeard("), ("SoundLog.kt", "fun microphoneRefused("),
    ("SoundLog.kt", "fun listenFailed("), ("SoundLog.kt", "fun stopped("), ("SoundLog.kt", "fun write(context: Context, message: String)"),
    ("SoundDsp.kt", "METER_FLOOR_DB"), ("SoundDecoder.kt", "THRESHOLD"),
    ("*", "fun display("),
]

def stop(msg):
    print("STOPPED: " + msg)
    print("Nothing was changed.")
    sys.exit(1)
def sha(b): return hashlib.sha256(b).hexdigest()
def bal(t): return (t.count("{") - t.count("}"), t.count("(") - t.count(")"))
def read(p):
    with open(p, encoding="utf-8") as h: return h.read()

if os.path.exists(TARGET): stop(TARGET + " already exists. Has this script, or a hand edit, been applied already?")
for f, needle in NEEDS:
    if f == "*":
        found = any(needle in read(SRC + n) for n in os.listdir(SRC) if n.endswith(".kt"))
    else:
        found = os.path.isfile(SRC + f) and needle in read(SRC + f)
    if not found: stop("%s doesn't have \"%s\", which SoundImport.kt calls: the script is wrong, or the file changed." % (f, needle))
print("Every function SoundImport.kt calls is in the project (%d checked)." % len(NEEDS))
if not KT.startswith("package org.appdevforall.localstore.server\n"): stop("SoundImport.kt's text doesn't start with its package line.")
if not KT.endswith("}\n// END OF SoundImport.kt\n"): stop("SoundImport.kt's text is cut off: it doesn't end with its END line.")
if bal(KT) != (0, 0): stop("SoundImport.kt's { } or ( ) don't balance %s: the script is wrong." % (bal(KT),))
data = KT.encode("utf-8")
print("Checks passed: SoundImport.kt, %d lines, %d bytes." % (KT.count("\n"), len(data)))

try:
    os.makedirs(bk)
    with open(os.path.join(bk, "added.txt"), "w") as h: h.write(TARGET + "\n")
    tmp = TARGET + ".step-c.tmp"
    with open(tmp, "wb") as o: o.write(data)
    os.replace(tmp, TARGET)
    with open(TARGET, "rb") as o:
        if sha(o.read()) != sha(data): raise IOError("%s doesn't read back as written" % TARGET)
except Exception as e:
    for p in (TARGET, TARGET + ".step-c.tmp"):
        try: os.remove(p)
        except Exception: pass
    shutil.rmtree(bk, ignore_errors=True)
    print("FAILED: %s. Nothing was left behind." % e)
    sys.exit(1)

with open(sums_path, "a") as h: h.write("step%s %s %s\n" % (step, sha(data), TARGET))
print("  added %s: %d bytes, sha256 %s..." % (TARGET, len(data), sha(data)[:12]))
print("It reads back as written.")
PYEOF
RC=$?
[ "$RC" = 0 ] || exit "$RC"

echo
echo "Record: $BK   Checksums: $SUMS"
echo "Next: a Standard Run (nothing uses SoundImport yet: this checks it compiles). Script 6 adds import_listen."
echo "Paste all of this output, and the build result, to the agent."
exit 0
# END OF step-c-5-soundimport.sh
