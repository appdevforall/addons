package org.appdevforall.localstore.server

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException

/**
 * The foreground service, in the app's main process. It keeps the app alive while the owner
 * is in CodeOnTheGo, and owns the server process: every start kills the old server process,
 * waits for it to die (so the port is free), and starts a fresh one.
 */
class HostService : Service() {

    companion object {
        const val ACTION_START = "org.appdevforall.localstore.server.START"
        const val ACTION_STOP = "org.appdevforall.localstore.server.STOP"
        /** Serve the imported copy (design, Appendix E), with the seller's own PIN. */
        const val ACTION_SERVE_COPY = "org.appdevforall.localstore.server.SERVE_COPY"
        /** Which kind of store was last served, "own" or "copy", so Start, unlocking and a reboot bring back the right one. */
        const val PREF_SOURCE = "source"
        /** The seller's PIN for the imported copy; the phone's own store keeps its own. */
        const val PREF_COPY_PIN = "copyPin"
        const val EXTRA_DIR = "dir"
        const val EXTRA_PORT = "port"
        /** With ACTION_STOP: why, for the log. Absent means the owner stopped it in Manage Server. */
        const val EXTRA_REASON = "reason"
        /** Set by LaunchActivity: the PIN, store name and token come from the plugin, not from saved settings. */
        const val EXTRA_FROM_PLUGIN = "fromPlugin"
        const val PREFS = "server"
        private const val PREF_PIN = "pin"
        const val PREF_STORE_NAME = "storeName"
        private const val PREF_SECRET = "cookieSecret"
        /** "always", or "screenOff": stop when the screen turns off. */
        const val PREF_RUN_MODE = "runMode"
        /** The access log's size limit in MB; 0 turns logging off. */
        const val PREF_LOG_MB = "logMb"
        const val DEFAULT_LOG_MB = 10
        private const val CHANNEL = "server"
        private const val NOTIFICATION = 1
        private const val DEATH_WAIT_MS = 3000L
        private const val STOP_ACK_WAIT_MS = 2000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg -> onReply(msg); true })

    private var connection: ServiceConnection? = null
    private var serverMessenger: Messenger? = null
    private var serverPid = 0
    private var request: Bundle? = null
    private var stopAck: (() -> Unit)? = null
    private var stopAckGen = -1
    /** Buyers need these to join the hotspot, until the Serve screen shows them as a QR code. */
    private var hotspotLine: String? = null
    /** Log lines from a failed hotspot attempt, written when a server next starts. */
    private val pendingLines = mutableListOf<String>()

    /** Bumped by every request; a callback from an older request does nothing. */
    private var requestSeq = 0
    /** Bumped by every server start, and echoed by that server in its replies. */
    private var serverGen = 0
    /** The only server whose started/failed replies count; -1 once its stop has begun. */
    private var acceptGen = -1
    /** A stop is in progress; later stop requests wait for it instead of racing it. */
    private var stopping = false
    private val afterStop = mutableListOf<() -> Unit>()

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android only delivers the screen-off event to receivers registered while running, so the
     * service registers one for as long as it runs, and checks the run mode each time.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_RUN_MODE, "always")
            if (mode != "screenOff" || connection == null) return
            val mySeq = ++requestSeq
            stopServer(withHotspot("paused: the screen turned off")) { if (mySeq == requestSeq) pause() }
        }
    }

    /**
     * Resumes a paused server when the phone is unlocked — not merely when the screen lights up,
     * so a notification waking the screen doesn't restart anything. Takes the same path as a
     * manual Start, with the saved values.
     */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!paused) return
            paused = false
            pendingLines += "[server] resuming: the phone was unlocked"
            startService(Intent(this@HostService, HostService::class.java).setAction(ACTION_START))
        }
    }

    /** Paused by screen-off: nothing is served and the hotspot is off, but the service stays, to notice the unlock. */
    @Volatile private var paused = false

    private fun pause() {
        Hotspot.stop()
        hotspotLine = null
        unwatchWifi()
        paused = true
        ServerStatus.set("Paused while the screen is off. It resumes when you unlock the phone.")
        goForeground("Paused while the screen is off — resumes when you unlock the phone")
    }

    // ---------------------------------------------------------------- watching the Wi-Fi network

    /** The Wi-Fi address the running server is bound to, when it's on Wi-Fi; checked whenever Wi-Fi changes. */
    private var boundWifi: String? = null
    private var wifiWatch: ConnectivityManager.NetworkCallback? = null
    /** Set when a start chooses Wi-Fi or the Mobile hotspot; the watch begins once the server has bound. */
    private var startingOnWifi: String? = null
    private var startingOnPhoneHotspot = false
    /** Serving on the phone's Mobile hotspot rather than Wi-Fi: changes the reason, and what the owner is told. */
    private var watchingPhoneHotspot = false
    /** Android tells apps nothing when the Mobile hotspot is turned off, so the address is also checked every few seconds. */
    private val poll = object : Runnable {
        override fun run() { if (boundWifi == null) return; checkWifi(confirming = false); main.postDelayed(this, 5_000) }
    }

    private fun watchWifi(address: String, phoneHotspot: Boolean) {
        unwatchWifi()
        boundWifi = address
        watchingPhoneHotspot = phoneHotspot
        main.postDelayed(poll, 5_000)
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { main.post { checkWifi(confirming = false) } }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { main.post { checkWifi(confirming = false) } }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            wifiWatch = cb
        } catch (e: Exception) { }
    }

    private fun unwatchWifi() {
        wifiWatch?.let { cb -> try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: Exception) { } }
        wifiWatch = null
        boundWifi = null
        main.removeCallbacks(poll)
    }

    /**
     * Stops the server if the phone no longer has the address it's bound to — it left the
     * network, or got a new address. Checked again two seconds later first, so a brief blip
     * during a change can't stop anything.
     */
    private fun checkWifi(confirming: Boolean) {
        val address = boundWifi ?: return
        val stillThere = systemInterfaces().any { i -> i.up && i.addresses.any { it.hostAddress == address } }
        if (stillThere) return
        if (!confirming) { main.postDelayed({ checkWifi(confirming = true) }, 2_000); return }
        val phone = watchingPhoneHotspot
        unwatchWifi()
        val why = if (phone) "the phone's Mobile hotspot was turned off" else "the Wi-Fi network was lost"
        val advice = if (phone) "Turn it on again and tap Start, or tap Start to use the server's own hotspot."
            else "Connect to Wi-Fi again, or tap Start to use a hotspot instead."
        val mySeq = ++requestSeq
        stopServer(why) { if (mySeq == requestSeq) finish("Stopped: $why. $advice") }
    }

    override fun onCreate() {
        super.onCreate()
        // A local-only hotspot can be stopped by the system; the store can't be reached without it.
        Hotspot.onLost = { why ->
            val mySeq = ++requestSeq
            stopServer(why) { if (mySeq == requestSeq) finish("Stopped: $why. " + if (ServerStatus.servingCopy) "Tap Start to start again." else "Tap Start, or publish again, to start a new one.") }
        }
        // Only the system can send SCREEN_OFF and USER_PRESENT, so the receivers may be exported.
        for ((receiver, action) in listOf(screenOff to Intent.ACTION_SCREEN_OFF, unlocked to Intent.ACTION_USER_PRESENT)) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(receiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() requires startForeground() promptly, whatever the action.
        goForeground(if (intent?.action == ACTION_START || intent?.action == ACTION_SERVE_COPY) "Starting…" else "Stopping…")
        val mySeq = ++requestSeq
        val current = { mySeq == requestSeq }
        // Any request ends a pause, and the Wi-Fi watch restarts with the next successful start.
        paused = false
        unwatchWifi()
        when (intent?.action) {
            ACTION_SERVE_COPY -> serveCopy(current)
            ACTION_START -> {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val fromPlugin = intent.getBooleanExtra(EXTRA_FROM_PLUGIN, false)
                // A manual Start, unlocking or a reboot brings back a copy if a copy was last served.
                // Publishing from the plugin always serves this phone's own store (Appendix E.6).
                // A phone with no store of its own, only a copy, has nothing else it could serve.
                val ownSaved = prefs.getString(EXTRA_DIR, null) != null
                if (!fromPlugin && intent.getStringExtra(EXTRA_DIR) == null && ImportJob.currentCopy(this) != null &&
                    (prefs.getString(PREF_SOURCE, "own") == "copy" || !ownSaved)) {
                    serveCopy(current); return START_NOT_STICKY
                }
                // The plugin names the directory; a manual Start serves the last one published.
                val dir = intent.getStringExtra(EXTRA_DIR) ?: prefs.getString(EXTRA_DIR, null)
                if (dir == null) {
                    stopServer("no request") { if (current()) finish("Failed: nothing has been published yet. Publish from the Local Store plugin first.") }
                    return START_NOT_STICKY
                }
                val req = Bundle().apply {
                    putString(Ipc.KEY_DIR, dir)
                    putInt(Ipc.KEY_PORT, intent.getIntExtra(EXTRA_PORT, prefs.getInt(EXTRA_PORT, Store.DEFAULT_PORT)))
                    putLong(Ipc.KEY_LOG_LIMIT, prefs.getInt(PREF_LOG_MB, DEFAULT_LOG_MB).toLong() * 1024 * 1024)
                    putString(Ipc.KEY_SECRET, cookieSecret())
                    if (fromPlugin) {
                        putString(Ipc.KEY_PIN, intent.getStringExtra(LaunchContract.EXTRA_PIN))
                        putString(Ipc.KEY_STORE_NAME, intent.getStringExtra(LaunchContract.EXTRA_STORE_NAME))
                        putString(Ipc.KEY_TOKEN, intent.getStringExtra(LaunchContract.EXTRA_TOKEN)) // memory only
                    } else {
                        // A manual Start, or a start after a reboot, uses the saved values; no token.
                        putString(Ipc.KEY_PIN, prefs.getString(PREF_PIN, null))
                        putString(Ipc.KEY_STORE_NAME, prefs.getString(PREF_STORE_NAME, null))
                    }
                }
                ServerStatus.set("Starting…")
                // The reason is Manage Server's unless the caller names another, as MCP control does ("restarted by an MCP client").
                val why = if (fromPlugin) "restarting for a new publish ($dir)"
                    else intent.getStringExtra(EXTRA_REASON)?.let { "$it ($dir)" } ?: "restarted from Manage Server ($dir)"
                stopServer(why) { if (current()) chooseNetwork(req, current) { startServer(req) } }
            }
            // The reason is the owner's unless the caller names another, as MCP control does ("stopped by an MCP client").
            ACTION_STOP -> stopServer(withHotspot(intent.getStringExtra(EXTRA_REASON) ?: "stopped by the owner in Manage Server")) { if (current()) finish("Stopped") }
            else -> stopServer("no request") { if (current()) finish("Stopped") }
        }
        return START_NOT_STICKY
    }

    /** Serves the imported copy, from the app's private storage, with the seller's PIN and no token. */
    private fun serveCopy(current: () -> Boolean) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // An expired copy is deleted before it could be served again (Appendix E.5).
        val expired = CopyExpiry.enforce(ImportJob.importedDir(this), ExpiryClock(CopyExpiry.clockFile(filesDir)))
        val copy = ImportJob.currentCopy(this)
        if (copy == null) {
            val why = expired?.let { "Stopped: the copy of ${it.storeName} expired, and was deleted." } ?: "Failed: there's no imported store on this phone."
            stopServer("no copy") { if (current()) finish(why) }
            return
        }
        val req = Bundle().apply {
            putString(Ipc.KEY_COPY_PATH, java.io.File(ImportJob.importedDir(this@HostService), copy.storeId).path)
            putString(Ipc.KEY_DIR, copy.storeName)
            putInt(Ipc.KEY_PORT, prefs.getInt(EXTRA_PORT, Store.DEFAULT_PORT))
            putLong(Ipc.KEY_LOG_LIMIT, prefs.getInt(PREF_LOG_MB, DEFAULT_LOG_MB).toLong() * 1024 * 1024)
            putString(Ipc.KEY_SECRET, cookieSecret())
            putString(Ipc.KEY_PIN, prefs.getString(PREF_COPY_PIN, null))
            putString(Ipc.KEY_STORE_NAME, copy.storeName)
        }
        ServerStatus.set("Starting…")
        stopServer("serving the imported copy of ${copy.storeName}") { if (current()) chooseNetwork(req, current) { startServer(req) } }
    }

    override fun onDestroy() {
        for (r in listOf(screenOff, unlocked)) try { unregisterReceiver(r) } catch (e: IllegalArgumentException) { }
        unwatchWifi()
        stopServer("the app's service was stopped") { }
    }

    private fun startServer(req: Bundle) {
        serverGen++
        acceptGen = serverGen
        req.putInt(Ipc.KEY_GEN, serverGen)
        request = req
        val conn = object : ServiceConnection {
            /** Set when the server process died on its own, so its restart is logged. */
            var restarting = false
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this) return
                val m = Messenger(service)
                serverMessenger = m
                val data = Bundle(req)
                if (restarting) {
                    restarting = false
                    data.putStringArrayList(Ipc.KEY_LOG_LINES, arrayListOf("[server] restarted after the server process stopped unexpectedly"))
                }
                val msg = Message.obtain(null, Ipc.MSG_START).apply { this.data = data; replyTo = replies }
                try { m.send(msg) } catch (e: RemoteException) { finish("Failed: the server process didn't respond") }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                // The server process died on its own. BIND_AUTO_CREATE restarts it, and
                // onServiceConnected sends the same request again.
                if (connection === this) { restarting = true; ServerStatus.set("Restarting after the server stopped unexpectedly…") }
            }
        }
        connection = conn
        val bound = bindService(Intent(this, ServerService::class.java), conn,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        if (!bound) {
            connection = null
            finish("Failed: couldn't start the server process")
        }
    }

    /**
     * Stops the server process and calls [then] once it's gone. First asks the server to log
     * [reason] and close its sockets; when it confirms — or after [STOP_ACK_WAIT_MS] regardless —
     * kills the process and waits for its death, so the port is free for the next start.
     */
    private fun stopServer(reason: String, then: () -> Unit) {
        if (stopping) { afterStop += then; return }
        val conn = connection
        val m = serverMessenger
        val pid = serverPid
        connection = null
        serverMessenger = null
        serverPid = 0
        if (conn == null) { then(); return }
        val stoppingGen = acceptGen
        acceptGen = -1 // from here on, a late "started" from this server is ignored

        stopping = true
        var finished = false
        val finishOnce = {
            if (!finished) {
                finished = true
                stopping = false
                then()
                val queued = afterStop.toList()
                afterStop.clear()
                queued.forEach { it() }
            }
        }

        var killed = false
        val kill = {
            if (!killed) {
                killed = true
                stopAck = null
                var waiting = false
                if (m != null) {
                    try {
                        m.binder.linkToDeath({ main.post(finishOnce) }, 0)
                        waiting = true
                    } catch (e: RemoteException) {
                        // already dead
                    }
                }
                try { unbindService(conn) } catch (e: IllegalArgumentException) { }
                if (pid > 0) Process.killProcess(pid)
                if (waiting) main.postDelayed(finishOnce, DEATH_WAIT_MS) else finishOnce()
            }
        }

        if (m == null) { kill(); return }
        stopAck = kill
        stopAckGen = stoppingGen
        try {
            m.send(Message.obtain(null, Ipc.MSG_STOP).apply {
                data = Bundle().apply { putString(Ipc.KEY_REASON, reason) }
                replyTo = replies
            })
            main.postDelayed(kill, STOP_ACK_WAIT_MS)
        } catch (e: RemoteException) {
            kill()
        }
    }

    private fun onReply(msg: Message) {
        val gen = msg.data?.getInt(Ipc.KEY_GEN, -1) ?: -1
        if (msg.what == Ipc.MSG_STOPPED) {
            if (gen == stopAckGen) stopAck?.invoke()
            return
        }
        if (gen != acceptGen) return // from a server being stopped or already replaced
        serverPid = msg.arg1
        when (msg.what) {
            Ipc.MSG_STARTED -> {
                val req = request ?: return
                val dir = req.getString(Ipc.KEY_DIR)
                val url = "http://${msg.data.getString(Ipc.KEY_ADDRESS)}:${msg.arg2}/"
                // Saved only after a successful bind, so a failed start never replaces a good setup.
                // Serving a copy never overwrites the saved details of the phone's own store.
                val isCopy = req.getString(Ipc.KEY_COPY_PATH) != null
                val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_SOURCE, if (isCopy) "copy" else "own")
                if (!isCopy) edit.putString(EXTRA_DIR, dir).putInt(EXTRA_PORT, req.getInt(Ipc.KEY_PORT))
                    .putString(PREF_PIN, req.getString(Ipc.KEY_PIN))
                    .putString(PREF_STORE_NAME, req.getString(Ipc.KEY_STORE_NAME))
                edit.apply()
                ServerStatus.servingCopy = isCopy
                val what = if (isCopy) "a copy of $dir" else dir
                ServerStatus.set("Serving $what", url, hotspotLine, phoneHotspot = startingOnPhoneHotspot)
                goForeground("Serving $what at $url")
                startingOnWifi?.let { watchWifi(it, startingOnPhoneHotspot) }
                LaunchResult.deliver(msg.arg2, null)
            }
            Ipc.MSG_COPY_EXPIRED -> {
                val name = msg.data.getString(Ipc.KEY_STORE_NAME) ?: "the store"
                val seq = requestSeq
                stopServer("the copy of $name expired, and was deleted") { if (seq == requestSeq) finish("Stopped: the copy of $name expired, and was deleted.") }
            }
            Ipc.MSG_FAILED -> {
                val error = msg.data.getString(Ipc.KEY_ERROR) ?: "unknown error"
                LaunchResult.deliver(0, error)
                val seq = requestSeq
                stopServer("the start failed") { if (seq == requestSeq) finish("Failed: $error") }
            }
        }
    }

    /** Generated once, kept in this process's private preferences; the cookie value buyers get. */
    private fun cookieSecret(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(PREF_SECRET, null)?.let { return it }
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val secret = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_SECRET, secret).apply()
        return secret
    }

    /**
     * Picks what the server binds to, then runs [go]: a hotspot already running (buyers may be
     * on it), else the joined Wi-Fi network, else a new hotspot — Wi-Fi Direct first, then
     * Android's local-only hotspot.
     */
    private fun chooseNetwork(req: Bundle, current: () -> Boolean, go: () -> Unit) {
        startingOnWifi = null
        startingOnPhoneHotspot = false
        Hotspot.info?.let { useHotspot(req, it, arrayListOf()); go(); return }
        val wifi = Store.wifiAddress()
        if (wifi != null) {
            req.putString(Ipc.KEY_BIND_ADDRESS, wifi.hostAddress)
            // Lines waiting for a start — such as a resume, or a failed hotspot attempt — are logged on Wi-Fi too.
            req.putStringArrayList(Ipc.KEY_LOG_LINES, ArrayList(pendingLines))
            pendingLines.clear()
            startingOnWifi = wifi.hostAddress
            go(); return
        }
        // The phone's own Mobile hotspot, turned on in Settings: buyers may already be on it, so serve there.
        val phone = Store.phoneHotspotAddress()
        if (phone != null) {
            req.putString(Ipc.KEY_BIND_ADDRESS, phone.hostAddress)
            req.putStringArrayList(Ipc.KEY_LOG_LINES, ArrayList(pendingLines + "[hotspot] using the phone's own Mobile hotspot, at ${phone.hostAddress}"))
            pendingLines.clear()
            startingOnWifi = phone.hostAddress
            startingOnPhoneHotspot = true
            hotspotLine = "On this phone's Mobile hotspot"
            go(); return
        }
        if (!Store.hasHotspotPermission(this)) {
            val what = if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location"
            finish("Failed: not connected to Wi-Fi, and starting a hotspot needs the $what permission. Open Local Store Server and tap Start to grant it.")
            return
        }
        ServerStatus.set("Not on Wi-Fi — starting a hotspot…")
        val lines = arrayListOf("[hotspot] not connected to Wi-Fi, so starting a hotspot")
        Hotspot.start(this, lines) { h ->
            if (!current()) return@start
            if (h == null) {
                // Nothing is running to write the log, so these lines wait for the next start.
                pendingLines += lines
                finish("Failed: not connected to Wi-Fi, and no hotspot could be started. ${lines.last().substringAfter("] ")}")
            } else {
                useHotspot(req, h, lines); go()
            }
        }
    }

    private fun useHotspot(req: Bundle, h: Hotspot.Info, lines: ArrayList<String>) {
        req.putString(Ipc.KEY_BIND_ADDRESS, h.address.hostAddress)
        req.putStringArrayList(Ipc.KEY_LOG_LINES, ArrayList(pendingLines + lines))
        pendingLines.clear()
        hotspotLine = "Hotspot “${h.ssid}”, password ${h.password}"
    }

    private fun withHotspot(reason: String) = if (Hotspot.info != null) "$reason; hotspot turned off" else reason

    private fun finish(status: String) {
        if (status.startsWith("Failed")) LaunchResult.deliver(0, status.removePrefix("Failed: "))
        paused = false
        unwatchWifi()
        // With nothing to serve, the hotspot only drains the battery.
        Hotspot.stop()
        hotspotLine = null
        ServerStatus.set(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local Store Server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Local Store Server")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION, n)
        }
    }
}
