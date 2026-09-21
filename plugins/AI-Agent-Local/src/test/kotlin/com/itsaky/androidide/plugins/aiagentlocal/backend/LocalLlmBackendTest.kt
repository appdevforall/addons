package com.itsaky.androidide.plugins.aiagentlocal.backend

import android.content.Context
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.feedback.IncompatibleModelException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.ModelLoadException
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufTestFiles
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadDiagnostics.Diagnosis
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelSourceWatcher
import com.itsaky.androidide.plugins.aiagentlocal.model.NativeModelSource
import com.itsaky.androidide.plugins.aiagentlocal.model.OpenModelFile
import com.itsaky.androidide.plugins.aiagentlocal.model.SourceReachability
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import io.mockk.every
import io.mockk.mockk
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLlmBackendTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var pluginContext: PluginContext
    private lateinit var backend: LocalLlmBackend

    /** Records that the model's descriptor was released, which is the fd leak we can catch here. */
    private class RecordingDescriptor : Closeable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    /** Serves prepared handles; anything not in [handles] is a model that cannot be reached. */
    private class FakeModelSource(private val handles: Map<String, OpenModelFile>) : NativeModelSource {
        var openCount = 0

        /** Flipped to simulate the user deleting the file out from under a resident model. */
        var reachable = true

        /** What the probe answers when [reachable] is false: a deletion, or a provider that died. */
        var whenUnreachable = SourceReachability.GONE

        /** Reachability probes served, so a burst of watch notifications can be counted. */
        @Volatile var probeCount = 0

        /** Read grants given back, which only a load that succeeded may do. */
        val released = mutableListOf<String>()

        override fun open(modelReference: String): OpenModelFile? {
            openCount++
            return handles[modelReference].takeIf { reachable }
        }

        override fun reachabilityOf(modelReference: String): SourceReachability {
            probeCount++
            return if (reachable && handles.containsKey(modelReference)) SourceReachability.REACHABLE
            else whenUnreachable
        }

        override fun releaseAccess(modelReference: String) {
            released += modelReference
        }
    }

    /** Stands in for the native engine, recording residency without loading any weights. */
    private class FakeEngine : ModelResidencyEngine {
        var loadCount = 0
        var unloadCount = 0

        /** The context size the backend sized for the last load, so the sizing is observable. */
        var lastContextTokens = 0

        /** Whether the last load asked for a quantized KV cache, so that choice is observable. */
        var lastQuantizeKv = false

        override suspend fun load(
            nativePath: String,
            contextTokens: Int,
            quantizeKv: Boolean,
            fallbackContextTokens: Int,
        ) {
            loadCount++
            lastContextTokens = contextTokens
            lastQuantizeKv = quantizeKv
        }

        override suspend fun unload() {
            unloadCount++
        }

        override suspend fun contextSize() = lastContextTokens
    }

    /** Captures the delete callback so a test can fire it the way the platform would. */
    private class FakeWatcher : ModelSourceWatcher {
        var onGone: (() -> Unit)? = null
        var closed = false
        override fun watch(modelReference: String, onGone: () -> Unit) = Closeable {
            closed = true
        }.also { this.onGone = onGone }
    }

    @Before
    fun setup() {
        filesDir = temporaryFolder.newFolder("files")
        val androidContext = mockk<Context>(relaxed = true)
        every { androidContext.filesDir } returns filesDir
        pluginContext = mockk(relaxed = true)
        every { pluginContext.androidContext } returns androidContext
        backend = LocalLlmBackend(pluginContext)
    }

    private fun backendWith(source: NativeModelSource) = LocalLlmBackend(pluginContext, source)

    private fun backendWith(
        source: NativeModelSource,
        engine: ModelResidencyEngine,
        watcher: ModelSourceWatcher = FakeWatcher(),
    ) = LocalLlmBackend(pluginContext, source, engine, watcher)

    @Test
    fun testBackendId() {
        assertEquals("local", backend.getId())
    }

    @Test
    fun testBackendName() {
        assertEquals("Local LLM", backend.getName())
    }

    @Test
    fun testIsAvailableWhenNotInitialized() {
        // Backend requires model initialization, should be false initially
        assertFalse(backend.isAvailable())
    }

    @Test
    fun givenTheBackend_whenAskedForItsCapabilities_thenItDeclaresHistoryButNotToolCalling() {
        // Dropping HistoryCapableBackend compiles and silently turns chat into one-shot prompting.
        val declared: LlmBackend = backend

        assertTrue(declared is HistoryCapableBackend)
        assertFalse(declared is ToolCallingBackend)
    }

    @Test
    fun testGenerateReturnsErrorWhenNotAvailable() {
        val config = LlmConfig("local")
        val future = backend.generate("Test prompt", config)
        val response = future.get()

        assertFalse(response.success)
        assertNotNull(response.error)
        // With no model configured, generate() fails fast before any native work.
        assertTrue(response.error!!.contains("No model configured"))
    }

    @Test
    fun givenAContentUriThatCannotBeOpened_whenLoading_thenFailsAsSourceUnavailable() {
        // The model is read in place now, so a revoked grant or a deleted document is the most
        // likely failure of all — and must not surface as "your model is corrupt".
        val source = FakeModelSource(emptyMap())

        val error = assertThrows(ModelLoadException::class.java) {
            runBlocking { backendWith(source).ensureModelLoaded(CONTENT_URI) }
        }

        assertEquals(Diagnosis.SourceUnavailable, error.diagnosis)
        assertEquals(1, source.openCount)
    }

    @Test
    fun givenAConfiguredPathThatIsGone_whenLoading_thenFailsAsFileMissing() {
        // A plain path survives from before the picker; "file not found" is still its right answer.
        val error = assertThrows(ModelLoadException::class.java) {
            runBlocking { backendWith(FakeModelSource(emptyMap())).ensureModelLoaded("/sdcard/model.gguf") }
        }

        assertEquals(Diagnosis.FileMissing, error.diagnosis)
    }

    @Test
    fun givenAStreamingDocument_whenLoading_thenRefusedAsNotSeekableWithoutReadingIt() {
        // A cloud provider hands back a pipe, whose bytes the header reads would consume before
        // llama.cpp sees any: refuse it with its own advice rather than call it corrupt.
        val descriptor = RecordingDescriptor()
        val pipe = OpenModelFile("/proc/self/fd/7", -1L, descriptor)
        val source = FakeModelSource(mapOf(CONTENT_URI to pipe))
        val engine = FakeEngine()

        val error = assertThrows(ModelLoadException::class.java) {
            runBlocking { backendWith(source, engine).ensureModelLoaded(CONTENT_URI) }
        }

        assertEquals(Diagnosis.SourceNotSeekable, error.diagnosis)
        assertEquals("nothing may reach the engine", 0, engine.loadCount)
        assertTrue("the refused descriptor must not leak", descriptor.closed)
    }

    @Test
    fun givenAResidentModel_whenItsWatchFiresRepeatedly_thenOnlyOneCheckIsQueued() {
        // One coroutine per notification would pile up behind generationMutex, each waking to
        // issue its own binder probe; the gate collapses a burst to a single check.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val engine = FakeEngine()
        val watcher = FakeWatcher()
        val backend = backendWith(source, engine, watcher)

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        val before = source.probeCount
        repeat(50) { watcher.onGone!!.invoke() }

        Thread.sleep(300)
        val probes = source.probeCount - before
        // Not exactly one: a notification arriving just after a check rightly starts another.
        assertTrue("a burst of 50 notifications cost $probes probes", probes in 1..5)
        assertEquals("a reachable model must stay loaded", 0, engine.unloadCount)
    }

    @Test
    fun givenAnEmbeddingModel_whenLoading_thenRejectedBeforeAnyNativeWork() {
        // ADFA-4388: the classify guard must still fire when the header arrives as a stream over a
        // document read in place. Reaching native code here would abort the whole IDE.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(GgufTestFiles.withArchitecture("bert"))))

        assertThrows(IncompatibleModelException::class.java) {
            runBlocking { backendWith(source).ensureModelLoaded(CONTENT_URI) }
        }
    }

    @Test
    fun givenARejectedModel_whenLoading_thenItsDescriptorIsReleased() {
        // A held descriptor that is never adopted leaks one fd per attempt, and the warm-up retries.
        val descriptor = RecordingDescriptor()
        val handle = handleFor(GgufTestFiles.withArchitecture("bert"), descriptor)
        val source = FakeModelSource(mapOf(CONTENT_URI to handle))

        assertThrows(IncompatibleModelException::class.java) {
            runBlocking { backendWith(source).ensureModelLoaded(CONTENT_URI) }
        }

        assertTrue("the rejected model's descriptor must not leak", descriptor.closed)
    }

    @Test
    fun givenAContentUriModel_whenLoading_thenNothingIsWrittenToInternalStorage() {
        // AC 3, as far as a JVM test can honestly go: resolving a picked model must not copy it.
        // The device check is `du -sh .../files/llm-models` before and after a real selection.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(GgufTestFiles.withArchitecture("bert"))))

        assertThrows(IncompatibleModelException::class.java) {
            runBlocking { backendWith(source).ensureModelLoaded(CONTENT_URI) }
        }

        assertEquals(emptyList<String>(), filesDir.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun givenModelCopiesFromAnEarlierRelease_whenCleaningUp_thenTheyAreDeleted() {
        // Without this the ticket saves nothing for anyone who already used the plugin.
        val legacyDir = File(filesDir, "llm-models").apply { mkdirs() }
        File(legacyDir, "1234_5678_model.gguf").writeBytes(ByteArray(4096))

        runBlocking { backendWith(FakeModelSource(emptyMap())).deleteLegacyModelCache() }

        assertFalse(legacyDir.exists())
    }

    @Test
    fun givenNoLegacyModelCache_whenCleaningUp_thenItIsAQuietNoOp() {
        // Runs on every activation, so the common case must neither throw nor create the directory.
        runBlocking { backendWith(FakeModelSource(emptyMap())).deleteLegacyModelCache() }

        assertFalse(File(filesDir, "llm-models").exists())
    }

    @Test
    fun givenAResidentModelWhoseFileWasDeleted_whenGenerating_thenItIsUnloadedAndReported() {
        // The descriptor keeps the deleted inode alive, so without the reachability check the
        // model answers happily from a file the user threw away. ADFA-5253.
        val descriptor = RecordingDescriptor()
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel(), descriptor)))
        val engine = FakeEngine()
        val backend = backendWith(source, engine)

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        source.reachable = false

        val error = assertThrows(ModelLoadException::class.java) {
            runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        }

        assertEquals(Diagnosis.SourceUnavailable, error.diagnosis)
        assertEquals("the model's pages must be freed, not just refused", 1, engine.unloadCount)
        assertTrue("the descriptor must be released or the inode stays alive", descriptor.closed)
    }

    @Test
    fun givenAResidentModel_whenTheProviderStopsAnswering_thenItKeepsServing() {
        // A dead provider process says nothing about the document; evicting costs a GB reload.
        val descriptor = RecordingDescriptor()
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel(), descriptor)))
        val engine = FakeEngine()
        val backend = backendWith(source, engine)

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        source.reachable = false
        source.whenUnreachable = SourceReachability.UNKNOWN

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }

        assertEquals("a silent provider must not cost a reload", 1, engine.loadCount)
        assertEquals("a silent provider must not evict the model", 0, engine.unloadCount)
        assertFalse("the descriptor must stay open", descriptor.closed)
    }

    @Test
    fun givenAProviderThatOnlyEverAnswersUnknown_whenGeneratingAgain_thenItIsNotReProbed() {
        // A provider wedged inside openFileDescriptor never yields REACHABLE, so arming the trust
        // window on REACHABLE alone left every message paying the probe, under generationMutex.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val backend = backendWith(source, FakeEngine())

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        source.reachable = false
        source.whenUnreachable = SourceReachability.UNKNOWN
        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        val after = source.probeCount

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }

        assertEquals("an answered probe stands in for the next ones", after, source.probeCount)
    }

    @Test
    fun givenAResidentModel_whenItsWatchFiresAndTheProviderIsSilent_thenItStaysLoaded() {
        // The same distinction on the watch path, where a burst of notifications arrives.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val engine = FakeEngine()
        val watcher = FakeWatcher()
        val backend = backendWith(source, engine, watcher)

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        source.reachable = false
        source.whenUnreachable = SourceReachability.UNKNOWN
        watcher.onGone!!.invoke()

        Thread.sleep(200)
        assertEquals("an unanswered probe must not unload the model", 0, engine.unloadCount)
    }

    @Test
    fun givenADocumentThatCannotBeReopenedByPath_whenLoading_thenRefusedWithItsOwnAdvice() {
        // Refused before native code, or it lands on "pick the model again" for a file that is there.
        // Named, not an fd number: a test worker holds hundreds, so /proc/self/fd/N often opens.
        val descriptor = RecordingDescriptor()
        val missing = File(temporaryFolder.root, "never-created/model.gguf").absolutePath
        val unreadable = OpenModelFile(missing, 4_096L, descriptor)
        val source = FakeModelSource(mapOf(CONTENT_URI to unreadable))
        val engine = FakeEngine()

        val error = assertThrows(ModelLoadException::class.java) {
            runBlocking { backendWith(source, engine).ensureModelLoaded(CONTENT_URI) }
        }

        assertEquals(Diagnosis.SourceNotReopenable, error.diagnosis)
        assertEquals("nothing may reach the engine", 0, engine.loadCount)
        assertTrue("the refused descriptor must not leak", descriptor.closed)
    }

    @Test
    fun givenAConfiguredModelThatFailsToLoad_whenAvailabilityIsAskedRepeatedly_thenItIsTriedOnce() {
        // A warm-up re-armed on failure launches one doomed load per isAvailable() call.
        configureModelPath(CONTENT_URI)
        // Not in the fake's handles, so the warm-up fails the way an unreachable model does.
        val source = FakeModelSource(emptyMap())
        val backend = backendWith(source, FakeEngine())

        repeat(5) { backend.isAvailable() }

        Thread.sleep(300)
        assertEquals("five asks must cost one warm-up attempt", 1, source.openCount)
    }

    @Test
    fun givenAFailedWarmUp_whenAnotherModelIsSelected_thenTheWarmUpIsTriedAgain() {
        // Keyed on the reference, not disabled outright: a new selection has to be warmed.
        configureModelPath(CONTENT_URI)
        val source = FakeModelSource(emptyMap())
        val backend = backendWith(source, FakeEngine())
        backend.isAvailable()

        configureModelPath(OTHER_CONTENT_URI)
        backend.isAvailable()

        Thread.sleep(300)
        assertEquals("a different selection must re-arm the warm-up", 2, source.openCount)
    }

    @Test
    fun givenAResidentModelStillOnDisk_whenGenerating_thenItIsServedWithoutReloading() {
        // The check must not cost a reload: a document's procfs path differs on every open, and
        // reloading gigabytes per message would be far worse than the bug it fixes.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val engine = FakeEngine()
        val backend = backendWith(source, engine)

        runBlocking {
            backend.ensureModelLoaded(CONTENT_URI)
            backend.ensureModelLoaded(CONTENT_URI)
            backend.ensureModelLoaded(CONTENT_URI)
        }

        assertEquals(1, engine.loadCount)
        assertEquals(0, engine.unloadCount)
    }

    @Test
    fun givenAResidentModel_whenItsWatchFires_thenItIsUnloadedWithoutWaitingForAMessage() {
        // Checkpoint 3: the gigabytes come back at deletion time, not at the user's next message.
        val descriptor = RecordingDescriptor()
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel(), descriptor)))
        val engine = FakeEngine()
        val watcher = FakeWatcher()
        val backend = backendWith(source, engine, watcher)

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        source.reachable = false
        watcher.onGone!!.invoke()

        awaitUnload(engine)
        assertTrue("the descriptor must be released or the inode stays alive", descriptor.closed)
    }

    @Test
    fun givenAResidentModelThatIsStillThere_whenItsWatchFiresForAnEdit_thenItStaysLoaded() {
        // Providers notify for edits and for the whole tree, so a notification is a hint. Acting
        // on it unconfirmed would unload a working model mid-conversation.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val engine = FakeEngine()
        val watcher = FakeWatcher()
        val backend = backendWith(source, engine, watcher)

        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }
        watcher.onGone!!.invoke()

        Thread.sleep(200)
        assertEquals("a spurious notification must not unload a reachable model", 0, engine.unloadCount)
    }

    @Test
    fun givenAResidentModelJustProbed_whenGeneratingAgain_thenTheProviderIsNotAskedEveryTime() {
        // The probe is a blocking binder call holding generationMutex, and a provider that hangs
        // rather than dies cannot be cancelled out of: bound it to one message, not the session.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val backend = backendWith(source, FakeEngine())

        runBlocking {
            backend.ensureModelLoaded(CONTENT_URI)
            repeat(4) { backend.ensureModelLoaded(CONTENT_URI) }
        }

        assertEquals("a fresh REACHABLE answer must stand in for the next probe", 1, source.probeCount)
    }

    /** The eviction runs on the backend's own cleanup scope, so the test waits for it. */
    @Test
    fun givenAReplacedModel_whenTheNewOneLoads_thenItsReadGrantIsGivenBack() {
        // Deferred to here from the selection: the settings pane keeps the grant so a pick this
        // method refuses cannot cost the user the model they were running (ADFA-5253).
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val pending = supersede(OTHER_CONTENT_URI)

        runBlocking { backendWith(source, FakeEngine()).ensureModelLoaded(CONTENT_URI) }

        assertEquals(listOf(OTHER_CONTENT_URI), source.released)
        assertEquals("a released grant must not be released again", emptySet<String>(), pending[KEY_SUPERSEDED])
    }

    @Test
    fun givenAReplacedModel_whenTheNewOneIsRefused_thenItsReadGrantSurvives() {
        // The whole point: an embedding model gets past the settings pane's checks and is refused
        // here, and the working model it replaced has to still be readable afterwards.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(GgufTestFiles.withArchitecture("bert"))))
        val pending = supersede(OTHER_CONTENT_URI)

        assertThrows(IncompatibleModelException::class.java) {
            runBlocking { backendWith(source).ensureModelLoaded(CONTENT_URI) }
        }

        assertEquals(emptyList<String>(), source.released)
        assertEquals(setOf(OTHER_CONTENT_URI), pending[KEY_SUPERSEDED])
    }

    @Test
    fun givenTheLoadedModelItselfOnTheList_whenItLoads_thenItsOwnGrantIsNotReleased() {
        // Re-picking a model that had been replaced takes it off the list; releasing it here would
        // revoke the grant on the model that just loaded.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val pending = supersede(CONTENT_URI)

        runBlocking { backendWith(source, FakeEngine()).ensureModelLoaded(CONTENT_URI) }

        assertEquals(emptyList<String>(), source.released)
        // Kept on the list rather than cleared with the rest: a generation that read the old path
        // can load it after the pane already configured another, and the list is the only record
        // of a grant to give back when that other model loads.
        assertEquals(setOf(CONTENT_URI), pending[KEY_SUPERSEDED])
    }

    @Test
    fun givenAModelAlreadyResident_whenItIsAskedForAgain_thenAReplacedModelsGrantIsStillGivenBack() {
        // The recovery path: A is resident, an embedding model is picked and refused, and re-picking
        // A finds it resident. That never reaches the load, so this is the only place the refused
        // model's grant can come back — it used to be held for the life of the process.
        val source = FakeModelSource(mapOf(CONTENT_URI to handleFor(chatModel())))
        val pending = supersede()
        val backend = backendWith(source, FakeEngine())
        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }

        pending[KEY_SUPERSEDED] = mutableSetOf(OTHER_CONTENT_URI)
        runBlocking { backend.ensureModelLoaded(CONTENT_URI) }

        assertEquals(listOf(OTHER_CONTENT_URI), source.released)
        assertEquals(emptySet<String>(), pending[KEY_SUPERSEDED])
    }

    private fun awaitUnload(engine: FakeEngine) {
        val deadline = System.currentTimeMillis() + 2000
        while (engine.unloadCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("the deleted model must be unloaded", 1, engine.unloadCount)
    }

    /** A minimal GGUF that passes the ADFA-4388 embedding guard, so loads reach the engine. */
    private fun chatModel(): File = GgufTestFiles.withArchitecture("qwen2")

    /** Points the backend's preferences at [modelRef], the way a saved selection does. */
    private fun configureModelPath(modelRef: String) {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString("local_llm_model_path", any()) } returns modelRef
        every { pluginContext.getPluginSharedPreferences(any()) } returns prefs
    }

    /**
     * Puts [references] on the list of models a later selection replaced, the way the settings pane
     * does, and hands back the store so the test can read what is left on it.
     */
    private fun supersede(vararg references: String): MutableMap<String, MutableSet<String>?> {
        val sets = mutableMapOf<String, MutableSet<String>?>(KEY_SUPERSEDED to references.toMutableSet())
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        every { prefs.getStringSet(any(), any()) } answers { sets[firstArg()] ?: mutableSetOf() }
        every { prefs.edit() } returns editor
        every { editor.putStringSet(any(), any()) } answers {
            sets[firstArg()] = secondArg()
            editor
        }
        every { pluginContext.getPluginSharedPreferences(any()) } returns prefs
        return sets
    }

    private fun handleFor(file: File, descriptor: Closeable? = null) =
        OpenModelFile(file.absolutePath, file.length(), descriptor)

    private companion object {
        const val CONTENT_URI = "content://com.android.externalstorage.documents/document/model.gguf"
        const val OTHER_CONTENT_URI = "content://com.android.externalstorage.documents/document/other.gguf"
        const val KEY_SUPERSEDED = "local_llm_superseded_models"
    }
}
