#!/bin/sh
# step-c-6-tools.sh: step C, script 6 of 6. The MCP sound tools, share_play_sound and import_listen, and the help.
#
# Run:   sh "/storage/emulated/0/CodeOnTheGoProjects/Share Your Apps Server Combined/step-c-6-tools.sh"
# Undo:  sh "/storage/emulated/0/CodeOnTheGoProjects/Share Your Apps Server Combined/step-c-6-tools.sh" undo
#
# New file McpSoundTools.kt:
#   share_play_sound: plays the open share's code as tones, as the share screen's Play sound does; stops by itself
#     after 30 s or once the share moves on; stop true stops it sooner. Its "played" line goes to the server's log
#     over a connection of its own (decision D6 a): ShareControl still only opens, polls and closes a share.
#   import_listen: listens through SoundImport (script 5); returns at once; stop true stops it.
# McpTools.kt:
#   1. The tool list gets both tools; the app's context is kept for import_status.
#   2. import_start is refused while import_listen is listening or joining.
#   3. import_status: on_owners_network checked now (the stale import result fix), and copy_on_phone.
#   4. Its note uses the network as it is now.
#   5. import_status gets a listen part once import_listen has been used.
#   6. import_status's wait_seconds also ends when the listen part's phase changes.
#   7. import_cancel stops listening too.
# McpService.kt:
#   8. Turning MCP control off stops its tones and its listening.
# help/build_help_db.py (still exactly two server.mcp tooltips, as build_help_db.sh checks):
#   9. mcp.html: allow the microphone when turning MCP control on.  10. mcp.html: a Sound section.
#  11. The server.mcp tooltip mentions sound.
#
# Same checks as scripts 2 to 5, plus: build_help_db.py must still compile as Python. Backup in step-c-backup/6.
# Plain sh, no bash; needs Python.

set -u
NAME="step-c-6-tools.sh"
STEP=6

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
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if [ -e "$f" ]; then rm -f "$f" && echo "  removed: $f"; fi
  done < "$BK/added.txt"
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
  undo) echo "Undoing $NAME..."; undo; exit $? ;;
  *) fail "unknown option \"$1\". Run it with nothing, or with undo." ;;
esac

[ -d "step-c-backup/5" ] || fail "step-c-5-soundimport.sh hasn't run yet (step-c-backup/5 isn't here). Run it first."
[ -e "$BK" ] && fail "this script has already run ($BK exists). To run it again, undo it first: sh $NAME undo"

PY=""
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
fi
[ -n "$PY" ] || fail "neither python3 nor python was found."

"$PY" - "$STEP" "$BK" "$SUMS" <<'PYEOF'
import hashlib, os, shutil, sys
step, bk, sums_path = sys.argv[1], sys.argv[2], sys.argv[3]
SRC = "app/src/main/java/org/appdevforall/shareyourapps/server/"
TOOLS = SRC + "McpTools.kt"
SERVICE = SRC + "McpService.kt"
HELP = "help/build_help_db.py"
NEWF = SRC + "McpSoundTools.kt"
P = []
def patch(f, what, old, new): P.append((f, what, old, new))

patch(TOOLS, "tool list gets both tools; the app's context kept", r'''    private const val DEFAULT_START_WAIT_S = 10

    fun all(context: Context): List<McpTool> {
        val app = context.applicationContext
        return listOf(serverStatus(app), storeInfo(app), serverStart(app), serverStop(app),
            shareStart(app), shareStatus(app), shareStop(app),
            importStartTool(app), importStatusTool(app), importCancelTool(app))
    }''', r'''    private const val DEFAULT_START_WAIT_S = 10
    /** The app's context, kept by [all], so import_status can check the network now, not when the import ended. */
    @Volatile private var appCtx: Context? = null

    fun all(context: Context): List<McpTool> {
        val app = context.applicationContext
        appCtx = app
        // Step C's sound tools, share_play_sound and import_listen, are in McpSoundTools.
        return listOf(serverStatus(app), storeInfo(app), serverStart(app), serverStop(app),
            shareStart(app), shareStatus(app), shareStop(app),
            importStartTool(app), importStatusTool(app), importCancelTool(app)) + McpSoundTools.all(app)
    }''')

patch(TOOLS, "import_start refused while listening", r'''        val why = ImportJob.start(ctx, code, name, address, byMcp = true)''',
r'''        if (SoundImport.busy) throw McpToolError("import_listen is still listening or joining. Wait for it, or stop it with import_cancel.",
            JSONObject().put("state", "listening").put("listen", SoundImport.status()))
        val why = ImportJob.start(ctx, code, name, address, byMcp = true)''')

patch(TOOLS, "import_status: owner's network now, copy_on_phone", r'''            out.put("expires", s.expires?.toString() ?: JSONObject.NULL).put("on_owners_network", s.onOwnersNetwork)''',
r'''            // Checked now, not when the import ended: Wi-Fi may have been turned off or changed since. And whether the
            // copy is still on this phone: it may have been removed, expired or replaced since.
            val owners = appCtx?.let { ImportJob.onOwnersNetworkNow(it) } ?: s.onOwnersNetwork
            val still = appCtx?.let { c -> runCatching { ImportJob.currentCopy(c)?.storeId == s.storeId }.getOrDefault(true) } ?: true
            out.put("expires", s.expires?.toString() ?: JSONObject.NULL).put("on_owners_network", owners).put("copy_on_phone", still)''')

patch(TOOLS, "import_status note uses the network now", r'''            if (s.onOwnersNetwork) out.put("note", ''', r'''            if (owners) out.put("note", ''')

patch(TOOLS, "import_status listen part", r'''                .put("question", JSONObject().put("title", q.title).put("message", q.message))
        }
        return out''', r'''                .put("question", JSONObject().put("title", q.title).put("message", q.message))
        }
        // import_listen's part, once it has been used: listening, checking, joining, import_started, nothing_heard ...
        if (SoundImport.state.phase != SoundImport.Phase.IDLE) out.put("listen", SoundImport.status())
        return out''')

patch(TOOLS, "wait_seconds also ends on a listen change", r'''        val key = { ImportJob.snapshot.phase to ImportJob.question }''',
r'''        // import_listen's phase counts too: the loop below looks every 250 ms.
        val key = { Triple(ImportJob.snapshot.phase, ImportJob.question, SoundImport.state.phase) }''')

patch(TOOLS, "import_cancel stops listening too", r'''    ) { _ ->
        if (!ImportJob.snapshot.busy) {
            importStatus().put("cancelled", false).put("note", "No import is running.")
        } else {''', r'''    ) { _ ->
        // Listening, checking or joining for import_listen stops too; an import it already started is cancelled below.
        val stoppedListening = SoundImport.stop(ctx)
        if (!ImportJob.snapshot.busy) {
            if (stoppedListening) importStatus().put("cancelled", true).put("note", "Stopped listening. No import had started.")
            else importStatus().put("cancelled", false).put("note", "No import is running.")
        } else {''')

patch(SERVICE, "turning MCP off stops its tones and listening", r'''        thread(name = "mcp-share-shutdown") { ShareControl.shutdown() }''',
r'''        thread(name = "mcp-share-shutdown") { ShareControl.shutdown() }
        // Tones an MCP client started, and listening for one, end with MCP control.
        McpSoundTools.stopPlaying()
        SoundImport.stop(this)''')

patch(HELP, "mcp.html: allow the microphone", r'''<p>Turn on <b>MCP control</b> in the Server panel. The app first says what it allows; tap <b>Turn on</b>. A notification shows while''',
r'''<p>Turn on <b>MCP control</b> in the Server panel. The app first says what it allows; tap <b>Turn on</b>, then allow the microphone
if Android asks. A notification shows while''')

patch(HELP, "mcp.html: Sound section", r'''<h2 id=token>The token</h2>''', r'''<h2 id=sound>Sound</h2>
<p>An app can also use sound instead of a code. On the sharing phone, it plays the share's code as tones, as <b>Play sound</b> does,
for up to 30 seconds. On the importing phone, it listens for them, as <b>Listen</b> does, for up to 30 seconds, and starts the import
once it hears the code. Android shows its microphone indicator while it listens. If the sharing phone serves on its own hotspot,
Android asks to join it: tap <b>Connect</b>. Apps are never told the hotspot's password.</p>
<p>The line under the switch says if the microphone isn't allowed, or works only once the app is opened, as after the phone
restarts. Without it, a person uses <b>Listen</b> on the import screen instead.</p>
<h2 id=token>The token</h2>''')

patch(HELP, "server.mcp tooltip mentions sound", r'''     "They can't change the store or approve a copy: you still tap <b>Allow</b>. Other devices on the network can't reach it.</p>"''',
r'''     "They can't change the store or approve a copy: you still tap <b>Allow</b>. Other devices on the network can't reach it.</p>"
     "<p>Apps can also play the share code as sound, and use the microphone to hear another phone's, only while they ask it to.</p>"''')

KT = r'''package org.appdevforall.shareyourapps.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Step C's sound tools for MCP clients. share_play_sound: the sharing phone plays the open share's code as tones, as
 * the share screen's Play sound does. import_listen: the importing phone listens for them, through [SoundImport].
 * Kept apart from [McpTools] and [ShareControl]: ShareControl may only open, poll and close a share, so the "played"
 * line goes to the server's log over a short connection of its own here. The hotspot's password is never reported.
 */
object McpSoundTools {
    private val lock = Any()
    @Volatile private var player: SoundIo.Player? = null
    /** Counts players started and stopped, so a finished player's callback, or its watcher, can't touch a newer one. */
    @Volatile private var soundRun = 0
    @Volatile private var playingCode: String? = null

    fun all(context: Context): List<McpTool> {
        val app = context.applicationContext
        return listOf(sharePlaySound(app), importListen(app))
    }

    private fun boolArg(args: JSONObject, key: String): Boolean = when (val v = args.opt(key)) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        else -> throw McpToolError("$key must be true or false.")
    }

    private fun personNeeded(what: String) =
        McpToolError("A person is needed: $what", JSONObject().put("state", "person_needed").put("person_needed", what))

    // ---------------------------------------------------------------- share_play_sound

    private fun sharePlaySound(ctx: Context) = McpTool(
        name = "share_play_sound",
        title = "Play the share code as sound",
        description = "Plays the open share's code as a short run of tones through this phone's speaker, as the share screen's Play sound " +
            "does, so a phone nearby can hear it instead of scanning or typing it: import_listen there, or Listen on its import screen. " +
            "Needs a share waiting for a phone: call share_start first. Repeats for up to 30 seconds, and stops by itself once a phone " +
            "connects or the share ends; stop true stops it sooner. On this phone's own hotspot the tones also carry the hotspot's " +
            "name and password, so anyone close enough to hear them gets them. The password is never in this tool's answer.",
        inputSchema = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("stop", JSONObject().put("type", "boolean").put("description", "true: stop the tones now.")))
            .put("additionalProperties", false),
        readOnly = false,
    ) { args -> if (boolArg(args, "stop")) stopTool() else play(ctx) }

    private fun play(ctx: Context): JSONObject {
        if (ServerStatus.url == null) throw McpToolError("The server isn't serving, so there's no share to play. Call server_start, then share_start.",
            JSONObject().put("state", "not_serving"))
        val share = ShareControl.status(ctx, 0)
        val code = share.optString("code", "")
        val link = share.optString("link", "")
        if (share.optString("state") != "waiting_for_phone" || code.isEmpty())
            throw McpToolError("Sound only works while a share is waiting for a phone. Call share_start first.",
                JSONObject().put("state", "no_share_waiting").put("share", share))
        synchronized(lock) {
            if (player != null && playingCode == code) return JSONObject().put("playing", true).put("already_playing", true).put("code", code)
        }
        val payload = payload(code, link) ?: throw McpToolError("Couldn't turn this share's code into sound.")
        val low = SoundIo.volumeLow(ctx)
        val r = synchronized(lock) {
            player?.stop()
            val n = ++soundRun
            playingCode = code
            lateinit var p: SoundIo.Player
            p = SoundIo.Player(payload.encode()) { error ->
                synchronized(lock) { if (n == soundRun) { player = null; playingCode = null } }
                log(ctx, SoundLog.played(p.rounds, p.stoppedEarly, low, payload.ownHotspot, error) + ", for an MCP client")
            }
            player = p.start()
            n
        }
        watch(ctx, r, code)
        val withHotspot = payload.ssid != null && payload.password != null
        val out = JSONObject().put("playing", true).put("already_playing", false).put("code", code)
            .put("seconds", SoundIo.MAX_MS / 1000).put("own_hotspot", payload.ownHotspot)
            .put("carries", if (withHotspot) "the code, the server's address, and this phone's hotspot name and password" else "the code and the server's address")
            .put("next", "On the other phone, call import_listen, or tap Listen on its import screen. Follow this share with share_status.")
        if (low) out.put("warning", "The media volume is low: the other phone may not hear the tones. A person can turn it up.")
        return out
    }

    private fun stopTool(): JSONObject {
        val was = stopPlaying()
        val out = JSONObject().put("playing", false).put("stopped", was)
        if (!was) out.put("note", "No sound was playing.")
        return out
    }

    /** Stops the tones, if any are playing; their "played" line is still logged. Also called when MCP control is turned off. */
    fun stopPlaying(): Boolean {
        synchronized(lock) {
            val p = player ?: return false
            soundRun++
            p.stop(); player = null; playingCode = null
            return true
        }
    }

    /** Once a second: the tones stop when the share leaves waiting_for_phone, the code changes, or the server stops. */
    private fun watch(ctx: Context, r: Int, code: String) {
        thread(name = "sound-share-watch", isDaemon = true) {
            while (true) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { return@thread }
                if (r != soundRun || player == null) return@thread
                val s = try { ShareControl.status(ctx, 0) } catch (e: Exception) { null }
                val movedOn = s != null && (s.optString("state") != "waiting_for_phone" || s.optString("code") != code)
                if (movedOn || ServerStatus.url == null) {
                    synchronized(lock) { if (r == soundRun) { soundRun++; player?.stop(); player = null; playingCode = null } }
                    return@thread
                }
            }
        }
    }

    /**
     * What the tones carry, as on the share screen: the code and the server's address from the share link; on this
     * phone's own hotspot, also its name and password. On other Wi-Fi, neither (Hotspot.info is null there).
     */
    private fun payload(code: String, link: String): SoundPayload? {
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

    /** The "played" line: to the server's log while it serves (its process owns the file), else here. */
    private fun log(ctx: Context, message: String) {
        val app = ctx.applicationContext
        if (ServerStatus.url == null) { SoundLog.write(app, message); return }
        val line = SoundLog.line(message)
        android.util.Log.i("ShareYourAppsSound", line)
        Handler(Looper.getMainLooper()).post { LogOnce(app, line).send() }
    }

    /** Binds the server process without starting it, sends one log line, and lets go; gives up after 3 s. */
    private class LogOnce(private val app: Context, private val line: String) : ServiceConnection {
        private var done = false
        fun send() {
            val bound = try { app.bindService(Intent(app, ServerService::class.java), this, 0) } catch (e: Exception) { false }
            if (!bound) finish() else Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            try { Messenger(service).send(Message.obtain(null, Ipc.MSG_LOG).apply { data = Bundle().apply { putString(Ipc.KEY_LINE, line) } }) }
            catch (e: Exception) { }
            finish()
        }
        override fun onServiceDisconnected(name: ComponentName) { }
        private fun finish() {
            if (done) return
            done = true
            try { app.unbindService(this) } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- import_listen

    private fun importListen(ctx: Context) = McpTool(
        name = "import_listen",
        title = "Listen for a share code",
        description = "Listens with this phone's microphone, for up to 30 seconds, for the share code another phone plays as sound: " +
            "share_play_sound there, or Play sound on its share screen. Once it hears the code, the import starts, as import_start would. " +
            "Returns at once: follow it with import_status, whose listen part is one of listening, checking, joining, import_started, " +
            "nothing_heard, person_needed, failed or stopped. If the other phone serves on its own hotspot, Android asks a person here " +
            "to tap Connect to join it. The hotspot's password is never reported. stop true stops listening; import_cancel does too. " +
            "Refused while this phone is serving, starting or paused: call server_stop first. Refused while an import is running.",
        inputSchema = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("phone_name", JSONObject().put("type", "string").put("maxLength", 40)
                .put("description", "The name the other phone's owner sees, at most 40 characters. Default: this phone's name."))
            .put("stop", JSONObject().put("type", "boolean").put("description", "true: stop listening now.")))
            .put("additionalProperties", false),
        readOnly = false,
    ) { args -> if (boolArg(args, "stop")) stopListening(ctx) else listen(ctx, args) }

    private fun stopListening(ctx: Context): JSONObject {
        val was = SoundImport.stop(ctx)
        val out = SoundImport.status().put("stopped", was)
        if (!was) out.put("note", "Nothing was listening.")
        return out
    }

    private fun listen(ctx: Context, args: JSONObject): JSONObject {
        val name = McpTools.phoneNameArg(args) ?: ImportActivity.defaultPhoneName(ctx)
        // As import_start: a phone serving its own store can't import one. Starting and paused count too.
        val server = McpTools.stateOf(ServerStatus.text, ServerStatus.url)
        if (server == "serving" || server == "starting" || server == "paused")
            throw McpToolError("This phone is serving a store, or about to, and can't import one while it does. Stop the server first, with server_stop.",
                JSONObject().put("state", "server_running").put("server", McpTools.status(ctx)))
        val why = SoundImport.start(ctx, name)
        if (why != null) {
            if (why.startsWith("person needed: ")) throw personNeeded(why.removePrefix("person needed: "))
            throw McpToolError(why, JSONObject().put("state", if (SoundImport.busy) "listening" else "importing").put("listen", SoundImport.status()))
        }
        return SoundImport.status().put("phone_name", name)
            .put("next", "Ask the owner to play the code: Play sound on their share screen, or share_play_sound on their phone. " +
                "Follow with import_status, wait_seconds up to 25: its listen part says what was heard, and the import's own fields follow once it starts.")
    }
}
// END OF McpSoundTools.kt
'''

# What McpSoundTools.kt and the patches call, which must be in the project as it is now. "*" = any .kt file in SRC.
NEEDS = [
    ("*", "class McpTool("), ("*", "class McpToolError("),
    ("McpTools.kt", "fun stateOf(text: String, url: String?)"), ("McpTools.kt", "fun status(ctx: Context): JSONObject"),
    ("McpTools.kt", "internal fun phoneNameArg(args: JSONObject)"),
    ("ShareControl.kt", "fun status(ctx: Context, waitSeconds: Int)"),
    ("SoundImport.kt", "fun start(context: Context, phoneName: String)"), ("SoundImport.kt", "fun stop(context: Context): Boolean"),
    ("SoundImport.kt", "fun status(): JSONObject"), ("SoundImport.kt", "val busy"), ("SoundImport.kt", "enum class Phase { IDLE"),
    ("SoundIo.kt", "class Player("), ("SoundIo.kt", "fun volumeLow("), ("SoundIo.kt", "const val MAX_MS"),
    ("SoundPayload.kt", "MAX_FIELD"), ("SoundPayload.kt", "fun encode("),
    ("ImportJob.kt", "fun onOwnersNetworkNow("), ("ImportJob.kt", "val storeId: String?"),
    ("ImportActivity.kt", "fun defaultPhoneName(context: android.content.Context)"),
    ("SoundLog.kt", "fun played("), ("SoundLog.kt", "fun line("),
    ("*", "MSG_LOG"), ("*", "KEY_LINE"), ("*", "fun parse("), ("*", "fun normalize("),
]
GUARDS = {TOOLS: "McpSoundTools", SERVICE: "McpSoundTools", HELP: "id=sound>Sound</h2>"}

def stop(msg):
    print("STOPPED: " + msg)
    print("Nothing was changed.")
    sys.exit(1)
def sha(b): return hashlib.sha256(b).hexdigest()
def bal(t): return (t.count("{") - t.count("}"), t.count("(") - t.count(")"))
def read(p):
    with open(p, encoding="utf-8") as h: return h.read()

if os.path.exists(NEWF): stop(NEWF + " already exists. Has this script, or a hand edit, been applied already?")
for f, needle in NEEDS:
    if f == "*": found = any(needle in read(SRC + n) for n in os.listdir(SRC) if n.endswith(".kt"))
    else: found = os.path.isfile(SRC + f) and needle in read(SRC + f)
    if not found: stop("%s doesn't have \"%s\", which this script's code calls: the script is wrong, or the file changed." % (f, needle))
print("Every function the new code calls is in the project (%d checked)." % len(NEEDS))

files = []
for p in P:
    if p[0] not in files: files.append(p[0])
raw, out = {}, {}
for f in files:
    if not os.path.isfile(f): stop(f + " isn't here.")
    with open(f, "rb") as h: raw[f] = h.read()
    t = raw[f].decode("utf-8")
    if GUARDS[f] in t: stop(f + " already has %s in it. Has this script, or a hand edit, been applied already?" % GUARDS[f])
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
try:
    compile(out[HELP].decode("utf-8"), HELP, "exec")
except SyntaxError as e:
    stop("the patched %s isn't valid Python (%s): the script is wrong." % (HELP, e))
print("The patched build_help_db.py compiles as Python.")
if not KT.startswith("package org.appdevforall.shareyourapps.server\n"): stop("McpSoundTools.kt's text doesn't start with its package line.")
if not KT.endswith("}\n// END OF McpSoundTools.kt\n"): stop("McpSoundTools.kt's text is cut off: it doesn't end with its END line.")
if bal(KT) != (0, 0): stop("McpSoundTools.kt's { } or ( ) don't balance %s: the script is wrong." % (bal(KT),))
data = KT.encode("utf-8")
print("Checks passed: %d patches to %d files, and McpSoundTools.kt (%d lines, %d bytes)." % (len(P), len(files), KT.count("\n"), len(data)))

try:
    os.makedirs(os.path.join(bk, "files"))
    with open(os.path.join(bk, "added.txt"), "w") as h: h.write(NEWF + "\n")
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

writes = [(f, out[f]) for f in files] + [(NEWF, data)]
try:
    for f, b in writes:
        tmp = f + ".step-c.tmp"
        with open(tmp, "wb") as o: o.write(b)
        os.replace(tmp, f)
        with open(f, "rb") as o:
            if sha(o.read()) != sha(b): raise IOError("%s doesn't read back as written" % f)
except Exception as e:
    for f in files:
        try:
            with open(f, "wb") as o: o.write(raw[f])
        except Exception: print("  COULDN'T PUT BACK %s: its copy is in %s/files" % (f, bk))
    for p in (NEWF, NEWF + ".step-c.tmp"):
        try: os.remove(p)
        except Exception: pass
    shutil.rmtree(bk, ignore_errors=True)
    print("FAILED: %s. Every file was put back as it was." % e)
    sys.exit(1)

with open(sums_path, "a") as h:
    for f, b in writes: h.write("step%s %s %s\n" % (step, sha(b), f))
for f in files:
    n = sum(1 for p in P if p[0] == f)
    print("  patched %s: %d changes, %d -> %d bytes, sha256 %s..." % (f, n, len(raw[f]), len(out[f]), sha(out[f])[:12]))
print("  added   %s: %d bytes, sha256 %s..." % (NEWF, len(data), sha(data)[:12]))
for i, (f, what, _, _) in enumerate(P, 1):
    print("    %d. %s: %s" % (i, os.path.basename(f), what))
print("Every file reads back as written.")
PYEOF
RC=$?
[ "$RC" = 0 ] || exit "$RC"

echo
echo "Backup: $BK   Checksums: $SUMS"
echo "Next: sh help/build_help_db.sh (rebuilds the help; it should end with OK), then a Standard Run,"
echo "then :app:testDebugUnitTest. Paste all of this output, and the results, to the agent."
exit 0
# END OF step-c-6-tools.sh
