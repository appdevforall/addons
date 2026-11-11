package org.appdevforall.shareyourapps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import kotlin.concurrent.thread

/**
 * The server APK can travel inside the plugin as the asset `local-store-server.apk`. On publish
 * it's installed when the server is missing or older, and a publish is refused when the
 * installed server is newer, since PackageInstaller won't downgrade. Without the asset, the
 * plugin uses whatever server is installed.
 */
object ServerInstaller {

    const val ASSET = "local-store-server.apk"
    private const val ACTION = "org.appdevforall.shareyourapps.INSTALL_RESULT"

    /** What this publish needs: the APK to install, or null to use the installed server. */
    class Plan(val install: File?)

    sealed class Result {
        object Installed : Result()
        class NeedsConfirmation(val intent: Intent) : Result()
        class Failed(val message: String) : Result()
    }

    /**
     * Pre-flight: decides whether to install. Throws PublishException for a downgrade.
     *
     * CodeOnTheGo declares no package-visibility queries, so it can only see a server it
     * installed itself (with Run, or through this plugin). Nothing here depends on seeing it:
     * with no bundled APK the launch reports a missing server, and with one, an unseen server
     * is simply installed over.
     */
    fun plan(app: Context): Plan {
        val apk = extractBundled(app) ?: return Plan(null)
        val bundled = app.packageManager.getPackageArchiveInfo(apk.path, 0)?.longVersionCode
            ?: throw PublishException("the Local Store Server APK inside this plugin can't be read")
        val installed = installedVersion(app)
        return when {
            installed != null && installed > bundled -> throw PublishException(
                "The installed Local Store Server (version $installed) is newer than the one in this plugin " +
                    "(version $bundled). Update the plugin, or uninstall the server.")
            installed == null || bundled > installed -> Plan(apk)
            else -> Plan(null)
        }
    }

    private fun installedVersion(app: Context): Long? = try {
        app.packageManager.getPackageInfo(ServerContract.PACKAGE, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** The bundled server's version name, such as "1.0.17", or null if there's none. Reads a 2 MB file: call it off the main thread. */
    fun bundledVersionName(app: Context): String? = try {
        extractBundled(app)?.let { app.packageManager.getPackageArchiveInfo(it.path, 0)?.versionName }
    } catch (e: Exception) { null }

    private fun extractBundled(app: Context): File? {
        val input = ShareYourAppsPlugin.context?.resources?.openPluginAsset(ASSET) ?: return null
        val out = File(app.cacheDir, ASSET)
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }

    /** Installs [apk], reporting once on the main thread's Handler via [done]. */
    fun install(app: Context, apk: File, done: (Result) -> Unit) {
        val installer = app.packageManager.packageInstaller
        var receiver: BroadcastReceiver? = null
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) done(Result.NeedsConfirmation(confirm))
                        else { unregister(app, receiver); done(Result.Failed("the install needs confirmation, but no confirmation screen was provided")) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { unregister(app, receiver); done(Result.Installed) }
                    else -> {
                        unregister(app, receiver)
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                        done(Result.Failed(explain(status, msg)))
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)

        thread(name = "install-server") {
            try {
                val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                installer.openSession(id).use { s ->
                    apk.inputStream().use { i -> s.openWrite("server", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName), flags)
                    s.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                unregister(app, receiver)
                done(Result.Failed(e.message ?: e.toString()))
            }
        }
    }

    private fun unregister(app: Context, r: BroadcastReceiver?) {
        try { if (r != null) app.unregisterReceiver(r) } catch (e: IllegalArgumentException) { }
    }

    private fun explain(status: Int, msg: String): String = when {
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> "the install was cancelled"
        // A newer server installed another way — from another owner's share screen, say — which this
        // plugin can't see beforehand, since Code On The Go only sees apps it installed itself.
        "DOWNGRADE" in msg.uppercase() -> "This phone has a newer Local Store Server than the one in this plugin, " +
            "perhaps from another owner's share screen. Update the Share Your Apps plugin, then publish again."
        status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "$msg. If the server in this plugin is signed with a different key from the installed one, " +
                "uninstall Local Store Server once, then publish again."
        status == PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough space to install: $msg"
        else -> msg
    }
}
