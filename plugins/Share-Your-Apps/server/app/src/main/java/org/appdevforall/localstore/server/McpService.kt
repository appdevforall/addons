package org.appdevforall.localstore.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * MCP control (the switch on the server screen): a foreground service in the main process that
 * listens on 127.0.0.1:[McpHttp.DEFAULT_PORT] for MCP clients on this phone. Off by default. Main
 * process, so tools reach [ServerStatus], [ImportJob] and HostService directly.
 *
 * The switch's setting is kept in [PREFS]; the service runs while it's on and the app has been
 * opened since the phone started (nothing starts it at boot). The token clients send is kept there
 * too, private to the app, and is never logged.
 */
class McpService : Service() {

    companion object {
        const val PREFS = "mcp"
        private const val PREF_ENABLED = "enabled"
        private const val PREF_TOKEN = "token"
        const val ACTION_TURN_OFF = "org.appdevforall.localstore.server.MCP_TURN_OFF"
        /**
         * Minimum importance: silent, with a collapsed card in the shade's silent section. Android still shows a
         * status-bar icon for a foreground service even on a MIN channel (checked on the phone: importance=1, not
         * user-set), so MCP's notification uses its own sync icon, apart from the server's upload icon. A channel's
         * importance can't be lowered once created, so this is a new channel; the old one, [OLD_CHANNEL], is deleted.
         */
        private const val CHANNEL = "mcp_quiet"
        private const val OLD_CHANNEL = "mcp"
        private const val NOTIFICATION = 6176
        private const val TAG = "LocalStoreMcp"

        fun enabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false)

        /** Saves the switch and starts or stops the service. Call [start] only from a screen the person is using. */
        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, on).apply()
            if (on) start(ctx) else ctx.stopService(Intent(ctx, McpService::class.java))
        }

        /** Starts the service if the switch is on. Safe to call when it's already running. */
        fun start(ctx: Context) {
            if (!enabled(ctx)) return
            try {
                ctx.startForegroundService(Intent(ctx, McpService::class.java))
            } catch (e: Exception) {
                // Android refuses foreground starts from the background; the next opening of the app tries again.
                McpStatus.set(McpStatus.State.FAILED, "Couldn't start: ${e.message ?: e.javaClass.simpleName}. Open the app to try again.")
            }
        }

        /** The token clients send as Authorization: Bearer <token>; made the first time it's needed. */
        fun token(ctx: Context): String {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(PREF_TOKEN, null)?.let { return it }
            return newToken(ctx)
        }

        /** Replaces the token: clients using the old one are refused from their next request. */
        fun newToken(ctx: Context): String {
            val t = hex(ShareCrypto.randomBytes(24))
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_TOKEN, t).apply()
            return t
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var http: McpHttp? = null
    /** Whether the service runs with the microphone type, so import_listen can hear tones with the app off screen. */
    @Volatile private var micType = false
    private var protocol: McpProtocol? = null
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() needs startForeground() promptly, whatever happens next.
        goForeground()
        if (intent?.action == ACTION_TURN_OFF) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enabled(this)) { stopSelf(); return START_NOT_STICKY }
        if (http != null || starting) {
            // Already running: the microphone type may have changed (the app opened, the microphone allowed or refused).
            http?.let { McpStatus.set(McpStatus.State.ON, onText(it.boundPort)) }
            return START_NOT_STICKY
        }
        starting = true
        McpStatus.set(McpStatus.State.STARTING, "Starting…")
        val app = applicationContext
        val p = McpProtocol(McpTools.all(app), BuildConfig.VERSION_NAME, { hex(ShareCrypto.randomBytes(16)) })
        val h = McpHttp(p, { token(app) }, McpHttp.DEFAULT_PORT, log = { line -> Log.w(TAG, line) })
        // Binding is network work: off the main thread.
        thread(name = "mcp-start") {
            val error = try { h.start(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            main.post {
                starting = false
                if (error != null) {
                    Log.w(TAG, "failed to start: $error")
                    McpStatus.set(McpStatus.State.FAILED, "Failed: $error. Is another app using port ${McpHttp.DEFAULT_PORT}?")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (!enabled(this)) {
                    h.stop()   // turned off while it was starting
                    stopSelf()
                } else {
                    http = h; protocol = p
                    Log.i(TAG, "listening on 127.0.0.1:${h.boundPort}")
                    McpStatus.set(McpStatus.State.ON, onText(h.boundPort))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        http?.stop(); http = null
        protocol?.endAll(); protocol = null
        // A share an MCP client opened ends with MCP control; one a person opened carries on. Off the main
        // thread: ShareControl may wait briefly for the server process, whose replies arrive on the main thread.
        thread(name = "mcp-share-shutdown") { ShareControl.shutdown() }
        // Tones an MCP client started, and listening for one, end with MCP control.
        McpSoundTools.stopPlaying()
        SoundImport.stop(this)
        // A failure stays on show; otherwise the switch's state says why it stopped.
        if (McpStatus.state != McpStatus.State.FAILED) {
            McpStatus.set(McpStatus.State.OFF, if (enabled(this)) "Not running. Open the app to start it." else "Off")
        }
        Log.i(TAG, "stopped")
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        // The old channel had low importance, which still shows a status-bar icon; channels can't be lowered, so it goes.
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "MCP control", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while MCP control is on. Silent."
            setShowBadge(false)
        })
        // Android still shows a status-bar icon for a foreground service, even on a MIN channel (confirmed on the
        // phone: importance=1, not user-set). So MCP gets its own icon, distinct from the server's upload icon.
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MCP control is on")
            .setContentText("Apps on this phone can control Local Store Server. They can't change the store or approve a copy.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .setOngoing(true)
            .build()
        // The microphone type too, when the app may use the microphone, so import_listen hears tones with the app off
        // screen. Android refuses that type unless the app is on screen (it isn't after a reboot, or from the
        // background): then MCP runs as before, without it, and the next opening of the app adds it (onResume).
        // Android 9 and 10 have no microphone type: any foreground service may use the microphone there.
        val mic = SoundIo.canListen(this)
        if (Build.VERSION.SDK_INT >= 30 && mic) {
            val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            try {
                startForeground(NOTIFICATION, n, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micType = true
                return
            } catch (e: Exception) {
                Log.w(TAG, "the microphone type was refused (${e.javaClass.simpleName}); running without it until the app is opened")
            }
        }
        micType = mic && Build.VERSION.SDK_INT < 30
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, n)
        }
    }

    /** The line under the switch while it's on: the address, and whether import_listen can use the microphone. */
    private fun onText(port: Int): String = "On, at http://127.0.0.1:$port${McpHttp.PATH}" + when {
        !SoundIo.canListen(this) -> "\nMicrophone not allowed: hearing a sharing phone's tones needs a person."
        !micType -> "\nMicrophone: works once the app is opened."
        else -> ""
    }
}

/** MCP control's state, for the server screen. Main process only; listeners run on the main thread. */
object McpStatus {
    enum class State { OFF, STARTING, ON, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var state = State.OFF
        private set
    @Volatile var text = "Off"
        private set

    fun set(s: State, t: String) {
        state = s; text = t
        main.post { listeners.forEach { it() } }
    }

    fun observe(l: () -> Unit) { listeners += l; l() }
    fun remove(l: () -> Unit) { listeners -= l }
}
