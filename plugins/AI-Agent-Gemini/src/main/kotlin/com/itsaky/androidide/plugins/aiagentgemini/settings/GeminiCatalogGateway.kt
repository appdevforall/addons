package com.itsaky.androidide.plugins.aiagentgemini.settings

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aiagentgemini.backend.GeminiBackend
import com.itsaky.androidide.plugins.aiagentgemini.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentgemini.plugin.GeminiPlugin
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The one place this plugin's settings ask for a model catalog.
 *
 * An abstraction the ViewModel can fake in tests, so the blocking wait on the backend's future
 * lives behind a single seam that fails in one recognisable way.
 */
interface GeminiCatalogGateway {

    /**
     * Models available to the key currently saved on disk. Used to populate the model picker,
     * where "which key" is never in question.
     */
    fun listModelsForSavedKey(): CatalogResult

    /**
     * Models available to [apiKey], which need not be — and during key entry is not — the saved
     * one. This is what makes checking a key before persisting it possible.
     */
    fun listModels(apiKey: String): CatalogResult
}

/**
 * [GeminiCatalogGateway] over this plugin's own [GeminiBackend].
 *
 * A plain call: the backend and the settings that configure it now ship in the same `.cgp`, so the
 * types are the same types. This replaces a reflective lookup through AI Core's registry that
 * could only fail at runtime, and only on a device.
 *
 * @param backendProvider resolves the backend; injectable so tests need no plugin lifecycle
 */
class BackendGeminiCatalogGateway(
    private val backendProvider: () -> GeminiBackend? = GeminiPlugin::getBackend
) : GeminiCatalogGateway {

    companion object {
        private const val TAG = "$LOG_PREFIX.GeminiCatalogGateway"

        /**
         * Failsafe cap, well above the backend's own budget (15 s connect + 15 s read, paginated) so a
         * slow-but-live fetch is never truncated. Bounds a future that may never complete, such as
         * one from an already-cancelled backend scope; not the expected wait.
         */
        private const val LIST_MODELS_TIMEOUT_SECONDS = 60L
    }

    /**
     * This plugin's IDE-surfaced log, so a failed catalog lookup shows up in the IDE's own log view
     * rather than only in logcat. Null before `initialize()` and in JVM tests.
     */
    private val logger: PluginLogger?
        get() = GeminiPlugin.getContext()?.logger

    override fun listModelsForSavedKey(): CatalogResult =
        await { it.listModels() }

    override fun listModels(apiKey: String): CatalogResult =
        await { it.listModels(apiKey) }

    /**
     * Runs [request] against the backend and awaits its future.
     *
     * Blocks on [CompletableFuture.get], so call it from an IO dispatcher — never the main thread.
     *
     * @param request the catalog call to make; picks which credential is used
     */
    private fun await(
        request: (GeminiBackend) -> CompletableFuture<List<String>>
    ): CatalogResult {
        val backend = try {
            backendProvider()
        } catch (e: Exception) {
            logger?.error("$TAG: could not resolve the Gemini backend", e)
            return CatalogResult.Failed(e)
        } ?: return CatalogResult.NoBackend

        val future = try {
            request(backend)
        } catch (e: Exception) {
            logger?.error("$TAG: listModels threw", e)
            return CatalogResult.Failed(e)
        }

        return try {
            CatalogResult.Success(future.get(LIST_MODELS_TIMEOUT_SECONDS, TimeUnit.SECONDS).orEmpty())
        } catch (e: ExecutionException) {
            // The API failure the backend reported; its message carries the HTTP status.
            CatalogResult.Failed(e.cause ?: e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutException) {
            future.cancel(true)
            logger?.error(
                "$TAG: listModels did not complete within ${LIST_MODELS_TIMEOUT_SECONDS}s",
                e
            )
            CatalogResult.Failed(e)
        } catch (e: InterruptedException) {
            // Restore the flag so the cancelled coroutine's thread still sees it.
            Thread.currentThread().interrupt()
            future.cancel(true)
            CatalogResult.Failed(e)
        }
    }
}
