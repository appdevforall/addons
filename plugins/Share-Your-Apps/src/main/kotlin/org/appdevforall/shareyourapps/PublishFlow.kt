package org.appdevforall.shareyourapps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** What the server's LaunchActivity expects. Must match the server's LaunchContract. */
object ServerContract {
    const val PACKAGE = "org.appdevforall.shareyourapps.server"
    const val ACTIVITY = "org.appdevforall.shareyourapps.server.LaunchActivity"
    const val EXTRA_DIR = "dir"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_PIN = "pin"
    const val EXTRA_STORE_NAME = "storeName"
    const val RESULT_PORT = "port"
    const val RESULT_ERROR = "error"
    const val RESULT_UNCHANGED = "unchanged"
}

/**
 * One publish at a time, process-wide. State lives here rather than in a Fragment, so it
 * survives the tab being rebuilt; the Fragment only displays it and performs the launch, which
 * needs an Activity. All state changes happen on the main thread.
 */
object PublishFlow {

    enum class Phase { IDLE, BUILDING, INSTALLING, WAITING_TO_LAUNCH, LAUNCHING, VERIFYING }

    private class Pending(val catalog: Catalog, val built: Built, val token: String)
    private var confirm: Intent? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var pending: Pending? = null

    var phase = Phase.IDLE
        private set
    var message = ""
        private set
    /** Whether the last publish failed, so the screen can show its message as an error. */
    var failed = false
        private set

    val busy: Boolean get() = phase != Phase.IDLE

    /** The publish's progress, shown every half second while the store is being built. */
    private val progress = PublishProgress()
    private val showProgress = object : Runnable {
        override fun run() {
            if (phase != Phase.BUILDING) return
            message = progress.line(); listeners.forEach { it() }
            main.postDelayed(this, 500)
        }
    }
    private val sdcard: File get() = Environment.getExternalStorageDirectory()

    fun observe(l: () -> Unit) { listeners += l; l() }
    fun remove(l: () -> Unit) { listeners -= l }

    /**
     * The message a finished publish leaves — success, failure, or refused before it started —
     * begins with the date and time, on the same line: the message stays up until the next publish,
     * possibly days later, and seconds tell two quick publishes apart. Progress messages don't.
     */
    private fun set(p: Phase, m: String, error: Boolean = false) {
        phase = p
        message = if (p == Phase.IDLE) "${stamp()} · $m" else m
        if (p == Phase.IDLE) failed = error
        listeners.forEach { it() }
    }

    /**
     * Now, with seconds, as the phone writes a date and time: its language and region decide the
     * order, month names and separators, and its "Use 24-hour format" setting decides the clock,
     * such as "Sep 24, 4:32:10 PM" or "Sep 24, 16:32:10".
     */
    private fun stamp(): String {
        val locale = java.util.Locale.getDefault()
        val hours = when (appContext?.let { android.text.format.DateFormat.is24HourFormat(it) }) {
            true -> "H"; false -> "h"; null -> "j"   // j: the region's usual clock
        }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd${hours}ms")
        return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date())
    }

    /**
     * Steps 1–5 in the background (pre-flight includes the server-version check), then step 6,
     * installing the server if needed, then waits for the Fragment to launch it.
     */
    /** For the phone's 12- or 24-hour clock setting, which only a Context can read. */
    @Volatile private var appContext: Context? = null

    fun publish(catalog: Catalog, extraError: String?, app: Context): Boolean {
        appContext = app.applicationContext
        if (busy) return false
        if (extraError != null) { set(Phase.IDLE, "Extra JSON: $extraError", error = true); return false }
        set(Phase.BUILDING, "Publishing…")
        progress.reset(); main.removeCallbacks(showProgress); main.postDelayed(showProgress, 500)
        thread(name = "publish") {
            try {
                val plan = ServerInstaller.plan(app)
                val built = Publisher(sdcard, Posters::make, apkFacts = { f -> ApkReader.read(app, f) }, version = BuildConfig.VERSION_NAME, progress = progress).build(catalog)
                val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                main.post {
                    pending = Pending(catalog, built, token)
                    val apk = plan.install
                    if (apk == null) set(Phase.WAITING_TO_LAUNCH, "Starting the server…")
                    else {
                        set(Phase.INSTALLING, "Installing Share Your Apps Server…")
                        ServerInstaller.install(app, apk) { r -> main.post { onInstall(r) } }
                    }
                }
            } catch (e: Exception) {
                main.post { set(Phase.IDLE, e.message ?: e.toString(), error = true) }
            }
        }
        return true
    }

    /** Android's install confirmation couldn't be shown: the publish fails, and is undone, as a failed install is,
     *  rather than waiting for a confirmation that won't come. */
    fun confirmFailed(why: String) = onInstall(ServerInstaller.Result.Failed("Android's install confirmation couldn't be shown: $why"))

    private fun onInstall(r: ServerInstaller.Result) {
        when (r) {
            is ServerInstaller.Result.Installed -> set(Phase.WAITING_TO_LAUNCH, "Installed. Starting the server…")
            is ServerInstaller.Result.NeedsConfirmation -> {
                confirm = r.intent
                set(Phase.INSTALLING, "Confirm the Share Your Apps Server install…")
            }
            is ServerInstaller.Result.Failed -> {
                val p = pending ?: return
                pending = null
                thread(name = "rollback") {
                    runCatching { deleteTree(File(Publisher(sdcard).projects, p.built.storeDir)) }
                    main.post { set(Phase.IDLE, "Couldn't install Share Your Apps Server: ${r.message}\nNothing was published; the previous store is unchanged.", error = true) }
                }
            }
        }
    }

    /** The install's confirmation screen, handed to the Fragment exactly once. */
    fun takeConfirmIntent(): Intent? = confirm.also { confirm = null }

    /** Step 7's intent, handed to the Fragment exactly once. */
    fun takeLaunchIntent(): Intent? {
        val p = pending ?: return null
        if (phase != Phase.WAITING_TO_LAUNCH) return null
        set(Phase.LAUNCHING, "Starting the server…")
        // Recorded before the server can switch, so a later publish never deletes it as a leftover.
        CatalogState.setPendingDir(p.built.storeDir)
        return Intent()
            .setComponent(ComponentName(ServerContract.PACKAGE, ServerContract.ACTIVITY))
            .putExtra(ServerContract.EXTRA_DIR, p.built.storeDir)
            .putExtra(ServerContract.EXTRA_TOKEN, p.token)
            .putExtra(ServerContract.EXTRA_PIN, p.catalog.pin)
            .putExtra(ServerContract.EXTRA_STORE_NAME, p.catalog.storeName)
    }

    /** The launch's result. On success: delete the old directory, save, verify (steps 8–10). */
    /** [unchanged]: the server app failed before stopping anything, so what it was serving, it still serves. */
    fun onLaunchResult(port: Int, error: String?, unchanged: Boolean = false) {
        val p = pending ?: return
        pending = null
        val projects = Publisher(sdcard).projects
        val newDir = File(projects, p.built.storeDir)
        if (error != null) {
            set(Phase.VERIFYING, "Cleaning up…")
            thread(name = "rollback") {
                runCatching { deleteTree(newDir) }
                main.post { CatalogState.setPendingDir(null) }
                // By the time a start fails, the server has usually stopped the old store already, so say which it is.
                val after = if (unchanged) "Nothing was published, and the server is as it was."
                    else "Nothing was published, and the store is no longer being served. To serve the previous version, open Share Your Apps Server and tap Start."
                main.post { set(Phase.IDLE, "The server didn't start: $error\n$after", error = true) }
            }
            return
        }
        set(Phase.VERIFYING, "Checking what the server serves…")
        thread(name = "verify") {
            // Save the new directory first, then remove every other one, including any left pending.
            main.post { CatalogState.setStoreDir(p.built.storeDir) }
            runCatching { Publisher(sdcard).removeOthers(setOf(p.built.storeDir)) }
            val problems = Verifier(port, p.token).verify(newDir, p.built.files)
            val count = p.built.files.size + 1
            // Products with nothing to download are left out; say which, and why, rather than skip them silently.
            val leftOut = p.catalog.products.mapNotNull { pr -> pr.leftOutReason?.let { "${pr.name} $it" } }
            val leftOutNote = when (leftOut.size) {
                0 -> ""
                1 -> "\nLeft out: ${leftOut[0]}."
                else -> "\nLeft out (${leftOut.size}):\n" + leftOut.joinToString("\n") { "• $it" }
            }
            main.post {
                val warnNote = p.built.warnings.let { w -> when (w.size) { 0 -> ""; 1 -> "\nNote: ${w[0]}"; else -> "\nNotes (${w.size}):\n" + w.joinToString("\n") { "• $it" } } }
                if (problems.isEmpty()) set(Phase.IDLE, "Published ${p.built.storeDir} — $count files verified.$leftOutNote$warnNote")
                else set(Phase.IDLE, "Published ${p.built.storeDir}, but the check found problems:\n" + problems.joinToString("\n") + leftOutNote + warnNote, error = true)
            }
        }
    }
}
