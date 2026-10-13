package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ReadTerminalCommandHandler] — the tool that checks on a command
 * run_shell_command left running, such as a dev server (ADFA-6339).
 */
class ReadTerminalCommandHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var terminal: IdeTerminalService
    private lateinit var handler: ReadTerminalCommandHandler

    @Before
    fun setup() {
        terminal = mockk()
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeTerminalService::class.java) } returns terminal
        handler = ReadTerminalCommandHandler(context)
    }

    @Test
    fun givenNoTerminalService_whenReading_thenItFails() = runTest {
        every { services.get(IdeTerminalService::class.java) } returns null

        val result = handler.execute(mapOf("command_id" to "cmd-1"))

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenNoCommandId_whenValidating_thenItIsRejected() = runTest {
        assertTrue(handler.validate(mapOf("command_id" to " ")) is Validation.Rejected)
    }

    @Test
    fun givenACommandId_whenReading_thenTheHostGetsItTrimmed() = runTest {
        coEvery { terminal.readCommand(any()) } returns null

        handler.execute(mapOf("command_id" to " cmd-1 "))

        coVerify { terminal.readCommand("cmd-1") }
    }

    @Test
    fun givenARunningCommand_whenReading_thenItSaysSoWithTheSessionAndOutput() = runTest {
        coEvery { terminal.readCommand(any()) } returns
            TerminalCommandResult.Running("cmd-1", "AI Core 2", "$ npm start\nlistening on 3000")

        val result = handler.execute(mapOf("command_id" to "cmd-1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("still running in Terminal session \"AI Core 2\""))
        assertEquals("$ npm start\nlistening on 3000", result.data)
    }

    @Test
    fun givenACommandThatExitedWithAnError_whenReading_thenItSucceedsWithTheCodeAndOutput() = runTest {
        coEvery { terminal.readCommand(any()) } returns
            TerminalCommandResult.Completed(1, "$ npm start\nEADDRINUSE")

        val result = handler.execute(mapOf("command_id" to "cmd-1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("exited with code 1"))
        assertTrue(result.data!!.contains("EADDRINUSE"))
    }

    @Test
    fun givenACommandThatExitedCleanly_whenReading_thenItSucceedsWithTheOutput() = runTest {
        coEvery { terminal.readCommand(any()) } returns TerminalCommandResult.Completed(0, "done")

        val result = handler.execute(mapOf("command_id" to "cmd-1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("exited with code 0"))
        assertEquals("done", result.data)
    }

    @Test
    fun givenAnExitCodeTheTerminalCouldNotTell_whenReading_thenItDoesNotClaimMinusOne() = runTest {
        coEvery { terminal.readCommand(any()) } returns TerminalCommandResult.Completed(-1, "done")

        val result = handler.execute(mapOf("command_id" to "server"))

        assertTrue(result.success)
        assertTrue(result.message.contains("could not tell its exit code"))
        assertFalse(result.message.contains("-1"))
    }

    @Test
    fun givenAnUnknownCommandId_whenReading_thenItFailsNamingIt() = runTest {
        coEvery { terminal.readCommand(any()) } returns null

        val result = handler.execute(mapOf("command_id" to "cmd-9"))

        assertFalse(result.success)
        assertTrue(result.message.contains("No command with id \"cmd-9\""))
    }
}
