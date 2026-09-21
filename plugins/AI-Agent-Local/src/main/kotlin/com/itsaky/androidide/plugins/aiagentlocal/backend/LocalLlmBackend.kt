package com.itsaky.androidide.plugins.aiagentlocal.backend

import android.app.ActivityManager
import android.content.Context
import android.llama.cpp.LLamaAndroid
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.feedback.IncompatibleModelException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.ModelLoadException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.UserActionableLlmException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.UserFeedback
import com.itsaky.androidide.plugins.aiagentlocal.format.ByteSize
import com.itsaky.androidide.plugins.aiagentlocal.model.ContentNativeModelSource
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufHeader
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufHeaderReader
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufModelInspector
import com.itsaky.androidide.plugins.aiagentlocal.model.KvCacheType
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelContextResolver
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelContextSize
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadDiagnostics
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadMessages
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelSourceWatcher
import com.itsaky.androidide.plugins.aiagentlocal.model.NativeModelSource
import com.itsaky.androidide.plugins.aiagentlocal.model.OpenModelFile
import com.itsaky.androidide.plugins.aiagentlocal.model.PlatformModelSourceWatcher
import com.itsaky.androidide.plugins.aiagentlocal.model.SourceReachability
import com.itsaky.androidide.plugins.aiagentlocal.preferences.LocalLlmPreferences
import com.itsaky.androidide.plugins.aiagentlocal.prompt.LocalSystemPrompt
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local LLM backend using llama-impl for on-device inference.
 * Wraps llama-impl APIs and implements LlmBackend interface.
 */
class LocalLlmBackend(
    private val context: PluginContext,
    private val modelSourceOverride: NativeModelSource? = null,
    private val engineOverride: ModelResidencyEngine? = null,
    private val watcherOverride: ModelSourceWatcher? = null,
) : HistoryCapableBackend, CancellableBackend, ConfigurableBackend {

    companion object {
        /**
         * [LlmConfig.extraParams] key for an optional GBNF grammar. The caller
         * owns it (keeping this backend free of any tool vocabulary); absent →
         * unconstrained sampling.
         */
        const val EXTRA_PARAM_GRAMMAR = "grammar"


        /**
         * Belt-and-braces guard: `<|im_end|>` is an EOG control token, so the native loop
         * normally stops on it by itself. This only matters if a model emits it as plain
         * text, in which case the native stop truncates before the match.
         */
        private val CHAT_STOP = listOf("<|im_end|>")

        /**
         * Where models were copied before ADFA-5253. Nothing writes here any more; see
         * [deleteLegacyModelCache], which gives the space back.
         */
        private const val LEGACY_MODEL_CACHE_DIR = "llm-models"

        /** How long an answered probe stands in for the next one; see [probeAnswerIsFresh]. */
        private val REACHABILITY_TRUST_NANOS = TimeUnit.SECONDS.toNanos(5)

        /** How long [close] waits for a generation to give [generationMutex] back. */
        private val UNLOAD_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5)
    }

    private val llamaLazy = lazy { LLamaAndroid.instance() }
    private val llama by llamaLazy
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Owns the teardown coroutine in [close]. Separate from [scope] because that one is cancelled
     * first (to stop in-flight generation) and so could not run the unload itself; a
     * [SupervisorJob] keeps the two teardown steps independent. [close] cancels this scope once
     * the work completes, so nothing outlives the plugin.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Single-flight guard serializing whole generations on the shared native context. */
    private val generationMutex = Mutex()

    @Volatile private var currentStreamingJob: Job? = null
    @Volatile private var currentGenerateJob: Job? = null

    /** Renders load diagnoses as user-facing text; keeps R.string lookups out of this engine. */
    private val loadMessages by lazy { ModelLoadMessages(context.androidContext) }

    @Volatile private var modelLoaded = false

    /**
     * The configured reference — path or `content://` URI — of the resident model.
     *
     * Keyed off the *reference*, never off the resolved native path: a document's procfs path is
     * a different string on every open, so comparing resolved paths would report "not loaded" for
     * a model that is already resident and reload it on every message.
     */
    @Volatile private var currentModelRef: String? = null

    /**
     * Holds the resident model's descriptor open. Closing it invalidates the procfs path the
     * native loader was given, so it lives exactly as long as the loaded model does.
     */
    @Volatile private var openModel: OpenModelFile? = null

    /**
     * Stops the delete watch on the resident model. Follows residency exactly: taken when a model
     * is adopted, closed when it is released.
     */
    @Volatile private var modelWatch: Closeable? = null

    /** Whether a watch-triggered reachability check is already queued; see [onModelSourceGone]. */
    private val sourceCheckInFlight = AtomicBoolean(false)

    /**
     * When the resident model's source last answered a probe at all, as a [System.nanoTime]
     * reading. The probe is a synchronous binder call in front of every generation, holding
     * [generationMutex] — and a provider that hangs rather than dies cannot be cancelled out of.
     */
    @Volatile private var lastProbeAnsweredNanos = 0L

    /**
     * Opens the configured model for the native loader. Lazy so construction touches no Android
     * services, and overridable so the load path can be tested without a device.
     */
    private val modelSource: NativeModelSource by lazy {
        modelSourceOverride ?: ContentNativeModelSource(context.androidContext) { message, error ->
            context.logger.error("LocalLlmBackend: $message", error)
        }
    }

    /**
     * Drives model residency. Defaults to the shared native engine; overridable so the residency
     * rules — evicting a model whose file went away, and releasing its descriptor — can be tested
     * without loading real weights.
     */
    private val engine: ModelResidencyEngine = engineOverride ?: object : ModelResidencyEngine {
        override suspend fun load(
            nativePath: String,
            contextTokens: Int,
            quantizeKv: Boolean,
            fallbackContextTokens: Int,
        ) = llama.load(
            pathToModel = nativePath,
            nCtx = contextTokens,
            quantizeKv = quantizeKv,
            fallbackNCtx = fallbackContextTokens,
        )
        override suspend fun unload() = llama.unload()
        override suspend fun contextSize() = llama.getContextSize()
    }

    /**
     * Reports the deletion of the resident model's file, so its gigabytes come back when the user
     * deletes it rather than at their next message. Lazy for the same reason as [modelSource].
     */
    private val watcher: ModelSourceWatcher by lazy {
        watcherOverride ?: PlatformModelSourceWatcher(context.androidContext) { message, error ->
            context.logger.warn("LocalLlmBackend: $message", error)
        }
    }

    /**
     * The reference the background warm-up has already been launched for. Keyed on the reference
     * rather than a flag: a failed warm-up leaves the same path configured, so re-arming it would
     * launch one doomed load per [isAvailable] call, which the chat screen makes on open.
     */
    private val warmedUpRef = AtomicReference<String?>(null)

    init {
        scope.launch { deleteLegacyModelCache() }
    }

    override fun getId(): String = "local"

    override fun getName(): String = "Local LLM"

    /**
     * Whether the user's current selection names this backend.
     *
     * Asked of the router, which owns the selection — this backend's own preferences say nothing
     * about which backend is active, and reading the router's would be the cross-plugin coupling
     * this store exists to remove.
     *
     * No selection, or no router yet, counts as selected: that was the default before a selection
     * was ever stored, and preparing a model the user goes on to pick costs only time.
     */
    private fun isSelectedBackend(): Boolean {
        val selected = try {
            SharedServices.get(LlmInferenceService::class.java)?.preferredBackendId
        } catch (e: Exception) {
            context.logger.warn("LocalLlmBackend: could not read the selected backend", e)
            null
        }
        return selected.isNullOrBlank() || selected == getId()
    }

    /**
     * Written for small on-device models; see [LocalSystemPrompt] for why the wording belongs here
     * rather than with the caller.
     *
     * Null when the user turned the short prompt off, which is what hands them back the caller's
     * own full tool-calling prompt — a larger model can follow it, and this backend can run one.
     */
    override fun getSystemPrompt(request: SystemPromptRequest): String? =
        if (LocalLlmPreferences.useSimplePrompt(context)) LocalSystemPrompt.build(request) else null

    /**
     * Near-greedy: tool arguments must be copied out of earlier tool output verbatim, and a small
     * model given room to sample invents paths instead.
     */
    override fun getDefaultTemperature(): Float = 0.15f

    /**
     * This backend draws its own settings, so the consumer needs no knowledge of `.gguf` files,
     * engine state or memory headroom.
     */
    override fun getSettingsFragmentClassName(): String =
        "com.itsaky.androidide.plugins.aiagentlocal.settings.LocalLlmSettingsFragment"

    override fun isAvailable(): Boolean {
        // Check if model is actually configured
        val prefs = LocalLlmPreferences.of(context)

        val configuredPath = prefs.getString(LocalLlmPreferences.KEY_MODEL_PATH, null)

        context.logger.debug("LocalLlmBackend.isAvailable() - configured path: $configuredPath, modelLoaded: $modelLoaded")

        // Chat-open hits this; start loading now so the first message isn't gated on a cold load.
        // Only ever the first ask for a given selection — a restored file is loaded by the send.
        maybeWarmUp(configuredPath)

        // Unreachability is left to ensureModelLoaded: a memo here goes stale the moment the user
        // restores the file, refusing their first message, and that path advises them properly.
        return modelLoaded || !configuredPath.isNullOrBlank()
    }

    /**
     * Preloads the configured model in the background, once per selection, so the first generation
     * doesn't pay the cold-load cost. No-op unless this backend is the selected one, and never
     * retried for a reference that failed — the generation path loads and diagnoses that one.
     *
     * @param configuredPath the configured model path/URI, or null/blank if unset.
     */
    private fun maybeWarmUp(configuredPath: String?) {
        if (configuredPath.isNullOrBlank() || modelLoaded) return
        if (!isSelectedBackend()) return
        // A different selection re-arms it; the same one, failed or not, does not.
        if (warmedUpRef.getAndSet(configuredPath) == configuredPath) return

        scope.launch {
            try {
                // Serialize with real generations so a mid-warm-up send just waits for this load.
                generationMutex.withLock { ensureModelLoaded(configuredPath) }
                context.logger.info("Local model warm-up complete")
            } catch (e: Exception) {
                // Stay silent and do not re-arm: the real send surfaces config errors.
                context.logger.warn("Local model warm-up failed: ${e.message}")
            }
        }
    }

    /**
     * Loads [modelRef] unless it is already resident, diagnosing any failure into a
     * [ModelLoadException]. Cancellation is rethrown first because [CancellationException] extends
     * [IllegalStateException] and would otherwise be diagnosed as a corrupt model.
     *
     * The model is opened in place — the document the user picked, through the persisted read
     * grant — and the native loader is handed the procfs path of that descriptor. Nothing is
     * copied. IMPORTANT: this loads *exactly* the file the user selected. It must never fall back
     * to "some other .gguf on disk" — doing so silently loads the wrong model (e.g. an embedding
     * model), which aborts native inference and takes the IDE down. See ADFA-4388.
     *
     * Visible to the module so the failure paths that never reach native code — an unreachable
     * model, an embedding model, and the descriptor release that follows both — can be tested off
     * a device.
     *
     * @param modelRef the configured model path or content URI
     */
    internal suspend fun ensureModelLoaded(modelRef: String) {
        if (modelLoaded && currentModelRef == modelRef) {
            // Residency is not evidence the file still exists. The descriptor this backend holds
            // keeps a deleted inode alive, so an unchecked early return keeps answering from a
            // model the user threw away — and keeps its gigabytes mapped. Confirm, then serve.
            if (!probeAnswerIsFresh()) {
                val reachability = modelSource.reachabilityOf(modelRef)
                if (reachability == SourceReachability.GONE) {
                    context.logger.info("Resident model is no longer reachable; unloading: $modelRef")
                    evictResidentModel()
                    throw unopenable(modelRef)
                }
                // Anything but GONE is served: a silent provider is no reason to pay a GB reload —
                // and it answered, so the window arms on it too. A provider wedged inside the open
                // only ever yields UNKNOWN, which armed nothing and cost every message a probe.
                lastProbeAnsweredNanos = System.nanoTime()
            }
            // Here too, not only on the load below: re-picking a model to recover from a refused
            // one finds it resident, so this is the only place the refused model's grant is ever
            // given back — without it the list grows one entry per refusal until the next start.
            releaseSupersededGrants(modelRef)
            return
        }

        val opened = modelSource.open(modelRef) ?: throw unopenable(modelRef)

        // Every failure below leaves this handle unadopted; without the finally it would leak a
        // file descriptor per failed attempt, and warm-up retries make that a loop.
        var adopted = false
        try {
            // Before the first read: the openStream calls below would each eat bytes off a pipe
            // llama.cpp never gets to read, leaving a fine model diagnosed as corrupt (ADFA-5253).
            if (!opened.isSeekable) {
                context.logger.warn("The selected model is not a local file: $modelRef")
                val diagnosis = ModelLoadDiagnostics.Diagnosis.SourceNotSeekable
                throw ModelLoadException(loadMessages.describe(diagnosis), diagnosis)
            }

            // Or it arrives as the loader's null handle: "pick it again" for a file that is there.
            if (!withContext(Dispatchers.IO) { opened.isReopenable() }) {
                context.logger.warn("The selected model cannot be re-opened by path: $modelRef")
                val diagnosis = ModelLoadDiagnostics.Diagnosis.SourceNotReopenable
                throw ModelLoadException(loadMessages.describe(diagnosis), diagnosis)
            }

            // One parse of the metadata block per load, feeding both the guard below and the
            // context sizing after the unload: it sits at the front of a multi-GB file, and a
            // model switch used to walk it twice.
            val header = withContext(Dispatchers.IO) { GgufHeaderReader.read(opened::openStream) }
            // The handle's own size, not File.length(): the native path is a procfs entry, on
            // which length() reports 0 and would price the KV cache off a zero-byte model.
            val modelSizeBytes = opened.sizeBytes.takeIf { it > 0L }

            // Guard the chat path against encoder-only embedding models. Running causal generation
            // on one aborts natively (SIGABRT) and takes the IDE down. Classify BEFORE unloading any
            // working chat model, so a wrong selection never tears down a good one. See ADFA-4388.
            // The overload rescans for the architecture alone, and only if the parse above gave up.
            val kind = withContext(Dispatchers.IO) {
                GgufModelInspector.classify(header, opened::openStream)
            }
            // UNKNOWN means the header could not be read, so the guard let this model through
            // unchecked. Logged so a future embedding-model abort can be told apart from one that
            // got past a header the inspector did read.
            context.logger.debug("Model architecture: ${kind.architecture ?: "unreadable"} (${kind.kind})")
            if (kind.isEmbeddingOnly) {
                throw IncompatibleModelException(
                    "The selected model is an embedding model and can't be used for chat. " +
                        "Choose a chat model in AI Settings."
                )
            }

            // Unload old model if loaded
            if (modelLoaded) {
                context.logger.info("Unloading previous model: $currentModelRef")
                evictResidentModel()
            }

            // Measured after the unload: availMem excludes the context and batch it just released.
            val availableBytes = availableMemoryBytes()
            ModelLoadDiagnostics.refuseBeforeLoad(availableBytes)?.let { shortfall ->
                throw ModelLoadException(loadMessages.describe(shortfall), shortfall)
            }

            val contextSize = resolveContextSize(modelRef, availableBytes, header, modelSizeBytes)

            context.logger.info("Loading model: $modelRef via ${opened.nativePath}")
            try {
                engine.load(
                    nativePath = opened.nativePath,
                    contextTokens = contextSize.contextTokens,
                    quantizeKv = contextSize.kvType == KvCacheType.Q8_0,
                    fallbackContextTokens = contextSize.fallbackContextTokens,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is UserActionableLlmException) throw e
                // Native load_model() signals failure only with a null handle, so diagnose the likely cause.
                context.logger.error("Native model load failed for $modelRef", e)
                val diagnosis = ModelLoadDiagnostics.diagnose(
                    sizeBytes = opened.sizeBytes,
                    availableMemoryBytes = availableMemoryBytes(),
                    nativeError = e.message,
                    openStream = opened::openStream,
                )
                throw ModelLoadException(loadMessages.describe(diagnosis), diagnosis)
            }
            modelLoaded = true
            currentModelRef = modelRef
            openModel = opened
            adopted = true
            releaseSupersededGrants(modelRef)
            startWatching(modelRef)
            context.logger.info("Model loaded successfully")
            reportEffectiveContextSize(contextSize.contextTokens)
        } finally {
            if (!adopted) opened.close()
        }
    }

    /**
     * Gives back the read grants of the models this selection replaced, now that one has actually
     * loaded. Deferred to here on purpose: the checks that reject a model — `isSeekable`,
     * `isReopenable`, the embedding-model guard — all run above, and nothing is copied any more,
     * so releasing at selection time would have cost the user the model they were running for a
     * pick this method never gets to (ADFA-5253). The settings pane writes the list; see
     * `LocalLlmSettingsViewModel.supersede`.
     *
     * Written back before the releases, so a provider that throws cannot leave the list to be
     * retried on every later load — and written back rather than cleared whole: [loadedRef] can be
     * on the list itself (a generation that read the old path queues behind a selection of a new
     * one), and dropping it there would hold its grant with nothing left recording it.
     *
     * @param loadedRef the model just adopted; never released, however it got onto the list
     */
    private fun releaseSupersededGrants(loadedRef: String) {
        try {
            val prefs = LocalLlmPreferences.of(context)
            val superseded = LocalLlmPreferences.supersededModels(prefs)
            if (superseded.isEmpty()) return
            val release = superseded - loadedRef
            LocalLlmPreferences.setSupersededModels(prefs, superseded - release)
            for (reference in release) {
                context.logger.debug("Releasing the read grant of a replaced model: $reference")
                modelSource.releaseAccess(reference)
            }
        } catch (e: Exception) {
            // Grant bookkeeping, not the load: a model that is resident stays resident.
            context.logger.warn("Could not release the replaced models' read grants", e)
        }
    }

    /**
     * Logs the context the native side actually created. It can be smaller than what was asked for
     * — clamped to the trained context, or dropped to the shorter f16 fallback when a quantized
     * cache was refused — and a prompt rejected as too long otherwise looks like it fit.
     *
     * @param requestedTokens the context [resolveContextSize] asked for
     */
    private suspend fun reportEffectiveContextSize(requestedTokens: Int) {
        val actual = try {
            engine.contextSize()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.logger.warn("Could not read the created context size: ${e.message}")
            return
        }
        if (actual == requestedTokens) {
            context.logger.info("Context size in effect: $actual tokens")
        } else {
            context.logger.warn(
                "Context size in effect: $actual tokens, not the $requestedTokens requested;" +
                    " prompt-length limits follow the smaller number"
            )
        }
    }

    /**
     * Sizes the KV cache for this model on this device and picks the type it is stored as. Must run
     * after any unload, so the freed context is counted as available. Answers rather than applies:
     * every part of the shape is an argument to [ModelResidencyEngine.load], so nothing can drift
     * between being chosen here and being used natively. [ModelContextResolver] fails open, so this has no
     * failure of its own.
     *
     * @param modelRef the configured model path or content URI, for the log line only
     * @param availableBytes free RAM as [availableMemoryBytes] reports it, negative if unknown
     * @param header the model's metadata as read once by [ensureModelLoaded], null if unreadable
     * @param modelSizeBytes the model's size, null if unreadable
     * @return the context size, cache type and f16 fallback size to load the model with
     */
    private fun resolveContextSize(
        modelRef: String,
        availableBytes: Long,
        header: GgufHeader?,
        modelSizeBytes: Long?,
    ): ModelContextSize {
        val resolved = ModelContextResolver.resolve(
            header = header,
            availableBytes = availableBytes.takeIf { it >= 0L },
            modelSizeBytes = modelSizeBytes,
        )
        // Unconditional: a wrongly sized context otherwise just reads as the assistant forgetting.
        context.logger.info(
            "Context size for $modelRef: ${resolved.contextTokens} tokens," +
                " ${resolved.kvType} KV cache" +
                " (model advertises ${resolved.advertisedTokens ?: "unknown"}," +
                " ${if (availableBytes >= 0L) "$availableBytes bytes free" else "free RAM unknown"})"
        )
        return resolved
    }

    /**
     * Whether the source answered a probe — with anything but GONE — recently enough to be taken
     * at its word again, which bounds a wedged provider to one blocked message rather than the
     * session. Only a probe arms it, so a model deleted before its first message is still caught.
     */
    private fun probeAnswerIsFresh(): Boolean {
        val since = System.nanoTime() - lastProbeAnsweredNanos
        return lastProbeAnsweredNanos != 0L && since in 0..REACHABILITY_TRUST_NANOS
    }

    /**
     * Forgets the resident model and releases its descriptor. The native unload is the caller's to
     * do first — the mapped pages must be freed before the descriptor behind them goes.
     */
    private fun releaseCurrentModel() {
        stopWatching()
        modelLoaded = false
        currentModelRef = null
        openModel?.close()
        openModel = null
        lastProbeAnsweredNanos = 0L
    }

    /**
     * Gives a resident model back in full — native pages first, then the descriptor holding the
     * inode alive. That order is the whole point: closing the descriptor while the loader still
     * has its procfs path mapped leaves it reading an entry whose target is gone.
     *
     * Callers must hold [generationMutex], so a model is never pulled out from under a generation.
     */
    private suspend fun evictResidentModel() {
        engine.unload()
        releaseCurrentModel()
    }

    /**
     * Builds the failure to report for a model that could not be opened at all.
     *
     * @return the exception to throw; never thrown here, so the caller's control flow stays visible
     */
    private fun unopenable(modelRef: String): ModelLoadException {
        val diagnosis = ModelLoadDiagnostics.diagnoseUnopenable(modelRef)
        return ModelLoadException(loadMessages.describe(diagnosis), diagnosis)
    }

    /**
     * Watches the newly resident model's file, so a deletion frees it right away instead of at the
     * next message. Best effort — an unwatchable source just leaves the check in
     * [ensureModelLoaded] to catch it.
     */
    private fun startWatching(modelRef: String) {
        modelWatch = try {
            watcher.watch(modelRef) { onModelSourceGone(modelRef) }
        } catch (e: Exception) {
            context.logger.warn("Could not watch the selected model: ${e.message}")
            null
        }
    }

    private fun stopWatching() {
        try {
            modelWatch?.close()
        } catch (e: Exception) {
            context.logger.warn("Could not stop watching the selected model: ${e.message}")
        }
        modelWatch = null
    }

    /**
     * A watch fired for [modelRef]. Notifications are hints, not verdicts — providers notify for
     * edits as well as deletions, and for a whole document tree — so reachability is confirmed
     * before anything is torn down.
     *
     * Runs under [generationMutex] on [cleanupScope]: a generation already in flight finishes on
     * the model it started with, and this survives the cancellation of [scope].
     *
     * Coalesced through [sourceCheckInFlight]: a chatty provider would otherwise queue one
     * coroutine and one binder probe per notification behind [generationMutex].
     */
    private fun onModelSourceGone(modelRef: String) {
        if (!sourceCheckInFlight.compareAndSet(false, true)) return
        cleanupScope.launch {
            try {
                generationMutex.withLock {
                    if (!modelLoaded || currentModelRef != modelRef) return@withLock
                    // Only what the provider itself called gone may cost a model its pages.
                    if (modelSource.reachabilityOf(modelRef) != SourceReachability.GONE) return@withLock
                    context.logger.info("Selected model was deleted; releasing it: $modelRef")
                    evictResidentModel()
                }
            } finally {
                sourceCheckInFlight.set(false)
            }
        }
    }

    /**
     * Deletes the private model copies made before ADFA-5253, which run to gigabytes. The model is
     * now read in place through its own grant, so nothing recreates this directory; once it is gone
     * this is a single `exists()` call, which is cheaper than storing an "already done" flag.
     *
     * Walks and deletes gigabytes, so it pins its own dispatcher rather than inheriting whichever
     * one a caller happens to launch it on.
     *
     * Visible to the module so a test can run it deterministically rather than racing [init].
     */
    internal suspend fun deleteLegacyModelCache() = withContext(Dispatchers.IO) {
        try {
            val legacy = File(context.androidContext.filesDir, LEGACY_MODEL_CACHE_DIR)
            if (!legacy.exists()) return@withContext
            val freedBytes = legacy.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (legacy.deleteRecursively()) {
                context.logger.info("Reclaimed ${ByteSize.format(freedBytes)} of copied model files")
            } else {
                context.logger.warn("Could not fully delete the old model cache at ${legacy.absolutePath}")
            }
        } catch (e: Exception) {
            context.logger.warn("Could not delete the old model cache: ${e.message}")
        }
    }

    /**
     * @return free RAM the OS reports, or -1 if unreadable (diagnosis then skips the low-memory case)
     */
    private fun availableMemoryBytes(): Long = try {
        val am = context.androidContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem
    } catch (e: Exception) {
        context.logger.warn("Could not read available memory: ${e.message}")
        -1L
    }

    /**
     * Wraps a turn in ChatML — the prompt format Qwen and most other on-device instruct GGUFs
     * were fine-tuned on.
     *
     * IMPORTANT: this must stay in sync with `formatChat = true` at the [LLamaAndroid.send] call
     * sites, which is what makes the native tokenizer parse `<|im_start|>`/`<|im_end|>` as control
     * tokens rather than literal text. Handed the bare `User:`/`Assistant:` scaffold this replaced,
     * a strictly-templated instruct model answers with `<|im_end|>` as its *first* token: the
     * native loop sees EOG immediately, generation ends before it starts, and the caller gets a
     * successful-but-empty response with no error to explain it.
     *
     * @param systemPrompt the system message, or null to omit the system turn
     * @param prompt the user message
     * @param history earlier turns, rendered as their own ChatML turns before [prompt]
     * @return the full ChatML prompt, ending in an open assistant turn for the model to continue
     */
    private fun buildPrompt(
        systemPrompt: String?,
        prompt: String,
        history: List<ChatMessage> = emptyList()
    ): String = buildString {
        if (systemPrompt != null) {
            append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
        }
        for (msg in history) {
            val role = when (msg.role) {
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
                ChatMessage.Role.SYSTEM -> "system"
                // No native function calling here, so a tool result rides in as a user turn.
                ChatMessage.Role.TOOL -> "user"
            }
            append("<|im_start|>").append(role).append("\n").append(msg.content).append("<|im_end|>\n")
        }
        append("<|im_start|>user\n").append(prompt).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generate() called with prompt: ${prompt.take(50)}...")
        return runGeneration(buildPrompt(config.systemPrompt, prompt), config)
    }

    /**
     * Runs a non-streaming generation over an already-formatted prompt.
     *
     * Takes the finished prompt rather than a bare message so [generate] and
     * [generateWithHistory] share one path and the system turn is emitted exactly once.
     *
     * @param fullPrompt the complete prompt, as built by [buildPrompt]
     * @param config sampling settings for this request
     * @return a future completed with the response, or with a failure result on error
     */
    private fun runGeneration(fullPrompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        // Check if model is configured
        val prefs = LocalLlmPreferences.of(context)

        val configuredPath = prefs.getString(LocalLlmPreferences.KEY_MODEL_PATH, null)
        context.logger.info("LocalLlmBackend: configured model path = $configuredPath")

        if (configuredPath.isNullOrBlank()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("No model configured. Please go to Settings and select a .gguf model file.")
            )
        }

        val future = CompletableFuture<LlmResponse>()

        currentGenerateJob = scope.launch {
            try {
                // Serialize against other generations on the shared native context.
                generationMutex.withLock {
                    // Configure sampling (use defaults for topP and topK)
                    LLamaAndroid.configureSampling(
                        config.temperature,
                        0.9f,  // topP default
                        40     // topK default
                    )
                    LLamaAndroid.configureMaxTokens(config.maxTokens)

                    // Ensure model is loaded
                    ensureModelLoaded(configuredPath)

                    val startTime = System.currentTimeMillis()

                    // Collect all tokens
                    val responseBuilder = StringBuilder()
                    var tokenCount = 0

                    // Unconstrained path: clear any grammar left by a concurrent
                    // streaming call so completions never inherit a tool-call grammar.
                    llama.setGrammar(null)

                    llama.send(
                        message = fullPrompt,
                        formatChat = true,
                        stop = CHAT_STOP,
                        clearCache = false
                    ).collect { token ->
                        // Honor cancellation so Stop frees the run loop early.
                        ensureActive()
                        responseBuilder.append(token)
                        tokenCount++
                    }

                    val responseText = responseBuilder.toString()
                    context.logger.info("Generated response: ${responseText.take(50)}... ($tokenCount tokens)")

                    future.complete(LlmResponse.success(responseText, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (ce: CancellationException) {
                context.logger.info("Generation cancelled")
                future.completeExceptionally(ce)
                throw ce
            } catch (e: Exception) {
                context.logger.error("Error during generation", e)
                if (e is UserActionableLlmException) {
                    UserFeedback.notify(context.androidContext, e.message ?: "Local LLM is not configured.")
                }
                future.complete(LlmResponse.failure("Error: ${e.message}"))
            }
        }

        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        context.logger.info("LocalLlmBackend.generateStreaming() called")
        streamGeneration(buildPrompt(config.systemPrompt, prompt), config, callback)
    }

    /**
     * Streams a reply for a multi-turn conversation, rendering each earlier turn as its own
     * ChatML turn.
     *
     * @param history earlier turns, oldest first, excluding [prompt]
     * @param prompt the current user turn
     * @param config sampling settings; [LlmConfig.systemPrompt] becomes the leading system turn
     * @param callback receives tokens, completion, and errors
     */
    override fun generateStreamingWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        context.logger.info("LocalLlmBackend.generateStreamingWithHistory() called with ${history.size} messages")
        streamGeneration(buildPrompt(config.systemPrompt, prompt, history), config, callback)
    }

    /**
     * Streams a generation over an already-formatted prompt, serialized against every other
     * generation on the shared native context.
     *
     * @param fullPrompt the complete prompt, as built by [buildPrompt]
     * @param config sampling settings for this request
     * @param callback receives tokens, completion, and errors
     */
    private fun streamGeneration(fullPrompt: String, config: LlmConfig, callback: StreamCallback) {
        // Check if model is configured
        val prefs = LocalLlmPreferences.of(context)

        val configuredPath = prefs.getString(LocalLlmPreferences.KEY_MODEL_PATH, null)

        if (configuredPath.isNullOrBlank()) {
            callback.onError("No model configured. Please go to Settings and select a .gguf model file.")
            return
        }

        currentStreamingJob = scope.launch {
            try {
                // Hold the single-flight lock for the whole streaming generation.
                generationMutex.withLock {
                    try {
                        // Configure sampling (use defaults for topP and topK)
                        LLamaAndroid.configureSampling(
                            config.temperature,
                            0.9f,  // topP default
                            40     // topK default
                        )
                        LLamaAndroid.configureMaxTokens(config.maxTokens)

                        // Ensure model is loaded
                        ensureModelLoaded(configuredPath)

                        val startTime = System.currentTimeMillis()
                        var tokenCount = 0
                        val responseBuilder = StringBuilder()

                        // Apply the caller's grammar for this send() only; reset in the finally.
                        val grammar = config.extraParams?.get(EXTRA_PARAM_GRAMMAR) as? String
                        llama.setGrammar(grammar?.takeIf { it.isNotBlank() })

                        llama.send(
                            message = fullPrompt,
                            formatChat = true,
                            stop = CHAT_STOP,
                            // Keep the KV cache so the native layer reuses the common prefix (system prompt).
                            clearCache = false
                        ).collect { token ->
                            ensureActive()
                            callback.onToken(token)
                            responseBuilder.append(token)
                            tokenCount++
                        }

                        callback.onComplete(LlmResponse.success(responseBuilder.toString(), tokenCount, System.currentTimeMillis() - startTime))
                    } finally {
                        llama.setGrammar(null)
                    }
                }
            } catch (ce: CancellationException) {
                context.logger.info("Streaming generation cancelled")
                throw ce
            } catch (e: Exception) {
                context.logger.error("Error during streaming generation", e)
                if (e is UserActionableLlmException) {
                    UserFeedback.notify(context.androidContext, e.message ?: "Local LLM is not configured.")
                }
                callback.onError("Error: ${e.message}")
            }
        }
    }

    /**
     * Cancels any in-flight streaming or non-streaming generation (user pressed Stop),
     * cancelling the coroutine Job so the single-threaded run loop is freed early.
     */
    override fun cancelStreaming() {
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        currentGenerateJob?.cancel()
        currentGenerateJob = null
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generateWithHistory() called with ${history.size} messages")
        return runGeneration(buildPrompt(config.systemPrompt, prompt, history), config)
    }

    /**
     * Suspending model unload — safe to call from any coroutine. Takes [generationMutex] because
     * [evictResidentModel] requires it: a watch notification that arrives just before [close] runs
     * its own eviction on [cleanupScope], and two of them would unload the native model twice.
     *
     * Callers on the teardown path must bound the wait; see [close].
     */
    private suspend fun unloadModelInternal() = generationMutex.withLock {
        if (modelLoaded) {
            evictResidentModel()
            context.logger.info("Model unloaded")
        }
    }

    /**
     * Release all resources. Called from [LocalLlmPlugin.dispose], which may run on
     * the main thread; llama.unload() drains a single-threaded native run loop and
     * can block while inference is in flight, so it must never run via runBlocking
     * on Main. Cancel generation, then unload on a background thread, then stop
     * the Llm-RunLoop thread so it doesn't outlive the plugin.
     *
     * The teardown runs in [cleanupScope] rather than a floating `CoroutineScope(...)`: the scope
     * is owned by this object and cancelled as soon as the work finishes, so there is no orphan
     * job left behind. It cannot be joined — dispose() may be on the main thread and unload()
     * blocks on the native run loop — so deterministic teardown is the strongest guarantee here.
     *
     * The unload is bounded: it waits on [generationMutex], which a generation can be holding
     * inside the uncancellable binder probe in [ensureModelLoaded], and a wedged `DocumentsProvider`
     * would otherwise park this coroutine for good — taking [LLamaAndroid.shutdown] with it and
     * leaking the native context and the run-loop thread for the life of the IDE process.
     */
    fun close() {
        scope.cancel()
        val cleanup = cleanupScope.launch {
            // Ahead of the native check: a watch outliving the plugin would fire into a dead scope.
            stopWatching()
            if (!llamaLazy.isInitialized()) {
                return@launch
            }
            try {
                if (withTimeoutOrNull(UNLOAD_TIMEOUT_MS) { unloadModelInternal() } == null) {
                    context.logger.warn("Timed out unloading the model during close()")
                }
            } catch (t: Throwable) {
                context.logger.error("Error unloading model during close()", t)
            }
            // Reached whether or not the unload did: shutdown() is what stops the run-loop thread.
            try {
                llama.shutdown()
            } catch (t: Throwable) {
                context.logger.error("Error shutting down Llm-RunLoop during close()", t)
            }
        }
        cleanup.invokeOnCompletion { cleanupScope.cancel() }
    }
}
