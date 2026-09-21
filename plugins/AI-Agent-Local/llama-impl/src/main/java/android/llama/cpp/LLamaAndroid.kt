package android.llama.cpp

import com.itsaky.androidide.llamacpp.api.ILlamaController
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Prefix on every logger name this module creates. Duplicated from LOG_PREFIX in the plugin's
 * logging/LogTags.kt, which this module cannot import, and kept in step with it by hand: llama-impl
 * only ever ships inside ai-agent-local's AAR, so a name without it points at no plugin.
 */
private const val LOG_PREFIX = "AiAgentLocal"

/**
 * Static library loader - ensures native library is loaded before any static methods are called.
 * This object's init block runs when the object is first accessed.
 */
private object NativeLibraryLoader {
    private val log = LoggerFactory.getLogger("$LOG_PREFIX.NativeLibraryLoader")

    @Volatile
    private var loaded = false

    init {
        ensureLoaded()
    }

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("llama-android")
                loaded = true
                log.info("Successfully loaded llama-android native library")
            } catch (e: UnsatisfiedLinkError) {
                // Check if the error is because library is already loaded by another ClassLoader
                if (e.message?.contains("already opened") == true) {
                    log.warn("Native library already loaded by another ClassLoader - continuing anyway")
                    loaded = true // Mark as loaded to prevent retries
                } else {
                    log.error("Failed to load llama-android native library", e)
                    throw e
                }
            }
        }
    }
}

class LLamaAndroid : ILlamaController {

    private val log = LoggerFactory.getLogger("$LOG_PREFIX.LLamaAndroid")

    init {
        // Ensure native library is loaded when any instance is created
        NativeLibraryLoader.ensureLoaded()
    }

    private external fun model_n_ctx(context: Long): Int

    private external fun tokenize(context: Long, text: String, add_bos: Boolean): IntArray
    suspend fun getContextSize(): Int {
        return withContext(runLoop()) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> model_n_ctx(state.context)
                else -> throw IllegalStateException("Model not loaded")
            }
        }
    }

    override suspend fun clearKvCache() {
        withContext(runLoop()) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> kv_cache_clear(state.context)
                else -> {}
            }
        }
    }

    override suspend fun countTokens(text: String): Int {
        return tokenize(text).size
    }

    suspend fun tokenize(text: String): IntArray {
        return withContext(runLoop()) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> tokenize(state.context, text, true)
                else -> throw IllegalStateException("Model not loaded")
            }
        }
    }

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    private val isStopped = AtomicBoolean(false)

    /**
     * Optional GBNF grammar constraining the next generation(s). When set, [send]
     * builds a fresh grammar-constrained sampler per call (grammar state is
     * per-generation) and frees it afterward. Null → the plain load-time sampler.
     */
    @Volatile
    private var grammar: String? = null

    /** Set (or clear, with null) the GBNF grammar used to constrain generation. */
    fun setGrammar(gbnf: String?) {
        grammar = gbnf?.takeIf { it.isNotBlank() }
    }

    @Volatile
    private var runLoopDispatcher: ExecutorCoroutineDispatcher = createRunLoop()

    @Volatile
    private var runLoopClosed = false

    private fun createRunLoop(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            log.debug("Dedicated thread for native code: {}", Thread.currentThread().name)

            // Library is now loaded in static initializer, so we can call native methods directly
            log_to_android()
            backend_init(false)

            log.debug("System Info: {}", system_info())

            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                log.error("Unhandled exception on Llm-RunLoop thread", exception)
            }
        }
    }.asCoroutineDispatcher()

    /**
     * Returns the live run-loop dispatcher, lazily recreating it if a prior [shutdown] closed it.
     * [instance] is process-global but [shutdown] runs per-plugin dispose(), so without recreation
     * a re-enable would dispatch onto a dead executor and brick inference.
     *
     * @return the active single-thread dispatcher for native calls, created fresh if needed
     */
    @Synchronized
    private fun runLoop(): ExecutorCoroutineDispatcher {
        if (runLoopClosed) {
            log.info("Recreating Llm-RunLoop dispatcher after a prior shutdown")
            runLoopDispatcher = createRunLoop()
            runLoopClosed = false
        }
        return runLoopDispatcher
    }

    private var nlen: Int = 256

    private fun updateMaxTokens(maxTokens: Int) {
        val clamped = maxTokens.coerceIn(64, 1024)
        nlen = clamped
    }

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(
        model: Long,
        nCtx: Int,
        quantizeKv: Boolean,
        fallbackNCtx: Int,
    ): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
    private external fun new_grammar_sampler(model: Long, grammar: String): Long
    private external fun free_sampler(sampler: Long)
    private external fun bench_model(
        context: Long,
        model: Long,
        batch: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int,
        stop: Array<String>
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar
    ): String?

    private external fun kv_cache_clear(context: Long)

    override fun stop() {
        log.info("Stop requested for current generation.")
        isStopped.set(true)
    }

    /**
     * Stops the Llm-RunLoop thread by closing its single-thread executor; call only at plugin
     * disposal, after unload() completes. Not permanent — [instance] is process-global, so the
     * executor is lazily recreated on the next [runLoop] use, letting a re-enable run native work.
     */
    @Synchronized
    fun shutdown() {
        if (runLoopClosed) {
            return
        }
        log.info("Shutting down Llm-RunLoop dispatcher")
        runLoopDispatcher.close()
        runLoopClosed = true
    }

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        return withContext(runLoop()) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    log.debug("bench(): {}", state)
                    bench_model(state.context, state.model, state.batch, pp, tg, pl, nr)
                }

                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    override suspend fun load(pathToModel: String) = load(pathToModel, DEFAULT_N_CTX)

    /**
     * Loads a model and gives its context [nCtx] tokens, stored as q8_0 when [quantizeKv] asks for
     * it. Every part of the shape is an argument rather than process-global state so that none of it
     * can be overwritten between being chosen and being used: the context is created on the run
     * loop, well after the caller picked them.
     *
     * A partial load frees what it allocated before rethrowing: [threadLocalState] stays `Idle`, so
     * nothing else can reach those handles, and a retry would otherwise mmap another model on top
     * of the leaked one for the process lifetime.
     *
     * @param pathToModel filesystem path to the `.gguf` model
     * @param nCtx context size in tokens, sized for [quantizeKv]; non-positive means [DEFAULT_N_CTX]
     * @param quantizeKv true to store the KV cache as q8_0, roughly half the bytes of f16; the
     *   native side may still refuse it, in which case the load falls back to f16 at [fallbackNCtx]
     * @param fallbackNCtx context size for that f16 fallback, which the caller sizes against f16's
     *   own per-token cost; non-positive means [DEFAULT_N_CTX], since [nCtx] would be the wrong
     *   default under [quantizeKv] — an f16 cache at a size q8_0 paid for asks for nearly twice the
     *   bytes the attempt that just failed did
     */
    suspend fun load(
        pathToModel: String,
        nCtx: Int,
        quantizeKv: Boolean = false,
        fallbackNCtx: Int = 0,
    ) {
        withContext(runLoop()) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L) throw IllegalStateException("load_model() failed")

                    var context = 0L
                    var batch = 0L
                    var sampler = 0L
                    try {
                        context = new_context(model, nCtx, quantizeKv, fallbackNCtx)
                        if (context == 0L) throw IllegalStateException("new_context() failed")

                        batch = new_batch(2048, 0, 1)
                        if (batch == 0L) throw IllegalStateException("new_batch() failed")

                        sampler = new_sampler()
                        if (sampler == 0L) throw IllegalStateException("new_sampler() failed")
                    } catch (failure: Throwable) {
                        // Reverse of the allocation order, and the model last: it owns the rest.
                        if (sampler != 0L) free_sampler(sampler)
                        if (batch != 0L) free_batch(batch)
                        if (context != 0L) free_context(context)
                        free_model(model)
                        throw failure
                    }

                    log.info("Loaded model {}", pathToModel)
                    threadLocalState.set(State.Loaded(model, context, batch, sampler))
                }

                else -> throw IllegalStateException("Model already loaded")
            }
        }
    }


    /*

        formatChat: Boolean = false,
        stop: List<String> = emptyList(),
        clearCache: Boolean = false
     */
    override fun send(
        message: String,
        formatChat: Boolean,
        stop: List<String>,
        clearCache: Boolean
    ): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                isStopped.set(false)

                if (clearCache) {
                    kv_cache_clear(state.context)
                }

                // A grammar sampler is stateful, so build a fresh one for this
                // generation and free it when done. Fall back to the plain
                // load-time sampler if there's no grammar or it fails to build.
                val grammarText = grammar
                val grammarSampler = grammarText?.let { new_grammar_sampler(state.model, it) }
                    ?.takeIf { it != 0L }
                if (grammarText != null && grammarSampler == null) {
                    log.warn("Grammar sampler failed to build; falling back to the plain sampler")
                }
                val sampler = grammarSampler ?: state.sampler

                try {
                    val ncur = IntVar(
                        completion_init(
                            state.context,
                            state.batch,
                            message,
                            formatChat,
                            nlen,
                            stop.toTypedArray()
                        )
                    )

                    while (true) {
                        if (isStopped.get()) {
                            log.info("Stopping generation loop because stop flag was set.")
                            break
                        }

                        val str = completion_loop(state.context, state.batch, sampler, nlen, ncur)
                        if (str == null) {
                            break
                        }
                        emit(str)
                    }
                } finally {
                    if (grammarSampler != null) free_sampler(grammarSampler)
                }
            }

            else -> {}
        }
    }.flowOn(runLoop())

    override suspend fun unload() {
        withContext(runLoop()) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    free_context(state.context)
                    free_model(state.model)
                    free_batch(state.batch)
                    free_sampler(state.sampler)

                    threadLocalState.set(State.Idle)
                }

                else -> {}
            }
        }
    }

    companion object {
        private val nativeLog = LoggerFactory.getLogger("$LOG_PREFIX.llama.cpp")

        /** Context a [load] gets when the caller does not pick one; matches DEFAULT_N_CTX natively. */
        const val DEFAULT_N_CTX = 4096

        // External native methods
        @JvmStatic
        private external fun native_configureThreads(nThreads: Int, nThreadsBatch: Int)

        @JvmStatic
        private external fun native_configureSampling(temperature: Float, topP: Float, topK: Int)

        @JvmStatic
        private external fun native_configureKvCacheReuse(enabled: Boolean)

        // Public wrapper methods that ensure library is loaded first
        @JvmStatic
        fun configureThreads(nThreads: Int, nThreadsBatch: Int) {
            NativeLibraryLoader.ensureLoaded()
            native_configureThreads(nThreads, nThreadsBatch)
        }

        @JvmStatic
        fun configureSampling(temperature: Float, topP: Float, topK: Int) {
            NativeLibraryLoader.ensureLoaded()
            native_configureSampling(temperature, topP, topK)
        }

        @JvmStatic
        fun configureKvCacheReuse(enabled: Boolean) {
            NativeLibraryLoader.ensureLoaded()
            native_configureKvCacheReuse(enabled)
        }

        @JvmStatic
        fun configureMaxTokens(maxTokens: Int) {
            _instance.updateMaxTokens(maxTokens)
        }

        @JvmStatic
        fun logFromNative(level: Int, message: String) {
            val cleanMessage = message.trim()
            when (level) {
                2 -> nativeLog.error(cleanMessage) // GGML_LOG_LEVEL_ERROR = 2
                3 -> nativeLog.warn(cleanMessage)  // GGML_LOG_LEVEL_WARN  = 3
                4 -> nativeLog.info(cleanMessage)   // GGML_LOG_LEVEL_INFO  = 4
                else -> nativeLog.debug(cleanMessage)
            }
        }

        private class IntVar(value: Int) {
            @Volatile
            var value: Int = value
                // Removed private set to allow JNI to call getValue()

            fun inc() {
                synchronized(this) {
                    value += 1
                }
            }
        }

        private sealed interface State {
            data object Idle : State
            data class Loaded(
                val model: Long,
                val context: Long,
                val batch: Long,
                val sampler: Long
            ) : State
        }

        private val _instance: LLamaAndroid = LLamaAndroid()

        @JvmStatic
        fun instance(): LLamaAndroid = _instance
    }
}
