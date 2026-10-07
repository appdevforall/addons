package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StopTerminalSessionHandler]: the tool that stops a command run_shell_command left
 * running, such as `ping`, which the agent otherwise told the user it could not do.
 */
class StopTerminalSessionHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var terminal: IdeTerminalService
    private lateinit var handler: StopTerminalSessionHandler

    @Before
    fun setup() {
        terminal = mockk()
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeTerminalService::class.java) } returns terminal
        handler = StopTerminalSessionHandler(context)
    }

    @Test
    fun givenTheStopTool_whenAskingForApproval_thenItIsAskedEveryTime() {
        assertTrue(handler.requiresApproval)
        assertFalse(handler.allowsSessionApproval)
    }

    @Test
    fun givenNoSession_whenValidating_thenItIsRejected() = runTest {
        assertTrue(handler.validate(mapOf("session" to " ")) is Validation.Rejected)
    }

    @Test
    fun givenARunningCommand_whenStopped_thenItSucceedsEvenWithTheInterruptsExitCode() = runTest {
        coEvery { terminal.stopSession("AI Core 1", any()) } returns
            TerminalCommandResult.Completed(130, "64 bytes from 1.1.1.1\n^C")

        val result = handler.execute(mapOf("session" to "AI Core 1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("has stopped (exit code 130)"))
        assertTrue(result.data!!.contains("^C"))
    }

    @Test
    fun givenTheReadTool_whenComparingArguments_thenStopTakesTheSameSessionNames() {
        assertEquals(ReadTerminalSessionHandler(context).argAliases, handler.argAliases)
    }

    @Test
    fun givenACommandThatIgnoresCtrlC_whenStopped_thenItFailsAndSaysItStillRuns() = runTest {
        coEvery { terminal.stopSession(any(), any()) } returns TerminalCommandResult.Running("AI Core 1", "still here")

        val result = handler.execute(mapOf("session" to "AI Core 1"))

        assertFalse(result.success)
        assertTrue(result.message.contains("still running"))
        assertTrue(result.error_details!!.contains("still here"))
    }

    @Test
    fun givenAnUnknownSession_whenStopped_thenItFailsNamingIt() = runTest {
        coEvery { terminal.stopSession(any(), any()) } returns null

        val result = handler.execute(mapOf("session" to "AI Core 9"))

        assertFalse(result.success)
        assertTrue(result.message.contains("\"AI Core 9\""))
    }
}
