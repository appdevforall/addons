package com.appdevforall.jvm.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProgramArgumentsTest {

    @Test
    fun `splits on whitespace`() {
        assertEquals(listOf("a", "b", "c"), ProgramArguments.parse("  a b\t\nc  "))
        assertEquals(emptyList<String>(), ProgramArguments.parse("   "))
    }

    @Test
    fun `quotes keep spaces and can hold the other quote`() {
        assertEquals(listOf("--name", "Ada Lovelace", "it's"), ProgramArguments.parse("--name \"Ada Lovelace\" \"it's\""))
        assertEquals(listOf("say \"hi\"", "a\\b"), ProgramArguments.parse("'say \"hi\"' 'a\\b'"))
    }

    @Test
    fun `backslash escapes outside single quotes`() {
        assertEquals(listOf("a b", "\"x\"", ""), ProgramArguments.parse("a\\ b \"\\\"x\\\"\" \"\""))
    }

    @Test
    fun `an unclosed quote is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ProgramArguments.parse("\"Ada") }
    }
}
