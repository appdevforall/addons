package com.itsaky.androidide.plugins.vectorsearch.settings

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ActiveModelReportingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingModelSelectable
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendCompatibilityResolverTest {

    @Test
    fun givenNoService_whenResolving_thenAiCoreIsReportedMissing() {
        assertEquals(BackendCompatibility.NoService, BackendCompatibilityResolver.resolve(null))
    }

    @Test
    fun givenNoStoredSelection_whenResolving_thenNoSelectionIsReported() {
        val resolution = BackendCompatibilityResolver.resolve(service(selected = null))

        assertEquals(BackendCompatibility.NoSelection, resolution)
    }

    @Test
    fun givenTheSelectedBackendIsNotRegistered_whenResolving_thenItIsReportedNotInstalled() {
        val resolution = BackendCompatibilityResolver.resolve(service(selected = "gemini"))

        assertEquals(BackendCompatibility.NotInstalled("gemini"), resolution)
    }

    @Test
    fun givenABackendThatDoesNotEmbed_whenResolving_thenItIsNotSupported() {
        // Local, or a Gemini plugin from before embeddings: the case whose fix is update or switch.
        val backend = mockk<LlmBackend> { every { name } returns "Local LLM" }

        val selected = selected(service(selected = "local", backend = backend))

        assertEquals(EmbeddingSupport.None, selected.support)
        assertEquals("Local LLM", selected.backendName)
    }

    @Test
    fun givenAnEmbeddingBackendThatIsNotSelectable_whenResolving_thenItIsNotSupported() {
        // AC4: a plugin too old to list and set its model is "Not supported", even though it embeds.
        val backend = mockk<EmbeddingBackend> {
            every { name } returns "Gemini API"
            every { embeddingModelId } returns "gemini-embedding-001"
        }

        val selected = selected(service(selected = "gemini", backend = backend))

        assertEquals(EmbeddingSupport.None, selected.support)
    }

    @Test
    fun givenASelectableEmbeddingBackend_whenResolving_thenTheScreenMayPickItsModel() {
        val backend = mockk<EmbeddingModelSelectable> {
            every { name } returns "OpenAI"
            every { embeddingModelId } returns " text-embedding-3-small "
        }

        val selected = selected(service(selected = "openai", backend = backend))

        assertEquals(
            EmbeddingSupport.Selectable(backend, "text-embedding-3-small"),
            selected.support,
        )
    }

    @Test
    fun givenABackendThatNamesNoEmbeddingModel_whenResolving_thenItIsNotSupported() {
        // Nothing can be embedded without a model, whatever interfaces the backend carries.
        val backend = mockk<EmbeddingModelSelectable> {
            every { name } returns "OpenAI"
            every { embeddingModelId } returns " "
        }

        val selected = selected(service(selected = "openai", backend = backend))

        assertEquals(EmbeddingSupport.None, selected.support)
    }

    @Test
    fun givenABackendReportingItsChatModel_whenResolving_thenTheChatModelIsShown() {
        val backend = mockkClass(
            EmbeddingModelSelectable::class,
            moreInterfaces = arrayOf(ActiveModelReportingBackend::class),
        )
        every { backend.name } returns "Gemini API"
        every { backend.embeddingModelId } returns "gemini-embedding-001"
        every { (backend as ActiveModelReportingBackend).activeModelName } returns "gemini-2.5-flash"

        val selected = selected(service(selected = "gemini", backend = backend))

        assertEquals("gemini-2.5-flash", selected.chatModel)
    }

    @Test
    fun givenABackendWhoseAccessorsThrow_whenResolving_thenItsIdStandsInForItsName() {
        // Another plugin's code; a throwing accessor must cost one line, not the Preferences screen.
        val backend = mockk<EmbeddingModelSelectable> {
            every { name } throws IllegalStateException("wedged")
            every { embeddingModelId } throws IllegalStateException("wedged")
        }

        val selected = selected(service(selected = "gemini", backend = backend))

        assertEquals("gemini", selected.backendName)
        assertNull(selected.chatModel)
        assertEquals(EmbeddingSupport.None, selected.support)
    }

    /** Resolves [service], which the test set up to have a registered selection. */
    private fun selected(service: LlmInferenceService) =
        BackendCompatibilityResolver.resolve(service) as BackendCompatibility.Selected

    /** An inference service answering with [selected] and [backend], and nothing else. */
    private fun service(selected: String?, backend: LlmBackend? = null) =
        mockk<LlmInferenceService> {
            every { preferredBackendId } returns selected
            every { getBackend(any()) } returns backend
        }
}
