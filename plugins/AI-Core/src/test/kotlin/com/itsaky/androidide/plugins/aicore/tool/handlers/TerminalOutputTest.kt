package com.itsaky.androidide.plugins.aicore.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [TerminalOutput], the slice of a shell command's output the model reads. */
class TerminalOutputTest {

    private val cap = 500

    @Test
    fun givenLongOutput_whenShaped_thenTheEndIsKeptAndTheCutIsMarked() {
        val output = "a".repeat(cap) + "THE END"

        val tail = TerminalOutput.tailOf(output, cap)

        assertTrue(tail.startsWith("$TRUNCATION_MARKER\n"))
        assertTrue(tail.endsWith("THE END"))
        assertEquals(cap, tail.length)
    }

    @Test
    fun givenOutputAtTheCap_whenShaped_thenItIsKeptWhole() {
        val output = "b".repeat(cap)

        assertEquals(output, TerminalOutput.tailOf(output, cap))
    }

    @Test
    fun givenNoOutput_whenShaped_thenItSaysSo() {
        assertEquals("(No output)", TerminalOutput.tailOf("  ", cap))
    }
}
