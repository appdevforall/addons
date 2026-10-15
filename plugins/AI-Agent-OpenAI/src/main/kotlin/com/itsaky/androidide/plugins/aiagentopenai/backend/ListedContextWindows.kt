package com.itsaky.androidide.plugins.aiagentopenai.backend

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aiagentopenai.preferences.OpenAiPreferences
import com.itsaky.androidide.plugins.aiagentopenai.settings.RememberedModels

/**
 * The context windows a server's model listing reported, kept per model and stamped with that
 * server, so [ContextWindow.resolve] can answer without asking the server again.
 *
 * @param prefs this plugin's settings store, or null when it cannot be opened
 */
internal class ListedContextWindows(private val prefs: () -> SharedPreferences?) {

    /** The last decoded value, keyed by the stored string it came from; decoding is per change. */
    @Volatile
    private var decoded: Pair<String, Map<String, Int>>? = null

    /**
     * Stores what [baseUrl] listed, replacing the last listing. A write that changes nothing is
     * skipped, so a recurring probe does not announce a change that did not happen.
     *
     * @param windows the windows the listing reported; empty forgets any earlier ones
     */
    fun record(baseUrl: String, windows: Map<String, Int>) {
        val prefs = prefs() ?: return
        val encoded = RememberedModels.encodeWindows(windows)
        val storedFor = prefs.getString(OpenAiPreferences.KEY_LISTED_CONTEXT_WINDOWS_URL, null)
        val stored = prefs.getString(OpenAiPreferences.KEY_LISTED_CONTEXT_WINDOWS, null)
        if (storedFor == baseUrl && stored == encoded) return
        prefs.edit()
            .putString(OpenAiPreferences.KEY_LISTED_CONTEXT_WINDOWS, encoded)
            .putString(OpenAiPreferences.KEY_LISTED_CONTEXT_WINDOWS_URL, baseUrl)
            .apply()
    }

    /**
     * The window [baseUrl] listed for [model], or null when it listed none or the stored listing
     * came from another server. Reads memory only, so it is safe on any thread.
     */
    fun windowFor(baseUrl: String, model: String): Int? {
        val prefs = prefs() ?: return null
        if (prefs.getString(OpenAiPreferences.KEY_LISTED_CONTEXT_WINDOWS_URL, null) != baseUrl) {
            return null
        }
        val stored = prefs.getString(OpenAiPreferences.KEY_LISTED_CONTEXT_WINDOWS, null)
            ?: return null
        val windows = decoded?.takeIf { it.first == stored }?.second
            ?: RememberedModels.decodeWindows(stored).also { decoded = stored to it }
        return windows[model]
    }
}
