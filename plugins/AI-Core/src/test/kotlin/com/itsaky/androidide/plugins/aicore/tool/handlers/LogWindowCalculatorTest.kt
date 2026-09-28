package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.aicore.tool.handlers.LogWindowCalculator.MAX_OUTPUT_CHARS
import com.itsaky.androidide.plugins.services.LogEntry
import com.itsaky.androidide.plugins.services.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LogWindowCalculator]: the budget holds including markers, cuts fall between
 * lines, and a crash is found whatever its case.
 */
class LogWindowCalculatorTest {

    private val marker = "...[truncated]..."

    private fun info(lines: Int, label: String = "step"): List<LogEntry> =
        (1..lines).map { LogEntry(LogLevel.INFO, "I MyApp: $label$it") }

    private val crash = LogEntry(LogLevel.ERROR, "E AndroidRuntime: FATAL EXCEPTION: main")

    /** Every line between the markers is one of [entries], whole. */
    private fun assertWholeLines(text: String, entries: List<LogEntry>) {
        val texts = entries.map { it.text }.toSet()
        text.split("\n").filter { it != marker }.forEach {
            assertTrue("cut line: '$it'", it in texts)
        }
    }

    @Test
    fun givenAHostTruncatedCrashFollowedByALongLog_whenWindowed_thenBothMarkersFitTheBudget() {
        val entries = info(10) + crash + info(2000, label = "after")

        val text = LogWindowCalculator.windowFor(entries, hostTruncated = true).text

        assertTrue("budget exceeded: ${text.length}", text.length <= MAX_OUTPUT_CHARS)
        assertTrue(text.startsWith("$marker\n${crash.text}"))
        assertTrue(text.endsWith("\n$marker"))
        assertWholeLines(text, entries)
    }

    @Test
    fun givenALongLogWithNoError_whenWindowed_thenTheNewestWholeLinesFitTheBudget() {
        val entries = info(2000)

        val text = LogWindowCalculator.windowFor(entries, hostTruncated = false).text

        assertTrue("budget exceeded: ${text.length}", text.length <= MAX_OUTPUT_CHARS)
        assertTrue(text.startsWith("$marker\n"))
        assertTrue(text.endsWith("I MyApp: step2000"))
        assertWholeLines(text, entries)
    }

    @Test
    fun givenALogOfExactlyTheBudget_whenWindowed_thenItIsReturnedWhole() {
        val entries = List(79) { LogEntry(LogLevel.INFO, "x".repeat(99)) } +
            LogEntry(LogLevel.INFO, "x".repeat(100))
        val whole = entries.joinToString("\n") { it.text }
        assertEquals(MAX_OUTPUT_CHARS, whole.length)

        val text = LogWindowCalculator.windowFor(entries, hostTruncated = false).text

        assertEquals(whole, text)
    }

    @Test
    fun givenOneOversizedCrashLine_whenWindowed_thenItsStartIsKeptWithoutSplittingAnEmoji() {
        // The emoji's surrogate pair straddles the cut point.
        val room = MAX_OUTPUT_CHARS - "\n$marker".length
        val line = "E AndroidRuntime: FATAL EXCEPTION: " + "a".repeat(room - 36) + "😀" + "b".repeat(100)

        val text = LogWindowCalculator.windowFor(listOf(LogEntry(LogLevel.ERROR, line)), false).text

        assertTrue(text.length <= MAX_OUTPUT_CHARS)
        assertTrue(text.startsWith("E AndroidRuntime: FATAL EXCEPTION"))
        assertFalse("split surrogate", text.removeSuffix("\n$marker").last().isHighSurrogate())
    }

    @Test
    fun givenOneOversizedPlainLine_whenWindowed_thenItsEndIsKeptWithoutSplittingAnEmoji() {
        val room = MAX_OUTPUT_CHARS - "$marker\n".length
        val line = "a".repeat(100) + "😀" + "b".repeat(room - 1)

        val text = LogWindowCalculator.windowFor(listOf(LogEntry(LogLevel.INFO, line)), false).text

        assertTrue(text.length <= MAX_OUTPUT_CHARS)
        assertTrue(text.endsWith("b"))
        assertFalse("split surrogate", text.removePrefix("$marker\n").first().isLowSurrogate())
    }

    @Test
    fun givenALowercaseCrashHeader_whenWindowed_thenItStillAnchors() {
        val earlier = LogEntry(LogLevel.ERROR, "E Glide: load failed")
        val lowercase = LogEntry(null, "fatal signal 11 (SIGSEGV), code 1")

        val window = LogWindowCalculator.windowFor(listOf(earlier) + info(3) + lowercase, false)

        assertTrue(window.anchoredOnError)
        assertTrue(window.text.startsWith("$marker\n${lowercase.text}"))
    }
}
