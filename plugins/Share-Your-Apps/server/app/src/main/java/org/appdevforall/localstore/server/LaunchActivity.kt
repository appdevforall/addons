package org.appdevforall.localstore.server

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper

/**
 * The plugin's way in. CodeOnTheGo launches this with startActivityForResult, passing the
 * Store directory's name, a token, the PIN and the store name ([LaunchContract]). It checks the
 * caller, asks HostService to restart the server on that directory, waits until the new server
 * has bound its socket, and returns the port — or an error — then finishes, so the owner
 * stays in CodeOnTheGo. It has no UI of its own.
 *
 * On a fresh install the server has no storage access yet, so this asks for it first — the
 * All-files access screen on Android 11+, the permission dialog on 9 and 10 — and carries on
 * with the publish once it's granted.
 */
class LaunchActivity : Activity() {

    companion object {
        private const val TIMEOUT_MS = 20_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var done = false
    private var dir = ""
    /** Sent to the All-files access screen; checked again when the owner comes back. */
    private var awayForAccess = false
    private var sentForAccess = false
    private val onResult: (Int, String?) -> Unit = { port, error -> reply(port, error) }
    private val timeout = Runnable { reply(0, "the server didn't start in time") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // getCallingPackage() is only set for startActivityForResult, and it comes from the
        // system, not from the sender — so another app can't claim to be CodeOnTheGo.
        if (callingPackage != LaunchContract.CALLER) return reply(0, "not launched by CodeOnTheGo", unchanged = true)
        dir = intent.getStringExtra(LaunchContract.EXTRA_DIR).orEmpty()
        if (!Store.validName(dir)) return reply(0, "\"$dir\" isn't a Store directory name", unchanged = true)
        if (Store.hasStorageAccess(this)) accessDecided() else askForAccess()
    }

    private fun askForAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            sentForAccess = true
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onPause() {
        super.onPause()
        if (sentForAccess) awayForAccess = true
    }

    override fun onResume() {
        super.onResume()
        // Only after actually leaving for the settings screen and coming back.
        if (!awayForAccess) return
        awayForAccess = false
        sentForAccess = false
        accessDecided()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        // 1 = storage access (Android 9 and 10); 2 = the hotspot permission. A denied hotspot
        // permission still proceeds: the service reports it clearly.
        if (requestCode == 1) accessDecided() else proceed()
    }

    private fun accessDecided() {
        if (!Store.hasStorageAccess(this)) return reply(0, "Storage access wasn't granted to Local Store Server, so it can't serve the store.", unchanged = true)
        // Not on Wi-Fi: a hotspot will be needed, and starting one needs a permission.
        if (Store.needsHotspot() && !Store.hasHotspotPermission(this)) requestPermissions(Store.hotspotPermissions(), 2)
        else proceed()
    }

    /** Restarts the server on the directory and waits for its answer. */
    private fun proceed() {
        LaunchResult.await(onResult)
        main.postDelayed(timeout, TIMEOUT_MS)
        startForegroundService(
            Intent(this, HostService::class.java)
                .setAction(HostService.ACTION_START)
                .putExtra(HostService.EXTRA_FROM_PLUGIN, true)
                .putExtra(HostService.EXTRA_DIR, dir)
                .putExtra(LaunchContract.EXTRA_TOKEN, intent.getStringExtra(LaunchContract.EXTRA_TOKEN))
                .putExtra(LaunchContract.EXTRA_PIN, intent.getStringExtra(LaunchContract.EXTRA_PIN))
                .putExtra(LaunchContract.EXTRA_STORE_NAME, intent.getStringExtra(LaunchContract.EXTRA_STORE_NAME))
        )
    }

    override fun onDestroy() {
        LaunchResult.cancel(onResult)
        main.removeCallbacks(timeout)
        super.onDestroy()
    }

    /** [unchanged]: the failure came before anything was stopped, so what was being served still is. */
    private fun reply(port: Int, error: String?, unchanged: Boolean = false) {
        if (done) return
        done = true
        main.removeCallbacks(timeout)
        LaunchResult.cancel(onResult)
        if (error == null) {
            setResult(RESULT_OK, Intent().putExtra(LaunchContract.RESULT_PORT, port))
        } else {
            setResult(RESULT_CANCELED, Intent().putExtra(LaunchContract.RESULT_ERROR, error).putExtra(LaunchContract.RESULT_UNCHANGED, unchanged))
        }
        finish()
    }
}
