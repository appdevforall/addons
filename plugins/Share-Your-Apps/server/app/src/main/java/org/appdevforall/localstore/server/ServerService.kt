package org.appdevforall.localstore.server

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Runs in its own process (android:process=":server"). Serves one Store directory for its
 * whole life: the main process kills this process and starts a fresh one for every change,
 * so nothing leaks from one run to the next.
 */
class ServerService : Service() {

    private var server: HttpServer? = null
    private var log: StoreLog? = null
    private var serving = ""
    @Volatile private var gen = -1
    // The store being served, for sharing: where it is, its name, and the address buyers use.
    private var servingDir: java.io.File? = null
    private var servingName = ""
    private var externalAddress = ""
    private var share: ShareHost? = null
    private var servingCopy = false
    private var startReply: Messenger? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * While a copy is served, once a minute: is it due (Appendix E.5)? Then stop taking new
     * connections, let downloads under way finish, for up to 10 minutes, delete it, and say so.
     */
    private val expiryCheck = object : Runnable {
        override fun run() {
            val dir = servingDir ?: return
            val copy = CopyExpiry.copies(dir.parentFile ?: return).firstOrNull { it.storeId == dir.name } ?: return
            val status = CopyExpiry.status(copy, ExpiryClock(CopyExpiry.clockFile(filesDir)))
            if (status == ExpiryClock.Status.OK) { main.postDelayed(this, 60_000); return }
            val setBack = status == ExpiryClock.Status.CLOCK_SET_BACK
            log?.status("server", "the copy of ${copy.storeName} expired: not taking new connections, and deleting it once downloads under way finish")
            server?.stopAccepting()
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            main.post(object : Runnable {
                override fun run() {
                    if ((server?.sending ?: 0) > 0 && System.currentTimeMillis() < deadline) { main.postDelayed(this, 1000); return }
                    server?.stop(); server = null
                    CopyExpiry.delete(dir.parentFile!!, copy, setBack)
                    log?.status("server", "deleted the expired copy of ${copy.storeName}")
                    send(startReply, Message.obtain(null, Ipc.MSG_COPY_EXPIRED).apply {
                        data = Bundle().apply { putString(Ipc.KEY_STORE_NAME, copy.storeName); putInt(Ipc.KEY_GEN, gen) }
                    })
                }
            })
        }
    }
    private var discovery: ShareDiscovery.Listener? = null
    /** The compressor's thread; a share waits until it's done, since it changes the store's files. */
    private var compressor: Thread? = null
    private var shareWaiting = false

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Ipc.MSG_START -> {
                    val data = Bundle(msg.data)
                    val reply = msg.replyTo
                    thread(name = "start") { start(data, reply) } // binding is network work: keep it off the main thread
                }
                Ipc.MSG_STOP -> stopGracefully(msg.data.getString(Ipc.KEY_REASON) ?: "no reason given", msg.replyTo)
                Ipc.MSG_LOG -> msg.data.getString(Ipc.KEY_LINE)?.let { log?.statusLine(it) }
                Ipc.MSG_SHARE_OPEN -> { openShare(); replyShare(msg.replyTo) }
                Ipc.MSG_SHARE_POLL -> replyShare(msg.replyTo)
                Ipc.MSG_SHARE_ALLOW -> {
                    val ms = msg.data.getLong(Ipc.KEY_SHARE_COPY_EXPIRES, -1)
                    val expires = if (ms < 0) null else java.time.Instant.ofEpochMilli(ms)
                    share?.let { h ->
                        h.allow(expires)
                        log?.status("share", "allowed ${h.phoneName()} a copy, " + (expires?.let { "expiring $it" } ?: "never expiring"))
                    }
                    replyShare(msg.replyTo)
                }
                Ipc.MSG_SHARE_DENY -> { share?.let { h -> h.deny(); log?.status("share", "denied ${h.phoneName()} a copy") }; replyShare(msg.replyTo) }
                Ipc.MSG_SHARE_CLOSE -> closeShare()
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent): IBinder = messenger.binder

    /**
     * Logs why the server is stopping and closes its sockets, then tells the main process it
     * can kill this process. Only a server that was actually serving gets a "stopped" line;
     * a failed start has already logged why.
     */
    private fun stopGracefully(reason: String, reply: Messenger?) {
        main.removeCallbacksAndMessages(null)
        closeShare()
        val s = server
        server = null
        if (s != null) {
            s.stop()
            log?.status("server", "stopped serving $serving: $reason")
        }
        log?.close()
        send(reply, Message.obtain(null, Ipc.MSG_STOPPED, Process.myPid(), 0).apply {
            data = Bundle().apply { putInt(Ipc.KEY_GEN, gen) }
        })
    }

    /** The process ends when the main process unbinds or kills it; there's nothing worth keeping. */
    override fun onDestroy() {
        closeShare()
        server?.stop()
        log?.close()
        Process.killProcess(Process.myPid())
    }

    private fun start(data: Bundle, reply: Messenger?) {
        gen = data.getInt(Ipc.KEY_GEN, -1)
        val name = data.getString(Ipc.KEY_DIR).orEmpty()
        val port = data.getInt(Ipc.KEY_PORT, Store.DEFAULT_PORT)
        val l = StoreLog(Store.logDir(this), data.getLong(Ipc.KEY_LOG_LIMIT, Store.DEFAULT_LOG_LIMIT))
        log = l
        data.getStringArrayList(Ipc.KEY_LOG_LINES)?.forEach { l.statusLine(it) }

        fun fail(error: String) {
            l.status("server", "failed to start: $error")
            send(reply, Message.obtain(null, Ipc.MSG_FAILED, Process.myPid(), 0).apply {
                this.data = Bundle().apply { putString(Ipc.KEY_ERROR, error); putInt(Ipc.KEY_GEN, gen) }
            })
        }

        // An imported copy lives in this app's private storage; a published store in shared storage.
        val copyPath = data.getString(Ipc.KEY_COPY_PATH)
        val dir = if (copyPath != null) {
            val f = java.io.File(copyPath)
            val imported = java.io.File(filesDir, "imported").canonicalPath + java.io.File.separator
            if (!f.canonicalPath.startsWith(imported) || !f.isDirectory) return fail("the imported copy isn't where it should be")
            f
        } else {
            if (!Store.validName(name)) return fail("\"$name\" isn't a Store directory name (Store_ and 8 digits)")
            if (!Store.hasStorageAccess(this)) return fail("storage access hasn't been granted")
            Store.dir(name).also { if (!it.isDirectory) return fail("${it.path} doesn't exist") }
        }
        servingCopy = copyPath != null
        startReply = reply
        val external = data.getString(Ipc.KEY_BIND_ADDRESS)?.let { InetAddress.getByName(it) }
            ?: pickExternalAddress(systemInterfaces())
            ?: return fail("not connected to a Wi-Fi network")

        val auth = Auth(
            data.getString(Ipc.KEY_PIN)?.takeIf { it.isNotEmpty() },
            data.getString(Ipc.KEY_SECRET).orEmpty(),
            data.getString(Ipc.KEY_TOKEN)?.takeIf { it.isNotEmpty() },
        )
        val storeName = data.getString(Ipc.KEY_STORE_NAME) ?: "Local Store"
        val s = HttpServer(dir, listOf(InetAddress.getByName("127.0.0.1"), external), port, l, auth, storeName)
        try {
            s.start()
        } catch (e: Exception) {
            return fail(e.message ?: e.toString())
        }
        server = s
        serving = name
        servingDir = dir; servingName = storeName; externalAddress = external.hostAddress ?: ""
        val address = external.hostAddress ?: "?"
        l.status("server", "serving ${if (servingCopy) "an imported copy of $name" else name} on $address:${s.boundPort}" + if (auth.pin != null) " with a PIN" else "")
        compressor = Compressor(dir, l).start()
        if (servingCopy) main.post(expiryCheck)
        send(reply, Message.obtain(null, Ipc.MSG_STARTED, Process.myPid(), s.boundPort).apply {
            this.data = Bundle().apply { putString(Ipc.KEY_ADDRESS, address); putInt(Ipc.KEY_GEN, gen) }
        })
    }

    /** A new share code for the store being served, replacing any share already showing. */
    private fun openShare() {
        val s = server ?: return
        val dir = servingDir ?: return
        if (compressor?.isAlive == true) { shareWaiting = true; return }   // opened once it's done
        shareWaiting = false
        if (servingCopy) { log?.status("share", "refused to share an imported copy: copies can't be passed on"); return }
        closeShare()
        val key = StoreKeyFile.loadOrCreate(java.io.File(filesDir, "share"))
        val h = ShareHost(dir, servingName, key)
        h.appApk = java.io.File(applicationInfo.sourceDir)   // this app's own installed APK, for a phone without it
        share = h; s.share = h
        discovery = try {
            ShareDiscovery.Listener({ share }, s.boundPort).also { it.start() }
        } catch (e: Exception) {
            log?.status("share", "can't answer typed codes, only QR codes: ${e.message}"); null
        }
        log?.status("share", "showing a share code for $servingName")
    }

    private fun closeShare() {
        shareWaiting = false
        discovery?.close(); discovery = null
        server?.share = null
        share?.close(); share = null
    }

    /** The share's state, for the share screen. */
    private fun replyShare(to: Messenger?) {
        val b = Bundle()
        val h = share
        val s = server
        if (shareWaiting && compressor?.isAlive != true) openShare()
        when {
            s == null -> b.putString(Ipc.KEY_SHARE_STATE, "NONE")
            shareWaiting -> b.putString(Ipc.KEY_SHARE_STATE, "PREPARING")
            h == null -> { b.putString(Ipc.KEY_SHARE_STATE, "ERROR")
                b.putString(Ipc.KEY_SHARE_ERROR, if (servingCopy) "This phone is serving a copy of someone else's store. Copies can't be passed on." else "No share is open.") }
            else -> {
                b.putString(Ipc.KEY_SHARE_STATE, h.state().name)
                b.putString(Ipc.KEY_SHARE_CODE, h.displayCode)
                b.putString(Ipc.KEY_SHARE_LINK, ShareLink.make(externalAddress, s.boundPort, h.code))
                b.putString(Ipc.KEY_SHARE_APP_URL, "http://$externalAddress:${s.boundPort}/share/app")
                b.putLong(Ipc.KEY_SHARE_CODE_EXPIRES, h.expiresAt)
                b.putString(Ipc.KEY_SHARE_STORE, h.storeName)
                b.putString(Ipc.KEY_SHARE_PHONE, h.phoneName())
                h.pending()?.let { b.putString(Ipc.KEY_SHARE_CHECK, it.checkCode) }
                b.putInt(Ipc.KEY_SHARE_FAILURES, h.failures())
            }
        }
        send(to, Message.obtain(null, Ipc.MSG_SHARE_STATE).apply { data = b })
    }

    private fun send(to: Messenger?, msg: Message) {
        try { to?.send(msg) } catch (e: RemoteException) { }
    }
}
