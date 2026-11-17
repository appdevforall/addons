package org.appdevforall.shareyourapps.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Gets this phone onto the owner's network after hearing their tones (project B, step B5). Main process only;
 * [join]'s answer and the release run on the main thread.
 *
 * 1. [reachable]: is the owner's server already reachable on this phone's Wi-Fi? Tried for about [REACH_MS]:
 *    a connection to the heard address, or, with none, a search for the code.
 * 2. If not, and the tones carried the owner's own hotspot with its password, [join] asks Android to join it, on
 *    Android 10 and later. Android shows a "Connect?" prompt, and the person taps it. The network is this app's
 *    alone: where the phone can hold two Wi-Fi networks its other connections carry on; where it can't, they
 *    pause meanwhile.
 * 3. Android keeps such a network only while the app holds its request. [holdUntilImportEnds] lets it go once
 *    the import is over (done, denied, cancelled, or failed with no Try again); [release] lets it go at once.
 *    A failed transfer that can be tried again keeps it, so Try again works; leaving the import screen then
 *    lets it go.
 *
 * On Android 9, or when the person doesn't tap Connect within [CONNECT_TAP_MS], the person joins by hand.
 * The password is never logged.
 */
object WifiJoin {
    /** How long to try the phone's current Wi-Fi before asking to join. */
    const val REACH_MS = 2_000
    /** How long to wait for the person to tap Connect. */
    const val CONNECT_TAP_MS = 30_000

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var holder: (() -> Unit)? = null
    private var joinedSsid: String? = null
    /** Whether Android joined the network asked for, as opposed to still asking or having refused. */
    private var joined = false

    /** Whether [join] can work on this phone: Android 10 or later. */
    val canJoin: Boolean get() = Build.VERSION.SDK_INT >= 29

    /** Whether a join is still waiting for the person's Connect tap. */
    val asking: Boolean get() = callback != null && !joined

    /**
     * Whether the owner's server answers on this phone's Wi-Fi: a connection to [address], or, with none, a search
     * for [code], each for about [REACH_MS]. Blocks: call it off the main thread.
     */
    fun reachable(context: Context, code: String, address: InetSocketAddress?): Boolean {
        val wifi = wifiNetwork(context) ?: return false
        return try {
            if (address != null) Socket().use { s -> wifi.bindSocket(s); s.connect(address, REACH_MS); true }
            else ShareDiscovery.find(code, timeoutMs = REACH_MS, bind = { wifi.bindSocket(it) }) != null
        } catch (e: Exception) { false }
    }

    /**
     * Asks Android to join the hotspot [ssid] with [password], for this app only. [done] runs on the main thread,
     * once: with the network, or null if the person didn't tap Connect within [CONNECT_TAP_MS], refused, or this
     * phone can't. Nothing runs if [release] came first. The network stays joined until it's released.
     */
    fun join(context: Context, ssid: String, password: String, done: (Network?) -> Unit) {
        release()
        if (Build.VERSION.SDK_INT < 29) { done(null); return }
        val app = context.applicationContext
        val c = app.getSystemService(ConnectivityManager::class.java) ?: run { done(null); return }
        val request = try {
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // a hotspot has no internet
                .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build())
                .build()
        } catch (e: Exception) { done(null); return } // a name or password Android won't take
        var answered = false
        lateinit var cb: ConnectivityManager.NetworkCallback
        fun answer(n: Network?) {
            if (answered || callback !== cb) return
            answered = true
            if (n == null) release() else joined = true
            done(n)
        }
        cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { answer(network) } }
            override fun onUnavailable() { main.post { answer(null) } }
        }
        appCtx = app; cm = c; callback = cb; joinedSsid = ssid
        try { c.requestNetwork(request, cb, CONNECT_TAP_MS) }
        catch (e: Exception) { callback = null; joinedSsid = null; done(null) }
    }

    /** Lets the joined network go once the import is over: done, denied, cancelled, or failed with no Try again. */
    fun holdUntilImportEnds() {
        holder?.let { ImportJob.remove(it) }
        val l: () -> Unit = { val s = ImportJob.snapshot; if (!s.busy && !s.canRetry) release() }
        holder = l
        ImportJob.observe(l)
    }

    /** Lets the joined network go now, if one is held or being asked for; the phone returns to its own Wi-Fi. */
    fun release() {
        holder?.let { ImportJob.remove(it) }
        holder = null
        val cb = callback ?: return
        callback = null
        try { cm?.unregisterNetworkCallback(cb) } catch (e: Exception) { }
        val ssid = joinedSsid
        val wasJoined = joined
        joinedSsid = null; joined = false
        // Only a network Android actually joined is "let go of"; a request that got no Connect tap logs nothing here.
        if (wasJoined) appCtx?.let { SoundLog.write(it, "let go of the owner's hotspot ${ssid ?: ""}".trimEnd()) }
    }

    @Suppress("DEPRECATION")
    private fun wifiNetwork(context: Context): Network? {
        val c = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return c.allNetworks.firstOrNull { c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }
}
