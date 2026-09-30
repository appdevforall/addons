package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.IdeLogService
import com.itsaky.androidide.plugins.services.LogEntry
import com.itsaky.androidide.plugins.services.LogLevel
import com.itsaky.androidide.plugins.services.LogQuery
import com.itsaky.androidide.plugins.services.LogReadResult
import com.itsaky.androidide.plugins.services.LogSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ReadLogsHandler] — the `read_app_logs` and `read_ide_logs` tools. Covers the
 * character budget, the crash-anchored window, and the empty and missing-service answers.
 */
class ReadLogsHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var logService: IdeLogService
    private lateinit var handler: ReadLogsHandler

    @Before
    fun setup() {
        logService = mockk()
        services = mockk()
        context = mockk()
        every { context.services } returns services
        // The handler logs failures through the host logger.
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeLogService::class.java) } returns logService
        handler = ReadLogsHandler(context, LogSource.APP)
    }

    private fun answerWith(entries: List<LogEntry>, truncated: Boolean = false) {
        every { logService.readLogs(any(), any()) } returns LogReadResult(entries, truncated)
    }

    private fun info(lines: Int, label: String = "step"): List<LogEntry> =
        (1..lines).map { LogEntry(LogLevel.INFO, "I MyApp: $label$it") }

    private val crash = listOf(
        LogEntry(LogLevel.ERROR, "E AndroidRuntime: FATAL EXCEPTION: main"),
        LogEntry(LogLevel.ERROR, "E AndroidRuntime: Process: com.example.app, PID: 4242"),
        LogEntry(LogLevel.ERROR, "E AndroidRuntime: java.lang.NullPointerException: boom"),
        LogEntry(LogLevel.ERROR, "E AndroidRuntime: \tat com.example.app.MainActivity.onCreate(MainActivity.kt:12)"),
    )

    @Test
    fun givenEachSource_whenNamed_thenTheToolNamesMatchTheTicket() {
        assertEquals("read_app_logs", ReadLogsHandler(context, LogSource.APP).toolName)
        assertEquals("read_ide_logs", ReadLogsHandler(context, LogSource.IDE).toolName)
    }

    @Test
    fun givenEitherSource_whenAskedForApproval_thenItNeedsNone() {
        assertFalse(ReadLogsHandler(context, LogSource.APP).requiresApproval)
        assertFalse(ReadLogsHandler(context, LogSource.IDE).requiresApproval)
    }

    @Test
    fun givenNoLogService_whenReading_thenItAnswersClearlyInsteadOfFailing() = runTest {
        every { services.get(IdeLogService::class.java) } returns null

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenAnEmptyLog_whenReading_thenItSaysSoAndPointsAtRunApp() = runTest {
        every { logService.readLogs(any(), any()) } returns LogReadResult.EMPTY

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.message.contains("No App Logs"))
        assertTrue(result.data.orEmpty().contains("run_app"))
    }

    @Test
    fun givenAFilterThatMatchesNothing_whenReading_thenItSuggestsDroppingTheFilter() = runTest {
        every { logService.readLogs(any(), any()) } returns LogReadResult.EMPTY

        val result = handler.execute(mapOf("filter" to "nothing-matches"))

        assertTrue(result.success)
        assertTrue(result.data.orEmpty().contains("without them"))
    }

    @Test
    fun givenTheIdeSource_whenReading_thenItAsksTheHostForIdeLogs() = runTest {
        val sourceSlot = slot<LogSource>()
        every { logService.readLogs(capture(sourceSlot), any()) } returns LogReadResult.EMPTY

        ReadLogsHandler(context, LogSource.IDE).execute(emptyMap())

        assertEquals(LogSource.IDE, sourceSlot.captured)
    }

    @Test
    fun givenAMinLevelAndFilter_whenReading_thenTheQueryCarriesThem() = runTest {
        val querySlot = slot<LogQuery>()
        every { logService.readLogs(any(), capture(querySlot)) } returns LogReadResult.EMPTY

        handler.execute(mapOf("min_level" to "Warning", "filter" to " MainActivity "))

        assertEquals(setOf(LogLevel.WARNING, LogLevel.ERROR), querySlot.captured.levels)
        assertEquals("MainActivity", querySlot.captured.text)
        assertEquals(LogQuery.MAX_LINES, querySlot.captured.maxLines)
    }

    @Test
    fun givenAnUnknownMinLevel_whenReading_thenItFailsWithoutCallingTheHost() = runTest {
        val result = handler.execute(mapOf("min_level" to "loud"))

        assertFalse(result.success)
        assertTrue(result.error_details.orEmpty().contains("warning"))
    }

    @Test
    fun givenLevelNames_whenParsed_thenEachKeepsItselfAndEverythingAbove() {
        assertEquals(LogLevel.values().toSet(), ReadLogsHandler.levelsFrom("verbose"))
        assertEquals(setOf(LogLevel.ERROR), ReadLogsHandler.levelsFrom("E"))
        assertNull(ReadLogsHandler.levelsFrom("fatal"))
    }

    @Test
    fun givenAShortCleanLog_whenReading_thenItIsReturnedUnchanged() = runTest {
        answerWith(info(3))

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertEquals("I MyApp: step1\nI MyApp: step2\nI MyApp: step3", result.data)
        assertTrue(result.message.contains("last"))
    }

    @Test
    fun givenACrashAfterChatter_whenReading_thenTheWindowStartsAtTheCrash() = runTest {
        answerWith(info(50) + crash + info(5, label = "after"))

        val result = handler.execute(emptyMap())

        val data = result.data.orEmpty()
        assertTrue(data.startsWith("...[truncated]...\nE AndroidRuntime: FATAL EXCEPTION: main"))
        assertTrue(data.contains("NullPointerException"))
        assertTrue(data.contains("MainActivity.kt:12"))
        assertFalse("the head must be dropped", data.contains("I MyApp: step1\n"))
        assertTrue(result.message.contains("newest error"))
    }

    @Test
    fun givenAnEarlierPlainErrorAndACrash_whenReading_thenTheCrashWins() = runTest {
        val earlier = LogEntry(LogLevel.ERROR, "E Glide: load failed")
        answerWith(listOf(earlier) + info(5) + crash)

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertFalse("a plain error must not anchor ahead of a crash", data.contains("Glide"))
        assertTrue(data.contains("FATAL EXCEPTION"))
    }

    @Test
    fun givenAnErrorButNoCrash_whenReading_thenTheWindowStartsAtTheError() = runTest {
        val error = LogEntry(LogLevel.ERROR, "E PluginManager: failed to load plugin")
        answerWith(info(20) + error + info(3, label = "after"))

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertTrue(data.startsWith("...[truncated]...\n" + error.text))
    }

    @Test
    fun givenALongLogWithNoError_whenReading_thenItIsCappedToTheNewestLines() = runTest {
        answerWith(info(2000))

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertTrue(data.startsWith("...[truncated]..."))
        assertTrue(data.endsWith("I MyApp: step2000"))
        assertTrue(
            "budget exceeded: ${data.length}",
            data.length <= LogWindowCalculator.MAX_OUTPUT_CHARS,
        )
    }

    @Test
    fun givenACrashFollowedByALongLog_whenReading_thenTheCrashHeadSurvivesTheCap() = runTest {
        answerWith(crash + info(2000, label = "after"))

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertTrue(data.startsWith("E AndroidRuntime: FATAL EXCEPTION: main"))
        assertTrue(data.contains("MainActivity.kt:12"))
        assertTrue(data.endsWith("...[truncated]..."))
        assertFalse(data.contains("I MyApp: after2000"))
    }

    @Test
    fun givenTheHostDroppedOlderLines_whenReading_thenTheOutputSaysItIsTruncated() = runTest {
        answerWith(info(3), truncated = true)

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertTrue(data.startsWith("...[truncated]...\nI MyApp: step1"))
    }

    @Test
    fun givenTheServiceThrows_whenReading_thenItFailsWithoutPropagating() = runTest {
        every { logService.readLogs(any(), any()) } throws IllegalStateException("boom")

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.error_details.orEmpty().contains("boom"))
    }
}
