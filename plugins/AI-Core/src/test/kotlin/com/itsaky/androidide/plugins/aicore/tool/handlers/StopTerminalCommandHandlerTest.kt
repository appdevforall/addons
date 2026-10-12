package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StopTerminalCommandHandler]: the tool that stops a command run_shell_command left
 * running, such as `ping`, which the agent otherwise told the user it could not do.
 */
class StopTerminalCommandHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var terminal: IdeTerminalService
    private lateinit var started: StartedCommands
    private lateinit var handler: StopTerminalCommandHandler

    @Before
    fun setup() {
        terminal = mockk()
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeTerminalService::class.java) } returns terminal
        started = StartedCommands()
        handler = StopTerminalCommandHandler(context, started)
    }

    @Test
    fun givenTheStopTool_whenAskingForApproval_thenItIsAskedEveryTime() {
        assertTrue(handler.requiresApproval)
        assertFalse(handler.allowsSessionApproval)
    }

    @Test
    fun givenACommandThisChatStarted_whenValidating_thenTheApprovalShowsItsTextAndSession() = runTest {
        started.record("3f9a1c2e", "npm run dev", "AI Core 2")

        val validation = handler.validate(mapOf("command_id" to "3f9a1c2e")) as Validation.Accepted

        assertEquals(ApprovalPreview.STOP_COMMAND, handler.approvalPreview)
        assertEquals("npm run dev", validation.args[StopTerminalCommandHandler.DETAIL_COMMAND])
        assertEquals("AI Core 2", validation.args[StopTerminalCommandHandler.DETAIL_SESSION])
    }

    @Test
    fun givenTheModelNamesACommandItself_whenValidating_thenTheApprovalNeverShowsIt() = runTest {
        val validation = handler.validate(
            mapOf("command_id" to "3f9a1c2e", "command" to "echo harmless", "session" to "Mine"),
        ) as Validation.Accepted

        // The dialog would otherwise show the model's words as the command being stopped.
        assertEquals(mapOf("command_id" to "3f9a1c2e"), validation.args)
        assertNull(validation.args[StopTerminalCommandHandler.DETAIL_COMMAND])
    }

    @Test
    fun givenNoCommandId_whenValidating_thenItIsRejected() = runTest {
        assertTrue(handler.validate(mapOf("command_id" to " ")) is Validation.Rejected)
    }

    @Test
    fun givenARunningCommand_whenStopped_thenItSucceedsEvenWithTheInterruptsExitCode() = runTest {
        coEvery { terminal.stopCommand("cmd-1", any()) } returns
            TerminalCommandResult.Completed(130, "64 bytes from 1.1.1.1\n^C")

        val result = handler.execute(mapOf("command_id" to "cmd-1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("has stopped: it exited with code 130"))
        assertTrue(result.data!!.contains("^C"))
    }

    @Test
    fun givenTheReadTool_whenComparingArguments_thenStopTakesTheSameCommandIds() {
        assertEquals(ReadTerminalCommandHandler(context).argAliases, handler.argAliases)
    }

    @Test
    fun givenACommandThatIgnoresCtrlC_whenStopped_thenItFailsAndNamesTheSessionToStopItIn() = runTest {
        coEvery { terminal.stopCommand(any(), any()) } returns
            TerminalCommandResult.Running("cmd-1", "AI Core 2", "still here")

        val result = handler.execute(mapOf("command_id" to "cmd-1"))

        assertFalse(result.success)
        assertTrue(result.message.contains("still running"))
        assertTrue(result.error_details!!.contains("\"AI Core 2\""))
        assertTrue(result.error_details!!.contains("still here"))
    }

    @Test
    fun givenAnUnknownCommandId_whenStopped_thenItFailsNamingIt() = runTest {
        coEvery { terminal.stopCommand(any(), any()) } returns null

        val result = handler.execute(mapOf("command_id" to "cmd-9"))

        assertFalse(result.success)
        assertTrue(result.message.contains("\"cmd-9\""))
    }
}
