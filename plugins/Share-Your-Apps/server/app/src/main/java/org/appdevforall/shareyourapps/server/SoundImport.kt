package org.appdevforall.shareyourapps.server

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
 * 1. Listens for up to 30 s (SoundIo.WINDOW_MS), while McpService runs with the microphone type.
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
            return "person needed: the app isn't allowed to use the microphone. Ask a person to open Share Your Apps Server, " +
                "turn MCP control off and on again, and allow the microphone."
        }
        if (busy) return "Already listening: follow it with import_status, or stop it with import_cancel."
        if (ImportJob.snapshot.busy) return "An import is already running. Follow it with import_status, or end it with import_cancel."
        val r = ++run
        var strongest = 0.0
        var loudest = SoundDsp.METER_FLOOR_DB
        state = State(Phase.LISTENING, "Listening. Ask the owner to tap Play sound on their share screen.", SoundIo.WINDOW_MS / 1000)
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
                    val seconds = SoundIo.WINDOW_MS / 1000
                    when (end) {
                        SoundIo.End.NOTHING_HEARD -> {
                            SoundLog.write(app, SoundLog.nothingHeard(seconds, strongest))
                            // Only silence: Android may be blocking the microphone while the app is off screen.
                            state = if (loudest <= SoundDsp.METER_FLOOR_DB)
                                State(Phase.PERSON_NEEDED, "The microphone heard only silence for $seconds s. Android may block it while the app " +
                                    "is off screen: ask a person to open Share Your Apps Server, then call import_listen again.")
                            else State(Phase.NOTHING_HEARD, "Didn't hear the code in $seconds s. The phones may need to be closer, or the owner's " +
                                "media volume higher. Call import_listen again while the owner's phone plays its sound.")
                        }
                        SoundIo.End.NO_MICROPHONE -> {
                            SoundLog.write(app, SoundLog.listenFailed(why))
                            state = State(Phase.PERSON_NEEDED, "$why Ask a person to open Share Your Apps Server, then call import_listen again.")
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
    private fun byHand(p: SoundPayload, why: String) = State(Phase.PERSON_NEEDED, "$why A person must open Share Your Apps Server, " +
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
