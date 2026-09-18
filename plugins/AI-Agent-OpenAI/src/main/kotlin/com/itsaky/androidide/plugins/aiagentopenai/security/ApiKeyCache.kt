package com.itsaky.androidide.plugins.aiagentopenai.security

import android.content.SharedPreferences
import android.os.Looper
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The decrypted API key, cached against the value on disk.
 *
 * Decrypting costs a Keystore IPC round trip and the backend's `isAvailable()` runs on every
 * generate, so the cost is paid once and paid again only when the stored key actually changes.
 * The main-thread rule lives here too, so the backend never has to know one exists.
 *
 * @param prefs the store holding the encrypted value; re-read on every call
 * @param prefKey the preference name to read
 * @param logger this plugin's IDE-surfaced log
 * @param scope the owner's scope, used to refresh off a main-thread call
 */
internal class ApiKeyCache(
    private val prefs: () -> SharedPreferences?,
    private val prefKey: String,
    private val logger: PluginLogger,
    private val scope: CoroutineScope,
) {

    /** Last decryption, as (value on disk -> plaintext). */
    @Volatile
    private var cached: Pair<String, String?>? = null

    /**
     * The saved key, or null when none is stored.
     *
     * Decryption is Keystore IPC + AES/GCM and must not run on the main thread, so a main-thread
     * call answers from the cache and kicks off a background refresh rather than blocking; [warm]
     * fills the cache first so that never reports "no key".
     */
    fun read(): String? {
        val stored = prefs()?.getString(prefKey, null)
        if (stored.isNullOrBlank()) {
            cached = null
            return null
        }
        cached?.let { (raw, plain) -> if (raw == stored) return plain }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logger.warn("ApiKeyCache: API key read on the main thread; refreshing off-thread")
            // The owner's close() cancels the scope, so without this guard launch is a no-op.
            if (scope.isActive) {
                scope.launch { refresh() }
            } else {
                logger.warn("ApiKeyCache: backend already closed; not refreshing the key cache")
            }
            return null
        }
        return refresh()
    }

    /**
     * Fill the cache off-thread, so a synchronous main-thread [read] never reports "no key" for a
     * stored, decryptable key just because it was first asked from the UI.
     */
    fun warm() {
        if (!scope.isActive) return
        scope.launch {
            try {
                val warmed = refresh() != null
                logger.debug("ApiKeyCache: key cache warmed (key present: $warmed)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("ApiKeyCache: could not warm key cache: ${e.message}")
            }
        }
    }

    /**
     * Drop the decrypted key. Otherwise the plaintext stays reachable on the host process heap for
     * as long as the IDE runs, which is exactly what encrypting at rest is meant to prevent.
     */
    fun clear() {
        cached = null
    }

    /**
     * Decrypt the stored key — upgrading a legacy plaintext value in passing — and cache the
     * result. Off-main-thread only; see [read].
     */
    private fun refresh(): String? {
        val prefs = prefs()
        val plain = when (val stored = secureApiKeyStore.readAndMigrate(prefs, prefKey)) {
            is KeystoreSecretStore.Stored.Value -> stored.plain.trim().takeIf { it.isNotBlank() }
            KeystoreSecretStore.Stored.Absent -> null
            // Reported here rather than passed on as "no key": generation fails either way, but a
            // lost Keystore entry needs the key entering again, and the log is all that says so.
            KeystoreSecretStore.Stored.Unreadable -> {
                logger.warn(
                    "ApiKeyCache: the saved API key cannot be decrypted on this device; " +
                        "it has to be entered again in settings"
                )
                null
            }
            // Transient, so it returns without caching: the key is very likely intact, and caching
            // this answer would freeze "no key" until the stored value itself changed.
            KeystoreSecretStore.Stored.Unavailable -> {
                logger.warn(
                    "ApiKeyCache: the keystore could not be reached to read the saved API key; " +
                        "retrying on the next read"
                )
                return null
            }
        }
        val raw = prefs?.getString(prefKey, null)
        cached = raw?.let { it to plain }
        return plain
    }
}
