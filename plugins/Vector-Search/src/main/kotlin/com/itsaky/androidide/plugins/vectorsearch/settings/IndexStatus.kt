package com.itsaky.androidide.plugins.vectorsearch.settings

import com.itsaky.androidide.plugins.vectorsearch.IndexedEmbedder

/** What the index holds, or is doing, for the open project, as the settings screen reports it. */
sealed interface IndexStatus {

    /** No project is open, so there is nothing to report on. */
    data object NoProject : IndexStatus

    /** The open project has no rows: never searched, or cleared since. */
    data object NotIndexed : IndexStatus

    /**
     * A build for the open project is running.
     *
     * @param stored chunks embedded and stored so far
     * @param total chunks to embed; zero while the files are still being collected
     */
    data class Building(val stored: Int, val total: Int) : IndexStatus

    /** The last build for the open project stopped on an error and stored nothing. */
    data object BuildFailed : IndexStatus

    /**
     * The open project's rows.
     *
     * @param embedders per embedder, largest first; never empty, and more than one only while a
     *   switched model is half rebuilt
     * @param fileCount how many files have rows
     * @param lastBuiltAt when a build of this project last completed, or null when none is recorded
     */
    data class Indexed(
        val embedders: List<IndexedEmbedder>,
        val fileCount: Int,
        val lastBuiltAt: Long?,
    ) : IndexStatus {

        /** Every chunk under the project, whichever embedder produced it. */
        val chunkCount: Int get() = embedders.sumOf { it.chunkCount }
    }
}

/** The one word the screen leads the Index section with. */
enum class IndexHealth {
    NO_PROJECT,
    NOT_INDEXED,
    INDEXING,
    BUILD_FAILED,

    /** Rows from exactly the selected backend and model: searches use them as they are. */
    UP_TO_DATE,

    /** Rows from another backend or model: the next search rebuilds the index. */
    OUT_OF_DATE,

    /** Rows exist, but the selected backend cannot say which model it would use. */
    INDEXED;

    companion object {

        /**
         * @param status what the index holds for the open project
         * @param compatibility the selected backend, or null before it is read
         * @return the state to lead with
         */
        fun of(status: IndexStatus, compatibility: BackendCompatibility?): IndexHealth =
            when (status) {
                IndexStatus.NoProject -> NO_PROJECT
                IndexStatus.NotIndexed -> NOT_INDEXED
                is IndexStatus.Building -> INDEXING
                IndexStatus.BuildFailed -> BUILD_FAILED
                is IndexStatus.Indexed -> freshness(status, compatibility)
            }

        private fun freshness(
            status: IndexStatus.Indexed,
            compatibility: BackendCompatibility?,
        ): IndexHealth {
            val selected = compatibility as? BackendCompatibility.Selected ?: return INDEXED
            val support = selected.support as? EmbeddingSupport.Selectable ?: return INDEXED
            val current = status.embedders.all {
                it.backendId == selected.backendId && it.modelId == support.modelId
            }
            return if (current) UP_TO_DATE else OUT_OF_DATE
        }
    }
}
