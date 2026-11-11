package org.appdevforall.localstore.server

import android.content.Context
import java.util.Locale

/**
 * The sound handshake's `[sound]` lines in the app's own log, so they show in View logs.
 *
 * The lines' text is built by plain functions, unit-tested, which never include a password. Where a line goes:
 * - On the sharing phone the server is running (sharing needs it), and its process owns the log file, so
 *   ShareActivity sends [line] to it with Ipc.MSG_LOG over the connection it already has.
 * - On the importing phone the server isn't running (importing is refused while serving), so [write] appends
 *   the line here, in the main process. It writes nothing while a server serves, so two processes never
 *   write the file at once; the line still goes to logcat.
 */
object SoundLog {
    const val TAG = "sound"
    private const val LOGCAT_TAG = "LocalStoreSound"

    /** The whole line, as the server's log writes it: `[sound] message`. */
    fun line(message: String) = "[$TAG] $message"

    fun played(burstsPlayed: Int, bursts: Int, stoppedEarly: Boolean, volumeLow: Boolean, ownHotspot: Boolean, error: String?): String {
        val what = if (ownHotspot) "the code and this phone's hotspot name and password" else "the code"
        // Stopped before one whole burst: "part of the first burst", which reads better than "0 of 36".
        val count = if (burstsPlayed == 0 && error == null) "part of the first burst" else "$burstsPlayed of $bursts bursts"
        val b = StringBuilder("played $what: $count")
        when {
            error != null -> b.append(", failed: ").append(error)
            stoppedEarly -> b.append(", stopped early")
            else -> b.append(", all bursts played")
        }
        if (volumeLow) b.append("; media volume was low")
        return b.toString()
    }

    /** A code heard from another phone: never its password, only whether one came. */
    fun heard(p: SoundPayload, how: String): String {
        val b = StringBuilder("heard code ${ShareCodes.display(p.code)} ($how)")
        if (p.address != null) b.append(", server ").append(p.address.hostAddress).append(':').append(p.port)
        when {
            p.ssid != null && p.password != null -> b.append(", its own hotspot ").append(p.ssid).append(" (password received, not logged)")
            p.ssid != null -> b.append(", on Wi-Fi ").append(p.ssid)
            p.ownHotspot -> b.append(", its own hotspot")
            else -> b.append(", no network name")
        }
        return b.toString()
    }

    fun nothingHeard(seconds: Long, strongest: Double) =
        "heard nothing in $seconds s (strongest start signal ${String.format(Locale.ROOT, "%.2f", strongest)})"

    fun microphoneRefused() = "can't listen: the microphone permission was refused"

    fun listenFailed(why: String) = "listening failed: $why"

    fun stopped() = "stopped listening"

    fun selfTest(passed: Boolean, detail: String) =
        if (passed) "self-test passed ($detail)" else "self-test failed: $detail"

    /** Appends a line from the main process, when no server is writing the log; always to logcat too. */
    fun write(context: Context, message: String) {
        android.util.Log.i(LOGCAT_TAG, line(message))
        if (ServerStatus.url != null) return // the server process owns the log file while it serves
        try {
            val prefs = context.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE)
            val limit = prefs.getInt(HostService.PREF_LOG_MB, HostService.DEFAULT_LOG_MB).toLong() * 1024 * 1024
            val log = StoreLog(Store.logDir(context), limit)
            try { log.status(TAG, message) } finally { log.close() }
        } catch (e: Exception) {
            // Logging must never break the handshake.
        }
    }
}
