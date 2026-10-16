package com.itsaky.androidide.plugins.aicore.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [ShellInvocation], a `run_shell_command` call as the host takes it. */
class ShellInvocationTest {

    @Test
    fun givenACommandAndPaddedDirectory_whenParsed_thenTheCommandIsKeptAndTheDirectoryTrimmed() {
        val invocation = ShellInvocation.from(mapOf("command" to " ./check.sh ", "working_directory" to " app "))

        assertEquals(ShellInvocation(command = " ./check.sh ", workingDirectory = "app"), invocation)
    }

    @Test
    fun givenABlankDirectory_whenParsed_thenItRunsAtTheProjectRoot() {
        assertNull(ShellInvocation.from(mapOf("command" to "pwd", "working_directory" to "  "))?.workingDirectory)
    }

    @Test
    fun givenABlankCommand_whenParsed_thenThereIsNoInvocation() {
        assertNull(ShellInvocation.from(mapOf("command" to "  ", "working_directory" to "app")))
    }
}
