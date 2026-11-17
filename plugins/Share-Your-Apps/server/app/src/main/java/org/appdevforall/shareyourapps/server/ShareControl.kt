package org.appdevforall.shareyourapps.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Sharing the store for MCP clients (share_start, share_status, share_stop). Main process only.
 *
 * Talks to the server process the way the share screen does: binds without starting it, and asks
 * what's happening. It only ever sends three messages — open a share, ask its state, close it —
 * and [send] refuses any other, so nothing here can approve or deny a copy. Allow and Deny are only
 * sent from [ShareActivity], by a person.
 *
 * While a share an MCP client started (or joined) is under way, a watcher asks its state once a
 * second, and when a phone asks for a copy, posts a notification that opens the share screen —
 * which joins that share rather than replacing it — for a person to check the digits and choose.
 */
object ShareControl {
    private const val CHANNEL = "share_requests"
    private const val NOTIFICATION = 6177
    private const val BIND_WAIT_MS = 1500L
    private const val REPLY_WAIT_MS = 2000L
    private const val WATCH_MS = 1000L
    const val MAX_WAIT_S = 25

    /** States of a share still under way, and of one that has finished. */
    private val UNDER_WAY = setOf("OPEN", "PREPARING", "ASKED", "REVEALED", "ALLOWED")
    private val FINISHED = setOf("DONE", "DENIED", "CLOSED")
    /** The only messages this may send. Never MSG_SHARE_ALLOW or MSG_SHARE_DENY. */
    private val ALLOWED_MESSAGES = setOf(Ipc.MSG_SHARE_OPEN, Ipc.MSG_SHARE_POLL, Ipc.MSG_SHARE_CLOSE)

    @Volatile private var app: Context? = null

    // ---------------------------------------------------------------- the connection

    @Volatile private var server: Messenger? = null
    @Volatile private var connected = CountDownLatch(1)
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            server = Messenger(service); connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // The server process stopped. Android may reconnect this binding to the next server process,
            // but doesn't always (server_stop then server_start left it unconnected): connect() binds afresh.
            server = null; connected = CountDownLatch(1)
        }
        /** The binding will never reconnect: drop it, so the next connect() binds afresh. */
        override fun onBindingDied(name: ComponentName) { dropBinding() }
        override fun onNullBinding(name: ComponentName) { dropBinding() }
    }

    /** One request at a time: replies carry nothing to match them to their request. */
    private val requestLock = Any()
    @Volatile private var waiting: CountDownLatch? = null
    @Volatile private var reply: Bundle? = null
    private val replies by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == Ipc.MSG_SHARE_STATE) { reply = Bundle(msg.data); waiting?.countDown() }
            true
        })
    }

    /** Forgets the binding and its messenger, so the next connect() binds afresh. */
    private fun dropBinding() {
        synchronized(this) {
            if (bound) { try { app?.unbindService(conn) } catch (e: IllegalArgumentException) { }; bound = false }
            server = null; connected = CountDownLatch(1)
        }
    }

    /**
     * The server process's messenger, binding first if needed; null if it doesn't answer. A binding left
     * from an earlier server process may never reconnect, so if no live messenger arrives in time, the
     * binding is dropped and made afresh, once.
     */
    private fun connect(ctx: Context): Messenger? {
        val a = ctx.applicationContext
        app = a
        for (attempt in 0..1) {
            if (attempt > 0) dropBinding()
            val latch = synchronized(this) {
                // No BIND_AUTO_CREATE: the server process is never started just for this.
                if (!bound) bound = a.bindService(Intent(a, ServerService::class.java), conn, 0)
                connected
            }
            server?.takeIf { it.binder.isBinderAlive }?.let { return it }
            latch.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS)
            server?.takeIf { it.binder.isBinderAlive }?.let { return it }
        }
        return null
    }

    private fun send(what: Int, expectReply: Boolean): Bundle? {
        require(what in ALLOWED_MESSAGES) { "ShareControl never sends message $what" }
        synchronized(requestLock) {
            val m = server ?: return null
            val latch = CountDownLatch(1)
            reply = null
            waiting = if (expectReply) latch else null
            try {
                m.send(Message.obtain(null, what).apply { replyTo = replies })
            } catch (e: RemoteException) {
                // The server process is gone: the next request binds afresh.
                waiting = null; dropBinding(); return null
            }
            if (!expectReply) return null
            latch.await(REPLY_WAIT_MS, TimeUnit.MILLISECONDS)
            waiting = null
            if (reply == null && !m.binder.isBinderAlive) dropBinding()
            return reply
        }
    }

    /** The share's state now: NONE when the server isn't serving. Throws when the server process doesn't answer. */
    private fun poll(ctx: Context): Bundle {
        if (ServerStatus.url == null) return Bundle().apply { putString(Ipc.KEY_SHARE_STATE, "NONE") }
        connect(ctx) ?: throw McpToolError("Couldn't reach the server process. Try again in a moment.")
        return send(Ipc.MSG_SHARE_POLL, true) ?: throw McpToolError("The server process didn't answer. Try again in a moment.")
    }

    // ---------------------------------------------------------------- the share an MCP client is following

    private val track = Any()
    /** A share an MCP client started or joined is being followed. */
    @Volatile private var following = false
    /** Whether an MCP client opened it (true), or joined one a person opened (false). */
    @Volatile private var startedByClient = false
    /** Its code; null while the store is still being prepared. */
    @Volatile private var followedCode: String? = null
    @Volatile private var stoppedByClient = false
    /** Why the followed share ended, once it has. */
    @Volatile private var ended: String? = null
    @Volatile private var notifiedKey: String? = null

    /** Updates what's followed from a fresh state; posts or clears the "asks for a copy" notification. */
    private fun observe(b: Bundle) {
        val state = b.getString(Ipc.KEY_SHARE_STATE) ?: "NONE"
        val code = b.getString(Ipc.KEY_SHARE_CODE)
        synchronized(track) {
            if (!following) { clearNotification(); return }
            if (followedCode == null && code != null && state in UNDER_WAY) followedCode = code
            val ours = (followedCode != null && code == followedCode) || (followedCode == null && state == "PREPARING")
            if (!ours) {
                ended = when {
                    stoppedByClient -> "stopped with share_stop"
                    state == "NONE" -> "the server stopped, which ended the share"
                    code != null -> "a new share was opened on this phone, replacing it"
                    else -> "it was ended on this phone: Cancel, or leaving the share screen that opened it"
                }
                following = false
                clearNotification()
                return
            }
        }
        if (state == "REVEALED") notifyAsk(b.getString(Ipc.KEY_SHARE_PHONE).orEmpty(), b.getString(Ipc.KEY_SHARE_STORE).orEmpty(),
            b.getString(Ipc.KEY_SHARE_CHECK).orEmpty())
        else clearNotification()
    }

    private var watchThread: HandlerThread? = null
    private var watchHandler: Handler? = null
    private val watchTick = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (!following) return
            val b = try { poll(ctx) } catch (e: Exception) { null }
            if (b != null) observe(b)
            val state = b?.getString(Ipc.KEY_SHARE_STATE)
            if (following && state !in FINISHED) watchHandler?.postDelayed(this, WATCH_MS)
        }
    }

    private fun watch() {
        synchronized(this) {
            if (watchThread == null) {
                watchThread = HandlerThread("share-watch").also { it.start() }
                watchHandler = Handler(watchThread!!.looper)
            }
            watchHandler?.removeCallbacks(watchTick)
            watchHandler?.postDelayed(watchTick, WATCH_MS)
        }
    }

    // ---------------------------------------------------------------- the tools

    /** share_start: opens a share, or returns the one already under way (with already_open). */
    fun start(ctx: Context): JSONObject {
        if (ServerStatus.url == null) throw McpToolError("The server isn't serving, so there's nothing to share. Call server_start first.",
            JSONObject().put("state", "not_serving"))
        if (ServerStatus.servingCopy) throw McpToolError("This phone is serving an imported copy of someone else's store. Copies can't be passed on, so it can't be shared.",
            JSONObject().put("state", "cant_share"))
        val now = poll(ctx)
        val state = now.getString(Ipc.KEY_SHARE_STATE)
        if (state in UNDER_WAY) {
            // A share is already under way — this client's, or a person's on the share screen: return it, never replace it.
            val code = now.getString(Ipc.KEY_SHARE_CODE)
            synchronized(track) {
                val same = following && (code == followedCode || followedCode == null)
                if (!same) { startedByClient = false; followedCode = code }
                following = true; stoppedByClient = false; ended = null
            }
            observe(now); watch()
            return describe(now).put("already_open", true).put("started_by", if (startedByClient) "mcp" else "person")
        }
        val opened = send(Ipc.MSG_SHARE_OPEN, true) ?: throw McpToolError("The server process didn't answer. Try again in a moment.")
        val openedState = opened.getString(Ipc.KEY_SHARE_STATE)
        if (openedState !in UNDER_WAY) {
            throw McpToolError(opened.getString(Ipc.KEY_SHARE_ERROR) ?: "The share didn't open (state $openedState).", describe(opened))
        }
        synchronized(track) {
            following = true; startedByClient = true; stoppedByClient = false; ended = null
            followedCode = opened.getString(Ipc.KEY_SHARE_CODE)
            notifiedKey = null
        }
        observe(opened); watch()
        val out = describe(opened).put("already_open", false).put("started_by", "mcp")
        val prefs = ctx.getSharedPreferences(HostService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(HostService.PREF_RUN_MODE, "always") == "screenOff") {
            out.put("warning", "\"Pause when the screen turns off\" is chosen on this phone: if its screen turns off, the server pauses, and this share ends.")
        }
        return out
    }

    /** share_status: the followed share's state; with [waitSeconds], waits up to that long for it to change. */
    fun status(ctx: Context, waitSeconds: Int): JSONObject {
        val first = current(ctx)
        val wait = waitSeconds.coerceIn(0, MAX_WAIT_S)
        if (wait == 0) return first
        val deadline = System.currentTimeMillis() + wait * 1000L
        var last = first
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            last = current(ctx)
            if (last.optString("state") != first.optString("state") || last.optString("phone") != first.optString("phone")) return last.put("changed", true)
        }
        return last.put("changed", false)
    }

    private fun current(ctx: Context): JSONObject {
        val b = poll(ctx)
        observe(b)
        return when {
            following -> describe(b).put("started_by", if (startedByClient) "mcp" else "person")
            ended != null -> JSONObject().put("state", "closed").put("reason", ended)
                .put("next", "Call share_start for a new share.")
            else -> describe(b).put("started_by", if (b.getString(Ipc.KEY_SHARE_STATE) in UNDER_WAY) "person" else JSONObject.NULL)
        }
    }

    /** share_stop: closes the share being followed. */
    fun stop(ctx: Context): JSONObject {
        if (!following) return JSONObject().put("stopped", false).put("state", "closed")
            .put("reason", ended ?: "No share started with share_start is open.")
        if (ServerStatus.url != null && connect(ctx) != null) send(Ipc.MSG_SHARE_CLOSE, false)
        synchronized(track) {
            stoppedByClient = true; following = false; ended = "stopped with share_stop"
        }
        clearNotification()
        return JSONObject().put("stopped", true).put("state", "closed").put("reason", ended)
    }

    /** MCP control turned off: a share an MCP client opened ends with it; one a person opened carries on. */
    fun shutdown() {
        val closeOurs = following && startedByClient
        synchronized(track) { following = false; ended = null; followedCode = null }
        if (closeOurs && ServerStatus.url != null && server != null) send(Ipc.MSG_SHARE_CLOSE, false)
        clearNotification()
        synchronized(this) {
            watchHandler?.removeCallbacksAndMessages(null)
            watchThread?.quitSafely(); watchThread = null; watchHandler = null
            if (bound) { try { app?.unbindService(conn) } catch (e: IllegalArgumentException) { }; bound = false }
            server = null; connected = CountDownLatch(1)
        }
    }

    // ---------------------------------------------------------------- what the client is told

    /** One word a client can act on, for a share's state. */
    fun stateWord(state: String?, failures: Int, servingCopy: Boolean): String = when (state) {
        "OPEN" -> "waiting_for_phone"
        "PREPARING" -> "preparing"
        "ASKED" -> "phone_connecting"
        "REVEALED" -> "waiting_for_allow"
        "ALLOWED" -> if (failures > 0) "transfer_failed" else "sending"
        "DONE" -> "done"
        "DENIED" -> "denied"
        "CLOSED" -> "expired"
        "NONE", null -> "not_serving"
        "ERROR" -> if (servingCopy) "cant_share" else "closed"
        else -> state.lowercase()
    }

    private fun describe(b: Bundle): JSONObject {
        val state = b.getString(Ipc.KEY_SHARE_STATE)
        val failures = b.getInt(Ipc.KEY_SHARE_FAILURES)
        val word = stateWord(state, failures, ServerStatus.servingCopy)
        val out = JSONObject().put("state", word)
        b.getString(Ipc.KEY_SHARE_STORE)?.let { out.put("store", it) }
        b.getString(Ipc.KEY_SHARE_CODE)?.let { out.put("code", it) }
        b.getString(Ipc.KEY_SHARE_LINK)?.let { out.put("link", it) }
        val expires = b.getLong(Ipc.KEY_SHARE_CODE_EXPIRES, 0)
        if (expires > 0) out.put("code_expires", Instant.ofEpochMilli(expires).toString())
        b.getString(Ipc.KEY_SHARE_PHONE)?.takeIf { it.isNotEmpty() }?.let { out.put("phone", it) }
        b.getString(Ipc.KEY_SHARE_CHECK)?.let { out.put("check_code", it) }
        if (failures > 0) out.put("failed_transfers", failures)
        b.getString(Ipc.KEY_SHARE_ERROR)?.let { out.put("message", it) }
        when (word) {
            "preparing" -> out.put("next", "The store is being prepared after a publish. Call share_status with wait_seconds until the code appears.")
            "waiting_for_phone" -> out.put("next", "On the other phone, import with this code or link. The code works for one phone, for 10 minutes.")
            "waiting_for_allow" -> {
                val notificationsOn = app?.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false
                out.put("person_needed", "A person must check that the four digits match on both phones, choose when the copy expires, " +
                    "and tap Allow or Deny on this phone. No tool can do this. " +
                    if (notificationsOn) "A notification asks them." else "Notifications are off for this app: ask them to open Share Your Apps Server and tap Share this store…")
            }
            "transfer_failed" -> out.put("next", "The other phone can try the transfer again while this share stays open.")
            "not_serving" -> out.put("next", "Call server_start first.")
        }
        return out
    }

    // ---------------------------------------------------------------- the notification

    private fun notifyAsk(phone: String, store: String, check: String) {
        val ctx = app ?: return
        val key = "$phone|$check"
        if (key == notifiedKey) return
        notifiedKey = key
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // High importance: a heads-up notification, so the person sees it whatever app they're in.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Requests for a copy", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A phone asks for a copy of a share an app started. Only you can allow it."
        })
        val open = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, ShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("${phone.ifEmpty { "A phone" }} asks for a copy of ${store.ifEmpty { "your store" }}")
            .setContentText("Tap to check the four digits, then Allow or Deny.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        try { nm.notify(NOTIFICATION, n) } catch (e: SecurityException) { }   // notifications not allowed
    }

    private fun clearNotification() {
        if (notifiedKey == null) return
        notifiedKey = null
        app?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
    }
}
