package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RunShellCommandHandler] — the tool that runs a shell command in the IDE's visible
 * Terminal, so the agent runs a script itself instead of handing it to the user (ADFA-6339).
 */
class RunShellCommandHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var terminal: IdeTerminalService
    private lateinit var handler: RunShellCommandHandler

    @Before
    fun setup() {
        terminal = mockk()
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeTerminalService::class.java) } returns terminal
        handler = RunShellCommandHandler(context)
    }

    private fun answerWith(result: TerminalCommandResult) {
        coEvery { terminal.runInTerminal(any(), any()) } returns result
    }

    @Test
    fun givenNoTerminalService_whenRunning_thenItFails() = runTest {
        every { services.get(IdeTerminalService::class.java) } returns null

        val result = handler.execute(mapOf("command" to "ls"))

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenABlankCommand_whenValidating_thenItIsRejectedBeforeApproval() = runTest {
        val validation = handler.validate(mapOf("command" to "  "))

        assertTrue(validation is Validation.Rejected)
    }

    @Test
    fun givenNoCommand_whenRunning_thenNothingRuns() = runTest {
        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        coVerify(exactly = 0) { terminal.runInTerminal(any(), any()) }
    }

    @Test
    fun givenACommandAndDirectory_whenRunning_thenTheTerminalGetsThemAsWritten() = runTest {
        answerWith(TerminalCommandResult.Completed(0, "ok"))

        handler.execute(mapOf("command" to "./scripts/check.sh --all", "working_directory" to " app "))

        coVerify { terminal.runInTerminal("./scripts/check.sh --all", "app") }
    }

    @Test
    fun givenNoDirectory_whenRunning_thenItRunsAtTheProjectRoot() = runTest {
        answerWith(TerminalCommandResult.Completed(0, "ok"))

        handler.execute(mapOf("command" to "pwd", "working_directory" to ""))

        coVerify { terminal.runInTerminal("pwd", null) }
    }

    @Test
    fun givenExitCodeZero_whenRunning_thenItSucceedsWithTheOutput() = runTest {
        answerWith(TerminalCommandResult.Completed(0, "$ ls\nbuild.gradle.kts"))

        val result = handler.execute(mapOf("command" to "ls"))

        assertTrue(result.success)
        assertEquals("$ ls\nbuild.gradle.kts", result.data)
    }

    @Test
    fun givenANonZeroExitCode_whenRunning_thenItFailsWithTheCodeAndOutput() = runTest {
        answerWith(TerminalCommandResult.Completed(127, "bash: foo: command not found"))

        val result = handler.execute(mapOf("command" to "foo"))

        assertFalse(result.success)
        assertTrue(result.message.contains("127"))
        assertTrue(result.error_details!!.contains("command not found"))
    }

    @Test
    fun givenACommandStillRunning_whenRunning_thenItSucceedsAndSaysToLeaveItRunning() = runTest {
        answerWith(TerminalCommandResult.Running("AI Core 1", "$ npm start\nlistening on 3000"))

        val result = handler.execute(mapOf("command" to "npm start"))

        assertTrue(result.success)
        assertTrue(result.message.contains("still running in Terminal session \"AI Core 1\""))
        assertTrue(result.data!!.contains("Do not run it again"))
        assertTrue(result.data!!.contains("leave it running unless the user asks you to stop it"))
        assertTrue(result.data!!.endsWith("listening on 3000"))
    }

    @Test
    fun givenACommandThatDidNotStart_whenRunning_thenItReportsTheReason() = runTest {
        answerWith(TerminalCommandResult.NotStarted("The terminal environment is not installed"))

        val result = handler.execute(mapOf("command" to "ls"))

        assertFalse(result.success)
        assertTrue(result.message.contains("did not start: The terminal environment is not installed"))
    }

    @Test
    fun givenADirectoryOutsideTheProject_whenRunning_thenItIsRefusedWithTheReason() = runTest {
        coEvery { terminal.runInTerminal(any(), any()) } throws
            SecurityException("Working directory is outside the project root")

        val result = handler.execute(mapOf("command" to "ls", "working_directory" to "/sdcard"))

        assertFalse(result.success)
        assertTrue(result.message.contains("outside the project root"))
    }

    @Test
    fun givenTheShellTool_whenAskingForApproval_thenItIsShownAsACommandAndNeverSessionApproved() {
        assertEquals(ApprovalPreview.SHELL_COMMAND, handler.approvalPreview)
        assertFalse(handler.allowsSessionApproval)
    }

    @Test
    fun givenTheShellTool_whenDispatched_thenTheWorkingDirectoryIsContainedToTheProject() {
        assertEquals(listOf(RunShellCommandHandler.ARG_WORKING_DIRECTORY), handler.pathArgs)
        assertFalse(handler.resolvesPathsInternally)
    }

    @Test
    fun givenARunningCommand_whenTheAgentIsStopped_thenTheCancellationReachesTheTerminal() = runTest {
        var cancelled = false
        coEvery { terminal.runInTerminal(any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        val job = launch { handler.execute(mapOf("command" to "sleep 100")) }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(cancelled)
    }
}
