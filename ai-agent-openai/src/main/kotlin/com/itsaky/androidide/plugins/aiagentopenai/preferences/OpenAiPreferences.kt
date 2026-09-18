package com.itsaky.androidide.plugins.aiagentopenai.preferences

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext

/**
 * This plugin's own settings store.
 *
 * The base URL, API key and model describe *this* backend, so they live in this plugin's storage
 * rather than in AI Core's — a backend must be configurable whether or not any particular consumer
 * plugin happens to be installed.
 *
 * There is no migration from an older file: this backend has never shipped before, so there is
 * nothing on any device to adopt.
 */
internal object OpenAiPreferences {

    /** This plugin's preferences file. Namespaced to this plugin by the host. */
    private const val FILE = "OpenAiSettings"

    /** Server base URL, e.g. `https://api.openai.com/v1`. Stored normalized. */
    const val KEY_BASE_URL = "openai_base_url"

    /** API key, stored as ciphertext only. Optional for a server that needs none. */
    const val KEY_API_KEY = "openai_api_key"

    const val KEY_API_KEY_TIMESTAMP = "openai_api_key_timestamp"
    const val KEY_API_KEY_VERIFIED = "openai_api_key_verified"

    /**
     * The base URL [KEY_API_KEY] was saved for.
     *
     * Stored alongside so the key is only ever sent to the server it was issued by: pointing the
     * URL at a private-range address afterwards would otherwise put an OpenAI bearer token on the
     * local network in the clear.
     */
    const val KEY_API_KEY_URL = "openai_api_key_url"

    /** Model id to request, e.g. `gpt-5` or `qwen2.5-coder`. */
    const val KEY_MODEL = "openai_model"

    /**
     * The base URL [KEY_MODEL] was chosen for.
     *
     * Stored alongside so switching servers can tell a model this server offers from one carried
     * over from the last server, which is what would 404 on the first message.
     */
    const val KEY_MODEL_URL = "openai_model_url"

    /** Set once the user has been warned about a cleartext URL, so the warning shows once. */
    const val KEY_CLEARTEXT_ACKNOWLEDGED = "openai_cleartext_acknowledged"

    /**
     * The last model list a server returned, so reopening the settings pane offers the dropdown
     * without another request. Encoded by `RememberedModels`.
     */
    const val KEY_REMEMBERED_MODELS = "openai_remembered_models"

    /**
     * The base URL [KEY_REMEMBERED_MODELS] was fetched from.
     *
     * Stored alongside so a list remembered from LM Studio is never offered after the URL is
     * pointed at OpenAI — the two catalogs have nothing in common.
     */
    const val KEY_REMEMBERED_MODELS_URL = "openai_remembered_models_url"

    /**
     * Why the last request was refused for credential reasons, or absent. Diagnostics rather than a
     * setting: written by the backend, and cleared when a new credential is saved or a request goes
     * through on the stored one — not when the settings pane reads it, which happens on every
     * rotation.
     */
    const val KEY_CREDENTIAL_FAILURE = "openai_credential_failure"

    /**
     * When the key that [KEY_CREDENTIAL_FAILURE] describes was saved, so a refusal that lands after
     * a replacement was saved can be told from one about the key in use.
     */
    const val KEY_CREDENTIAL_FAILURE_KEY_STAMP = "openai_credential_failure_key_stamp"

    /**
     * This plugin's preferences.
     *
     * @param context this plugin's own context — never another plugin's
     */
    fun of(context: PluginContext): SharedPreferences =
        context.getPluginSharedPreferences(FILE)
}
