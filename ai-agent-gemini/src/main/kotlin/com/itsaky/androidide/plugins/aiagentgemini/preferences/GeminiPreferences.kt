package com.itsaky.androidide.plugins.aiagentgemini.preferences

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext
// Imported for the KDoc link below: the alias the API key is encrypted under lives there.
import com.itsaky.androidide.plugins.aiagentgemini.security.secureApiKeyStore

/**
 * This plugin's own settings store, and the one-time move of its settings out of AI Core's.
 *
 * The API key and the chosen model describe *this* backend, so they belong in this plugin's
 * storage. They used to live in the agent plugin's preferences, which meant this backend could not
 * be configured — or even report itself available — unless that plugin happened to be installed
 * and to have published its own context first.
 */
internal object GeminiPreferences {

    /** This plugin's preferences file. Namespaced to this plugin by the host. */
    private const val FILE = "GeminiSettings"

    const val KEY_API_KEY = "gemini_api_key"
    const val KEY_API_KEY_TIMESTAMP = "gemini_api_key_timestamp"
    const val KEY_API_KEY_VERIFIED = "gemini_api_key_verified"
    const val KEY_MODEL = "gemini_model"

    /**
     * Why the last request was refused for credential reasons, or absent. Diagnostics rather than a
     * setting, so deliberately outside [OWNED_KEYS]: there is nothing here worth carrying over from
     * a legacy store, and a stale reason would accuse a key the user has since replaced.
     */
    const val KEY_CREDENTIAL_FAILURE = "gemini_credential_failure"

    /**
     * When the key that [KEY_CREDENTIAL_FAILURE] describes was saved, so a refusal that lands after
     * a replacement was saved can be told from one about the key in use. Outside [OWNED_KEYS] with
     * the reason it stamps.
     */
    const val KEY_CREDENTIAL_FAILURE_KEY_STAMP = "gemini_credential_failure_key_stamp"

    /** Set once [migrateIfNeeded] has run, so a value changed since is never overwritten. */
    private const val KEY_MIGRATED = "migrated_from_agent_settings"

    /** Everything this backend owns; anything else in the old shared file is not ours to take. */
    private val OWNED_KEYS = listOf(
        KEY_API_KEY, KEY_API_KEY_TIMESTAMP, KEY_API_KEY_VERIFIED, KEY_MODEL,
    )

    /**
     * Files that may still hold this backend's values, newest first. Only ids that have shipped:
     * the pre-rename `…plugins.aigemini` id never reached a release, so no device stores anything
     * under it.
     *
     * Every one is read because plugins load in parallel with no ordering: AI Core adopts the older
     * plugin's settings into its own file on activation, but this backend may migrate before that
     * has happened, and would otherwise find the newer file empty and conclude there is nothing to
     * take.
     */
    private val LEGACY_FILES = listOf(
        legacyFileName("com.itsaky.androidide.plugins.aicore"),
        legacyFileName("com.itsaky.androidide.plugins.aiassistant"),
    )

    /** Name the host gives a plugin's preferences file; see `PluginContextImpl`. */
    private fun legacyFileName(pluginId: String) = "plugin_${pluginId}_AgentSettings"

    /**
     * This plugin's preferences.
     *
     * @param context this plugin's own context — never another plugin's
     */
    fun of(context: PluginContext): SharedPreferences =
        context.getPluginSharedPreferences(FILE)

    /**
     * Copies this backend's settings out of every store in [LEGACY_FILES], once.
     *
     * The API key moves as ciphertext and stays readable: it is encrypted under a Keystore alias
     * (see [secureApiKeyStore]) rather than under anything plugin-specific, and every plugin runs
     * in the host's process and UID. Copies rather than moves, so downgrading still finds the old
     * values. Call before anything reads a setting.
     *
     * @param context this plugin's own context
     */
    fun migrateIfNeeded(context: PluginContext) {
        val prefs = of(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        try {
            for (fileName in LEGACY_FILES) {
                val legacy = context.getAppSharedPreferences(fileName) ?: continue
                if (copyOwnedValues(legacy, prefs)) {
                    context.logger.info("GeminiPreferences: adopted settings from $fileName")
                }
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        } catch (e: Exception) {
            // Not fatal, and deliberately not marked migrated: a fresh install has nothing to
            // copy, and a failure here should get another chance rather than stranding the user
            // with a backend that has lost its key.
            context.logger.error("GeminiPreferences: could not migrate settings", e)
        }
    }

    /**
     * @return true when at least one value was taken from [legacy]
     */
    private fun copyOwnedValues(legacy: SharedPreferences, into: SharedPreferences): Boolean {
        val editor = into.edit()
        var copied = 0
        for (key in OWNED_KEYS) {
            // A value set here already post-dates the old one, so it must not be overwritten.
            if (!legacy.contains(key) || into.contains(key)) continue
            when (val value = legacy.all[key]) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                else -> continue
            }
            copied++
        }
        editor.apply()
        return copied > 0
    }
}
