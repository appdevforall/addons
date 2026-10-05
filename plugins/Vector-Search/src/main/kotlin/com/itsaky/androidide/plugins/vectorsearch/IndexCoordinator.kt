package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingBackend
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Names this class in every line it logs; the host's `[pluginId]` prefix names only the plugin. */
private const val TAG = "IndexCoordinator"

/** What the coordinator is doing, for the settings screen. */
sealed interface BuildActivity {

    /** No build is running, and the last one did not fail. */
    data object Idle : BuildActivity

    /**
     * A build is running.
     *
     * @param rootsKey the roots it indexes
     * @param stored chunks embedded and stored so far
     * @param total chunks to embed; zero while the files are still being collected
     */
    data class Building(val rootsKey: String, val stored: Int, val total: Int) : BuildActivity

    /**
     * The last build stopped on an error and stored nothing; a search will not retry it until the
     * index is cleared, so a backend refusing every call is not billed on every query.
     *
     * @param rootsKey the roots it was indexing
     */
    data class Failed(val rootsKey: String) : BuildActivity
}

/**
 * An index build a search may wait for.
 *
 * @param job the build
 * @param replacedModel the embedding model whose rows it replaces, or null for a first build
 */
data class IndexBuild(val job: Job, val replacedModel: String?)

/**
 * Builds, reuses and clears the index, one operation at a time, so no two ever interleave writes.
 * Owns all build state; the plugin only asks for a build or a clear.
 *
 * @param store the index database
 * @param buildLog where each completed build's time is kept
 * @param logger this plugin's log, or null before it has a context
 * @param onIndexChanged called on a background thread after each stored batch, build end and clear
 * @param clock the current time in milliseconds, injectable for tests
 */
class IndexCoordinator(
    private val store: EmbeddingIndexingService,
    private val buildLog: IndexBuildLog,
    private val logger: () -> PluginLogger?,
    private val onIndexChanged: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Background scope for indexing so a large project never stalls a search request.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The build or clear in flight, which the next one cancels and waits for before writing.
    @Volatile private var current: Job? = null

    // What the last completed build produced; null until one has completed in this session.
    @Volatile private var indexedState: IndexState? = null

    // What [current] is building; null while it is a clear, or nothing runs.
    @Volatile private var target: IndexTarget? = null

    // The last clear: a build waits for it but never cancels it, or an unstarted one is skipped.
    @Volatile private var clearing: Job? = null

    /** What runs now, or how the last build ended; read by the settings screen. */
    @Volatile
    var activity: BuildActivity = BuildActivity.Idle
        private set

    /**
     * Starts (or reuses) a background build for [roots], or returns null when the index already
     * answers for these roots and this embedder.
     *
     * @param rootsKey the roots being indexed, as one value
     * @param roots project root directories to index
     * @param backend the embedder to build with
     * @param identity what that embedder produces, as the query has just demonstrated
     * @return the running build, or null if the existing index is reusable
     */
    @Synchronized
    fun buildIfNeeded(
        rootsKey: String,
        roots: List<File>,
        backend: EmbeddingBackend,
        identity: EmbedderIdentity,
    ): IndexBuild? {
        val wanted = IndexTarget(rootsKey, identity)
        val running = current
        if (running != null && running.isActive && target == wanted) {
            return IndexBuild(running, replacedModel = null)
        }
        if (running?.isActive != true) {
            // The marker is per-session but the rows persist: ask the database before re-embedding.
            if (!ReindexDecision.isReusable(indexedState, rootsKey, identity)) {
                val stored = store.countEmbeddings(rootsKey, identity)
                if (stored > 0) indexedState = IndexState(rootsKey, identity, stored)
            }
            if (ReindexDecision.isReusable(indexedState, rootsKey, identity)) return null
        }

        val replaced = store.storedModels(rootsKey).firstOrNull { it != identity.modelId }
        if (replaced != null) {
            logger()?.info("$TAG: embedding model changed from $replaced; rebuilding as $identity")
        }

        // Cancel a build and wait for it to unwind, so builds never interleave writes.
        if (running !== clearing) running?.cancel()
        target = wanted
        val job = scope.launch {
            try {
                running?.join()
                build(rootsKey, roots, backend, identity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger()?.error("$TAG: background indexing failed", e)
            }
            onIndexChanged()
        }
        current = job
        return IndexBuild(job, replaced)
    }

    /**
     * Clears every project's index once whatever runs now has unwound, so no row it writes later
     * survives as a partial index. Never skipped: a search that supersedes it waits for it.
     *
     * @return the clear, complete once the index is empty or the delete has failed and been logged
     */
    @Synchronized
    fun clearAll(): Job {
        val running = current
        running?.cancel()
        target = null
        indexedState = null
        val job = scope.launch {
            withContext(NonCancellable) {
                running?.join()
                try {
                    store.clearIndex()
                    buildLog.clear()
                    logger()?.info("$TAG: index cleared")
                } catch (e: Exception) {
                    // Caught here: nothing above this launch handles it, so it would crash the IDE.
                    logger()?.error("$TAG: could not clear the index", e)
                }
                // Again after the join: the build it waited for may have recorded itself last.
                indexedState = null
                // Also forgets a failed build, which is how the user asks for a retry.
                activity = BuildActivity.Idle
                onIndexChanged()
            }
        }
        current = job
        clearing = job
        return job
    }

    /** When a build of roots within [projectRoot] last completed, or null when none is recorded. */
    fun lastBuiltUnder(projectRoot: File): Long? = buildLog.lastBuiltUnder(projectRoot)

    /** Stops every build; the coordinator is unusable afterwards. */
    fun close() {
        scope.cancel()
    }

    /**
     * Builds the whole index for [roots], batch by batch.
     *
     * A failed batch aborts the build and leaves the index empty rather than substituting anything.
     * A substitute would be stored under the real embedder's identity and ranked beside it.
     */
    private suspend fun build(
        rootsKey: String,
        roots: List<File>,
        backend: EmbeddingBackend,
        identity: EmbedderIdentity,
    ) {
        // Reset the marker up front so a mid-build failure doesn't leave a stale "indexed" flag.
        indexedState = null
        reportProgress(BuildActivity.Building(rootsKey, stored = 0, total = 0))
        store.clearIndex(rootsKey)

        var stored = 0
        var total = 0
        try {
            val pending = collectChunks(roots)
            total = pending.size
            reportProgress(BuildActivity.Building(rootsKey, stored, total))
            for (batch in EmbeddingBatches.split(pending) { it.chunkText.length }) {
                coroutineContext.ensureActive()
                val vectors = backend.awaitVectors(batch.map { it.chunkText })
                store.storeEmbeddings(rootsKey, toEmbeddings(batch, vectors, identity))
                stored += batch.size
                reportProgress(BuildActivity.Building(rootsKey, stored, total))
            }
        } catch (e: CancellationException) {
            // Superseded or cleared: whatever replaces this build sets the activity next.
            activity = BuildActivity.Idle
            throw e
        } catch (e: Exception) {
            // Emptied, not left partial: a half-built index reads as bad ranking, not a failure.
            store.clearIndex(rootsKey)
            // Recorded as an attempt, so a backend refusing every call is not re-billed per search.
            indexedState = IndexState(rootsKey, identity, chunkCount = 0)
            activity = BuildActivity.Failed(rootsKey)
            logger()?.error("$TAG: indexing aborted after $stored of $total chunks", e)
            return
        }

        indexedState = IndexState(rootsKey, identity, stored)
        activity = BuildActivity.Idle
        buildLog.recordBuilt(rootsKey, clock())
        logger()?.info("$TAG: indexed $stored chunks with $identity")
    }

    /** Publishes [progress] and tells the screen, which reads it without touching the database. */
    private fun reportProgress(progress: BuildActivity.Building) {
        activity = progress
        onIndexChanged()
    }

    /**
     * What one build is for, as the one value two builds are compared on.
     *
     * @param rootsKey the roots being indexed
     * @param identity the embedder the vectors will come from
     */
    private data class IndexTarget(val rootsKey: String, val identity: EmbedderIdentity)

    /**
     * One chunk waiting to be embedded. Collected before any embedding, so the loop that calls the
     * network is about batches rather than about walking a file tree.
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
            store.collectFiles(root).forEach { file ->
                // Bail promptly if this build was superseded by one for different roots.
                coroutineContext.ensureActive()
                fileCount++
                val language = store.languageFor(file)
                val chunks = try {
                    CodeChunker.chunkFile(file)
                } catch (e: Exception) {
                    logger()?.warn("$TAG: failed to chunk ${file.absolutePath}", e)
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

        logger()?.info("$TAG: collected ${pending.size} chunks from $fileCount files")
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
}
