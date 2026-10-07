package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingBackend
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexCoordinatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val identity = EmbedderIdentity(EmbedderKey("gemini", "gemini-embedding-001"), 2)
    private val store = mockk<EmbeddingIndexingService>(relaxed = true)
    private val buildLog = FakeBuildLog()
    private val activities = mutableListOf<BuildActivity>()
    private val coordinator: IndexCoordinator

    init {
        coordinator = IndexCoordinator(
            store = store,
            buildLog = buildLog,
            logger = { null },
            onIndexChanged = { activities += coordinator.activity },
            clock = { 1_000L },
        )
    }

    /** The first embed call never answers, standing for a slow server mid-build. */
    private val stalled = CompletableFuture<List<FloatArray>>()
    private val embedCalls = AtomicInteger()
    private val backend = mockk<EmbeddingBackend> {
        every { embed(any()) } answers {
            val texts = firstArg<List<String>>()
            if (embedCalls.getAndIncrement() == 0) stalled
            else CompletableFuture.completedFuture(texts.map { floatArrayOf(1f, 0f) })
        }
    }

    private fun givenAProjectWithOneFile(storedModels: List<String> = emptyList()): File {
        val source = folder.newFile("Main.kt").apply { writeText("fun main() {\n    println(1)\n}\n") }
        every { store.collectFiles(any()) } returns listOf(source)
        every { store.languageFor(any()) } returns "kotlin"
        every { store.countEmbeddings(any(), any()) } returns 0
        every { store.storedModels(any()) } returns storedModels
        return folder.root
    }

    @Test
    fun givenABuildInFlight_whenCleared_thenNothingIsStoredAndEveryRowIsDeleted() = runBlocking {
        // AC11: the build is stopped before the delete, so none of its rows outlive the clear.
        val root = givenAProjectWithOneFile()
        val build = coordinator.buildIfNeeded("roots", listOf(root), backend, identity)!!
        verify(timeout = 5_000) { backend.embed(any()) }

        withTimeout(5_000) { coordinator.clearAll().join() }

        assertTrue(build.job.isCancelled)
        assertTrue(stalled.isCancelled)
        verify(exactly = 0) { store.storeEmbeddings(any(), any()) }
        verifyOrder {
            store.clearIndex("roots")
            store.clearIndex(null)
        }
    }

    @Test
    fun givenAClearInFlight_whenASearchStartsABuild_thenTheBuildWritesOnlyAfterTheDelete() = runBlocking {
        val root = givenAProjectWithOneFile()
        coordinator.buildIfNeeded("roots", listOf(root), backend, identity)
        verify(timeout = 5_000) { backend.embed(any()) }

        coordinator.clearAll()
        val rebuild = coordinator.buildIfNeeded("roots", listOf(root), backend, identity)!!
        withTimeout(5_000) { rebuild.job.join() }

        verifyOrder {
            store.clearIndex(null)
            store.storeEmbeddings("roots", any())
        }
    }

    @Test
    fun givenABuild_whenItCompletes_thenProgressWasReportedAndItsTimeRecorded() = runBlocking {
        val root = givenAProjectWithOneFile()
        // Answered at once, so the build runs to the end.
        embedCalls.set(1)

        withTimeout(5_000) {
            coordinator.buildIfNeeded("roots", listOf(root), backend, identity)!!.job.join()
        }

        assertEquals(BuildActivity.Building("roots", stored = 0, total = 0), activities.first())
        assertTrue(activities.any { it is BuildActivity.Building && it.stored > 0 })
        assertEquals(BuildActivity.Idle, coordinator.activity)
        assertEquals(mapOf("roots" to 1_000L), buildLog.built)
    }

    @Test
    fun givenABackendThatRefuses_whenBuilding_thenTheBuildIsReportedFailedAndNotRecorded() =
        runBlocking {
            val root = givenAProjectWithOneFile()
            stalled.completeExceptionally(IllegalStateException("quota exhausted"))

            withTimeout(5_000) {
                coordinator.buildIfNeeded("roots", listOf(root), backend, identity)!!.job.join()
            }

            assertEquals(BuildActivity.Failed("roots"), coordinator.activity)
            assertTrue(buildLog.built.isEmpty())
        }

    @Test
    fun givenAFailedBuild_whenCleared_thenTheFailureAndEveryBuildTimeAreForgotten() = runBlocking {
        val root = givenAProjectWithOneFile()
        stalled.completeExceptionally(IllegalStateException("quota exhausted"))
        withTimeout(5_000) {
            coordinator.buildIfNeeded("roots", listOf(root), backend, identity)!!.job.join()
        }

        withTimeout(5_000) { coordinator.clearAll().join() }

        assertEquals(BuildActivity.Idle, coordinator.activity)
        assertTrue(buildLog.cleared)
    }

    @Test
    fun givenRowsOfAnotherModel_whenBuilding_thenTheBuildNamesTheModelItReplaces() {
        val root = givenAProjectWithOneFile(storedModels = listOf("text-embedding-004"))

        val build = coordinator.buildIfNeeded("roots", listOf(root), backend, identity)!!

        assertEquals("text-embedding-004", build.replacedModel)
    }

    @Test
    fun givenNoRowsYet_whenBuilding_thenNothingIsReportedReplaced() {
        val root = givenAProjectWithOneFile()

        val build = coordinator.buildIfNeeded("roots", listOf(root), backend, identity)!!

        assertNull(build.replacedModel)
    }

    /** Remembers what the coordinator recorded, in memory. */
    private class FakeBuildLog : IndexBuildLog {
        val built = mutableMapOf<String, Long>()
        var cleared = false

        override fun recordBuilt(rootsKey: String, atMillis: Long) {
            built[rootsKey] = atMillis
        }

        override fun lastBuiltUnder(projectRoot: File): Long? = built.values.maxOrNull()

        override fun clear() {
            built.clear()
            cleared = true
        }
    }
}
