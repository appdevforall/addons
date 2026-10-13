package com.itsaky.androidide.plugins.aicore.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [TerminalOutput], the slice of a shell command's output the model reads. */
class TerminalOutputTest {

    @Test
    fun givenLongOutput_whenShaped_thenTheEndIsKeptAndTheCutIsMarked() {
        val output = "a".repeat(TerminalOutput.MAX_CHARS) + "THE END"

        val tail = TerminalOutput.tailOf(output)

        assertTrue(tail.startsWith("$TRUNCATION_MARKER\n"))
        assertTrue(tail.endsWith("THE END"))
        assertEquals(TRUNCATION_MARKER.length + 1 + TerminalOutput.MAX_CHARS, tail.length)
    }

    @Test
    fun givenOutputAtTheCap_whenShaped_thenItIsKeptWhole() {
        val output = "b".repeat(TerminalOutput.MAX_CHARS)

        assertEquals(output, TerminalOutput.tailOf(output))
    }

    @Test
    fun givenNoOutput_whenShaped_thenItSaysSo() {
        assertEquals("(No output)", TerminalOutput.tailOf("  "))
    }
}
