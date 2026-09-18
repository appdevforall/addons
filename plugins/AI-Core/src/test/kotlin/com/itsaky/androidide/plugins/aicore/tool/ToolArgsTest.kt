package com.itsaky.androidide.plugins.aicore.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [parseToolBoolean] — one parser shared by every place that reads a boolean out
 * of a tool call, so the approval dialog and the handler cannot disagree about a flag as
 * consequential as `replace_all`.
 */
class ToolArgsTest {

    @Test
    fun givenARealBoolean_whenParsed_thenItIsUsedDirectly() {
        assertTrue(parseToolBoolean(true))
        assertFalse(parseToolBoolean(false))
    }

    @Test
    fun givenTheStringsAModelEmits_whenParsed_thenTheyReadAsTrue() {
        listOf("true", "TRUE", "True", " true ", "yes", "YES", "1").forEach {
            assertTrue("'$it' should read as true", parseToolBoolean(it))
        }
    }

    @Test
    fun givenAnythingElse_whenParsed_thenItIsFalse() {
        listOf(null, "", "  ", "false", "no", "0", "maybe", "2", "on").forEach {
            assertFalse("'$it' should read as false", parseToolBoolean(it))
        }
    }

    @Test
    fun givenATurkishDefaultLocale_whenParsingAnUppercaseTrue_thenItStillReadsAsTrue() {
        // The no-argument lowercase() is locale-independent; a locale-aware one drops the flag.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(parseToolBoolean("TRUE"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
