package org.appdevforall.shellhooks

import android.content.SharedPreferences
import org.appdevforall.shellhooks.engine.Hook
import org.appdevforall.shellhooks.engine.HookSwitches
import org.appdevforall.shellhooks.engine.KeyValueStore
import org.appdevforall.shellhooks.engine.TimeoutSetting

/** What the user chose on the settings screen. Everything starts off: hooks run only once enabled. */
class ShellHooksSettings(
    private val prefs: SharedPreferences,
) : HookSwitches {
    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    override var projectHooksEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROJECT_HOOKS, false)
        set(value) = prefs.edit().putBoolean(KEY_PROJECT_HOOKS, value).apply()

    override fun isHookEnabled(hook: Hook): Boolean = prefs.getBoolean(hookKey(hook), true)

    fun setHookEnabled(
        hook: Hook,
        enabled: Boolean,
    ) = prefs.edit().putBoolean(hookKey(hook), enabled).apply()

    /** Set only from [TimeoutSetting.parse]; a value out of range is refused, not clamped. */
    var timeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS).coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        set(value) {
            require(value in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) { "Timeout out of range: $value" }
            prefs.edit().putInt(KEY_TIMEOUT, value).apply()
        }

    private fun hookKey(hook: Hook) = "hook.${hook.id}"

    companion object {
        const val PREFS = "settings"
        const val TRUST_PREFS = "trust"
        const val DEFAULT_TIMEOUT_SECONDS = TimeoutSetting.DEFAULT_SECONDS
        const val MIN_TIMEOUT_SECONDS = TimeoutSetting.MIN_SECONDS
        const val MAX_TIMEOUT_SECONDS = TimeoutSetting.MAX_SECONDS

        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROJECT_HOOKS = "project_hooks"
        private const val KEY_TIMEOUT = "timeout_seconds"
    }
}

/** [KeyValueStore] over SharedPreferences, for the trust store. */
class PrefsKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    // commit, not apply: a trust decision must be on disk before the script it covers can run.
    override fun putString(
        key: String,
        value: String?,
    ) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }

    override fun keys(): Set<String> = prefs.all.keys
}
