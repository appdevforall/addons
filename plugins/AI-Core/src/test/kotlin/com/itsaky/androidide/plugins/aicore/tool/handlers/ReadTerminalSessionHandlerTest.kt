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
 * Unit tests for [ReadTerminalSessionHandler] — the tool that checks on a command
 * run_shell_command left running, such as a dev server (ADFA-6339).
 */
class ReadTerminalSessionHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var terminal: IdeTerminalService
    private lateinit var handler: ReadTerminalSessionHandler

    @Before
    fun setup() {
        terminal = mockk()
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeTerminalService::class.java) } returns terminal
        handler = ReadTerminalSessionHandler(context)
    }

    @Test
    fun givenNoTerminalService_whenReading_thenItFails() = runTest {
        every { services.get(IdeTerminalService::class.java) } returns null

        val result = handler.execute(mapOf("session" to "AI Core 1"))

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenNoSession_whenValidating_thenItIsRejected() = runTest {
        assertTrue(handler.validate(mapOf("session" to " ")) is Validation.Rejected)
    }

    @Test
    fun givenASessionName_whenReading_thenTheHostGetsItTrimmed() = runTest {
        coEvery { terminal.readSession(any()) } returns null

        handler.execute(mapOf("session" to " AI Core 1 "))

        coVerify { terminal.readSession("AI Core 1") }
    }

    @Test
    fun givenARunningCommand_whenReading_thenItSaysSoWithTheOutput() = runTest {
        coEvery { terminal.readSession(any()) } returns
            TerminalCommandResult.Running("AI Core 1", "$ npm start\nlistening on 3000")

        val result = handler.execute(mapOf("session" to "AI Core 1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("still running"))
        assertEquals("$ npm start\nlistening on 3000", result.data)
    }

    @Test
    fun givenACommandThatExitedWithAnError_whenReading_thenItSucceedsWithTheCodeAndOutput() = runTest {
        coEvery { terminal.readSession(any()) } returns
            TerminalCommandResult.Completed(1, "$ npm start\nEADDRINUSE")

        val result = handler.execute(mapOf("session" to "AI Core 1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("exited with code 1"))
        assertTrue(result.data!!.contains("EADDRINUSE"))
    }

    @Test
    fun givenACommandThatExitedCleanly_whenReading_thenItSucceedsWithTheOutput() = runTest {
        coEvery { terminal.readSession(any()) } returns TerminalCommandResult.Completed(0, "done")

        val result = handler.execute(mapOf("session" to "AI Core 1"))

        assertTrue(result.success)
        assertTrue(result.message.contains("exited with code 0"))
        assertEquals("done", result.data)
    }

    @Test
    fun givenAnUnknownSession_whenReading_thenItFailsNamingIt() = runTest {
        coEvery { terminal.readSession(any()) } returns null

        val result = handler.execute(mapOf("session" to "other 1"))

        assertFalse(result.success)
        assertTrue(result.message.contains("No Terminal session named \"other 1\""))
    }
}
