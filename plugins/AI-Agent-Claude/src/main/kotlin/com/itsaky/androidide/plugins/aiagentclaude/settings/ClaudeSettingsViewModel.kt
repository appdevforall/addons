package com.itsaky.androidide.plugins.aiagentclaude.settings

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aiagentclaude.backend.ClaudeBackend
import com.itsaky.androidide.plugins.aiagentclaude.backend.WorkspaceIds
import com.itsaky.androidide.plugins.aiagentclaude.errors.CredentialFailure
import com.itsaky.androidide.plugins.aiagentclaude.errors.CredentialFailureLog
import com.itsaky.androidide.plugins.aiagentclaude.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentclaude.preferences.ClaudePreferences
import com.itsaky.androidide.plugins.aiagentclaude.security.secureApiKeyStore
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
data class ClaudeModelOptions(val models: List<String>, val isLive: Boolean)

/**
 * Backs this backend's own settings pane. Owns the API key's whole lifecycle — verification,
 * encryption, storage — and the live model catalog.
 */
class ClaudeSettingsViewModel(
    private val getContext: () -> PluginContext?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val catalogGateway: ClaudeCatalogGateway = BackendClaudeCatalogGateway(),
) : ViewModel() {

    companion object {
        private const val TAG = "$LOG_PREFIX.ClaudeSettingsViewModel"

        /**
         * Shown only when the live catalog can't be fetched, so the picker still offers something
         * to tap before a key is entered.
         */
        private val FALLBACK_MODELS = listOf(
            "claude-opus-5-5",
            "claude-sonnet-5-5",
            "claude-haiku-4-5",
            "claude-fable-5-1",
        )
    }

    /**
     * True between tapping *Get API key* and the settings pane's next resume, so the UI can point
     * at the next step once the user is back from the browser. Held here rather than on the fragment
     * so a rotation while the browser is in front doesn't reset it and swallow the hint.
     */
    var sentUserToKeyPage: Boolean = false

    private val _models = MutableLiveData(ClaudeModelOptions(emptyList(), isLive = false))
    val models: LiveData<ClaudeModelOptions> get() = _models

    /**
     * The model the field should show. Re-published when a fetched catalog retires the saved one,
     * so the pane never keeps offering a model the API cannot serve.
     */
    private val _selectedModel = MutableLiveData<String>()
    val selectedModel: LiveData<String> get() = _selectedModel

    private val _modelsLoading = MutableLiveData(false)
    val modelsLoading: LiveData<Boolean> get() = _modelsLoading

    // Last, after every stream it publishes to. Kotlin runs initializers in declaration order, so
    // an init block above them would call publishRememberedModels() while their backing fields are
    // still null — which threw inside the ViewModel's constructor and left the pane blank.
    init {
        // This ViewModel is scoped to the settings pane, so it is rebuilt every time the pane
        // opens. Without this the model dropdown would be empty until the user tested the
        // connection again, which is what made the field look text-only on a second visit.
        publishRememberedModels()
    }

    /**
     * This plugin's own settings store — the same one [ClaudeBackend] reads at request time, so a
     * value saved here is the value that gets used.
     */
    private fun prefs(): SharedPreferences? =
        getContext()?.let(ClaudePreferences::of)

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
     * Publishes the remembered list, if there is one.
     *
     * Marked not-live: a remembered list must never migrate the saved model off itself the way a
     * freshly fetched catalog may, because it could be months old.
     */
    private fun publishRememberedModels() {
        val prefs = prefs() ?: return
        val remembered =
            RememberedModels.decode(prefs.getString(ClaudePreferences.KEY_REMEMBERED_MODELS, null))
        if (remembered.isNotEmpty()) {
            logger?.debug("$TAG: offering ${remembered.size} remembered models")
            publishModels(ClaudeModelOptions(remembered, isLive = false))
        }
    }

    /**
     * Publishes [options] to the picker and retires the saved model when it is not among them.
     *
     * One path for every source of a catalog — remembered, fetched or fallback — so a model can
     * never survive a catalog change by arriving through a route that forgot to check.
     */
    private fun publishModels(options: ClaudeModelOptions) {
        _models.postValue(options)

        val replacement = ModelSelection.adopt(
            current = getModel(),
            models = options.models,
            isLive = options.isLive,
            preferred = ClaudeBackend.DEFAULT_MODEL,
        ) ?: return

        logger?.debug("$TAG: the API does not offer the saved model; switching to $replacement")
        saveModel(replacement)
        _selectedModel.postValue(replacement)
    }

    /** Offers the static list when a live lookup produced nothing. */
    private fun publishFallbackModels() {
        publishModels(ClaudeModelOptions(FALLBACK_MODELS, isLive = false))
    }

    /** Stores a fetched catalog, so the next visit can offer the picker at once. */
    private fun rememberModels(models: List<String>) {
        val encoded = RememberedModels.encode(models) ?: return
        prefs()?.edit()?.putString(ClaudePreferences.KEY_REMEMBERED_MODELS, encoded)?.apply()
    }

    /**
     * Check whether [apiKey] actually works, without storing it.
     *
     * @param apiKey the candidate key as typed, trimmed here
     * @param workspaceId the candidate workspace for a key that belongs to none; checked by
     *   [WorkspaceIds] where it is sent
     * @return the verdict; [ConnectionVerification.Unknown] when nothing could be established
     */
    suspend fun verifyConnection(
        apiKey: String,
        workspaceId: String? = getWorkspaceId(),
    ): ConnectionVerification = withContext(ioDispatcher) {
        val result = try {
            catalogGateway.listModels(apiKey.trim(), workspaceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last-resort net: a verification crash must never be mistaken for a pass.
            logger?.error("$TAG: connection check failed unexpectedly", e)
            CatalogResult.Failed(e)
        }
        result.toConnectionVerification().also { verification ->
            if (verification is ConnectionVerification.Verified) {
                logger?.debug("$TAG: the API offers ${verification.modelCount} models")
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
     * @param workspaceId the workspace this key must name, or null for a key that belongs to one;
     *   written in the same commit, since a key and its workspace are only valid together
     * @return true only if the key was both encrypted and persisted
     */
    suspend fun saveApiKey(apiKey: String, verified: Boolean = false, workspaceId: String? = null): Boolean =
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
            val editor = prefs.edit()
                .putString(ClaudePreferences.KEY_API_KEY, encrypted)
                .putLong(ClaudePreferences.KEY_API_KEY_TIMESTAMP, System.currentTimeMillis())
                .putBoolean(ClaudePreferences.KEY_API_KEY_VERIFIED, verified)
            val workspace = WorkspaceIds.headerValue(workspaceId)
            if (workspace != null) {
                editor.putString(ClaudePreferences.KEY_WORKSPACE_ID, workspace)
            } else {
                editor.remove(ClaudePreferences.KEY_WORKSPACE_ID)
            }
            val saved = editor.commit()
            // The recorded refusal described the key this one replaces; kept, it would be reported
            // against a key that has never been tried.
            if (saved) credentialFailures.clear()
            saved
        }

    /**
     * Whether the stored key was confirmed working by the API when it was saved.
     *
     * False for a key kept after an inconclusive check, so the status line can say "saved" without
     * claiming "verified". Raw pref only, so safe on the main thread.
     */
    fun isKeyVerified(): Boolean =
        prefs()?.getBoolean(ClaudePreferences.KEY_API_KEY_VERIFIED, false) ?: false

    /**
     * Decrypt the stored key off the main thread (Keystore IPC + AES/GCM), upgrading a plaintext
     * value to ciphertext in passing.
     *
     * @return what is on disk: nothing, the key, a key this device's Keystore can no longer open,
     *   or one it would not open just now. Those are not the same — a lost Keystore entry has to be
     *   entered again, a keystore that did not answer only retried — so the caller says which.
     */
    suspend fun getApiKey(): KeystoreSecretStore.Stored = withContext(ioDispatcher) {
        secureApiKeyStore.readAndMigrate(prefs(), ClaudePreferences.KEY_API_KEY)
    }

    /**
     * True when a key is present on disk, whether or not it can still be decrypted: what the key
     * block is dressed from, which must not collapse the moment a Keystore entry is lost. Raw pref
     * only, so no Keystore IPC and safe on the main thread — which [getApiKey] is not.
     */
    fun hasStoredApiKey(): Boolean =
        !prefs()?.getString(ClaudePreferences.KEY_API_KEY, null).isNullOrBlank()

    /** The workspace saved with the key, or null when the key needs none. */
    fun getWorkspaceId(): String? =
        prefs()?.getString(ClaudePreferences.KEY_WORKSPACE_ID, null)?.takeIf { it.isNotBlank() }

    fun getApiKeySaveTimestamp(): Long =
        prefs()?.getLong(ClaudePreferences.KEY_API_KEY_TIMESTAMP, 0L) ?: 0L

    fun clearApiKey() {
        prefs()?.edit()?.apply {
            remove(ClaudePreferences.KEY_API_KEY)
            remove(ClaudePreferences.KEY_API_KEY_TIMESTAMP)
            // Removed with the key, or the next saved key would inherit this one's verdict.
            remove(ClaudePreferences.KEY_API_KEY_VERIFIED)
            // The workspace was this key's; a replacement may belong to one of its own.
            remove(ClaudePreferences.KEY_WORKSPACE_ID)
            // Same reasoning: the refusal described the key being removed.
            remove(ClaudePreferences.KEY_CREDENTIAL_FAILURE)
            remove(ClaudePreferences.KEY_CREDENTIAL_FAILURE_KEY_STAMP)
            apply()
        }
    }

    /** Stores [model], ignoring a blank one rather than storing an unusable model. */
    fun saveModel(model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        prefs()?.edit()?.putString(ClaudePreferences.KEY_MODEL, trimmed)?.apply()
    }

    fun getModel(): String =
        prefs()?.getString(ClaudePreferences.KEY_MODEL, ClaudeBackend.DEFAULT_MODEL)
            ?.takeIf { it.isNotBlank() }
            ?: ClaudeBackend.DEFAULT_MODEL

    /**
     * Ask the API which models the saved key can use, and publish them to [models].
     *
     * Falls back to [FALLBACK_MODELS] when the lookup fails. The fragment always offers free-text
     * entry, so a failed listing never blocks the user.
     */
    fun fetchModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _modelsLoading.postValue(true)

            try {
                when (val result = catalogGateway.listModelsForSavedSettings()) {
                    is CatalogResult.Success -> {
                        if (result.models.isEmpty()) {
                            logger?.warn("$TAG: the API listed no models")
                            publishFallbackModels()
                        } else {
                            logger?.debug("$TAG: fetched ${result.models.size} models")
                            // Remembered before publishing, so a pane reopened straight after a
                            // successful test still finds the list.
                            rememberModels(result.models)
                            publishModels(ClaudeModelOptions(result.models, isLive = true))
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
