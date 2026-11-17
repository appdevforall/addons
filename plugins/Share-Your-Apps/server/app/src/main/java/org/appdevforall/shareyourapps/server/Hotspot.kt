package org.appdevforall.shareyourapps.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * The hotspot the server uses when the phone isn't on Wi-Fi. Main process only.
 *
 * Tries a Wi-Fi Direct group first: its network name and password are chosen once and kept, so a
 * phone that joined before rejoins by itself. Wi-Fi Direct is part of Wi-Fi, so it can't start
 * while Wi-Fi is switched off, and it needs Android 10 for a fixed name and password. If it
 * fails, falls back to Android's local-only hotspot, whose name and password Android picks
 * afresh each time. Buyers join either one as an ordinary Wi-Fi network.
 */
object Hotspot {

    enum class Kind { WIFI_DIRECT, LOCAL_ONLY }
    class Info(val kind: Kind, val ssid: String, val password: String, val address: InetAddress)

    private const val PREF_NAME = "p2pName"
    private const val PREF_PASS = "p2pPass"
    private const val POLLS = 20        // × 500 ms: how long to wait for the hotspot's address
    private val main = Handler(Looper.getMainLooper())

    @Volatile var info: Info? = null
        private set
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var watcher: BroadcastReceiver? = null
    private var appCtx: Context? = null
    /** Called on the main thread if a running hotspot stops without being asked to. */
    var onLost: ((String) -> Unit)? = null

    /**
     * Starts a hotspot, or reuses the running one. Appends `[hotspot]` log lines to [log] as it
     * goes; [done] runs on the main thread with the hotspot, or null if neither kind would start.
     */
    fun start(ctx: Context, log: MutableList<String>, done: (Info?) -> Unit) {
        info?.let { done(it); return }
        val app = ctx.applicationContext
        appCtx = app
        if (Build.VERSION.SDK_INT >= 29) {
            startWifiDirect(app, log) { i -> if (i != null) done(i) else startLocalOnly(app, log, done) }
        } else {
            log += "[hotspot] Wi-Fi Direct skipped: a fixed network name needs Android 10 or later"
            startLocalOnly(app, log, done)
        }
    }

    /** Turns the hotspot off. Returns what was stopped, for the log, or null if nothing was running. */
    fun stop(): String? {
        val was = info ?: return null
        info = null
        unwatch()
        when (was.kind) {
            Kind.WIFI_DIRECT -> {
                val m = p2p; val ch = channel
                try { if (m != null && ch != null) m.removeGroup(ch, null) } catch (e: Exception) { }
                if (Build.VERSION.SDK_INT >= 27) try { ch?.close() } catch (e: Exception) { }
                channel = null; p2p = null
            }
            Kind.LOCAL_ONLY -> { try { reservation?.close() } catch (e: Exception) { }; reservation = null }
        }
        return if (was.kind == Kind.WIFI_DIRECT) "Wi-Fi Direct group" else "local-only hotspot"
    }

    // ------------------------------------------------------------------ Wi-Fi Direct

    private fun startWifiDirect(app: Context, log: MutableList<String>, next: (Info?) -> Unit) {
        fun fail(why: String) {
            log += "[hotspot] Wi-Fi Direct group failed: $why"
            if (Build.VERSION.SDK_INT >= 27) try { channel?.close() } catch (e: Exception) { }
            channel = null; p2p = null
            next(null)
        }
        val mgr = app.getSystemService(WifiP2pManager::class.java) ?: return fail("Wi-Fi Direct isn't available on this phone")
        val ch = mgr.initialize(app, Looper.getMainLooper(), null) ?: return fail("Wi-Fi Direct couldn't be initialized")
        p2p = mgr; channel = ch
        val (name, pass) = credentials(app)
        val config = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass).build()
        val create = {
            try {
                mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = waitForGroup(mgr, ch, name, pass, log, POLLS, next, ::fail)
                    override fun onFailure(reason: Int) = fail(p2pReason(reason))
                })
            } catch (e: SecurityException) { fail("permission denied: ${e.message}") }
        }
        // Clear any group left from an earlier run, whatever the outcome, then create ours.
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = create()
                override fun onFailure(reason: Int) = create()
            })
        } catch (e: SecurityException) { fail("permission denied: ${e.message}") }
    }

    private fun waitForGroup(
        mgr: WifiP2pManager, ch: WifiP2pManager.Channel, name: String, pass: String,
        log: MutableList<String>, triesLeft: Int, next: (Info?) -> Unit, fail: (String) -> Unit,
    ) {
        try {
            mgr.requestGroupInfo(ch) { g ->
                val addr = g?.`interface`?.let { ipv4Of(it) }
                when {
                    g != null && addr != null -> {
                        val i = Info(Kind.WIFI_DIRECT, g.networkName ?: name, g.passphrase ?: pass, addr)
                        info = i
                        log += "[hotspot] Wi-Fi Direct group up: ${i.ssid} at ${addr.hostAddress}"
                        watchGroup(mgr, ch)
                        next(i)
                    }
                    triesLeft > 0 -> main.postDelayed({ waitForGroup(mgr, ch, name, pass, log, triesLeft - 1, next, fail) }, 500)
                    else -> fail("the group started but never got an address")
                }
            }
        } catch (e: SecurityException) { fail("permission denied: ${e.message}") }
    }

    /**
     * Notices a Wi-Fi Direct group disappearing while the server uses it. Wi-Fi Direct turning
     * off (as it does when Wi-Fi is switched off) ends the group at once. A connection change —
     * also sent when buyers join or leave — prompts a check that the group still exists, and it
     * only counts as lost if it's still missing a second later, so a momentary gap can't stop
     * the server.
     */
    private fun watchGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val app = appCtx ?: return
        unwatch()
        fun check(confirming: Boolean) {
            if (info?.kind != Kind.WIFI_DIRECT) return
            try {
                mgr.requestGroupInfo(ch) { g ->
                    if (g != null || info?.kind != Kind.WIFI_DIRECT) return@requestGroupInfo
                    if (confirming) lost("the Wi-Fi Direct group stopped")
                    else main.postDelayed({ check(confirming = true) }, 1000)
                }
            } catch (e: SecurityException) { }
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (info?.kind != Kind.WIFI_DIRECT) return
                val p2pOff = i.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION &&
                    i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED
                if (p2pOff) lost("Wi-Fi was switched off, which stopped the Wi-Fi Direct group") else check(confirming = false)
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        // Only the system can send these, so the receiver may be exported.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, filter)
        watcher = r
    }

    private fun unwatch() {
        val r = watcher ?: return
        watcher = null
        try { appCtx?.unregisterReceiver(r) } catch (e: IllegalArgumentException) { }
    }

    /** The running group vanished on its own: tidy up, then let the service stop the server. */
    private fun lost(why: String) {
        if (info?.kind != Kind.WIFI_DIRECT) return
        info = null
        unwatch()
        if (Build.VERSION.SDK_INT >= 27) try { channel?.close() } catch (e: Exception) { }
        channel = null; p2p = null
        onLost?.invoke(why)
    }

    /**
     * The Wi-Fi Direct name and password: generated once and kept, so phones that joined before
     * rejoin by themselves, until the owner changes them in Manage Server.
     */
    fun credentials(ctx: Context): Pair<String, String> {
        val prefs = ctx.applicationContext.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(PREF_NAME, null)
        val pass = prefs.getString(PREF_PASS, null)
        if (name != null && pass != null) return name to pass
        val rnd = SecureRandom()
        val letters = "ABCDEFGHJKMNPQRSTUVWXYZ"
        val n = HotspotSettings.PREFIX + (1..4).map { letters[rnd.nextInt(letters.length)] }.joinToString("")
        val p = HotspotSettings.randomPassword()
        prefs.edit().putString(PREF_NAME, n).putString(PREF_PASS, p).apply()
        return n to p
    }

    /** Saves the owner's choice; used the next time a Wi-Fi Direct group starts. Checked by the caller. */
    fun saveCredentials(ctx: Context, name: String, password: String) {
        ctx.applicationContext.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_NAME, name).putString(PREF_PASS, password).apply()
    }

    private fun ipv4Of(ifaceName: String): InetAddress? = try {
        NetworkInterface.getByName(ifaceName)?.inetAddresses?.toList()?.firstOrNull { it is Inet4Address }
    } catch (e: Exception) { null }

    private fun p2pReason(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "this phone doesn't support Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi is busy or switched off"
        WifiP2pManager.ERROR -> "Android reported an error (Wi-Fi may be switched off)"
        else -> "reason $r"
    }

    // ------------------------------------------------------------------ local-only hotspot

    private fun startLocalOnly(app: Context, log: MutableList<String>, done: (Info?) -> Unit) {
        // Android 9 to 12 won't start a local-only hotspot while Location is off, and say only that it failed.
        if (Build.VERSION.SDK_INT in 28..31) {
            val lm = app.getSystemService(android.location.LocationManager::class.java)
            if (lm != null && !lm.isLocationEnabled) {
                log += "[hotspot] local-only hotspot failed: Location is off. This version of Android won't start the store's hotspot without it: turn Location on in Settings, then tap Start."
                done(null); return
            }
        }
        val wifi = app.getSystemService(WifiManager::class.java)
        if (wifi == null) { log += "[hotspot] local-only hotspot failed: Wi-Fi isn't available"; done(null); return }
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val (ssid, pass) = localOnlyCredentials(r)
                    waitForAddress(ssid, pass, log, POLLS, done)
                }
                override fun onStopped() {
                    if (info?.kind == Kind.LOCAL_ONLY) { info = null; reservation = null; onLost?.invoke("the local-only hotspot stopped") }
                }
                override fun onFailed(reason: Int) {
                    log += "[hotspot] local-only hotspot failed: ${lohsReason(reason)}"
                    done(null)
                }
            }, main)
        } catch (e: Exception) {
            log += "[hotspot] local-only hotspot failed: ${e.message ?: e.toString()}"
            done(null)
        }
    }

    private fun waitForAddress(ssid: String, pass: String, log: MutableList<String>, triesLeft: Int, done: (Info?) -> Unit) {
        val addr = pickHotspotAddress(systemInterfaces())
        when {
            addr != null -> {
                val i = Info(Kind.LOCAL_ONLY, ssid, pass, addr)
                info = i
                log += "[hotspot] local-only hotspot up: $ssid at ${addr.hostAddress}"
                done(i)
            }
            triesLeft > 0 -> main.postDelayed({ waitForAddress(ssid, pass, log, triesLeft - 1, done) }, 500)
            else -> {
                try { reservation?.close() } catch (e: Exception) { }
                reservation = null
                log += "[hotspot] local-only hotspot failed: it started but never got an address"
                done(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun localOnlyCredentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> =
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            (c.ssid ?: "?") to (c.passphrase ?: "")
        } else {
            val c = r.wifiConfiguration
            (c?.SSID?.trim('"') ?: "?") to (c?.preSharedKey?.trim('"') ?: "")
        }

    private fun lohsReason(r: Int) = when (r) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no Wi-Fi channel was available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "the phone's own hotspot or another Wi-Fi mode is in use"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "hotspots are disallowed on this phone"
        else -> "Android reported an error"
    }
}
