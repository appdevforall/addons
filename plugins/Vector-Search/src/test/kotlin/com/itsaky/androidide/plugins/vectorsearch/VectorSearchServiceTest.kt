package com.itsaky.androidide.plugins.vectorsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSearchServiceTest {

    @Test
    fun givenSeveralChunks_whenSearching_thenTheClosestRanksFirst() {
        val query = floatArrayOf(1f, 0f)
        val near = embedding("near", floatArrayOf(0.99f, 0.14f))
        val far = embedding("far", floatArrayOf(0.71f, 0.71f))

        val ranked = VectorSearchService.searchWithScores(query, listOf(far, near))

        assertEquals(listOf("near", "far"), ranked.map { it.first.key })
    }

    @Test
    fun givenMoreMatchesThanAsked_whenSearching_thenOnlyTopKAreReturned() {
        val query = floatArrayOf(1f, 0f)
        val chunks = (0 until 5).map { embedding("chunk $it", floatArrayOf(1f, it * 0.01f)) }

        val ranked = VectorSearchService.searchWithScores(query, chunks, topK = 2)

        assertEquals(2, ranked.size)
    }

    @Test
    fun givenOnlyUnrelatedChunks_whenSearching_thenNothingIsReturned() {
        // Below the similarity floor: an unrelated chunk in the results is worse than no results,
        // because the section header claims these matched on meaning.
        val ranked = VectorSearchService.searchWithScores(
            floatArrayOf(1f, 0f),
            listOf(embedding("opposite", floatArrayOf(-1f, 0f))),
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun givenAWeakMatchBesideAStrongOne_whenSearching_thenTheWeakOneIsCutOff() {
        // The relevance cutoff keeps a long tail of barely-related chunks out of a list the user
        // reads as "these are the matches".
        val query = floatArrayOf(1f, 0f)
        val strong = embedding("strong", floatArrayOf(1f, 0f))
        val weak = embedding("weak", floatArrayOf(0.2f, 0.98f))

        val ranked = VectorSearchService.searchWithScores(query, listOf(strong, weak))

        assertEquals(listOf("strong"), ranked.map { it.first.key })
    }

    @Test
    fun givenAnEmptyIndex_whenSearching_thenNothingIsReturned() {
        assertTrue(VectorSearchService.searchWithScores(floatArrayOf(1f), emptyList()).isEmpty())
    }

    @Test
    fun givenAnEmptyQueryVector_whenSearching_thenNothingIsReturned() {
        val ranked = VectorSearchService.searchWithScores(
            FloatArray(0),
            listOf(embedding("any", floatArrayOf(1f, 0f))),
        )

        assertTrue(ranked.isEmpty())
    }

    /** A stored chunk carrying [vector], with everything else set to something legible. */
    private fun embedding(key: String, vector: FloatArray) = CodeEmbedding(
        key = key,
        filePath = "/project/$key.kt",
        chunkText = key,
        language = "kotlin",
        chunkIndex = 0,
        startLine = 1,
        endLine = 2,
        embedding = vector,
        identity = EmbedderIdentity(EmbedderKey("openai", "text-embedding-3-small"), vector.size),
    )
}
