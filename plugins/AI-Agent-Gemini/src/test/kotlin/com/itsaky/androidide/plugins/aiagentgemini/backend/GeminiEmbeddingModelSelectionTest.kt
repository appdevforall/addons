package com.itsaky.androidide.plugins.aiagentgemini.backend

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiEmbeddingModelSelectionTest {

    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val prefs = mockk<SharedPreferences>(relaxed = true) {
        every { edit() } returns editor
        every { getString(GeminiPreferences.KEY_EMBEDDING_MODEL, any()) } returns "gemini-embedding-001"
    }
    private val context = mockk<PluginContext>(relaxed = true) {
        every { getPluginSharedPreferences(any()) } returns prefs
    }
    private val backend = GeminiBackend(context) { null }

    init {
        every { editor.putString(any(), any()) } returns editor
    }

    @Test
    fun givenAPaddedModel_whenSet_thenItIsStoredTrimmedWhereTheBackendReadsIt() {
        backend.setEmbeddingModelId(" text-embedding-004 ")

        verify { editor.putString(GeminiPreferences.KEY_EMBEDDING_MODEL, "text-embedding-004") }
    }

    @Test
    fun givenTheCurrentModel_whenSetAgain_thenNothingIsWritten() {
        // No write, so no change notice: the contract lets an unchanged model notify nobody.
        backend.setEmbeddingModelId("gemini-embedding-001")

        verify(exactly = 0) { editor.putString(any(), any()) }
    }

    @Test
    fun givenABlankModel_whenSet_thenItIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { backend.setEmbeddingModelId("  ") }
    }

    @Test
    fun givenAClosedBackend_whenListingModels_thenTheFutureFailsAtOnce() {
        backend.close()

        val future = backend.listEmbeddingModels()

        assertTrue(future.isCompletedExceptionally)
        val failure = assertThrows(ExecutionException::class.java) { future.get() }
        assertTrue(failure.cause is IllegalStateException)
    }
}
