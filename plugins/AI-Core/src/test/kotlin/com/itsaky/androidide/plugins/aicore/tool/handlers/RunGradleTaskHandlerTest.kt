package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
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
 * Unit tests for [RunGradleTaskHandler] — the tool that runs any Gradle task with arguments on the
 * IDE's tooling server, so the agent can run the tests it was asked to run (ADFA-6337).
 */
class RunGradleTaskHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var buildService: IdeBuildService
    private lateinit var handler: RunGradleTaskHandler

    @Before
    fun setup() {
        buildService = mockk(relaxed = true)
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeBuildService::class.java) } returns buildService
        every { buildService.getBuildOutput() } returns "BUILD SUCCESSFUL in 4s"
        handler = RunGradleTaskHandler(context)
    }

    private fun answerWith(result: GradleTaskResult) {
        every { buildService.executeTasks(any<List<String>>(), any()) } returns
            CompletableFuture.completedFuture(result)
    }

    @Test
    fun givenNoBuildService_whenRunning_thenItFails() = runTest {
        every { services.get(IdeBuildService::class.java) } returns null

        val result = handler.execute(mapOf("tasks" to "test"))

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenNoTasks_whenValidating_thenItIsRejectedBeforeApproval() = runTest {
        val validation = handler.validate(mapOf("tasks" to "  "))

        assertTrue(validation is Validation.Rejected)
    }

    @Test
    fun givenNoTasks_whenRunning_thenNoBuildStarts() = runTest {
        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        verify(exactly = 0) { buildService.executeTasks(any<List<String>>(), any()) }
    }

    @Test
    fun givenTasksAndArguments_whenRunning_thenTheHostGetsThemSeparately() = runTest {
        answerWith(GradleTaskResult.Success)

        handler.execute(mapOf("tasks" to ":app:testDebugUnitTest", "arguments" to "--tests \"com.example.Foo*\" --info"))

        verify {
            buildService.executeTasks(
                listOf(":app:testDebugUnitTest"),
                listOf("--tests", "com.example.Foo*", "--info"),
            )
        }
    }

    @Test
    fun givenOnlyOptions_whenValidating_thenItIsRejected() = runTest {
        val validation = handler.validate(mapOf("tasks" to "--info \"\""))

        assertTrue(validation is Validation.Rejected)
    }

    @Test
    fun givenAFailedFutureStage_whenRunning_thenTheHostErrorIsReported() = runTest {
        every { buildService.executeTasks(any<List<String>>(), any()) } returns
            CompletableFuture.failedFuture<GradleTaskResult>(IllegalStateException("tooling server down"))
                .thenApply { it }

        val result = handler.execute(mapOf("tasks" to "test"))

        assertFalse(result.success)
        assertEquals("Error: IllegalStateException", result.message)
        assertTrue(result.error_details!!.startsWith("tooling server down"))
    }

    @Test
    fun givenASuccessfulTask_whenRunning_thenItSucceedsWithTheBuildOutput() = runTest {
        answerWith(GradleTaskResult.Success)

        val result = handler.execute(mapOf("tasks" to "test"))

        assertTrue(result.success)
        assertEquals("BUILD SUCCESSFUL in 4s", result.data)
    }

    @Test
    fun givenEveryTaskUpToDate_whenRunning_thenTheResultSaysNothingRanAndHowToRerun() = runTest {
        answerWith(GradleTaskResult.Success)
        every { buildService.getBuildOutput() } returns
            "> Task :app:compileDebugKotlin UP-TO-DATE\n> Task :app:testDebugUnitTest UP-TO-DATE\nBUILD SUCCESSFUL"

        val result = handler.execute(mapOf("tasks" to "test"))

        assertTrue(result.success)
        assertTrue(result.message.contains("up to date"))
        assertTrue(result.data!!.contains("--rerun"))
    }

    @Test
    fun givenATaskThatRan_whenCheckingTheOutput_thenSomethingRan() {
        val output = "> Task :app:compileDebugKotlin UP-TO-DATE\n> Task :app:testDebugUnitTest\nBUILD SUCCESSFUL"

        assertFalse(RunGradleTaskHandler.nothingRan(output))
    }

    @Test
    fun givenColouredTaskLines_whenCheckingTheOutput_thenTheEscapesAreIgnored() {
        val output = "\u001B[1m> Task :app:compileDebugKotlin\u001B[m UP-TO-DATE\n" +
            "\u001B[1m> Task :app:testDebugUnitTest\u001B[m\nBUILD SUCCESSFUL"

        assertFalse(RunGradleTaskHandler.nothingRan(output))
        assertTrue(RunGradleTaskHandler.nothingRan("\u001B[33m> Task :app:test UP-TO-DATE\u001B[0m"))
    }

    @Test
    fun givenNoTaskLines_whenCheckingTheOutput_thenNothingIsClaimed() {
        assertFalse(RunGradleTaskHandler.nothingRan("BUILD SUCCESSFUL in 4s"))
    }

    @Test
    fun givenAFailedTask_whenRunning_thenItFailsWithTheReasonAndTheFirstError() = runTest {
        answerWith(GradleTaskResult.Failed("BUILD_FAILED"))
        every { buildService.getBuildOutput() } returns
            "> Task :app:testDebugUnitTest\nFooTest > adds FAILED\nFAILURE: Build failed with an exception."

        val result = handler.execute(mapOf("tasks" to "test"))

        assertFalse(result.success)
        assertTrue(result.message.contains("BUILD_FAILED"))
        assertTrue(result.error_details!!.contains("FAILURE: Build failed"))
    }

    @Test
    fun givenARefusedTask_whenRunning_thenItSaysNoBuildRan() = runTest {
        answerWith(GradleTaskResult.Refused("another build is in progress"))

        val result = handler.execute(mapOf("tasks" to "test"))

        assertFalse(result.success)
        assertTrue(result.message.contains("did not start: another build is in progress"))
    }

    @Test
    fun givenACancelledTask_whenRunning_thenItReportsTheCancellation() = runTest {
        answerWith(GradleTaskResult.Cancelled)

        val result = handler.execute(mapOf("tasks" to "test"))

        assertFalse(result.success)
        assertTrue(result.message.contains("cancelled"))
    }

    @Test
    fun givenARunningTask_whenTheAgentIsStopped_thenTheBuildIsCancelled() = runTest {
        every { buildService.executeTasks(any<List<String>>(), any()) } returns CompletableFuture()

        val job = launch { handler.execute(mapOf("tasks" to "test")) }
        runCurrent()
        job.cancelAndJoin()

        verify(exactly = 1) { buildService.cancelBuild() }
    }

    @Test
    fun givenATaskThatFinished_whenRunning_thenNoBuildIsCancelled() = runTest {
        answerWith(GradleTaskResult.Success)

        handler.execute(mapOf("tasks" to "test"))

        verify(exactly = 0) { buildService.cancelBuild() }
    }
}
