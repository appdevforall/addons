package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ai.LlmBackendRegistration
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginSettingsEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ProjectSearchExtension
import com.itsaky.androidide.plugins.extensions.ProjectSearchRequest
import com.itsaky.androidide.plugins.extensions.ProjectSearchResult
import com.itsaky.androidide.plugins.extensions.ProjectSearchSection
import com.itsaky.androidide.plugins.extensions.SettingsExtension
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.vectorsearch.settings.SemanticSearchSettingsFragment
import com.itsaky.androidide.plugins.vectorsearch.settings.SemanticSearchSource
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Names this class in every line it logs.
 *
 * The host already prefixes each line with `[pluginId]`, which says which `.cgp` wrote it but not
 * which of its classes.
 */
private const val TAG = "VectorSearchPlugin"

/**
 * How long one search may spend waiting, across every wait it makes.
 *
 * Under the host's own 10-second budget for a project search, and shared between embedding the
 * query and awaiting an in-flight build: either can hang on a slow server, so a ceiling on one of
 * them alone is not a ceiling. A first query on a large project answers from the partial index it
 * has rather than being cut off mid-wait with nothing to show.
 */
private const val SEARCH_BUDGET_MS = 8_000L

/**
 * Vector Search Plugin provides semantic code search capabilities.
 *
 * When activated, it indexes the project with the embedding model of whichever AI backend the user
 * selected in AI settings, and stores the vectors in a local SQLite database. Every stored vector
 * records the embedder that produced it, and a search only ever ranks vectors from the embedder it
 * is querying with: vectors from two models are not comparable, and comparing them anyway returns
 * plausible nonsense rather than an error.
 *
 * When no embedder is available the plugin contributes no results at all. It deliberately has no
 * lexical fallback — one used to fill the index with hashed token bags that were
 * indistinguishable from real vectors once stored, which made the feature look like it worked.
 */
class VectorSearchPlugin :
    IPlugin, ProjectSearchExtension, DocumentationExtension, SettingsExtension {

    private lateinit var context: PluginContext
    private lateinit var indexingService: EmbeddingIndexingService
    private lateinit var indexing: IndexCoordinator

    // Watches the selected backend for the settings screen; live from activate to deactivate.
    private var backendWatch: BackendWatch? = null

    // What the settings screen reads, rebuilt with [indexing] on each activate.
    @Volatile private var semanticSearch: SemanticSearchSource? = null

    /**
     * This plugin's IDE-surfaced log, so indexing diagnostics reach the IDE's own log view rather
     * than only logcat. Null before [initialize], the state [dispose] runs in after a failed load.
     */
    private val log: PluginLogger?
        get() = if (::context.isInitialized) context.logger else null

    fun logger(): PluginLogger? = log

    /** The settings screen's view of this activation's index, or null before [activate]. */
    fun semanticSearchSource(): SemanticSearchSource? = semanticSearch

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        instance = this
        log?.info("$TAG: initialized")
        return true
    }

    override fun activate(): Boolean {
        log?.info("$TAG: activating...")

        if (::indexing.isInitialized) indexing.close()
        if (::indexingService.isInitialized) indexingService.close()
        indexingService = EmbeddingIndexingService(context.androidContext, context.logger)
        val indexChanges = MutableStateFlow(0L)
        indexing = IndexCoordinator(
            store = indexingService,
            buildLog = PreferencesIndexBuildLog {
                context.getPluginSharedPreferences(PreferencesIndexBuildLog.FILE)
            },
            logger = { log },
            onIndexChanged = { indexChanges.update { it + 1 } },
        )

        backendWatch?.stop()
        val backendChanges = MutableStateFlow(0L)
        backendWatch = BackendWatch(context, ::inferenceService) {
            backendChanges.update { it + 1 }
        }.also { it.start() }

        semanticSearch = SemanticSearchSourceImpl(
            context = context,
            store = indexingService,
            indexing = indexing,
            inference = ::inferenceService,
            backendChanges = backendChanges.asStateFlow(),
            indexChanges = indexChanges.asStateFlow(),
        )

        log?.info("$TAG: activated. The first search builds the index.")
        return true
    }

    override fun deactivate(): Boolean {
        log?.info("$TAG: deactivating")
        backendWatch?.stop()
        backendWatch = null
        return true
    }

    override fun dispose() {
        backendWatch?.stop()
        backendWatch = null
        if (instance === this) instance = null
        semanticSearch = null
        if (::indexing.isInitialized) indexing.close()
        // Release the SQLite connection so it doesn't leak when the plugin unloads.
        if (::indexingService.isInitialized) {
            indexingService.close()
        }
        log?.info("$TAG: disposed")
    }

    /**
     * Gets the list of code files to index in the current project.
     */
    fun getFiles(): List<File> {
        val projectDir = File(
            System.getProperty("project.dir") ?: System.getProperty("user.dir") ?: return emptyList()
        )
        return indexingService.collectFiles(projectDir)
    }

    override fun searchProject(request: ProjectSearchRequest): CompletableFuture<List<ProjectSearchSection>> {
        log?.info("$TAG: project search requested for '${request.query}'")
        return CompletableFuture.supplyAsync {
            val outcome = runBlocking {
                searchReporting(query = request.query, roots = request.roots, topK = 10)
            }
            if (outcome.results.isEmpty()) {
                log?.info("$TAG: no semantic results available for '${request.query}'")
                emptyList()
            } else {
                listOf(
                    ProjectSearchSection(
                        title = resultsTitle(outcome.rebuiltWith),
                        results = outcome.results.map { it.toProjectSearchResult(request.query) },
                    )
                )
            }
        }
    }

    /**
     * Searches embeddings semantically, indexing [roots] first if the existing index cannot answer.
     *
     * The backend is never named here: it is whichever one the user selected for chat, resolved the
     * same way a chat turn resolves it. A query that cannot be embedded returns nothing rather than
     * being answered by something else.
     *
     * @param query Search query string
     * @param roots project roots to index; empty searches whatever is already indexed
     * @param topK Maximum number of results to return (default 10)
     * @return List of CodeEmbedding results ranked by relevance, empty when there is no answer
     */
    suspend fun search(
        query: String,
        roots: List<File> = emptyList(),
        topK: Int = 10,
    ): List<CodeEmbedding> = searchReporting(query, roots, topK).results

    /**
     * One search's answer, and whether answering it replaced the index's embedding model.
     *
     * @param results the ranked chunks, empty when there is no answer
     * @param rebuiltWith the model this search rebuilt the index with, or null when it reused one
     */
    private data class SearchOutcome(
        val results: List<CodeEmbedding>,
        val rebuiltWith: String? = null,
    )

    /** [search], also reporting a rebuild so the results can say why the first query was slow. */
    private suspend fun searchReporting(
        query: String,
        roots: List<File>,
        topK: Int,
    ): SearchOutcome {
        var rebuiltWith: String? = null
        val ranked = try {
            val deadline = System.nanoTime() + SEARCH_BUDGET_MS * 1_000_000
            val embedder = EmbedderResolver.resolve(inferenceService())
            if (embedder !is EmbedderResolution.Ready) {
                logUnresolved(embedder)
                return SearchOutcome(emptyList())
            }

            // The query is embedded first because its vector is what reports the width: only a
            // vector the backend actually produced can say what space the index must be built in.
            val queryEmbedding = withTimeoutOrNull(remainingMs(deadline)) {
                embedder.backend.awaitVectors(listOf(query)).firstOrNull()
            }
            if (queryEmbedding == null) {
                log?.warn("$TAG: no query vector within ${SEARCH_BUDGET_MS}ms; no semantic results")
                return SearchOutcome(emptyList())
            }
            val identity = EmbedderIdentity(embedder.key, queryEmbedding.size)

            val rootsKey = if (roots.isEmpty()) null else RootsKey.of(roots)
            if (rootsKey != null) {
                // Wait for an in-flight build so the first query returns real results, not empty.
                val build = indexing.buildIfNeeded(rootsKey, roots, embedder.backend, identity)
                if (build?.replacedModel != null) rebuiltWith = identity.modelId
                val job = build?.job
                if (job != null && withTimeoutOrNull(remainingMs(deadline)) { job.join() } == null) {
                    log?.warn("$TAG: indexing outlasted the search budget; using partial index")
                }
            }

            val comparable = indexingService.getAllEmbeddings(identity, rootsKey)
            if (comparable.isEmpty()) {
                val rebuilding = rebuiltWith?.let { " (index rebuilding with $it)" }.orEmpty()
                log?.warn("$TAG: no embeddings from $identity yet; no semantic results$rebuilding")
                return SearchOutcome(emptyList(), rebuiltWith)
            }

            val scored =
                VectorSearchService.searchWithScores(queryEmbedding, comparable, topK = topK)
            log?.debug("$TAG: search for '$query' returned ${scored.size} results")

            scored.map { it.first }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.error("$TAG: error during search", e)
            emptyList()
        }
        return SearchOutcome(ranked, rebuiltWith)
    }

    /**
     * The results section's title, naming the new model when this search rebuilt the index for it.
     *
     * @param rebuiltWith the model the index was rebuilt with, or null when it was reused
     */
    private fun resultsTitle(rebuiltWith: String?): String =
        if (rebuiltWith == null) {
            string(R.string.search_results_title, FALLBACK_RESULTS_TITLE)
        } else {
            string(R.string.search_results_title_rebuilt, FALLBACK_RESULTS_TITLE, rebuiltWith)
        }

    /**
     * Resolves [resId] against this plugin's own resources.
     *
     * @param fallback returned when the context is missing or the lookup fails; the host builds
     *   search results and Preferences on its own schedule and must never see an exception here
     */
    private fun string(resId: Int, fallback: String, vararg args: Any): String = try {
        context.androidContext.getString(resId, *args)
    } catch (e: Exception) {
        fallback
    }

    /** What is left of the search budget, floored at zero so an expired budget waits no longer. */
    private fun remainingMs(deadline: Long): Long =
        ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

    /**
     * AI Core's inference service, or null before [initialize], since Preferences may build the
     * settings screen first. Not `context.services` as a third fallback: that registry holds only
     * the host's own `Ide*Service`s, so a cross-plugin lookup there never answers.
     */
    private fun inferenceService(): LlmInferenceService? {
        if (!::context.isInitialized) return null
        return SharedServices.get(LlmInferenceService::class.java)
            ?: context.getPluginService(
                LlmBackendRegistration.AI_CORE_PLUGIN_ID,
                LlmInferenceService::class.java,
            )
    }

    /**
     * Says why there is no embedder, in the words that name the fix.
     *
     * Each case is a different thing for the user to do, so they are not collapsed: an unhelpful
     * "no results" is precisely what the removed lexical fallback used to produce.
     */
    private fun logUnresolved(resolution: EmbedderResolution) {
        val logger = log ?: return
        when (resolution) {
            is EmbedderResolution.Ready -> Unit

            EmbedderResolution.NoService ->
                logger.info("$TAG: semantic search needs AI Core, which is not available")

            EmbedderResolution.NoSelection ->
                logger.info("$TAG: semantic search needs a backend chosen in AI settings; none is")

            is EmbedderResolution.NotEmbeddingCapable ->
                logger.info(
                    "$TAG: the selected backend '${resolution.backendId}' does not produce " +
                        "embeddings; choose one that does to use semantic search"
                )

            is EmbedderResolution.Unavailable ->
                logger.info(
                    "$TAG: the selected backend '${resolution.backendId}' is not configured yet"
                )

            is EmbedderResolution.Unusable ->
                logger.warn(
                    "$TAG: the selected backend '${resolution.backendId}' cannot embed: " +
                        resolution.reason
                )
        }
    }

    private fun CodeEmbedding.toProjectSearchResult(query: String): ProjectSearchResult {
        val line = startLine.coerceAtLeast(1) - 1
        return ProjectSearchResult(
            file = File(filePath),
            linePreview = chunkText.replace(Regex("\\s+"), " ").take(160),
            matchText = query,
            startLine = line,
            startColumn = 0,
            endLine = endLine.coerceAtLeast(startLine).coerceAtLeast(1) - 1,
            endColumn = 0,
        )
    }

    // --- SettingsExtension: the Semantic Search row in Preferences -> Configuration ---

    override fun getSettingsEntries(): List<PluginSettingsEntry> = listOf(
        PluginSettingsEntry(
            id = "semantic_search",
            title = string(R.string.pref_semantic_search_title, "Semantic Search"),
            summary = string(
                R.string.pref_semantic_search_summary,
                "Embedding model, backend support and index",
            ),
            fragmentClassName = SemanticSearchSettingsFragment::class.java.name,
        )
    )

    // --- DocumentationExtension: three-tier in-IDE help ---

    override fun getTooltipCategory(): String = TOOLTIP_CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = VectorSearchHelp.entries()

    override fun getTier3DocsAssetPath(): String = "docs"

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.vectorsearch"

        /**
         * Category the host registers this plugin's tooltips under. Must be `"plugin_"` + the full
         * plugin id, or a long-press renders the literal string `n/a`.
         */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        /** The results title when this plugin's own resources cannot be read. */
        private const val FALLBACK_RESULTS_TITLE = "Semantic Results"

        @Volatile
        private var instance: VectorSearchPlugin? = null

        /** The live plugin, for the settings screen the host constructs by class name. */
        fun getInstance(): VectorSearchPlugin? = instance
    }
}
