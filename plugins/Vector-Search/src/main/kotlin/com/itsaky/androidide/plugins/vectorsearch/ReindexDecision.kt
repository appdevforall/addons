package com.itsaky.androidide.plugins.vectorsearch

/**
 * What the index currently holds, as the three facts that decide whether it can be reused.
 *
 * @param rootsKey the project roots it was built from
 * @param identity the embedder every one of its vectors came from
 * @param chunkCount how many chunks it holds; zero means a build that never produced anything
 */
data class IndexState(
    val rootsKey: String,
    val identity: EmbedderIdentity,
    val chunkCount: Int,
)

/**
 * Whether an existing index can answer the query about to be run, or has to be built again.
 *
 * Pure, so the rule that keeps vectors from two different embedders out of one ranking is testable
 * without a database, a device or a provider.
 */
object ReindexDecision {

    /**
     * Whether [state] can answer a query over [rootsKey] embedded by [identity].
     *
     * An identity change is as disqualifying as a roots change. It is the less obvious of the two:
     * different roots return visibly wrong files, while a different embedder returns vectors that
     * rank meaninglessly against each other and look like a quality problem rather than a bug.
     *
     * @param state what the index holds, or null when nothing has been indexed in this session
     * @param rootsKey the roots the query covers
     * @param identity the embedder the query itself was embedded by
     * @return true when the index can be queried as it stands
     */
    fun isReusable(state: IndexState?, rootsKey: String, identity: EmbedderIdentity): Boolean {
        if (state == null) return false
        if (state.chunkCount <= 0) return false
        if (state.rootsKey != rootsKey) return false
        return state.identity == identity
    }
}
