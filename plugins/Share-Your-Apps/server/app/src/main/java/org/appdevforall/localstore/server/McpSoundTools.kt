package org.appdevforall.localstore.server

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
            p = SoundIo.Player(payload.encode(), { error ->
                synchronized(lock) { if (n == soundRun) { player = null; playingCode = null } }
                log(ctx, SoundLog.played(p.burstsPlayed, p.bursts, p.stoppedEarly, low, payload.ownHotspot, error) + ", for an MCP client")
            }, null)
            player = p.start()
            n
        }
        watch(ctx, r, code)
        val withHotspot = payload.ssid != null && payload.password != null
        val out = JSONObject().put("playing", true).put("already_playing", false).put("code", code)
            .put("seconds", SoundIo.WINDOW_MS / 1000).put("own_hotspot", payload.ownHotspot)
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
        android.util.Log.i("LocalStoreSound", line)
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
