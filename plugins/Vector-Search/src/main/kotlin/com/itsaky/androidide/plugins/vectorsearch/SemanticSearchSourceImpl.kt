package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.vectorsearch.settings.IndexStatus
import com.itsaky.androidide.plugins.vectorsearch.settings.SemanticSearchSource
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** Names this class in every line it logs; the host's `[pluginId]` prefix names only the plugin. */
private const val TAG = "SemanticSearchSource"

/**
 * What the settings screen reads and resets, over one activation's index. The plugin builds a new
 * one with each [IndexCoordinator], so it never outlives the database and coordinator it reads.
 *
 * @param context the plugin's context, for the open project and the log
 * @param store the index database
 * @param indexing the coordinator that builds and clears [store]
 * @param inference resolves AI Core's inference service, or null when it is absent
 * @param backendChanges ticks when the selected backend may have changed
 * @param indexChanges ticks when [indexing] stored a batch, ended a build or cleared the index
 */
internal class SemanticSearchSourceImpl(
    private val context: PluginContext,
    private val store: EmbeddingIndexingService,
    private val indexing: IndexCoordinator,
    private val inference: () -> LlmInferenceService?,
    override val backendChanges: StateFlow<Long>,
    override val indexChanges: StateFlow<Long>,
) : SemanticSearchSource {

    override fun inferenceService(): LlmInferenceService? = inference()

    override fun clearIndex(): Job = indexing.clearAll()

    override fun indexStatus(): IndexStatus {
        val root = currentProjectRoot() ?: return IndexStatus.NoProject
        // From memory, not the database: progress arrives once per stored batch.
        when (val activity = indexing.activity) {
            is BuildActivity.Building ->
                if (RootsKey.isUnder(activity.rootsKey, root)) {
                    return IndexStatus.Building(activity.stored, activity.total)
                }
            is BuildActivity.Failed ->
                if (RootsKey.isUnder(activity.rootsKey, root)) return IndexStatus.BuildFailed
            BuildActivity.Idle -> Unit
        }
        val embedders = store.summarize(root)
        if (embedders.isEmpty()) return IndexStatus.NotIndexed
        return IndexStatus.Indexed(
            embedders = embedders,
            fileCount = store.countFiles(root),
            lastBuiltAt = indexing.lastBuiltUnder(root),
        )
    }

    override fun indexHoldsAnything(): Boolean = store.hasAnyRows()

    override fun logger(): PluginLogger? = context.logger

    /** The open project's root, or null when none is open or the project service refused. */
    private fun currentProjectRoot(): File? = try {
        context.services.get(IdeProjectService::class.java)?.getCurrentProject()?.rootDir
    } catch (e: Exception) {
        context.logger.warn("$TAG: could not read the open project", e)
        null
    }
}
