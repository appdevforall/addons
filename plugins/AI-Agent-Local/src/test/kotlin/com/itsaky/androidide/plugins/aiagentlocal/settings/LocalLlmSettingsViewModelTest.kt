package com.itsaky.androidide.plugins.aiagentlocal.settings

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.model.DeviceMemory
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelFileInfo
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelFileSource
import com.itsaky.androidide.plugins.aiagentlocal.model.SourceReachability
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The persisted read grant is the only thing keeping a model readable now that nothing is copied,
 * so releasing the wrong one strands a model the user is still running. These pin the grant
 * lifecycle across the paths that abandon a selection. See ADFA-5253.
 */
class LocalLlmSettingsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    /** Records what was granted and given back, and answers as the file source would. */
    private class FakeModelFiles : ModelFileSource {
        val persisted = mutableListOf<String>()
        val released = mutableListOf<String>()

        /** References the provider will not serve, standing in for a deleted document. */
        val unreadable = mutableSetOf<String>()

        /** References the provider does not answer for, standing in for one killed under pressure. */
        val silent = mutableSetOf<String>()

        /** Makes the lookup blow up, standing in for a provider that fails mid-selection. */
        var failInfo = false

        /** References the grant table has no room for, standing in for a full one. */
        val unpersistable = mutableSetOf<String>()

        /** References whose durable grant is no longer held, as a revoked one reads later. */
        val ungranted = mutableSetOf<String>()

        /** References the resolver will not answer the grant question for at all. */
        val grantUnknown = mutableSetOf<String>()

        /** Runs inside a readability probe, so a test can land a status while one is in flight. */
        var duringReadability: ((String) -> Unit)? = null

        override fun info(context: Context, uriString: String): ModelFileInfo {
            if (failInfo) throw IllegalStateException("provider failed")
            return ModelFileInfo(fallbackDisplayName(uriString), 1_024L)
        }

        override fun openStream(context: Context, uriString: String): InputStream? = null

        override fun readability(context: Context, uriString: String): SourceReachability {
            duringReadability?.invoke(uriString)
            return when (uriString) {
                in silent -> SourceReachability.UNKNOWN
                in unreadable -> SourceReachability.GONE
                else -> SourceReachability.REACHABLE
            }
        }

        override fun fallbackDisplayName(uriOrPath: String) = uriOrPath.substringAfterLast('/')

        override fun persistAccess(context: Context, uriString: String): Boolean {
            if (uriString in unpersistable) return false
            persisted += uriString
            return true
        }

        override fun hasPersistedAccess(context: Context, uriString: String): Boolean? =
            if (uriString in grantUnknown) null else uriString !in ungranted

        override fun releaseAccess(context: Context, uriString: String) {
            released += uriString
        }
    }

    private lateinit var stored: MutableMap<String, String?>
    private lateinit var storedSets: MutableMap<String, MutableSet<String>?>
    private lateinit var resolver: ContentResolver
    private lateinit var pluginContext: PluginContext
    private lateinit var modelFiles: FakeModelFiles

    @Before
    fun setup() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { Uri.decode(any()) } answers { firstArg() }

        stored = mutableMapOf()
        storedSets = mutableMapOf()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        every { prefs.getStringSet(any(), any()) } answers { storedSets[firstArg()] ?: mutableSetOf() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        every { editor.putStringSet(any(), any()) } answers {
            storedSets[firstArg()] = secondArg()
            editor
        }

        resolver = mockk(relaxed = true)
        // The GGUF sniff fails OPEN, so a pick is accepted unless a test serves other bytes.
        every { resolver.openInputStream(any()) } returns null
        val androidContext = mockk<Context>(relaxed = true)
        every { androidContext.contentResolver } returns resolver

        pluginContext = mockk(relaxed = true)
        every { pluginContext.androidContext } returns androidContext
        every { pluginContext.getPluginSharedPreferences(any()) } returns prefs

        modelFiles = FakeModelFiles()
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    /**
     * Unconfined, so every launch runs inline: nothing here suspends on a real dispatcher, and the
     * memory pre-flight fails open on the fake's unreadable header.
     */
    private fun viewModel(deviceMemory: DeviceMemory = DeviceMemory { null }) =
        LocalLlmSettingsViewModel(
            getContext = { pluginContext },
            ioDispatcher = Dispatchers.Unconfined,
            deviceMemory = deviceMemory,
            modelFiles = modelFiles,
        )

    @Test
    fun givenASelection_whenItIsKept_thenItsGrantIsPersistedAndStored() {
        val viewModel = viewModel()

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(listOf(MODEL_A), modelFiles.persisted)
        assertEquals(emptyList<String>(), modelFiles.released)
        assertEquals(MODEL_A, viewModel.getLocalModelPath())
        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
    }

    @Test
    fun givenAConfiguredModel_whenAnotherIsSelected_thenTheReplacedGrantIsHeldNotReleased() {
        // Nothing here rejects a model — isSeekable, isReopenable and the embedding-model guard all
        // run in the backend — so releasing now would strand a working model behind a pick that is
        // about to be refused. The backend gives it back once a model actually loads.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_A, MODEL_B), modelFiles.persisted)
        assertEquals("the replaced model must stay readable", emptyList<String>(), modelFiles.released)
        assertEquals(setOf(MODEL_A), storedSets[KEY_SUPERSEDED_MODELS])
        assertEquals(MODEL_B, viewModel.getLocalModelPath())
    }

    @Test
    fun givenAReplacedModel_whenItIsSelectedAgain_thenItIsNoLongerQueuedForRelease() {
        // How the user recovers from a pick the backend refused: the model they came back to is the
        // one to keep, and the refused one takes its place on the list.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        viewModel.loadModelFromUri(MODEL_B)

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(setOf(MODEL_B), storedSets[KEY_SUPERSEDED_MODELS])
        assertEquals(emptyList<String>(), modelFiles.released)
    }

    @Test
    fun givenAConfiguredModel_whenItIsReSelected_thenItsGrantIsNotReleased() {
        // "Load from saved" re-picks the configured model; releasing here would revoke the grant
        // on the model the user is still running.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(emptyList<String>(), modelFiles.released)
        assertEquals(MODEL_A, viewModel.getLocalModelPath())
    }

    @Test
    fun givenAConfiguredModelThatIsGone_whenItIsReSelected_thenItsGrantSurvivesTheFailure() {
        // The model may be on storage that is merely unmounted; re-mounting must not need a pick.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_A

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(emptyList<String>(), modelFiles.released)
        assertEquals(ModelLoadingState.Unavailable("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)
    }

    @Test
    fun givenANewSelectionThatIsRejected_thenItsOwnGrantIsGivenBackAndTheConfiguredOneKept() {
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_B

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals("the configured model must survive a failed pick", MODEL_A, viewModel.getLocalModelPath())
    }

    @Test
    fun givenANonGgufSelection_thenItIsRejectedWithoutBeingStoredAndItsGrantIsReleased() {
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        val viewModel = viewModel()

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals(null, viewModel.getLocalModelPath())
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)
    }

    @Test
    fun givenARejectedSelection_thenTheConfiguredModelsReadinessIsLeftAlone() {
        // The pane keys its "(unavailable)" marker off the engine status, so a rejected pick of
        // another file must leave it alone.
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        val viewModel = viewModel()
        stored[KEY_MODEL_PATH] = MODEL_A
        viewModel.refreshSavedModelAvailability()
        modelFiles.unreadable += MODEL_A
        viewModel.refreshSavedModelAvailability()
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)

        viewModel.loadModelFromUri(MODEL_B)

        // Not Initializing: the pick published that on its way in and never got anywhere.
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)
    }

    @Test
    fun givenAConfiguredModelThatWentAway_whenTheScreenReturns_thenItIsReportedUnavailable() {
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_A

        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Unavailable("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)
        assertEquals("a re-check must not touch the grant", emptyList<String>(), modelFiles.released)
    }

    @Test
    fun givenAModelThatCameBack_whenTheScreenReturns_thenItIsReportedReadyAgain() {
        // Unmounted storage comes back; the stale "unavailable" has to clear without a fresh pick.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_A
        viewModel.refreshSavedModelAvailability()

        modelFiles.unreadable -= MODEL_A
        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.Initialized, viewModel.state.value?.engine)
    }

    @Test
    fun givenARejectedPicksError_whenTheScreenReturnsAndTheModelReadsBack_thenItClears() {
        // The error described the pick; left standing it shows on every return to the screen.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        viewModel.loadModelFromUri(MODEL_B)
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)

        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.Initialized, viewModel.state.value?.engine)
    }

    @Test
    fun givenAConfiguredModelWhoseProviderIsSilent_whenTheScreenReturns_thenItsStatusIsLeftAlone() {
        // A resident multi-GB model is the pressure that kills a DocumentsProvider; reading that
        // silence as a deletion tells the user to re-pick a model that needs nothing.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.silent += MODEL_A

        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.Initialized, viewModel.state.value?.engine)
    }

    @Test
    fun givenAnErrorAboutTheConfiguredModel_whenTheScreenReturns_thenItStandsInsteadOfReadingAsLoaded() {
        // "Load from saved" refuses the configured model itself, and a readable stream is no answer
        // to why: clearing on that probe would report a model loaded that just would not load.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        viewModel.loadModelFromUri(MODEL_A)
        val refusal = viewModel.state.value?.model as ModelLoadingState.Error

        viewModel.refreshSavedModelAvailability()

        assertEquals(refusal.message, (viewModel.state.value?.model as ModelLoadingState.Error).message)
    }

    @Test
    fun givenAPickAbandonedBeforeItWasStored_thenItsGrantIsGivenBackByTheFinally() {
        // Grants are capped, and only the finally covers every way out of the selection.
        val viewModel = viewModel()

        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.persisted)
        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals(null, viewModel.getLocalModelPath())
    }

    @Test
    fun givenASelectionThatThrows_thenItsGrantIsStillGivenBack() {
        // Leaves through code no abandon path runs, as a cancellation at the dialog would.
        modelFiles.failInfo = true
        val viewModel = viewModel()

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.persisted)
        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)
    }

    @Test
    fun givenAModelDeclinedAtTheMemoryWarning_thenItsGrantIsGivenBackAndNothingIsStored() {
        val viewModel = viewModel(deviceMemory = DeviceMemory { 1L })
        viewModel.loadModelFromUri(MODEL_B)
        assertTrue("the pre-flight must be waiting on an answer", viewModel.hasPendingMemoryWarning)

        viewModel.onMemoryWarningDecision(false)

        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals(null, viewModel.getLocalModelPath())
    }

    @Test
    fun givenADeclineAtTheMemoryWarning_whenSomethingWasStoredMeanwhile_thenItIsNotReverted() {
        // The decline owns the two status lines and nothing else in the state.
        val viewModel = viewModel(deviceMemory = DeviceMemory { 1L })
        viewModel.loadModelFromUri(MODEL_B)
        viewModel.saveLocalModelPath(MODEL_A)

        viewModel.onMemoryWarningDecision(false)

        assertEquals(MODEL_A, viewModel.state.value?.savedModelPath)
    }

    @Test
    fun givenASelectionWhoseGrantCannotBePersisted_thenItIsKeptAndTheCaveatIsShown() {
        // Only logging it left the model working all session and failing every message after a
        // restart, with advice to re-pick a file that never moved.
        modelFiles.unpersistable += MODEL_A
        val viewModel = viewModel()

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(MODEL_A, viewModel.getLocalModelPath())
        assertEquals(
            ModelLoadingState.Loaded("a.gguf", accessPersisted = false),
            viewModel.state.value?.model,
        )
        assertEquals(EngineState.Initialized, viewModel.state.value?.engine)
    }

    @Test
    fun givenARefusalOfTheConfiguredModel_thenTheEngineLineCarriesItToo() {
        // Otherwise the pane draws "Engine ready" beside "isn't a valid .gguf", about one file.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }

        viewModel.loadModelFromUri(MODEL_A)

        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)
        assertEquals(EngineState.Error, viewModel.state.value?.engine)
    }

    @Test
    fun givenAnEngineReadingUnavailable_whenTheConfiguredModelIsRefused_thenItStopsSayingUnavailable() {
        // The mirror case: the model reads back fine, so "(unavailable)" must not outlive the probe
        // that disproved it — and nothing else can clear it, now that the error rightly stands.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_A
        viewModel.refreshSavedModelAvailability()
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)

        modelFiles.unreadable -= MODEL_A
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        viewModel.loadModelFromUri(MODEL_A)

        assertTrue(viewModel.state.value?.engine is EngineState.Error)
    }

    @Test
    fun givenARejectedPick_whenAReCheckStartedBeforeItLands_thenTheRejectionsErrorStands() {
        // The re-check's answer is only about the configured model, and it is the older one: it
        // used to overwrite the refusal with "Model loaded" and lose the only explanation.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.duringReadability = { probed ->
            if (probed == MODEL_A) {
                modelFiles.duringReadability = null
                every { resolver.openInputStream(any()) } answers {
                    ByteArrayInputStream("NOPE".toByteArray())
                }
                viewModel.loadModelFromUri(MODEL_B)
            }
        }

        viewModel.refreshSavedModelAvailability()

        val error = viewModel.state.value?.model as ModelLoadingState.Error
        assertEquals(MODEL_B, error.reference)
    }

    @Test
    fun givenAConfiguredModelWhoseGrantWasDropped_whenTheScreenReturns_thenTheCaveatIsShownAgain() {
        // The caveat used to be published only by the selection that took the grant, so leaving the
        // pane and coming back repainted a plain "Model loaded" for a model that will not survive a
        // restart. It is derived from the grants actually held, on every visit.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)

        modelFiles.ungranted += MODEL_A
        viewModel.refreshSavedModelAvailability()

        assertEquals(
            ModelLoadingState.Loaded("a.gguf", accessPersisted = false),
            viewModel.state.value?.model,
        )
        assertEquals(EngineState.Initialized, viewModel.state.value?.engine)
    }

    @Test
    fun givenAModelWhoseGrantWasTakenLater_whenTheScreenReturns_thenTheCaveatGoesAway() {
        // The mirror case: a caveat that outlived the grant table making room reads as a warning
        // about a selection that is now durable.
        modelFiles.unpersistable += MODEL_A
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        assertEquals(
            ModelLoadingState.Loaded("a.gguf", accessPersisted = false),
            viewModel.state.value?.model,
        )

        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
    }

    @Test
    fun givenAResolverThatCannotAnswer_whenTheScreenReturns_thenTheCaveatStands() {
        // "Could not tell" used to come back as "the grant is held", so a resolver that would not
        // answer erased a warning a real persistAccess failure had raised — and the restart it
        // warned about then broke the model with nothing on screen having said so.
        modelFiles.unpersistable += MODEL_A
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)

        modelFiles.grantUnknown += MODEL_A
        viewModel.refreshSavedModelAvailability()

        assertEquals(
            ModelLoadingState.Loaded("a.gguf", accessPersisted = false),
            viewModel.state.value?.model,
        )
    }

    private companion object {
        const val MODEL_A = "content://com.android.externalstorage.documents/document/a.gguf"
        const val MODEL_B = "content://com.android.externalstorage.documents/document/b.gguf"
        const val KEY_MODEL_PATH = "local_llm_model_path"
        const val KEY_SUPERSEDED_MODELS = "local_llm_superseded_models"
    }
}
