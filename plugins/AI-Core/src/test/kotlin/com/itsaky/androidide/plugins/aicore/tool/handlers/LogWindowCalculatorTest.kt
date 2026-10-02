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
        assertTrue(text.contains("\n$marker\n"))
        assertTrue(text.endsWith("I MyApp: after2000"))
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
        val entries = List(38) { LogEntry(LogLevel.INFO, "x".repeat(99)) } +
            LogEntry(LogLevel.INFO, "x".repeat(100))
        val whole = entries.joinToString("\n") { it.text }
        assertEquals(MAX_OUTPUT_CHARS, whole.length)

        val text = LogWindowCalculator.windowFor(entries, hostTruncated = false).text

        assertEquals(whole, text)
    }

    @Test
    fun givenOneOversizedCrashLine_whenWindowed_thenItsStartIsKeptWithoutSplittingAnEmoji() {
        // The emoji's surrogate pair straddles the cut point.
        val half = (MAX_OUTPUT_CHARS - "\n$marker\n".length) / 2
        val line = "E AndroidRuntime: FATAL EXCEPTION: " + "a".repeat(half - 36) + "😀" + "b".repeat(MAX_OUTPUT_CHARS)

        val text = LogWindowCalculator.windowFor(listOf(LogEntry(LogLevel.ERROR, line)), false).text

        assertTrue(text.length <= MAX_OUTPUT_CHARS)
        assertTrue(text.startsWith("E AndroidRuntime: FATAL EXCEPTION"))
        assertFalse("split surrogate", text.substringBefore("\n$marker\n").last().isHighSurrogate())
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
    fun givenAnOlderCrashAndANewerOne_whenWindowed_thenItAnchorsOnTheNewest() {
        val older = LogEntry(LogLevel.ERROR, "E AndroidRuntime: FATAL EXCEPTION: old")
        val newer = LogEntry(LogLevel.ERROR, "E AndroidRuntime: FATAL EXCEPTION: new")

        val text = LogWindowCalculator.windowFor(listOf(older) + info(2000) + newer, false).text

        assertTrue(text.startsWith("$marker\n${newer.text}"))
    }

    @Test
    fun givenTwoErrorRuns_whenWindowed_thenItAnchorsOnTheStartOfTheNewest() {
        val older = LogEntry(LogLevel.ERROR, "E Plugin: old")
        val newest = listOf(LogEntry(LogLevel.ERROR, "E Plugin: new"), LogEntry(LogLevel.ERROR, "E Plugin: at frame"))

        val text = LogWindowCalculator.windowFor(listOf(older) + info(2000) + newest, false).text

        assertEquals("$marker\nE Plugin: new\nE Plugin: at frame", text)
    }

    @Test
    fun givenALowercaseCrashHeader_whenWindowed_thenItStillAnchors() {
        val earlier = LogEntry(LogLevel.ERROR, "E Glide: load failed")
        val lowercase = LogEntry(null, "fatal signal 11 (SIGSEGV), code 1")

        val window = LogWindowCalculator.windowFor(listOf(earlier) + info(2000) + lowercase, false)

        assertTrue(window.anchoredOnError)
        assertTrue(window.text.startsWith("$marker\n${lowercase.text}"))
    }

    @Test
    fun givenAnOlderCrashAndANewerErrorRun_whenWindowed_thenItAnchorsOnTheErrorRun() {
        val newer = LogEntry(LogLevel.ERROR, "E Plugin: new")

        val text = LogWindowCalculator.windowFor(listOf(crash) + info(2000) + newer, false).text

        assertEquals("$marker\n${newer.text}", text)
    }

    @Test
    fun givenACrashThenALaterErrorThatBothFit_whenWindowed_thenItAnchorsOnTheCrash() {
        val later = LogEntry(LogLevel.ERROR, "E FrameEvents: updateAcquireFence")

        val text = LogWindowCalculator.windowFor(info(2000) + crash + info(3) + later, false).text

        assertTrue(text.startsWith("$marker\n${crash.text}"))
        assertTrue(text.endsWith(later.text))
    }

    @Test
    fun givenAReadThatFits_whenWindowed_thenItIsReturnedWholeAndUnanchored() {
        val entries = info(3) + LogEntry(LogLevel.ERROR, "E MyApp: failed")

        val window = LogWindowCalculator.windowFor(entries, hostTruncated = false)

        assertEquals(entries.joinToString("\n") { it.text }, window.text)
        assertFalse(window.anchoredOnError)
    }

    @Test
    fun givenOnlyErrorLines_whenWindowed_thenTheNewestAreKept() {
        val entries = (1..2000).map { LogEntry(LogLevel.ERROR, "E MyApp: error$it") }

        val text = LogWindowCalculator.windowFor(entries, hostTruncated = false).text

        assertTrue(text.startsWith("$marker\n"))
        assertTrue(text.endsWith("E MyApp: error2000"))
        assertWholeLines(text, entries)
    }
}
