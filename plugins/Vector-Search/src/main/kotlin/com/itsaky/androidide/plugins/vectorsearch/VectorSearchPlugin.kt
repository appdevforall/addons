package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ProjectSearchExtension
import com.itsaky.androidide.plugins.extensions.ProjectSearchRequest
import com.itsaky.androidide.plugins.extensions.ProjectSearchResult
import com.itsaky.androidide.plugins.extensions.ProjectSearchSection
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingBackend
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.coroutineContext

/**
 * Names this class in every line it logs.
 *
 * The host already prefixes each line with `[pluginId]`, which says which `.cgp` wrote it but not
 * which of its classes; this plugin logs from two.
 */
private const val TAG = "VectorSearchPlugin"
private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"
private const val SEMANTIC_RESULTS_TITLE = "Semantic Results"

/**
 * How long a search waits for an in-flight index build before answering from what exists.
 *
 * Under the host's own 10-second budget for a project search, so a first query on a large project
 * returns the partial index it has rather than being cut off mid-wait with nothing to show.
 */
private const val INDEXING_WAIT_MS = 8_000L

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
class VectorSearchPlugin : IPlugin, ProjectSearchExtension, DocumentationExtension {

    private lateinit var context: PluginContext
    private lateinit var indexingService: EmbeddingIndexingService
    @Volatile private var indexingJob: Job? = null
    // What the last completed build produced; null until one has completed in this session.
    @Volatile private var indexedState: IndexState? = null
    // What the in-flight [indexingJob] is building; guards against awaiting a build whose result
    // this search could not use — another project's roots, or another embedder's vector space.
    @Volatile private var indexingTarget: IndexTarget? = null

    // Background scope for indexing so a large project never stalls a search request.
    private val indexingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * This plugin's IDE-surfaced log, so indexing diagnostics reach the IDE's own log view rather
     * than only logcat.
     *
     * Null before [initialize], which is the state [dispose] can be called in when the plugin
     * failed to load — the one path that must not throw on its way out.
     */
    private val log: PluginLogger?
        get() = if (::context.isInitialized) context.logger else null

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        log?.info("$TAG: initialized")
        return true
    }

    override fun activate(): Boolean {
        log?.info("$TAG: activating...")

        // Initialize indexing service
        indexingService = EmbeddingIndexingService(context.androidContext, context.logger)

        log?.info("$TAG: activated. The first search builds the index.")
        return true
    }

    override fun deactivate(): Boolean {
        log?.info("$TAG: deactivating")
        return true
    }

    override fun dispose() {
        indexingScope.cancel()
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
            val results = runBlocking {
                search(query = request.query, roots = request.roots, topK = 10)
            }
            if (results.isEmpty()) {
                log?.info("$TAG: no semantic results available for '${request.query}'")
                emptyList()
            } else {
                listOf(
                    ProjectSearchSection(
                        title = SEMANTIC_RESULTS_TITLE,
                        results = results.map { it.toProjectSearchResult(request.query) },
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
    ): List<CodeEmbedding> {
        return try {
            val embedder = EmbedderResolver.resolve(inferenceService())
            if (embedder !is EmbedderResolution.Ready) {
                logUnresolved(embedder)
                return emptyList()
            }

            // The query is embedded first because its vector is what reports the width: only a
            // vector the backend actually produced can say what space the index must be built in.
            val queryEmbedding = embed(embedder.backend, listOf(query)).firstOrNull()
                ?: return emptyList()
            val identity = EmbedderIdentity(embedder.key, queryEmbedding.size)

            if (roots.isNotEmpty()) {
                // Wait for an in-flight build so the first query returns real results, not empty.
                val job = startIndexingIfNeeded(roots, embedder.backend, identity)
                if (job != null && withTimeoutOrNull(INDEXING_WAIT_MS) { job.join() } == null) {
                    log?.warn(
                        "$TAG: indexing still running after ${INDEXING_WAIT_MS}ms; " +
                            "using partial index"
                    )
                }
            }

            val comparable = indexingService.getAllEmbeddings(identity)
            if (comparable.isEmpty()) {
                log?.warn("$TAG: no embeddings from $identity yet; no semantic results for now")
                return emptyList()
            }

            val results =
                VectorSearchService.searchWithScores(queryEmbedding, comparable, topK = topK)
            log?.debug("$TAG: search for '$query' returned ${results.size} results")

            results.map { it.first }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.error("$TAG: error during search", e)
            emptyList()
        }
    }

    /**
     * Clears the index (e.g., for reindexing after project changes).
     */
    fun clearIndex() {
        indexingService.clearIndex()
        indexedState = null
        log?.info("$TAG: index cleared")
    }

    /**
     * AI Core's inference service, however this host publishes it.
     *
     * @return the service, or null when AI Core is absent or has not published it yet
     */
    private fun inferenceService(): LlmInferenceService? =
        SharedServices.get(LlmInferenceService::class.java)
            ?: context.getPluginService(AI_CORE_PLUGIN_ID, LlmInferenceService::class.java)

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

    /**
     * Starts (or reuses) a background index build for [roots], returning its [Job] so the caller
     * can await it, or null when the index already answers for these roots and this embedder.
     *
     * @param roots project root directories to index
     * @param backend the embedder to build with
     * @param identity what that embedder produces, as the query has just demonstrated
     * @return the running index-build job, or null if the existing index is reusable
     */
    @Synchronized
    private fun startIndexingIfNeeded(
        roots: List<File>,
        backend: EmbeddingBackend,
        identity: EmbedderIdentity,
    ): Job? {
        val target = IndexTarget(rootsKeyOf(roots), identity)
        val rootsKey = target.rootsKey
        val running = indexingJob
        if (running != null && running.isActive && indexingTarget == target) {
            return running
        }
        if (running?.isActive != true &&
            ReindexDecision.isReusable(indexedState, rootsKey, identity)
        ) {
            return null
        }

        // Cancel a build for another target and wait for it to unwind, so builds never
        // interleave writes.
        val previous = running
        previous?.cancel()
        indexingTarget = target
        val job = indexingScope.launch {
            try {
                previous?.join()
                buildIndex(rootsKey, roots, backend, identity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log?.error("$TAG: background indexing failed", e)
            }
        }
        indexingJob = job
        return job
    }

    /**
     * What one index build is for, as the one value two builds are compared on.
     *
     * @param rootsKey the roots being indexed
     * @param identity the embedder the vectors will come from
     */
    private data class IndexTarget(val rootsKey: String, val identity: EmbedderIdentity)

    /** The roots, as one value two builds can be compared on. */
    private fun rootsKeyOf(roots: List<File>): String =
        roots.map { it.absolutePath }.sorted().joinToString("|")

    /**
     * Builds the whole index for [roots], batch by batch.
     *
     * A failed batch aborts the build and leaves the index empty rather than substituting anything.
     * A substitute would be stored under the real embedder's identity, so the origin filter could
     * not tell it apart and every later search would rank it beside genuine vectors.
     */
    private suspend fun buildIndex(
        rootsKey: String,
        roots: List<File>,
        backend: EmbeddingBackend,
        identity: EmbedderIdentity,
    ) {
        // Reset the marker up front so a mid-build failure doesn't leave a stale "indexed" flag.
        indexedState = null
        indexingService.clearIndex()

        val pending = collectChunks(roots)
        var stored = 0
        try {
            for (batch in EmbeddingBatches.split(pending) { it.chunkText.length }) {
                coroutineContext.ensureActive()
                val vectors = embed(backend, batch.map { it.chunkText })
                indexingService.storeEmbeddings(toEmbeddings(batch, vectors, identity))
                stored += batch.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Emptied, not left partial: a half-built index answers with whatever it happened to
            // reach first, which reads as bad ranking rather than as a failed build.
            indexingService.clearIndex()
            log?.error("$TAG: indexing aborted after $stored of ${pending.size} chunks", e)
            return
        }

        indexedState = IndexState(rootsKey, identity, stored)
        log?.info("$TAG: indexed $stored chunks with $identity")
    }

    /**
     * One chunk waiting to be embedded.
     *
     * Collected before any embedding so the loop that calls the network is about batches rather
     * than about walking a file tree.
     */
    private data class PendingChunk(
        val key: String,
        val filePath: String,
        val chunkText: String,
        val language: String,
        val chunkIndex: Int,
        val startLine: Int,
        val endLine: Int,
    )

    /** Walks [roots] and chunks every code file it finds. */
    private suspend fun collectChunks(roots: List<File>): List<PendingChunk> {
        val pending = mutableListOf<PendingChunk>()
        var fileCount = 0

        roots.forEach { root ->
            indexingService.collectFiles(root).forEach { file ->
                // Bail promptly if this build was superseded by one for different roots.
                coroutineContext.ensureActive()
                fileCount++
                val language = indexingService.languageFor(file)
                val chunks = try {
                    CodeChunker.chunkFile(file)
                } catch (e: Exception) {
                    log?.warn("$TAG: failed to chunk ${file.absolutePath}", e)
                    emptyList()
                }

                chunks.forEachIndexed { index, chunk ->
                    pending.add(
                        PendingChunk(
                            key = "${file.absolutePath}:$index",
                            filePath = file.absolutePath,
                            chunkText = chunk.content,
                            language = language,
                            chunkIndex = index,
                            startLine = chunk.startLine + 1,
                            endLine = chunk.endLine + 1,
                        )
                    )
                }
            }
        }

        log?.info("$TAG: collected ${pending.size} chunks from $fileCount files")
        return pending
    }

    /**
     * Pairs a batch with the vectors it produced.
     *
     * @throws IOException when the backend answered with a different width than the index is being
     *   built in — storing those rows would put two spaces under one identity
     */
    private fun toEmbeddings(
        batch: List<PendingChunk>,
        vectors: List<FloatArray>,
        identity: EmbedderIdentity,
    ): List<CodeEmbedding> = batch.mapIndexed { position, chunk ->
        val vector = vectors[position]
        if (vector.size != identity.dimensions) {
            throw IOException(
                "The embedder answered with ${vector.size} dimensions, not ${identity.dimensions}"
            )
        }
        CodeEmbedding(
            key = chunk.key,
            filePath = chunk.filePath,
            chunkText = chunk.chunkText,
            language = chunk.language,
            chunkIndex = chunk.chunkIndex,
            startLine = chunk.startLine,
            endLine = chunk.endLine,
            embedding = vector,
            identity = identity,
        )
    }

    /**
     * Embeds [texts] with [backend], unwrapping the future's failure so the caller sees the
     * backend's own message rather than an [ExecutionException] wrapper.
     *
     * The wait is interruptible and cancels the future with it: without that, abandoning an index
     * build would leave its HTTP requests running to completion for vectors nobody will store.
     *
     * @return one vector per text, in order
     * @throws IOException when the backend failed or answered with the wrong number of vectors
     */
    private suspend fun embed(
        backend: EmbeddingBackend,
        texts: List<String>,
    ): List<FloatArray> {
        val future = backend.embed(texts)
        val vectors = try {
            runInterruptible { future.get() }
        } catch (e: CancellationException) {
            future.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            throw IOException(e.cause?.message ?: "The embedder failed", e.cause ?: e)
        }
        if (vectors == null || vectors.size != texts.size) {
            throw IOException(
                "The embedder answered with ${vectors?.size ?: 0} vectors for ${texts.size} texts"
            )
        }
        return vectors
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

    override fun getTooltipCategory(): String = "plugin_com.itsaky.androidide.plugins.vectorsearch"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Vector Search adds semantic, meaning-based matches to project search.",
            detail = """
                <p><b>Vector Search</b> chunks project files, embeds them with
                the AI backend you selected in AI settings, and ranks matches by
                semantic similarity instead of only exact text.</p>
                <p>It needs a backend that produces embeddings, which today means
                a cloud one. With no such backend selected it simply contributes
                no results, rather than quietly matching on words and presenting
                that as semantic search.</p>
                <p>Changing the backend or its embedding model builds the index
                again: vectors from two different models cannot be compared.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Vector Search guide",
                    uri = "index.html",
                    order = 0
                )
            )
        )
    )

    override fun getTier3DocsAssetPath(): String = "docs"

    private companion object {
        const val TOOLTIP_TAG_PLUGIN = "plugin_vector_search"
    }
}
