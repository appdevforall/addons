package com.itsaky.androidide.plugins.vectorsearch

import android.content.SharedPreferences
import java.io.File

/**
 * When each index build last completed. Kept beside the database rather than in it, so recording
 * it needs no schema version bump, which would discard every user's paid-for index.
 */
interface IndexBuildLog {

    /** Records that the build for [rootsKey] completed at [atMillis]. */
    fun recordBuilt(rootsKey: String, atMillis: Long)

    /** The latest completion among builds of roots within [projectRoot], or null when none. */
    fun lastBuiltUnder(projectRoot: File): Long?

    /** Forgets every build, as clearing the whole index does. */
    fun clear()
}

/**
 * [IndexBuildLog] in this plugin's own preferences file, one entry per roots key.
 *
 * @param preferences the file, or null when the plugin has no context to open it with
 */
class PreferencesIndexBuildLog(
    private val preferences: () -> SharedPreferences?,
) : IndexBuildLog {

    override fun recordBuilt(rootsKey: String, atMillis: Long) {
        preferences()?.edit()?.putLong(KEY_PREFIX + rootsKey, atMillis)?.apply()
    }

    override fun lastBuiltUnder(projectRoot: File): Long? =
        preferences()?.all.orEmpty().entries
            .filter { (key, _) -> key.startsWith(KEY_PREFIX) }
            .filter { (key, _) -> RootsKey.isUnder(key.removePrefix(KEY_PREFIX), projectRoot) }
            .mapNotNull { (_, value) -> value as? Long }
            .maxOrNull()

    override fun clear() {
        preferences()?.edit()?.clear()?.apply()
    }

    companion object {
        /** The preferences file, which holds nothing else. */
        const val FILE = "vector_search_index_builds"

        private const val KEY_PREFIX = "built_at:"
    }
}
