package com.itsaky.androidide.plugins.aiagentclaude.preferences

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext

/**
 * This plugin's own settings store.
 *
 * The API key and model describe *this* backend, so they live in this plugin's storage rather
 * than in AI Core's — a backend must be configurable whether or not any particular consumer
 * plugin happens to be installed.
 *
 * There is no migration from an older file: this backend has never shipped before, so there is
 * nothing on any device to adopt.
 */
internal object ClaudePreferences {

    /** This plugin's preferences file. Namespaced to this plugin by the host. */
    private const val FILE = "ClaudeSettings"

    /** API key, stored as ciphertext only. */
    const val KEY_API_KEY = "claude_api_key"

    const val KEY_API_KEY_TIMESTAMP = "claude_api_key_timestamp"
    const val KEY_API_KEY_VERIFIED = "claude_api_key_verified"

    /** Model id to request, e.g. `claude-opus-5-5`. */
    const val KEY_MODEL = "claude_model"

    /**
     * The last model list the API returned, so reopening the settings pane offers the dropdown
     * without another request. Encoded by `RememberedModels`.
     */
    const val KEY_REMEMBERED_MODELS = "claude_remembered_models"

    /**
     * Why the last request was refused for credential reasons, or absent. Diagnostics rather than a
     * setting: written by the backend, and cleared when a new credential is saved or a request goes
     * through on the stored one — not when the settings pane reads it, which happens on every
     * rotation.
     */
    const val KEY_CREDENTIAL_FAILURE = "claude_credential_failure"

    /**
     * When the key that [KEY_CREDENTIAL_FAILURE] describes was saved, so a refusal that lands after
     * a replacement was saved can be told from one about the key in use.
     */
    const val KEY_CREDENTIAL_FAILURE_KEY_STAMP = "claude_credential_failure_key_stamp"

    /**
     * This plugin's preferences.
     *
     * @param context this plugin's own context — never another plugin's
     */
    fun of(context: PluginContext): SharedPreferences =
        context.getPluginSharedPreferences(FILE)
}
