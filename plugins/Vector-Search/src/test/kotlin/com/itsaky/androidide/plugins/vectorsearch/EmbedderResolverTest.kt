package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbedderResolverTest {

    @Test
    fun givenNoService_whenResolving_thenNothingIsEmbedded() {
        assertEquals(EmbedderResolution.NoService, EmbedderResolver.resolve(null))
    }

    @Test
    fun givenNoStoredSelection_whenResolving_thenNothingIsEmbedded() {
        // Indexing a project ships its source to whichever provider is picked, so picking one for
        // the user would be a disclosure they never made. AI Core's own rule is the same.
        val service = service(selected = null)

        assertEquals(EmbedderResolution.NoSelection, EmbedderResolver.resolve(service))
    }

    @Test
    fun givenTheSelectedBackendIsNotRegistered_whenResolving_thenItIsReportedUnavailable() {
        val service = service(selected = "openai", backend = null)

        assertEquals(
            EmbedderResolution.Unavailable("openai"),
            EmbedderResolver.resolve(service),
        )
    }

    @Test
    fun givenABackendThatDoesNotEmbed_whenResolving_thenNoOtherBackendIsSubstituted() {
        // The on-device backend's permanent answer. Reaching past it to a cloud one that happens to
        // embed would send the whole project to a provider the user did not choose.
        val service = service(selected = "local", backend = mockk<LlmBackend>(relaxed = true))

        assertEquals(
            EmbedderResolution.NotEmbeddingCapable("local"),
            EmbedderResolver.resolve(service),
        )
    }

    @Test
    fun givenAnUnconfiguredEmbeddingBackend_whenResolving_thenItIsReportedUnavailable() {
        val service = service(
            selected = "openai",
            backend = embeddingBackend(available = false, modelId = "text-embedding-3-small"),
        )

        assertEquals(
            EmbedderResolution.Unavailable("openai"),
            EmbedderResolver.resolve(service),
        )
    }

    @Test
    fun givenABackendThatNamesNoModel_whenResolving_thenItIsReportedUnusable() {
        // An identity without a model id cannot detect a model swap, so a blank one is refused
        // rather than stored as part of the provenance.
        val service = service(selected = "openai", backend = embeddingBackend(modelId = "  "))

        val resolution = EmbedderResolver.resolve(service)

        assertEquals("openai", (resolution as EmbedderResolution.Unusable).backendId)
    }

    @Test
    fun givenAConfiguredEmbeddingBackend_whenResolving_thenItsIdentityNamesTheModel() {
        val backend = embeddingBackend(modelId = " text-embedding-3-small ")
        val service = service(selected = "openai", backend = backend)

        val resolution = EmbedderResolver.resolve(service)

        assertEquals(
            EmbedderResolution.Ready(backend, EmbedderKey("openai", "text-embedding-3-small")),
            resolution,
        )
    }

    @Test
    fun givenABackendWhoseAccessorThrows_whenResolving_thenTheFailureIsContained() {
        // The backend object is another plugin's code; a throwing accessor must cost a search
        // result rather than the host's whole project-search request.
        val backend = mockk<EmbeddingBackend> {
            every { isAvailable } returns true
            every { embeddingModelId } throws IllegalStateException("wedged")
        }
        val service = service(selected = "openai", backend = backend)

        val resolution = EmbedderResolver.resolve(service)

        assertEquals("openai", (resolution as EmbedderResolution.Unusable).backendId)
    }

    /** An inference service answering with [selected] and [backend], and nothing else. */
    private fun service(selected: String?, backend: LlmBackend? = null) =
        mockk<LlmInferenceService> {
            every { preferredBackendId } returns selected
            every { getBackend(any()) } returns backend
        }

    /** An embedding-capable backend that answers but never actually embeds. */
    private fun embeddingBackend(
        available: Boolean = true,
        modelId: String,
    ) = mockk<EmbeddingBackend> {
        every { isAvailable } returns available
        every { embeddingModelId } returns modelId
        every { embeddingDimensions } returns 0
        every { embed(any()) } returns CompletableFuture.completedFuture(emptyList())
    }
}
