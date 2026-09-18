package com.itsaky.androidide.plugins.aiagentopenai.settings

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aiagentopenai.backend.OpenAiBackend
import com.itsaky.androidide.plugins.aiagentopenai.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentopenai.plugin.OpenAiPlugin
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
interface OpenAiCatalogGateway {

    /**
     * Models available with the settings currently saved on disk. Used to populate the model
     * picker, where "which server" is never in question.
     */
    fun listModelsForSavedSettings(): CatalogResult

    /**
     * Models available at [baseUrl] with [apiKey], neither of which need be — and during setup is
     * not — the saved pair. This is what makes testing a server before persisting it possible.
     *
     * @param apiKey the candidate key, or blank for a server that needs none
     * @param baseUrl the candidate server, already normalized
     */
    fun listModels(apiKey: String, baseUrl: String): CatalogResult
}

/**
 * [OpenAiCatalogGateway] over this plugin's own [OpenAiBackend].
 *
 * A plain call: the backend and the settings that configure it ship in the same `.cgp`, so the
 * types are the same types — no reflection across a classloader boundary.
 *
 * @param backendProvider resolves the backend; injectable so tests need no plugin lifecycle
 */
class BackendOpenAiCatalogGateway(
    private val backendProvider: () -> OpenAiBackend? = OpenAiPlugin::getBackend
) : OpenAiCatalogGateway {

    companion object {
        private const val TAG = "$LOG_PREFIX.OpenAiCatalogGateway"

        /**
         * Failsafe cap, well above the backend's own budget (15 s connect + 15 s read) so a
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
        get() = OpenAiPlugin.getContext()?.logger

    override fun listModelsForSavedSettings(): CatalogResult =
        await { it.listModels() }

    override fun listModels(apiKey: String, baseUrl: String): CatalogResult =
        await { it.listModels(apiKey, baseUrl) }

    /**
     * Runs [request] against the backend and awaits its future.
     *
     * Blocks on [CompletableFuture.get], so call it from an IO dispatcher — never the main thread.
     *
     * @param request the catalog call to make; picks which server and credential are used
     */
    private fun await(
        request: (OpenAiBackend) -> CompletableFuture<List<String>>
    ): CatalogResult {
        val backend = try {
            backendProvider()
        } catch (e: Exception) {
            logger?.error("$TAG: could not resolve the OpenAI backend", e)
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
            logger?.warn("$TAG: listModels was cancelled by the backend", e)
            CatalogResult.Failed(e)
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
