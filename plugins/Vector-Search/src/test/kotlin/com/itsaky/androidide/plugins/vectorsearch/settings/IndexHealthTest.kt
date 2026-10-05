package com.itsaky.androidide.plugins.vectorsearch.settings

import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingModelSelectable
import com.itsaky.androidide.plugins.vectorsearch.IndexedEmbedder
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexHealthTest {

    private val selectedGemini = BackendCompatibility.Selected(
        backendId = "gemini",
        backendName = "Gemini API",
        chatModel = null,
        support = EmbeddingSupport.Selectable(mockk<EmbeddingModelSelectable>(), "gemini-embedding-001"),
    )

    @Test
    fun givenRowsFromTheSelectedModel_whenRead_thenTheIndexIsUpToDate() {
        val status = indexed(IndexedEmbedder("gemini", "gemini-embedding-001", 3072, 40))

        assertEquals(IndexHealth.UP_TO_DATE, IndexHealth.of(status, selectedGemini))
    }

    @Test
    fun givenRowsFromAnotherModel_whenRead_thenTheIndexIsOutOfDate() {
        // AC2 on the screen: the next search rebuilds, and the user sees so before searching.
        val status = indexed(IndexedEmbedder("gemini", "text-embedding-004", 768, 40))

        assertEquals(IndexHealth.OUT_OF_DATE, IndexHealth.of(status, selectedGemini))
    }

    @Test
    fun givenAHalfRebuiltIndex_whenRead_thenItIsStillOutOfDate() {
        val status = indexed(
            IndexedEmbedder("gemini", "gemini-embedding-001", 3072, 30),
            IndexedEmbedder("gemini", "text-embedding-004", 768, 10),
        )

        assertEquals(IndexHealth.OUT_OF_DATE, IndexHealth.of(status, selectedGemini))
    }

    @Test
    fun givenABackendThatIsNotSupported_whenRead_thenFreshnessIsNotClaimed() {
        val local = selectedGemini.copy(backendId = "local", support = EmbeddingSupport.None)
        val status = indexed(IndexedEmbedder("gemini", "gemini-embedding-001", 3072, 40))

        assertEquals(IndexHealth.INDEXED, IndexHealth.of(status, local))
    }

    @Test
    fun givenEachNonIndexedStatus_whenRead_thenItLeadsWithItsOwnState() {
        assertEquals(IndexHealth.NO_PROJECT, IndexHealth.of(IndexStatus.NoProject, selectedGemini))
        assertEquals(IndexHealth.NOT_INDEXED, IndexHealth.of(IndexStatus.NotIndexed, selectedGemini))
        assertEquals(IndexHealth.INDEXING, IndexHealth.of(IndexStatus.Building(1, 2), selectedGemini))
        assertEquals(IndexHealth.BUILD_FAILED, IndexHealth.of(IndexStatus.BuildFailed, selectedGemini))
    }

    @Test
    fun givenRowsFromTwoModels_whenCounted_thenTheCountCoversBoth() {
        val status = indexed(
            IndexedEmbedder("openai", "text-embedding-3-small", 1536, 40),
            IndexedEmbedder("openai", "text-embedding-3-large", 3072, 2),
        )

        assertEquals(42, status.chunkCount)
    }

    private fun indexed(vararg embedders: IndexedEmbedder) =
        IndexStatus.Indexed(embedders.toList(), fileCount = 3, lastBuiltAt = null)
}
