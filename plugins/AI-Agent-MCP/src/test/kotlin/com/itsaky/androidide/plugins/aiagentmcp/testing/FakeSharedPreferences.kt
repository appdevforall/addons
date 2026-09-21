package com.itsaky.androidide.plugins.aiagentmcp.testing

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory [SharedPreferences], so the settings store can be exercised off a device.
 *
 * The seam this plugs into exists for one reason — showing that `McpServerStore`'s lock actually
 * serialises a read-modify-write — so [readDelayMillis] is here too: a real preferences read costs
 * a lock and a file, and a race that needs microseconds of window is not a race a test would ever
 * catch on an in-memory map.
 */
class FakeSharedPreferences : SharedPreferences {

    // Typed, not String-keyed: every getter and putter is backed by this one map, so a test that
    // stores a flag or a timestamp through the fake reads back what it wrote rather than the
    // default. A no-op putter would give it a green run that proved nothing.
    private val values = ConcurrentHashMap<String, Any>()

    /** How long a read blocks, widening the window a missing lock would lose a write in. */
    @Volatile
    var readDelayMillis: Long = 0

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String, defValue: String?): String? {
        if (readDelayMillis > 0) Thread.sleep(readDelayMillis)
        return values[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    /** Batches edits and applies them at once, as the real editor does. */
    private inner class FakeEditor : SharedPreferences.Editor {

        private val puts = LinkedHashMap<String, Any>()
        private val removals = LinkedHashSet<String>()
        private var cleared = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) removals += key else puts[key] = value
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            if (values == null) removals += key else puts[key] = LinkedHashSet(values)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            puts[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            puts[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            puts[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            puts[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        override fun commit(): Boolean {
            if (cleared) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(puts)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
