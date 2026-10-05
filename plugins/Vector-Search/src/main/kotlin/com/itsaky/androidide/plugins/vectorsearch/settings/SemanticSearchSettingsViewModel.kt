package com.itsaky.androidide.plugins.vectorsearch.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Bounds a model listing that never answers, well above a backend's own request timeouts so a slow
 * but live catalog is not cut short.
 */
private const val LIST_MODELS_TIMEOUT_MS = 60_000L

/** The embedding models the dropdown may offer, for one backend. */
sealed interface ModelOptions {

    /** Nothing to list: the backend is not supported, or has not been read yet. */
    data object None : ModelOptions

    /** @param backendId the backend being asked */
    data class Loading(val backendId: String) : ModelOptions

    /**
     * @param backendId the backend that answered
     * @param models its embedding models, in its order; possibly empty
     */
    data class Loaded(val backendId: String, val models: List<String>) : ModelOptions

    /**
     * @param backendId the backend that failed
     * @param reason the backend's own sentence, or null when it gave none
     */
    data class Failed(val backendId: String, val reason: String?) : ModelOptions
}

/**
 * Everything the Semantic Search screen draws.
 *
 * @param compatibility the selected backend, or null until first read
 * @param models what the embedding model dropdown offers
 * @param indexStatus what the open project's index holds, or null until first read
 * @param pickedModel the model the user just picked here, so the screen can say a rebuild follows
 * @param clearing whether a confirmed clear is still running
 * @param canClear whether a clear would do anything: some project has rows, or a build is running
 */
data class SemanticSearchState(
    val compatibility: BackendCompatibility? = null,
    val models: ModelOptions = ModelOptions.None,
    val indexStatus: IndexStatus? = null,
    val pickedModel: String? = null,
    val clearing: Boolean = false,
    val canClear: Boolean = false,
) {

    /** The word the Index section leads with, or null until the index has been read. */
    val indexHealth: IndexHealth?
        get() = indexStatus?.let { IndexHealth.of(it, compatibility) }


    /** [models] when they belong to the selected backend; another backend's are never shown. */
    val selectedModels: ModelOptions
        get() {
            val selectedId = (compatibility as? BackendCompatibility.Selected)?.backendId
            return if (models.ownerId == selectedId) models else ModelOptions.None
        }
}

/** The backend these options were read from, or null when they belong to none. */
private val ModelOptions.ownerId: String?
    get() = when (this) {
        ModelOptions.None -> null
        is ModelOptions.Loading -> backendId
        is ModelOptions.Loaded -> backendId
        is ModelOptions.Failed -> backendId
    }

/**
 * Reads the selected backend, its embedding models and the index, and reads them again whenever
 * the source reports a change, so switching backend in AI settings reaches an open screen.
 *
 * @param source what the screen reads and resets, or null before the plugin is activated
 * @param ioDispatcher where reads that may touch another plugin or the database run
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchSettingsViewModel(
    private val source: () -> SemanticSearchSource?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SemanticSearchState())
    val state: StateFlow<SemanticSearchState> = _state.asStateFlow()

    /** Asks for a full read; merged into the backend flow so only one read ever runs. */
    private val refreshRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The source whose changes are watched, re-read on [refresh]: null before the plugin is
     * activated, and a new instance on each activate, whose flows are not the old one's.
     */
    private val owners = MutableStateFlow(source())

    init {
        viewModelScope.launch {
            // Latest: a newer change cancels the older read, so its result can never land after.
            owners
                .flatMapLatest { owner -> owner?.backendChanges ?: flowOf(0L) }
                .let { merge(it, refreshRequests) }
                .collectLatest { reloadAll() }
        }
        viewModelScope.launch {
            owners
                .flatMapLatest { owner -> owner?.indexChanges ?: emptyFlow() }
                .collectLatest { reloadIndex() }
        }
    }

    /** Reads everything again; for a screen coming back to the foreground. */
    fun refresh() {
        owners.value = source()
        refreshRequests.tryEmit(Unit)
    }

    /**
     * Makes [modelId] the backend's embedding model. The backend stores it and notifies AI Core,
     * whose notice comes back as a backend change.
     *
     * @param modelId a model the dropdown offered
     */
    fun selectModel(modelId: String) {
        val selectable = selectableOf(_state.value.compatibility) ?: return
        if (modelId == selectable.modelId) return
        viewModelScope.launch {
            try {
                // Off the main thread: another plugin's setter may load its preferences from disk.
                withContext(ioDispatcher) { selectable.backend.setEmbeddingModelId(modelId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("could not set the embedding model to $modelId", e)
                return@launch
            }
            _state.update { it.copy(pickedModel = modelId) }
        }
    }

    /** Clears every project's index; the caller has already asked the user to confirm. */
    fun clearIndex() {
        val owner = source() ?: return
        if (_state.value.clearing) return
        _state.update { it.copy(clearing = true) }
        viewModelScope.launch {
            try {
                // Off the main thread: the clear queues behind a build holding the index lock.
                withContext(ioDispatcher) { owner.clearIndex() }.join()
            } finally {
                _state.update { it.copy(clearing = false) }
            }
            reloadIndex()
        }
    }

    private suspend fun reloadAll() {
        val compatibility = withContext(ioDispatcher) {
            BackendCompatibilityResolver.resolve(source()?.inferenceService())
        }
        val selectable = selectableOf(compatibility)
        _state.update { current ->
            current.copy(
                compatibility = compatibility,
                models = if (selectable == null) ModelOptions.None else current.models,
                // Dropped once the backend's model is no longer the pick, e.g. changed elsewhere.
                pickedModel = current.pickedModel.takeIf { it == selectable?.modelId },
            )
        }
        reloadIndex()
        if (selectable != null) {
            loadModels((compatibility as BackendCompatibility.Selected).backendId, selectable)
        }
    }

    private suspend fun reloadIndex() {
        val (indexStatus, holdsAnything) = withContext(ioDispatcher) {
            try {
                val owner = source()
                val status = owner?.indexStatus() ?: IndexStatus.NoProject
                // A running build always has something to stop, so it skips the database read.
                status to (status is IndexStatus.Building || owner?.indexHoldsAnything() == true)
            } catch (e: Exception) {
                log("could not read the index status", e)
                IndexStatus.NoProject to false
            }
        }
        _state.update { it.copy(indexStatus = indexStatus, canClear = holdsAnything) }
    }

    /**
     * Lists [backendId]'s embedding models on every backend change, since a new server or key
     * changes them under the same id. A list already shown stays up while the new one loads.
     */
    private suspend fun loadModels(backendId: String, selectable: EmbeddingSupport.Selectable) {
        if (_state.value.selectedModels !is ModelOptions.Loaded) {
            _state.update { it.copy(models = ModelOptions.Loading(backendId)) }
        }
        val options = try {
            val models = withTimeout(LIST_MODELS_TIMEOUT_MS) {
                selectable.backend.listEmbeddingModels().await()
            }
            ModelOptions.Loaded(backendId, models.filter { it.isNotBlank() }.distinct())
        } catch (e: TimeoutCancellationException) {
            ModelOptions.Failed(backendId, reason = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelOptions.Failed(backendId, e.message?.takeIf { it.isNotBlank() })
        }
        _state.update { it.copy(models = options) }
    }

    private fun selectableOf(compatibility: BackendCompatibility?): EmbeddingSupport.Selectable? =
        (compatibility as? BackendCompatibility.Selected)?.support as? EmbeddingSupport.Selectable

    private fun log(message: String, error: Throwable) {
        source()?.logger()?.warn("SemanticSearchSettings: $message", error)
    }
}

/** Builds [SemanticSearchSettingsViewModel] against the live plugin's current source. */
class SemanticSearchSettingsViewModelFactory(
    private val source: () -> SemanticSearchSource?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SemanticSearchSettingsViewModel::class.java)) {
            return SemanticSearchSettingsViewModel(source) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
