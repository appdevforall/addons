package com.itsaky.androidide.plugins.aicore.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [parseToolBoolean] and [parseToolInt] — one parser per type, shared by every place
 * that reads it out of a tool call, so the approval dialog and the handler cannot disagree.
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

    @Test
    fun givenNoValueOrABlankString_whenParsedAsInt_thenItIsAbsent() {
        listOf(null, "", "   ").forEach {
            assertEquals("'$it' should be absent", ToolInt.Absent, parseToolInt(it))
        }
    }

    @Test
    fun givenAJsonNumber_whenParsedAsInt_thenAWholeOneIsPresent() {
        assertEquals(ToolInt.Present(96), parseToolInt(96))
        assertEquals(ToolInt.Present(96), parseToolInt(96L))
        assertEquals(ToolInt.Present(96), parseToolInt(96.0))
        assertEquals(ToolInt.Present(-3), parseToolInt(-3.0f))
    }

    @Test
    fun givenAFractionalOrOutOfRangeNumber_whenParsedAsInt_thenItIsInvalid() {
        assertEquals(ToolInt.Invalid("12.5"), parseToolInt(12.5))
        assertEquals(ToolInt.Invalid("3000000000"), parseToolInt(3_000_000_000L))
        assertEquals(ToolInt.Invalid("NaN"), parseToolInt(Double.NaN))
    }

    @Test
    fun givenTheStringsAModelEmits_whenParsedAsInt_thenTheyReadLikeTheNumbers() {
        mapOf("96" to 96, " 96 " to 96, "-3" to -3, "+7" to 7, "12.0" to 12, "12.00" to 12, "1e2" to 100).forEach { (text, expected) ->
            assertEquals("'$text'", ToolInt.Present(expected), parseToolInt(text))
        }
    }

    @Test
    fun givenAStringThatIsNotAWholeNumber_whenParsedAsInt_thenItIsInvalidWithTheTrimmedText() {
        listOf("12.5", "top", "12f", "0x10", "NaN", "Infinity", "3000000000").forEach {
            assertEquals("'$it'", ToolInt.Invalid(it), parseToolInt(" $it "))
        }
    }
}
