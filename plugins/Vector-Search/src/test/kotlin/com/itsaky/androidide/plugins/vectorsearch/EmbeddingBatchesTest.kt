package com.itsaky.androidide.plugins.vectorsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingBatchesTest {

    @Test
    fun givenMoreChunksThanOneCallAllows_whenSplitting_thenOrderIsPreserved() {
        val chunks = (0 until EmbeddingBatches.MAX_CHUNKS_PER_CALL + 4).map { "chunk $it" }

        val batches = EmbeddingBatches.split(chunks) { it.length }

        assertEquals(2, batches.size)
        assertEquals(EmbeddingBatches.MAX_CHUNKS_PER_CALL, batches[0].size)
        assertEquals(chunks, batches.flatten())
    }

    @Test
    fun givenLargeChunks_whenSplitting_thenTheCharacterBudgetSplitsThemFirst() {
        val half = "x".repeat(EmbeddingBatches.MAX_CHARS_PER_CALL / 2)

        val batches = EmbeddingBatches.split(listOf(half, half, half)) { it.length }

        assertEquals(2, batches.size)
        assertEquals(2, batches[0].size)
    }

    @Test
    fun givenOneChunkOverTheBudget_whenSplitting_thenItIsKeptInABatchOfItsOwn() {
        // Dropping it would leave a hole in the index that nothing reports; only the embedder can
        // judge whether it actually fits.
        val oversized = "x".repeat(EmbeddingBatches.MAX_CHARS_PER_CALL * 2)

        val batches = EmbeddingBatches.split(listOf("small", oversized)) { it.length }

        assertEquals(listOf(listOf("small"), listOf(oversized)), batches)
    }

    @Test
    fun givenNoChunks_whenSplitting_thenThereIsNothingToEmbed() {
        assertTrue(EmbeddingBatches.split(emptyList<String>()) { it.length }.isEmpty())
    }
}
