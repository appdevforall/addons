package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RunAppHandler] — the tool that builds and launches the app. Covers the wait for
 * [BuildAndLaunchCallback] that replaced fire-and-forget, which reported success roughly a second
 * after the build started and long before it could have failed.
 */
class RunAppHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var buildService: IdeBuildService
    private lateinit var handler: RunAppHandler

    @Before
    fun setup() {
        buildService = mockk(relaxed = true)
        services = mockk()
        context = mockk()
        every { context.services } returns services
        // The handler logs failures through the host logger.
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeBuildService::class.java) } returns buildService
        every { buildService.isBuildInProgress() } returns false
        handler = RunAppHandler(context)
    }

    /** Stubs `runApp` to invoke its callback synchronously, as the host does on several paths. */
    private fun answerWith(vararg outcomes: Pair<Boolean, String>) {
        every { buildService.runApp(any()) } answers {
            val callback = firstArg<BuildAndLaunchCallback>()
            outcomes.forEach { (success, message) -> callback.onComplete(success, message) }
        }
    }

    @Test
    fun givenNoBuildService_whenRunning_thenItFails() = runTest {
        every { services.get(IdeBuildService::class.java) } returns null

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenABuildAlreadyInProgress_whenRunning_thenItFailsWithoutTriggeringAnother() = runTest {
        every { buildService.isBuildInProgress() } returns true

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("already running"))
        verify(exactly = 0) { buildService.runApp(any()) }
    }

    @Test
    fun givenTheCallbackReportsSuccess_whenRunning_thenItReturnsSuccessWithTheMessage() = runTest {
        answerWith(true to "Build successful")

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertEquals("Build succeeded", result.message)
        assertEquals("Build successful", result.data)
    }

    @Test
    fun givenTheCallbackReportsFailure_whenRunning_thenItReturnsFailureCarryingTheMessage() = runTest {
        answerWith(false to "Build failed: compilation error")

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertEquals("Build failed", result.message)
        assertTrue(result.error_details.orEmpty().contains("compilation error"))
        assertTrue(
            "the agent must be pointed at the log",
            result.error_details.orEmpty().contains("read_build_output"),
        )
    }

    @Test
    fun givenTheCallbackNeverFires_whenRunning_thenItReportsBuildStillRunning() = runTest {
        every { buildService.runApp(any()) } just Runs

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertEquals("Build still running", result.message)
        assertTrue(result.error_details.orEmpty().contains("read_build_output"))
    }

    @Test
    fun givenTheCallbackFiresTwice_whenRunning_thenTheFirstOutcomeWinsWithoutCrashing() = runTest {
        answerWith(true to "first", false to "second")

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertEquals("first", result.data)
    }

    @Test
    fun givenRunAppThrows_whenRunning_thenItReportsTheErrorWithoutPropagating() = runTest {
        every { buildService.runApp(any()) } throws IllegalStateException("tooling server gone")

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("IllegalStateException"))
        assertTrue(result.error_details.orEmpty().contains("tooling server gone"))
    }

    @Test
    fun givenTheCallerIsCancelled_whenWaitingForTheBuild_thenTheCancellationUnwinds() = runTest {
        // Stop must interrupt the wait. Swallowed into a ToolResult it reads as a build failure,
        // and the agent loop carries on with the run the user just stopped.
        every { buildService.runApp(any()) } just Runs
        var result: ToolResult? = null

        val job = launch { result = handler.execute(emptyMap()) }
        runCurrent()
        job.cancelAndJoin()

        assertNull("a cancelled run must produce no result", result)
    }

    @Test
    fun givenTheToolDescription_whenReadByTheModel_thenItSaysTheInstallNeedsTheUser() {
        // The host completes the callback when the installer takes the APK, before the system
        // install prompt is answered. Without this caveat the model reports the app as running.
        assertTrue(
            "run_app success does not mean the app launched",
            handler.description.contains("install started"),
        )
    }

    @Test
    fun givenABuildThatIsNotInProgress_whenRunning_thenItTriggersExactlyOneBuild() = runTest {
        answerWith(true to "Build successful")

        handler.execute(emptyMap())

        verify(exactly = 1) { buildService.runApp(any()) }
    }
}
