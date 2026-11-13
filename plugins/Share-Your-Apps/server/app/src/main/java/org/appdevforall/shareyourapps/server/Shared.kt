package org.appdevforall.shareyourapps.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Messages between the main process (HostService) and the server process (ServerService). */
object Ipc {
    const val MSG_START = 1    // main → server: data = dir, port, logLimit
    const val MSG_STARTED = 2  // server → main: arg1 = pid, arg2 = port, data = address
    const val MSG_FAILED = 3   // server → main: arg1 = pid, data = error
    const val MSG_STOP = 4     // main → server: data = reason. The server logs it, closes its sockets, replies.
    const val MSG_STOPPED = 5  // server → main: ready to be killed
    const val MSG_LOG = 6      // main → server: data = line. Lets the main process log while the server writes the file.

    // Sharing a store with another seller (design, Appendix E). The share screen binds to the
    // server process directly, and asks what's happening twice a second.
    const val MSG_SHARE_OPEN = 7   // main → server: show a new share code for the store being served
    const val MSG_SHARE_POLL = 8   // main → server: what's happening?
    const val MSG_SHARE_ALLOW = 9  // main → server: data = shareCopyExpires (epoch ms, or -1 for never)
    const val MSG_SHARE_DENY = 10  // main → server
    const val MSG_SHARE_CLOSE = 11 // main → server: the share screen closed
    const val MSG_SHARE_STATE = 12 // server → main: data = the share's state, below
    const val MSG_COPY_EXPIRED = 13 // server → main: the copy being served expired, and was deleted; data = storeName

    const val KEY_DIR = "dir"
    const val KEY_PORT = "port"
    const val KEY_LOG_LIMIT = "logLimit"
    const val KEY_ADDRESS = "address"
    const val KEY_ERROR = "error"
    const val KEY_PIN = "pin"
    const val KEY_STORE_NAME = "storeName"
    const val KEY_SECRET = "secret"
    const val KEY_TOKEN = "token"
    const val KEY_REASON = "reason"
    /** Which server start a reply belongs to, so replies from a server being replaced are ignored. */
    const val KEY_GEN = "gen"
    /** The external address to bind: the Wi-Fi address, or the hotspot's. Chosen by the main process. */
    const val KEY_BIND_ADDRESS = "bindAddress"
    /** `[hotspot]` lines from the main process, written to the log before anything else. */
    const val KEY_LOG_LINES = "logLines"
    const val KEY_LINE = "line"
    /** A ShareHost.State name, or "NONE" when the server isn't serving, or "ERROR" with shareError. */
    const val KEY_SHARE_STATE = "shareState"
    const val KEY_SHARE_CODE = "shareCode"
    const val KEY_SHARE_LINK = "shareLink"
    const val KEY_SHARE_CODE_EXPIRES = "shareCodeExpires"
    const val KEY_SHARE_PHONE = "sharePhone"
    const val KEY_SHARE_CHECK = "shareCheck"
    const val KEY_SHARE_STORE = "shareStore"
    const val KEY_SHARE_COPY_EXPIRES = "shareCopyExpires"
    const val KEY_SHARE_ERROR = "shareError"
    /** Where a phone without the app downloads it, while the share is open. */
    const val KEY_SHARE_APP_URL = "shareAppUrl"
    /** How many transfers of the allowed copy failed partway; the seller may try again. */
    const val KEY_SHARE_FAILURES = "shareFailures"
    /** Serving an imported copy (design, Appendix E): its folder in the app's private storage, instead of KEY_DIR. */
    const val KEY_COPY_PATH = "copyPath"
}

/**
 * What the plugin sends to [LaunchActivity], and what it gets back. The plugin launches with
 * startActivityForResult, naming this component explicitly.
 */
object LaunchContract {
    const val PACKAGE = "org.appdevforall.shareyourapps.server"
    const val ACTIVITY = "org.appdevforall.shareyourapps.server.LaunchActivity"
    const val CALLER = "com.itsaky.androidide" // CodeOnTheGo

    const val EXTRA_DIR = "dir"              // Store_ and 8 digits
    const val EXTRA_TOKEN = "token"          // random; lets the verify step past the PIN
    const val EXTRA_PIN = "pin"              // digit string, or absent for no PIN
    const val EXTRA_STORE_NAME = "storeName"

    const val RESULT_PORT = "port"           // Int, with RESULT_OK
    const val RESULT_ERROR = "error"         // String, with RESULT_CANCELED
    /**
     * Boolean, with RESULT_CANCELED: true only if the launch failed before touching the server, so
     * whatever was being served still is. False once a start has begun, since the old store is
     * stopped first. Absent (from older servers) means false.
     */
    const val RESULT_UNCHANGED = "unchanged"
}

/** Hands one start result from HostService to the waiting LaunchActivity. Main process only. */
object LaunchResult {
    private var waiting: ((port: Int, error: String?) -> Unit)? = null

    fun await(callback: (port: Int, error: String?) -> Unit) {
        waiting?.invoke(0, "superseded by a newer launch")
        waiting = callback
    }
    fun cancel(callback: (port: Int, error: String?) -> Unit) { if (waiting === callback) waiting = null }
    fun deliver(port: Int, error: String?) {
        val w = waiting ?: return
        waiting = null
        w(port, error)
    }
}

object Store {
    const val DEFAULT_PORT = 6175
    const val DEFAULT_LOG_LIMIT = 10L * 1024 * 1024
    private val NAME = Regex("""Store_[0-9]{8}""")

    fun validName(name: String) = NAME.matches(name)

    /** CodeOnTheGoProjects/<name> in shared storage. Only called with a name that passed [validName]. */
    fun dir(name: String) = File(Environment.getExternalStorageDirectory(), "CodeOnTheGoProjects/$name")

    fun logDir(context: Context) = File(context.filesDir, "logs")

    /** The joined Wi-Fi network's address, or null when the phone isn't on Wi-Fi. */
    fun wifiAddress(): java.net.InetAddress? = pickWifiAddress(systemInterfaces())

    /** The phone's own Mobile hotspot's address, when it's turned on in Settings. */
    fun phoneHotspotAddress(): java.net.InetAddress? = pickPhoneHotspotAddress(systemInterfaces())

    /** Starting a hotspot needs Nearby devices on Android 13+, and location on 12 and earlier. */
    fun hotspotPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasHotspotPermission(context: Context): Boolean =
        hotspotPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** A hotspot would be needed: not on Wi-Fi, and none running yet. */
    fun needsHotspot(): Boolean = wifiAddress() == null && phoneHotspotAddress() == null && Hotspot.info == null

    /** MANAGE_EXTERNAL_STORAGE on Android 11+, the older storage permissions on Android 9 and 10. */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

/**
 * The server's state as the main process sees it: a status line, and the store's address while
 * it's serving. Main process only; listeners run on the main thread.
 */
object ServerStatus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var text: String = "Stopped"
        private set
    @Volatile var url: String? = null
        private set
    /** Whether what's being served is an imported copy, not this phone's own store. Set by HostService. */
    @Volatile var servingCopy: Boolean = false
    /** While a hotspot is up: its network name and password, for buyers to join. */
    @Volatile var hotspot: String? = null
        private set
    /** Serving on the phone's own Mobile hotspot, whose name and password apps can't read. */
    @Volatile var onPhoneHotspot = false
        private set

    fun set(t: String, address: String? = null, hotspotLine: String? = null, phoneHotspot: Boolean = false) {
        onPhoneHotspot = phoneHotspot
        text = t
        url = address
        hotspot = hotspotLine
        main.post { listeners.forEach { it() } }
    }

    fun observe(l: () -> Unit) { listeners += l; l() }
    fun remove(l: () -> Unit) { listeners -= l }
}
