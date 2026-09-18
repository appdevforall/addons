package com.itsaky.androidide.plugins.aiagentopenai.settings

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aiagentopenai.backend.OpenAiBackend
import com.itsaky.androidide.plugins.aiagentopenai.errors.CredentialFailure
import com.itsaky.androidide.plugins.aiagentopenai.errors.CredentialFailureLog
import com.itsaky.androidide.plugins.aiagentopenai.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentopenai.preferences.OpenAiPreferences
import com.itsaky.androidide.plugins.aiagentopenai.security.secureApiKeyStore
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Models to offer, plus whether they came from a live catalog fetch (vs the fallback list).
 * Migrate a saved-but-missing model off the list only when [isLive] is true.
 *
 * @param models model ids to display in the picker
 * @param isLive true if [models] is a confirmed live catalog, false for the offline fallback
 */
data class OpenAiModelOptions(val models: List<String>, val isLive: Boolean)

/**
 * Backs this backend's own settings pane. Owns the server URL, the API key's whole lifecycle —
 * verification, encryption, storage — and the live model catalog.
 */
class OpenAiSettingsViewModel(
    private val getContext: () -> PluginContext?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val catalogGateway: OpenAiCatalogGateway = BackendOpenAiCatalogGateway(),
) : ViewModel() {

    companion object {
        private const val TAG = "$LOG_PREFIX.OpenAiSettingsViewModel"

        /**
         * Shown only when the live catalog can't be fetched, which for a custom server is the
         * normal case — plenty of them do not implement `/v1/models` at all.
         */
        private val FALLBACK_MODELS = listOf(
            "gpt-5",
            "gpt-5-mini",
            "gpt-4.1",
            "gpt-4o",
            "gpt-4o-mini",
        )
    }

    /**
     * True between tapping *Get API key* and the settings pane's next resume, so the UI can point
     * at the next step once the user is back from the browser. Held here rather than on the fragment
     * so a rotation while the browser is in front doesn't reset it and swallow the hint.
     */
    var sentUserToKeyPage: Boolean = false

    private val _models = MutableLiveData(OpenAiModelOptions(emptyList(), isLive = false))
    val models: LiveData<OpenAiModelOptions> get() = _models

    /**
     * The model the field should show. Re-published when a server change or a fetched catalog
     * retires the saved one, so the pane never keeps offering a model this server cannot serve.
     */
    private val _selectedModel = MutableLiveData<String>()
    val selectedModel: LiveData<String> get() = _selectedModel

    init {
        // This ViewModel is scoped to the settings pane, so it is rebuilt every time the pane
        // opens. Without this the model dropdown would be empty until the user tested the
        // connection again, which is what made the field look text-only on a second visit.
        publishRememberedModels()
    }

    private val _modelsLoading = MutableLiveData(false)
    val modelsLoading: LiveData<Boolean> get() = _modelsLoading

    /**
     * This plugin's own settings store — the same one [OpenAiBackend] reads at request time, so a
     * value saved here is the value that gets used.
     */
    private fun prefs(): SharedPreferences? =
        getContext()?.let(OpenAiPreferences::of)

    /**
     * This plugin's IDE-surfaced log, so settings diagnostics land in the IDE's own log view rather
     * than only in logcat. Null before `initialize()` and in JVM tests.
     */
    private val logger: PluginLogger?
        get() = getContext()?.logger

    /** The credential refusal the backend last hit, which this pane is the place to act on. */
    private val credentialFailures = CredentialFailureLog(::prefs)

    /**
     * Why the last request was refused for credential reasons.
     *
     * Reading does not forget: the view is recreated on every rotation, and a read that cleared
     * would drop the message on the first one — while the credential it names is still the one
     * being used. It is cleared where it stops being true instead: when a new credential is saved,
     * and when a request goes through on the stored one.
     *
     * @return the failure to report, whose wording the caller resolves, or null when the
     *   credential has not been refused
     */
    internal fun credentialFailure(): CredentialFailure? = credentialFailures.read()

    /** The stored server URL, or OpenAI's own API when nothing has been saved. */
    fun getBaseUrl(): String =
        prefs()?.getString(OpenAiPreferences.KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: BaseUrlPolicy.DEFAULT_BASE_URL

    /**
     * Validate and store [input] as the server URL.
     *
     * @param input the URL as typed
     * @return the policy's verdict; nothing is written unless it accepted
     */
    fun saveBaseUrl(input: String): BaseUrlResult {
        val result = BaseUrlPolicy.normalize(input)
        if (result is BaseUrlResult.Accepted) {
            prefs()?.edit()?.putString(OpenAiPreferences.KEY_BASE_URL, result.url)?.apply()
            logger?.debug("$TAG: server URL saved")
            // A different server has a different catalog; keep offering only what still applies.
            publishRememberedModels()
            // The new server can usually be asked outright, which is what retires a stale model.
            if (canListModels()) fetchModels()
        }
        return result
    }

    /**
     * Publishes the remembered list for the currently saved server, if there is one.
     *
     * Marked not-live: a remembered list must never migrate the saved model off itself the way a
     * freshly fetched catalog may, because it could be months old.
     */
    private fun publishRememberedModels() {
        val prefs = prefs() ?: return
        val rememberedFor = prefs.getString(OpenAiPreferences.KEY_REMEMBERED_MODELS_URL, null)
        if (rememberedFor != getBaseUrl()) {
            // Remembered from a different server, so it says nothing about this one.
            publishModels(OpenAiModelOptions(emptyList(), isLive = false))
            return
        }
        val remembered =
            RememberedModels.decode(prefs.getString(OpenAiPreferences.KEY_REMEMBERED_MODELS, null))
        if (remembered.isNotEmpty()) {
            logger?.debug("$TAG: offering ${remembered.size} remembered models")
            publishModels(OpenAiModelOptions(remembered, isLive = false))
        }
    }

    /**
     * Publishes [options] to the picker and retires the saved model when it is not among them.
     *
     * One path for every source of a catalog — remembered, fetched or fallback — so a model can
     * never survive a server change by arriving through a route that forgot to check.
     */
    private fun publishModels(options: OpenAiModelOptions) {
        _models.postValue(options)

        val replacement = ModelSelection.adopt(
            current = getModel(),
            models = options.models,
            isLive = options.isLive,
            savedForThisServer = modelBelongsToSavedServer(),
            preferred = OpenAiBackend.DEFAULT_MODEL,
        ) ?: return

        logger?.debug("$TAG: this server does not offer the saved model; switching to $replacement")
        saveModel(replacement)
        _selectedModel.postValue(replacement)
    }

    /**
     * Whether the saved model was chosen for the server now configured.
     *
     * An unrecorded server counts as this one: settings written before the model was tracked per
     * server cannot be proven stale, and retiring a model the user did pick here would be worse.
     */
    private fun modelBelongsToSavedServer(): Boolean {
        val chosenFor = prefs()?.getString(OpenAiPreferences.KEY_MODEL_URL, null) ?: return true
        return chosenFor == getBaseUrl()
    }

    /**
     * Whether the saved server can be asked for its catalog at all: an OpenAI-compatible server
     * needing a key we do not have would only answer 401.
     */
    private fun canListModels(): Boolean =
        !BaseUrlPolicy.requiresApiKey(getBaseUrl()) || hasStoredApiKey()

    /**
     * Offers the static list when a live lookup produced nothing.
     *
     * Only for OpenAI's own API: those names are OpenAI's, and offering `gpt-5` for an LM Studio
     * server would populate the picker with models that are guaranteed to 404. A custom server
     * keeps whatever was remembered, or stays free-text.
     */
    private fun publishFallbackModels() {
        if (!BaseUrlPolicy.requiresApiKey(getBaseUrl())) {
            logger?.debug("$TAG: custom server listed nothing; leaving the model field free-text")
            return
        }
        publishModels(OpenAiModelOptions(FALLBACK_MODELS, isLive = false))
    }

    /** Stores [models] against the current server, so the next visit can offer them at once. */
    private fun rememberModels(models: List<String>) {
        val encoded = RememberedModels.encode(models) ?: return
        prefs()?.edit()
            ?.putString(OpenAiPreferences.KEY_REMEMBERED_MODELS, encoded)
            ?.putString(OpenAiPreferences.KEY_REMEMBERED_MODELS_URL, getBaseUrl())
            ?.apply()
    }

    /** True when a key is mandatory for the currently saved server, i.e. it is OpenAI's own API. */
    fun keyRequiredForSavedServer(): Boolean = BaseUrlPolicy.requiresApiKey(getBaseUrl())

    /** True once the user has acknowledged sending traffic in the clear, so it is asked once. */
    fun isCleartextAcknowledged(): Boolean =
        prefs()?.getBoolean(OpenAiPreferences.KEY_CLEARTEXT_ACKNOWLEDGED, false) ?: false

    fun markCleartextAcknowledged() {
        prefs()?.edit()?.putBoolean(OpenAiPreferences.KEY_CLEARTEXT_ACKNOWLEDGED, true)?.apply()
    }

    /**
     * Check whether [apiKey] actually works against [baseUrl], without storing either.
     *
     * @param apiKey the candidate key as typed, trimmed here; blank is valid for a local server
     * @param baseUrl the candidate server; defaults to the saved one
     * @return the verdict; [ConnectionVerification.Unknown] when nothing could be established
     */
    suspend fun verifyConnection(
        apiKey: String,
        baseUrl: String = getBaseUrl(),
    ): ConnectionVerification = withContext(ioDispatcher) {
        val normalized = (BaseUrlPolicy.normalize(baseUrl) as? BaseUrlResult.Accepted)?.url
            ?: return@withContext ConnectionVerification.Unknown
        val result = try {
            catalogGateway.listModels(apiKey.trim(), normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last-resort net: a verification crash must never be mistaken for a pass.
            logger?.error("$TAG: connection check failed unexpectedly", e)
            CatalogResult.Failed(e)
        }
        result.toConnectionVerification().also { verification ->
            if (verification is ConnectionVerification.Verified) {
                logger?.debug("$TAG: server offers ${verification.modelCount} chat models")
            }
        }
    }

    /**
     * Encrypts [apiKey] via [secureApiKeyStore] and persists only the ciphertext, off the main
     * thread. Nothing is written on failure.
     *
     * @param apiKey the plaintext key to store (trimmed before encryption)
     * @param verified true when [verifyConnection] confirmed this key; recorded in the same write so
     *   the flag can never outlive or precede the key it describes
     * @return true only if the key was both encrypted and persisted
     */
    suspend fun saveApiKey(apiKey: String, verified: Boolean = false): Boolean =
        withContext(ioDispatcher) {
            // Checked first, or the UI would claim an unwritten key was saved.
            val prefs = prefs()
            if (prefs == null) {
                logger?.error("$TAG: cannot save API key: plugin preferences unavailable")
                return@withContext false
            }
            val encrypted = try {
                secureApiKeyStore.encrypt(apiKey.trim())
            } catch (e: Exception) {
                logger?.error("$TAG: failed to encrypt API key", e)
                return@withContext false
            }
            // commit(), not apply(): only a synchronous write can honestly return "persisted".
            val saved = prefs.edit()
                .putString(OpenAiPreferences.KEY_API_KEY, encrypted)
                .putLong(OpenAiPreferences.KEY_API_KEY_TIMESTAMP, System.currentTimeMillis())
                .putBoolean(OpenAiPreferences.KEY_API_KEY_VERIFIED, verified)
                // Written with the key so the backend can refuse to send it anywhere else.
                .putString(OpenAiPreferences.KEY_API_KEY_URL, getBaseUrl())
                .commit()
            // The recorded refusal described the key this one replaces; kept, it would be reported
            // against a key that has never been tried.
            if (saved) credentialFailures.clear()
            saved
        }

    /**
     * Whether the stored key was confirmed working by the server when it was saved.
     *
     * False for a key kept after an inconclusive check, so the status line can say "saved" without
     * claiming "verified". Raw pref only, so safe on the main thread.
     */
    fun isKeyVerified(): Boolean =
        prefs()?.getBoolean(OpenAiPreferences.KEY_API_KEY_VERIFIED, false) ?: false

    /**
     * Decrypt the stored key off the main thread (Keystore IPC + AES/GCM), upgrading a plaintext
     * value to ciphertext in passing.
     *
     * @return what is on disk: nothing, the key, a key this device's Keystore can no longer open,
     *   or one it would not open just now. Those are not the same — a lost Keystore entry has to be
     *   entered again, a keystore that did not answer only retried — so the caller says which.
     */
    suspend fun getApiKey(): KeystoreSecretStore.Stored = withContext(ioDispatcher) {
        secureApiKeyStore.readAndMigrate(prefs(), OpenAiPreferences.KEY_API_KEY)
    }

    /**
     * What is stored for [baseUrl] — the whole four-way read, not a key-or-null.
     *
     * What the connection test sends: testing a LAN server must not hand it the key the user
     * entered for OpenAI, so a key saved for another origin reads as absent. A key stored before
     * the origin was recorded is returned, matching the backend's own rule.
     *
     * @return the read, with a key belonging to another server reported as
     *   [KeystoreSecretStore.Stored.Absent] — the test's answer for it is the same as for nothing
     *   stored. [KeystoreSecretStore.Stored.Unavailable] is *not* that answer: the key is intact,
     *   so the caller retries instead of testing without one.
     */
    suspend fun getApiKeyFor(baseUrl: String): KeystoreSecretStore.Stored {
        val savedFor = prefs()?.getString(OpenAiPreferences.KEY_API_KEY_URL, null)
        if (savedFor != null && !BaseUrlPolicy.sameOrigin(savedFor, baseUrl)) {
            return KeystoreSecretStore.Stored.Absent
        }
        return getApiKey()
    }

    /**
     * True when a key is present on disk, whether or not it can still be decrypted: what the key
     * block is dressed from, which must not collapse the moment a Keystore entry is lost. Raw pref
     * only, so no Keystore IPC and safe on the main thread — which [getApiKey] is not.
     */
    fun hasStoredApiKey(): Boolean =
        !prefs()?.getString(OpenAiPreferences.KEY_API_KEY, null).isNullOrBlank()

    fun getApiKeySaveTimestamp(): Long =
        prefs()?.getLong(OpenAiPreferences.KEY_API_KEY_TIMESTAMP, 0L) ?: 0L

    fun clearApiKey() {
        prefs()?.edit()?.apply {
            remove(OpenAiPreferences.KEY_API_KEY)
            remove(OpenAiPreferences.KEY_API_KEY_TIMESTAMP)
            // Removed with the key, or the next saved key would inherit this one's verdict.
            remove(OpenAiPreferences.KEY_API_KEY_VERIFIED)
            // Likewise its origin: a stale one would decide where the *next* key may be sent.
            remove(OpenAiPreferences.KEY_API_KEY_URL)
            // Same reasoning: the refusal described the key being removed.
            remove(OpenAiPreferences.KEY_CREDENTIAL_FAILURE)
            remove(OpenAiPreferences.KEY_CREDENTIAL_FAILURE_KEY_STAMP)
            apply()
        }
    }

    /**
     * Stores [model] against the server it was chosen for, so a later server change can tell it
     * from one carried over.
     */
    fun saveModel(model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        prefs()?.edit()
            ?.putString(OpenAiPreferences.KEY_MODEL, trimmed)
            ?.putString(OpenAiPreferences.KEY_MODEL_URL, getBaseUrl())
            ?.apply()
    }

    fun getModel(): String =
        prefs()?.getString(OpenAiPreferences.KEY_MODEL, OpenAiBackend.DEFAULT_MODEL)
            ?.takeIf { it.isNotBlank() }
            ?: OpenAiBackend.DEFAULT_MODEL

    /**
     * Ask the configured server which models it offers, and publish them to [models].
     *
     * Falls back to [FALLBACK_MODELS] when the lookup fails — which for a compatible server that
     * does not implement `/v1/models` is expected, not exceptional. The fragment always offers
     * free-text entry, so a failed listing never blocks the user.
     */
    fun fetchModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _modelsLoading.postValue(true)

            try {
                when (val result = catalogGateway.listModelsForSavedSettings()) {
                    is CatalogResult.Success -> {
                        if (result.models.isEmpty()) {
                            logger?.warn("$TAG: server listed no models")
                            publishFallbackModels()
                        } else {
                            logger?.debug("$TAG: fetched ${result.models.size} models")
                            // Remembered before publishing, so a pane reopened straight after a
                            // successful test still finds the list.
                            rememberModels(result.models)
                            publishModels(OpenAiModelOptions(result.models, isLive = true))
                        }
                    }
                    // Logged by the gateway; degrade to something the user can override.
                    CatalogResult.NoBackend, is CatalogResult.Failed -> publishFallbackModels()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.error("$TAG: error fetching models", e)
                publishFallbackModels()
            } finally {
                _modelsLoading.postValue(false)
            }
        }
    }
}
