package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildUnavailableReason
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for [QuickBuildHandler] — the tool that starts Quick Build through the host, so the
 * agent can push a change to the running app the way the toolbar button does (ADFA-6300).
 */
class QuickBuildHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var buildService: IdeBuildService
    private lateinit var handler: QuickBuildHandler

    private val pluginProject = QuickBuildAvailability.Unavailable(
        QuickBuildUnavailableReason.PLUGIN_PROJECT,
        "Quick Build runs apps, not plugins.",
    )

    @Before
    fun setup() {
        buildService = mockk(relaxed = true)
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeBuildService::class.java) } returns buildService
        every { buildService.getQuickBuildAvailability() } returns QuickBuildAvailability.Available
        handler = QuickBuildHandler(context)
    }

    private fun answerWith(result: QuickBuildResult) {
        every { buildService.startQuickBuild() } returns CompletableFuture.completedFuture(result)
    }

    @Test
    fun givenQuickBuildAvailable_whenValidating_thenTheCallGoesToApproval() = runTest {
        val validation = handler.validate(emptyMap())

        assertTrue(validation is Validation.Accepted)
    }

    @Test
    fun givenAPluginProject_whenValidating_thenItIsRejectedWithTheHostReasonBeforeApproval() = runTest {
        every { buildService.getQuickBuildAvailability() } returns pluginProject

        val validation = handler.validate(emptyMap())

        val result = (validation as Validation.Rejected).result
        assertFalse(result.success)
        assertTrue(result.message.contains("Quick Build runs apps, not plugins."))
        assertTrue(result.error_details!!.contains("run_gradle_task"))
    }

    @Test
    fun givenNoBuildService_whenValidating_thenItIsRejectedBeforeApproval() = runTest {
        every { services.get(IdeBuildService::class.java) } returns null

        val validation = handler.validate(emptyMap())

        val result = (validation as Validation.Rejected).result
        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenNoBuildService_whenRunning_thenItFails() = runTest {
        every { services.get(IdeBuildService::class.java) } returns null

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenQuickBuildBecameUnavailable_whenRunning_thenNoBuildStarts() = runTest {
        every { buildService.getQuickBuildAvailability() } returns QuickBuildAvailability.Unavailable(
            QuickBuildUnavailableReason.PROJECT_SYNCING,
            "The project is still syncing.",
        )

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("still syncing"))
        verify(exactly = 0) { buildService.startQuickBuild() }
    }

    @Test
    fun givenAReload_whenRunning_thenItSucceedsWithTheTimeItTook() = runTest {
        answerWith(QuickBuildResult.Reloaded(durationMillis = 2_400, restarted = false))

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertEquals("Quick Build updated the app: reloaded in 2.4s", result.message)
    }

    @Test
    fun givenAQuickBuildThatNeverFinishes_whenRunning_thenItReportsStillRunningWithoutCancelling() = runTest {
        every { buildService.startQuickBuild() } returns CompletableFuture()

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertEquals("Quick Build still running", result.message)
        assertTrue(result.error_details!!.contains("read_build_output"))
        verify(exactly = 0) { buildService.cancelQuickBuild() }
    }

    @Test
    fun givenAFailedFutureStage_whenRunning_thenTheHostErrorIsReported() = runTest {
        every { buildService.startQuickBuild() } returns
            CompletableFuture.failedFuture<QuickBuildResult>(IllegalStateException("session gone"))
                .thenApply { it }

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertEquals("Error: IllegalStateException", result.message)
        assertTrue(result.error_details!!.startsWith("session gone"))
    }

    @Test
    fun givenARunningQuickBuild_whenTheAgentIsStopped_thenTheQuickBuildIsCancelled() = runTest {
        every { buildService.startQuickBuild() } returns CompletableFuture()

        val job = launch { handler.execute(emptyMap()) }
        runCurrent()
        job.cancelAndJoin()

        verify(exactly = 1) { buildService.cancelQuickBuild() }
        verify(exactly = 0) { buildService.cancelBuild() }
    }

    @Test
    fun givenCancelThrows_whenTheAgentIsStopped_thenTheStopStillCompletes() = runTest {
        every { buildService.startQuickBuild() } returns CompletableFuture()
        every { buildService.cancelQuickBuild() } throws SecurityException("system.commands not granted")

        val job = launch { handler.execute(emptyMap()) }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun givenAQuickBuildThatFinished_whenRunning_thenNothingIsCancelled() = runTest {
        answerWith(QuickBuildResult.UpToDate)

        handler.execute(emptyMap())

        verify(exactly = 0) { buildService.cancelQuickBuild() }
    }
}
