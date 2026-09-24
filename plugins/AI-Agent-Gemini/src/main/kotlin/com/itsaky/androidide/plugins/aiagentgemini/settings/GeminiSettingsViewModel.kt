package com.itsaky.androidide.plugins.aiagentgemini.settings

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aiagentgemini.backend.GeminiBackend
import com.itsaky.androidide.plugins.aiagentgemini.errors.CredentialFailure
import com.itsaky.androidide.plugins.aiagentgemini.errors.CredentialFailureLog
import com.itsaky.androidide.plugins.aiagentgemini.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences
import com.itsaky.androidide.plugins.aiagentgemini.security.secureApiKeyStore
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
data class GeminiModelOptions(val models: List<String>, val isLive: Boolean)

/**
 * Backs this backend's own settings pane. Owns the API key's whole lifecycle — verification,
 * encryption, storage — and the live model catalog, none of which the hosting screen sees.
 */
class GeminiSettingsViewModel(
    private val getContext: () -> PluginContext?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val catalogGateway: GeminiCatalogGateway = BackendGeminiCatalogGateway(),
) : ViewModel() {

    companion object {
        private const val TAG = "$LOG_PREFIX.GeminiSettingsViewModel"

        private val KEY_API_KEY = GeminiPreferences.KEY_API_KEY
        private val KEY_API_KEY_TIMESTAMP = GeminiPreferences.KEY_API_KEY_TIMESTAMP
        private val KEY_API_KEY_VERIFIED = GeminiPreferences.KEY_API_KEY_VERIFIED
        private val KEY_MODEL = GeminiPreferences.KEY_MODEL
        private val KEY_EMBEDDING_MODEL = GeminiPreferences.KEY_EMBEDDING_MODEL

        /** Shown only when the live catalog can't be fetched — current models, no retired ones. */
        private val FALLBACK_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        )

        /** The embedding half of [FALLBACK_MODELS], offered under the same conditions. */
        private val FALLBACK_EMBEDDING_MODELS = listOf(
            "gemini-embedding-001",
            "text-embedding-004",
        )
    }

    /**
     * True between tapping *Get API Key* and the settings pane's next resume, so the UI can
     * point at the next step once the user is back from AI Studio. Held here rather than on the
     * fragment so a rotation while the browser is in front doesn't reset it and swallow the hint.
     */
    var sentUserToAiStudio: Boolean = false

    private val _geminiModels = MutableLiveData(GeminiModelOptions(emptyList(), isLive = false))
    val geminiModels: LiveData<GeminiModelOptions> get() = _geminiModels

    private val _geminiModelsLoading = MutableLiveData(false)
    val geminiModelsLoading: LiveData<Boolean> get() = _geminiModelsLoading

    private val _geminiEmbeddingModels =
        MutableLiveData(GeminiModelOptions(emptyList(), isLive = false))
    val geminiEmbeddingModels: LiveData<GeminiModelOptions> get() = _geminiEmbeddingModels

    /**
     * This plugin's own settings store — the same one [GeminiBackend] reads at request time, so a
     * key saved here is the key that gets used.
     */
    private fun prefs(): SharedPreferences? =
        getContext()?.let(GeminiPreferences::of)

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

    /**
     * Check whether [apiKey] actually works, without storing it anywhere.
     *
     * Asks the backend to list the models the candidate key can reach; see [KeyVerification] for
     * what each verdict establishes. Run this *before* [saveGeminiApiKey]. The key is never logged.
     *
     * @param apiKey the candidate key as typed, trimmed here
     * @return the verdict; [KeyVerification.Unknown] when nothing could be established
     */
    suspend fun verifyGeminiKey(apiKey: String): KeyVerification = withContext(ioDispatcher) {
        val candidate = apiKey.trim()
        if (candidate.isEmpty()) return@withContext KeyVerification.Rejected
        val result = try {
            catalogGateway.listModels(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last-resort net: a verification crash must never be mistaken for a pass.
            logger?.error("$TAG: Gemini key verification failed unexpectedly", e)
            CatalogResult.Failed(e)
        }
        result.toKeyVerification().also { verification ->
            // Diagnostic only: saving a key is not a request for a catalog, so the UI omits this.
            if (verification is KeyVerification.Verified) {
                logger?.debug(
                    "$TAG: Gemini key verified against ${verification.modelCount} " +
                        "chat-capable models"
                )
            }
        }
    }

    /**
     * Encrypts [apiKey] via [secureApiKeyStore] and persists only the ciphertext to private prefs,
     * off the main thread. Nothing is written on failure. Kept separate from [verifyGeminiKey]: a
     * rejected key never reaches here, and an unverifiable one only after the user says so.
     *
     * @param apiKey the plaintext key to store (trimmed before encryption)
     * @param verified true when [verifyGeminiKey] confirmed this key; recorded in the same write so
     *   the flag can never outlive or precede the key it describes
     * @return true only if the key was both encrypted and persisted
     */
    suspend fun saveGeminiApiKey(apiKey: String, verified: Boolean = false): Boolean =
        withContext(ioDispatcher) {
            // Checked first, or the UI would claim an unwritten key was saved.
            val prefs = prefs()
            if (prefs == null) {
                logger?.error("$TAG: cannot save Gemini API key: plugin preferences unavailable")
                return@withContext false
            }
            val encrypted = try {
                secureApiKeyStore.encrypt(apiKey.trim())
            } catch (e: Exception) {
                logger?.error("$TAG: failed to encrypt Gemini API key", e)
                return@withContext false
            }
            // commit(), not apply(): only a synchronous write can honestly return "persisted".
            val saved = prefs.edit()
                .putString(KEY_API_KEY, encrypted)
                .putLong(KEY_API_KEY_TIMESTAMP, System.currentTimeMillis())
                .putBoolean(KEY_API_KEY_VERIFIED, verified)
                .commit()
            // The recorded refusal described the key this one replaces; kept, it would be reported
            // against a key that has never been tried.
            if (saved) credentialFailures.clear()
            saved
        }

    /**
     * Whether the stored key was confirmed working by Google when it was saved.
     *
     * False for a key kept after an inconclusive check, so the status line can say "saved" without
     * claiming "verified". Raw pref only, so safe on the main thread.
     */
    fun isGeminiKeyVerified(): Boolean =
        prefs()?.getBoolean(KEY_API_KEY_VERIFIED, false) ?: false

    /**
     * Decrypt the stored key off the main thread (Keystore IPC + AES/GCM), upgrading a
     * pre-encryption plaintext key to ciphertext in passing so existing installs actually
     * end up encrypted rather than waiting for the user to re-enter the key.
     *
     * @return what is on disk: nothing, the key, a key this device's Keystore can no longer open,
     *   or one it would not open just now. Those are not the same — a lost Keystore entry has to be
     *   entered again, a keystore that did not answer only retried — so the caller says which.
     */
    suspend fun getGeminiApiKey(): KeystoreSecretStore.Stored = withContext(ioDispatcher) {
        secureApiKeyStore.readAndMigrate(prefs(), KEY_API_KEY)
    }

    /**
     * True when a key is present on disk, whether or not it can still be decrypted: what the key
     * block is dressed from, which must not collapse the moment the Keystore declines to answer.
     * Raw pref only, so no Keystore IPC and safe on the main thread — which [getGeminiApiKey] is not.
     */
    fun hasStoredGeminiApiKey(): Boolean =
        !prefs()?.getString(KEY_API_KEY, null).isNullOrBlank()

    fun getGeminiApiKeySaveTimestamp(): Long = prefs()?.getLong(KEY_API_KEY_TIMESTAMP, 0L) ?: 0L

    fun clearGeminiApiKey() {
        prefs()?.edit()?.apply {
            remove(KEY_API_KEY)
            remove(KEY_API_KEY_TIMESTAMP)
            // Removed with the key, or the next saved key would inherit this one's verdict.
            remove(KEY_API_KEY_VERIFIED)
            // Same reasoning: the refusal described the key being removed.
            remove(GeminiPreferences.KEY_CREDENTIAL_FAILURE)
            remove(GeminiPreferences.KEY_CREDENTIAL_FAILURE_KEY_STAMP)
            apply()
        }
    }

    fun saveGeminiModel(model: String) {
        prefs()?.edit()?.putString(KEY_MODEL, model)?.apply()
    }

    fun getGeminiModel(): String =
        prefs()?.getString(KEY_MODEL, GeminiBackend.DEFAULT_MODEL) ?: GeminiBackend.DEFAULT_MODEL

    /**
     * Stores [model] as the embedding model.
     *
     * Its own setting, not a second use of [saveGeminiModel]: the two catalogs are disjoint, and a
     * chat model asked to embed answers 404.
     */
    fun saveGeminiEmbeddingModel(model: String) {
        prefs()?.edit()?.putString(KEY_EMBEDDING_MODEL, model)?.apply()
    }

    fun getGeminiEmbeddingModel(): String =
        prefs()?.getString(KEY_EMBEDDING_MODEL, GeminiBackend.DEFAULT_EMBEDDING_MODEL)
            ?: GeminiBackend.DEFAULT_EMBEDDING_MODEL

    /**
     * Ask the backend for the models the current API key can actually use, and publish them to
     * [geminiModels]. Falls back to [FALLBACK_MODELS] (current models only — never a retired one)
     * when there is no key, no backend, or the live lookup fails, so the picker is never populated
     * with a model that would 404.
     */
    fun fetchGeminiModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _geminiModelsLoading.postValue(true)

            try {
                // The fallback list is the answer to any key it cannot read, whatever the reason,
                // so this is one of the few callers that has no use for the difference.
                val apiKey = (getGeminiApiKey() as? KeystoreSecretStore.Stored.Value)?.plain?.trim()
                if (apiKey.isNullOrBlank()) {
                    logger?.warn("$TAG: no usable Gemini API key saved; showing fallback models")
                    publishFallbackModels()
                    return@launch
                }

                when (val result = catalogGateway.listModelsForSavedKey()) {
                    is CatalogResult.Success -> {
                        if (result.models.isEmpty() && result.embeddingModels.isEmpty()) {
                            logger?.warn("$TAG: live model list empty; showing fallback models")
                            publishFallbackModels()
                        } else {
                            logger?.debug(
                                "$TAG: fetched ${result.models.size} chat and " +
                                    "${result.embeddingModels.size} embedding Gemini models"
                            )
                            publishLive(_geminiModels, result.models, FALLBACK_MODELS)
                            publishLive(
                                _geminiEmbeddingModels,
                                result.embeddingModels,
                                FALLBACK_EMBEDDING_MODELS,
                            )
                        }
                    }
                    // Logged by the gateway; degrade to current-models-only, never a 404 model.
                    CatalogResult.NoBackend, is CatalogResult.Failed -> publishFallbackModels()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.error("$TAG: error fetching Gemini models", e)
                publishFallbackModels()
            } finally {
                _geminiModelsLoading.postValue(false)
            }
        }
    }

    /** Offers both pickers the current-models-only list, which never carries a retired model. */
    private fun publishFallbackModels() {
        _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
        _geminiEmbeddingModels.postValue(
            GeminiModelOptions(FALLBACK_EMBEDDING_MODELS, isLive = false)
        )
    }

    /**
     * Publishes one half of a fetched catalog, falling back when this key can reach nothing of
     * that kind.
     *
     * A key with no embedding models is possible while its chat list is healthy, and publishing an
     * empty list would leave that picker blank with no way to pick anything at all.
     *
     * @param target the picker to publish to
     * @param fetched what the key can reach, possibly empty
     * @param fallback the current-models-only list to offer instead of nothing
     */
    private fun publishLive(
        target: MutableLiveData<GeminiModelOptions>,
        fetched: List<String>,
        fallback: List<String>,
    ) {
        if (fetched.isEmpty()) {
            target.postValue(GeminiModelOptions(fallback, isLive = false))
        } else {
            target.postValue(GeminiModelOptions(fetched, isLive = true))
        }
    }
}
