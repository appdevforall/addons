package com.itsaky.androidide.plugins.vectorsearch.settings

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingModelSelectable
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import com.itsaky.androidide.plugins.vectorsearch.IndexedEmbedder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun givenTheServerChangedUnderTheSameBackend_whenItReportsAChange_thenItsModelsAreListedAgain() {
        // A new OpenAI server or Gemini key keeps the backend id but changes what it can embed with.
        val backend = selectable(lists = listOf(listOf("old-model"), listOf("new-model")))
        val source = FakeSource(serviceSelecting(backend))
        val viewModel = viewModel(source)
        assertEquals(ModelOptions.Loaded("gemini", listOf("old-model")), viewModel.state.value.models)

        source.backendChanges.value = 1

        assertEquals(ModelOptions.Loaded("gemini", listOf("new-model")), viewModel.state.value.models)
    }

    @Test
    fun givenModelsOfAnotherBackend_whenTheSelectionHasMoved_thenTheyAreNotOffered() {
        val state = SemanticSearchState(
            compatibility = BackendCompatibility.Selected(
                "openai", "OpenAI", chatModel = null, support = EmbeddingSupport.None,
            ),
            models = ModelOptions.Loaded("gemini", listOf("gemini-embedding-001")),
        )

        assertEquals(ModelOptions.None, state.selectedModels)
    }

    @Test
    fun givenTheListingFails_whenLoading_thenTheBackendsOwnReasonIsKept() {
        val backend = selectable(failure = IOException("No Gemini API key is saved."))
        val viewModel = viewModel(FakeSource(serviceSelecting(backend)))

        assertEquals(
            ModelOptions.Failed("gemini", "No Gemini API key is saved."),
            viewModel.state.value.models,
        )
    }

    @Test
    fun givenAPick_whenTheBackendReportsIt_thenTheModelIsStoredAndTheRebuildStaysAnnounced() {
        val backend = selectable(lists = listOf(listOf("a", "b")))
        val source = FakeSource(serviceSelecting(backend))
        val viewModel = viewModel(source)

        viewModel.selectModel("b")
        source.backendChanges.value = 1

        verify { backend.setEmbeddingModelId("b") }
        assertEquals("b", viewModel.state.value.pickedModel)
    }

    @Test
    fun givenABackendThatIsNotSelectable_whenRead_thenNothingIsListed() {
        val backend = mockk<LlmBackend>(relaxed = true) { every { name } returns "Local LLM" }
        val viewModel = viewModel(FakeSource(serviceSelecting(backend, id = "local")))

        assertEquals(ModelOptions.None, viewModel.state.value.models)
    }

    @Test
    fun givenAConfirmedClear_whenItCompletes_thenTheIndexReadsNotIndexed() {
        val source = FakeSource(service = null).apply {
            status = IndexStatus.Indexed(
                listOf(IndexedEmbedder("gemini", "m", 768, 3)), fileCount = 1, lastBuiltAt = null,
            )
            holdsAnything = true
        }
        val viewModel = viewModel(source)

        viewModel.clearIndex()

        assertEquals(1, source.clears)
        assertEquals(IndexStatus.NotIndexed, viewModel.state.value.indexStatus)
        assertFalse(viewModel.state.value.clearing)
        assertFalse(viewModel.state.value.canClear)
    }

    @Test
    fun givenABuildRunning_whenRead_thenItCanBeClearedEvenWithNoRowsYet() {
        // Clearing stops the build, so there is something to do before a single row is stored.
        val source = FakeSource(service = null).apply { status = IndexStatus.Building(0, 0) }

        val viewModel = viewModel(source)

        assertTrue(viewModel.state.value.canClear)
    }

    @Test
    fun givenNoPluginAtCreation_whenItLoadsAndTheScreenRefreshes_thenItsIndexChangesAreWatched() {
        var live: FakeSource? = null
        val viewModel = SemanticSearchSettingsViewModel({ live }, ioDispatcher = dispatcher)
        val source = FakeSource(service = null).also { live = it }

        viewModel.refresh()
        source.status = IndexStatus.Building(0, 0)
        source.indexChanges.value = 1

        assertEquals(IndexStatus.Building(0, 0), viewModel.state.value.indexStatus)
    }

    private fun viewModel(source: FakeSource) =
        SemanticSearchSettingsViewModel({ source }, ioDispatcher = dispatcher)

    /** A selectable backend whose model follows its setter, listing [lists] in turn. */
    private fun selectable(
        lists: List<List<String>> = emptyList(),
        failure: Exception? = null,
    ): EmbeddingModelSelectable {
        var current = "a"
        var call = 0
        return mockk(relaxed = true) {
            every { name } returns "Gemini API"
            every { embeddingModelId } answers { current }
            every { setEmbeddingModelId(any()) } answers { current = firstArg() }
            every { listEmbeddingModels() } answers {
                if (failure != null) {
                    CompletableFuture<List<String>>().apply { completeExceptionally(failure) }
                } else {
                    CompletableFuture.completedFuture(lists[minOf(call++, lists.lastIndex)])
                }
            }
        }
    }

    private fun serviceSelecting(backend: LlmBackend, id: String = "gemini") =
        mockk<LlmInferenceService> {
            every { preferredBackendId } returns id
            every { getBackend(id) } returns backend
        }

    /** A [SemanticSearchSource] the test drives by hand. */
    private class FakeSource(private val service: LlmInferenceService?) : SemanticSearchSource {
        override val backendChanges = MutableStateFlow(0L)
        override val indexChanges = MutableStateFlow(0L)
        var status: IndexStatus = IndexStatus.NotIndexed
        var holdsAnything = false
        var clears = 0

        override fun inferenceService(): LlmInferenceService? = service

        override fun indexStatus(): IndexStatus = status

        override fun indexHoldsAnything(): Boolean = holdsAnything

        override fun clearIndex(): Job {
            clears++
            status = IndexStatus.NotIndexed
            holdsAnything = false
            return Job().apply { complete() }
        }

        override fun logger(): PluginLogger? = null
    }
}
