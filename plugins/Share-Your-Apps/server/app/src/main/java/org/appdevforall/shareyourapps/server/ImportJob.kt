package org.appdevforall.shareyourapps.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Importing a copy of someone else's store (design, Appendix E.2–E.3, E.6; screens 5 and 6), in the
 * background, so rotating or leaving the import screen doesn't break it. Main process only;
 * listeners run on the main thread.
 *
 * Copies are kept in the app's private storage, `files/imported/<storeId>/`, each with its
 * description beside it, `<storeId>.json`, for the store's name and expiry. A phone holds at most one.
 */
object ImportJob {
    enum class Phase { IDLE, FINDING, CONNECTING, WAITING, RECEIVING, DONE, FAILED, DENIED, CANCELLED }

    class Snapshot(
        val phase: Phase, val message: String = "", val checkCode: String? = null, val storeName: String? = null,
        val received: Long = 0, val total: Long = 0, val files: Int = 0, val expires: java.time.Instant? = null,
        /** A transfer that failed partway, which can be tried again while the owner's share is open. */
        val canRetry: Boolean = false,
        /** Imported over the owner's own network: serving it there would depend on the owner's phone. */
        val onOwnersNetwork: Boolean = false,
        /** Once DONE: the imported copy's store ID, so the screen can check the copy is still on this phone. */
        val storeId: String? = null,
    ) {
        val busy: Boolean get() = phase in setOf(Phase.FINDING, Phase.CONNECTING, Phase.WAITING, Phase.RECEIVING)
    }

    /** A question for the seller before replacing a copy; the import waits for the answer. */
    class Question(val title: String, val message: String, val answer: (Boolean) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var snapshot = Snapshot(Phase.IDLE); private set
    @Volatile var question: Question? = null; private set
    @Volatile private var client: ShareClient? = null
    /** The allowed client kept after a failed transfer, for Try again; and where the owner is. */
    @Volatile private var retryClient: ShareClient? = null
    @Volatile private var ownerAddress: java.net.InetAddress? = null
    @Volatile private var lastCheck: String? = null
    @Volatile private var cancelled = false

    fun observe(l: () -> Unit) { listeners += l; l() }
    fun remove(l: () -> Unit) { listeners -= l }
    private fun set(s: Snapshot) { snapshot = s; main.post { listeners.forEach { it() } } }

    fun importedDir(context: Context) = File(context.filesDir, "imported")

    /** The copy on this phone, if any: its description. */
    fun currentCopy(context: Context): SharePackage.Description? = CopyExpiry.copies(importedDir(context)).firstOrNull()

    /** Whether the current (or last) import was started by an MCP client, not a person on the import screen. */
    @Volatile var startedByMcp = false; private set

    /**
     * Starts importing, from [address] if known — from a QR link or the owner's tones — or by searching for the code.
     * [network]: the network to use, such as the owner's hotspot that WifiJoin joined; null uses the phone's Wi-Fi.
     * [byMcp]: started by an MCP client, so replacing a different store needs no person; an older copy still does.
     * Returns null once started, or why it didn't start: an import is already running, or Android refused the
     * foreground service. Only an MCP start gives up then; a person's start runs on, as before.
     * Synchronized: MCP calls this from its own threads, the import screen from the main thread.
     */
    @Synchronized
    fun start(context: Context, code: String, phoneName: String, address: InetSocketAddress?,
              network: android.net.Network? = null, byMcp: Boolean = false): String? {
        if (snapshot.busy) return "An import is already running."
        val app = context.applicationContext
        cancelled = false; retryClient = null; startedByMcp = byMcp
        val before = snapshot
        // Busy before the service starts: it stops itself as soon as it sees an import that isn't busy.
        set(Snapshot(Phase.FINDING, "Looking for the phone showing that code…"))
        // A foreground service, so Android doesn't freeze the import once the app leaves the screen.
        if (!ImportService.start(app) && byMcp) {
            set(before)
            return "person needed: Android didn't let the import start in the background. Open Share Your Apps Server, then try again."
        }
        thread(name = "import") {
            try { run(app, code, phoneName, address, network) }
            catch (e: IOException) { failed(e) }
            catch (e: Exception) { failed(IOException("The import failed: ${e.message}")) }
            finally { client = null; question = null }
        }
        return null
    }

    fun cancel() { cancelled = true; client?.cancel(); question?.answer?.invoke(false); retryClient = null }

    /** Tries a failed transfer again, with the same exchange, while the owner's share is still open. */
    fun retry(context: Context) {
        val c = retryClient ?: return
        if (snapshot.busy) return
        val app = context.applicationContext
        cancelled = false
        set(Snapshot(Phase.RECEIVING, "Receiving", lastCheck))
        ImportService.start(app)
        thread(name = "import") {
            try { client = c; receive(app, c, lastCheck.orEmpty()) }
            catch (e: IOException) { failed(e) }
            catch (e: Exception) { failed(IOException("The import failed: ${e.message}")) }
            finally { client = null; question = null }
        }
    }

    private fun failed(e: IOException) {
        set(if (cancelled) Snapshot(Phase.CANCELLED, "Cancelled. Nothing was imported.")
            else Snapshot(Phase.FAILED, e.message ?: "The import failed.", canRetry = retryClient != null))
    }

    /** The Wi-Fi network, which a network without internet — such as the owner's hotspot — isn't by default. */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(context: Context): android.net.Network? {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** Whether this phone's Wi-Fi is the owner's own network: its gateway is the owner's phone. */
    private fun onOwnersNetwork(context: Context, wifi: android.net.Network): Boolean {
        val owner = ownerAddress ?: return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.getLinkProperties(wifi)?.routes?.any { it.isDefaultRoute && it.gateway == owner } == true
    }

    private fun run(context: Context, code: String, phoneName: String, known: InetSocketAddress?, joined: android.net.Network?) {
        // Everything goes over Wi-Fi explicitly: a network without internet, such as the owner's hotspot,
        // isn't Android's default, and requests would otherwise go to mobile data, and fail. A network WifiJoin
        // joined (the owner's hotspot) comes first: it may not be the first Wi-Fi network Android lists.
        val wifi = joined ?: wifiNetwork(context) ?: throw IOException("This phone isn't on Wi-Fi. Join the owner's Wi-Fi network or hotspot, then try again.")
        val address = known ?: ShareDiscovery.find(code, bind = { wifi.bindSocket(it) })
            ?: throw IOException("Couldn't find a phone showing that code. Check both phones are on the same Wi-Fi network or hotspot, and that the code is still showing.")
        if (cancelled) throw IOException("Cancelled.")
        ownerAddress = address.address
        set(Snapshot(Phase.CONNECTING, "Connecting to the owner's phone…"))
        val c = ShareClient("http://${address.address.hostAddress}:${address.port}", code, phoneName, opener = { wifi.openConnection(it) }); client = c
        val check = c.ask(); lastCheck = check
        set(Snapshot(Phase.WAITING, "Waiting for the owner to allow it…", check))
        val deadline = System.currentTimeMillis() + ShareHost.CODE_LIFETIME_MS
        while (true) {
            if (cancelled) throw IOException("Cancelled.")
            when (c.status()) {
                "allowed" -> break
                "denied" -> { set(Snapshot(Phase.DENIED, "The owner denied the request. Nothing was imported.")); return }
                "expired" -> throw IOException("The code expired before the owner allowed it. Ask for a new one.")
            }
            if (System.currentTimeMillis() > deadline) throw IOException("The owner didn't answer in time. Ask for a new code.")
            Thread.sleep(1000)
        }
        // Allowed: from now on, a failed transfer can be tried again with this exchange.
        retryClient = c
        receive(context, c, check)
    }

    private fun receive(context: Context, c: ShareClient, check: String) {
        if (c.status() != "allowed") throw IOException("The owner's share has ended. Ask them to share again.")
        val dir = importedDir(context)
        var described: SharePackage.Description? = null
        val (desc, _) = c.download(dir, accept = { d -> described = d; confirmReplacing(context, d) }) { written, total ->
            set(Snapshot(Phase.RECEIVING, "Receiving", check, described?.storeName, written, total, described?.files?.size ?: 0))
        }
        // One copy per phone: this one, and its description beside it.
        File(dir, "${desc.storeId}.json").writeText(desc.json.toString())
        dir.listFiles()?.filter { !it.name.startsWith(".") && it.name != desc.storeId && it.name != "${desc.storeId}.json" }?.forEach { it.deleteRecursively() }
        // A fresh copy restarts the expiry clock at the current time (Appendix E.5): a clock once set too far
        // ahead mustn't condemn it. An old expiry notice goes with the old copy.
        ExpiryClock(CopyExpiry.clockFile(context.filesDir)).restart(System.currentTimeMillis())
        retryClient = null
        val owners = wifiNetwork(context)?.let { onOwnersNetwork(context, it) } == true
        set(Snapshot(Phase.DONE, "Imported", check, desc.storeName, desc.totalBytes, desc.totalBytes, desc.files.size, desc.expires,
            onOwnersNetwork = owners, storeId = desc.storeId))
    }

    /**
     * Forgets a finished import (done, denied, cancelled, or failed with no Try again), so the next visit to the import
     * screen shows the empty form, not an old result. A failed transfer that can be tried again is kept, as is a busy one.
     * Synchronized with [start], so an import an MCP client starts at the same moment is never wiped.
     */
    @Synchronized
    fun clearFinished() {
        val s = snapshot
        if (s.busy || s.phase == Phase.IDLE || s.canRetry) return
        set(Snapshot(Phase.IDLE))
    }

    /**
     * Whether this phone is on the owner's network now, not when the import ended: Wi-Fi may have been turned off or
     * changed since. False with no Wi-Fi, or if no owner is known.
     */
    fun onOwnersNetworkNow(context: Context): Boolean =
        wifiNetwork(context.applicationContext)?.let { onOwnersNetwork(context.applicationContext, it) } == true

    /** Appendix E.6: a newer copy of the same store replaces it; an older one, or a different store, asks first. */
    private fun confirmReplacing(context: Context, incoming: SharePackage.Description) {
        val current = currentCopy(context) ?: return
        // An MCP import answers "Replace?" for a different store itself (decision 4); an older copy still asks a person.
        if (startedByMcp && current.storeId != incoming.storeId) return
        val q = when {
            current.storeId != incoming.storeId -> "Replace ${current.storeName}?" to
                "This phone has a copy of ${current.storeName}. Importing ${incoming.storeName} replaces it."
            incoming.created < current.created -> "Import an older copy?" to
                "This copy of ${incoming.storeName} is older than the one on this phone. Importing it replaces the newer one."
            else -> return
        }
        val latch = CountDownLatch(1); var yes = false
        question = Question(q.first, q.second) { a -> yes = a; latch.countDown() }
        main.post { listeners.forEach { it() } }
        latch.await(2, TimeUnit.MINUTES)
        question = null
        if (!yes) throw IOException(if (cancelled) "Cancelled." else "Not imported: the copy on this phone was kept.")
    }
}
