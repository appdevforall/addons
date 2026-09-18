package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.IdeBuildService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ReadBuildOutputHandler] — the tool that hands the agent the build log.
 * Covers the 8000-character budget and the error-anchored window that replaced a plain tail,
 * which lost the compiler errors behind Gradle's closing summary.
 */
class ReadBuildOutputHandlerTest {

    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var buildService: IdeBuildService
    private lateinit var handler: ReadBuildOutputHandler

    @Before
    fun setup() {
        buildService = mockk(relaxed = true)
        services = mockk()
        context = mockk()
        every { context.services } returns services
        // The handler logs failures through the host logger.
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeBuildService::class.java) } returns buildService
        handler = ReadBuildOutputHandler(context)
    }

    /** Gradle chatter that must never be mistaken for an error line. */
    private fun noise(lines: Int, label: String = "step"): String =
        (1..lines).joinToString("\n") { "> Task :app:$label$it" }

    @Test
    fun givenNoBuildService_whenReading_thenItFails() = runTest {
        every { services.get(IdeBuildService::class.java) } returns null

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenNullOutput_whenReading_thenItReportsNoOutput() = runTest {
        every { buildService.getBuildOutput() } returns null

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.message.contains("No build output"))
    }

    @Test
    fun givenBlankOutput_whenReading_thenItReportsNoOutput() = runTest {
        every { buildService.getBuildOutput() } returns "   \n\n  "

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.message.contains("No build output"))
    }

    @Test
    fun givenShortCleanOutput_whenReading_thenItIsReturnedUnchanged() = runTest {
        val output = "> Task :app:assembleDebug\nBUILD SUCCESSFUL in 4s\n"
        every { buildService.getBuildOutput() } returns output

        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.data == output)
        assertFalse(result.data.orEmpty().contains("truncated"))
    }

    @Test
    fun givenOutputLongerThanTheLimit_whenReading_thenItIsCappedAtTheLimit() = runTest {
        val output = noise(2000)
        assertTrue("fixture must exceed the budget", output.length > ReadBuildOutputHandler.MAX_OUTPUT_CHARS)
        every { buildService.getBuildOutput() } returns output

        val result = handler.execute(emptyMap())

        val data = result.data.orEmpty()
        assertTrue(result.success)
        assertTrue(data.startsWith("...[truncated]..."))
        assertTrue(data.contains("> Task :app:step2000"))
        assertFalse("the head must be dropped", data.contains("> Task :app:step1\n"))
    }

    @Test
    fun givenNoErrorInALongLog_whenReading_thenItReturnsThePlainTail() = runTest {
        every { buildService.getBuildOutput() } returns noise(2000) + "\nBUILD SUCCESSFUL in 41s"

        val result = handler.execute(emptyMap())

        assertTrue(result.data.orEmpty().endsWith("BUILD SUCCESSFUL in 41s"))
        assertTrue(result.message.contains("last"))
    }

    @Test
    fun givenAKotlinErrorEarlyInALongLog_whenReading_thenTheWindowStartsAtThatError() = runTest {
        val error = "e: file:///project/app/src/main/java/Main.kt:12:5 unresolved reference: foo"
        every { buildService.getBuildOutput() } returns
            noise(500) + "\n" + error + "\n" + noise(20, label = "tail") + "\nBUILD FAILED in 9s"

        val result = handler.execute(emptyMap())

        val data = result.data.orEmpty()
        assertTrue("the error must survive", data.contains(error))
        assertFalse("the head must be dropped", data.contains("> Task :app:step1\n"))
        assertTrue(data.endsWith("BUILD FAILED in 9s"))
        assertTrue(result.message.contains("first error"))
    }

    @Test
    fun givenAWarningBeforeAnError_whenReading_thenItAnchorsOnTheErrorNotTheWarning() = runTest {
        val warning = "w: file:///project/app/src/main/java/Main.kt:3:1 unused variable"
        val error = "e: file:///project/app/src/main/java/Main.kt:12:5 unresolved reference: foo"
        every { buildService.getBuildOutput() } returns "$warning\n$error\nBUILD FAILED in 9s"

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertFalse("a warning must not anchor the window", data.contains(warning))
        assertTrue(data.startsWith("...[truncated]...\n$error"))
    }

    @Test
    fun givenAGradleTaskFailure_whenReading_thenTheWindowStartsAtTheFailureBanner() = runTest {
        val banner = "FAILURE: Build failed with an exception."
        every { buildService.getBuildOutput() } returns
            noise(400) + "\n" + banner + "\n* What went wrong:\nExecution failed for task ':app:compileDebugKotlin'."

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertTrue(data.contains(banner))
        assertFalse(data.contains("> Task :app:step1\n"))
    }

    @Test
    fun givenAJavacError_whenReading_thenTheWindowStartsAtThatError() = runTest {
        val error = "/project/app/src/main/java/Main.java:12: error: cannot find symbol"
        every { buildService.getBuildOutput() } returns noise(300) + "\n" + error + "\nBUILD FAILED"

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertTrue(data.contains(error))
        assertFalse(data.contains("> Task :app:step1\n"))
    }

    @Test
    fun givenTimingPrefixedLines_whenReading_thenTheErrorIsStillFound() = runTest {
        val error = "[10:31:02.412] Δ12ms    e: file:///project/Main.kt:12:5 unresolved reference: foo"
        every { buildService.getBuildOutput() } returns
            "[10:31:01.000] Δ4ms     > Task :app:compileDebugKotlin\n" + error + "\nBUILD FAILED"

        val data = handler.execute(emptyMap()).data.orEmpty()

        assertTrue(data.contains(error))
        assertFalse(data.contains("compileDebugKotlin"))
    }

    @Test
    fun givenHundredsOfErrors_whenReading_thenItStillEndsAtTheSummary() = runTest {
        val errors = (1..2000).joinToString("\n") { "e: file:///project/File$it.kt:1:1 unresolved reference" }
        every { buildService.getBuildOutput() } returns errors + "\nBUILD FAILED in 12s"

        val result = handler.execute(emptyMap())

        val data = result.data.orEmpty()
        assertTrue(data.endsWith("BUILD FAILED in 12s"))
        assertTrue(data.startsWith("...[truncated]..."))
        assertTrue(
            "budget exceeded: ${data.length}",
            data.length <= ReadBuildOutputHandler.MAX_OUTPUT_CHARS + "...[truncated]...\n".length,
        )
    }

    @Test
    fun givenTheServiceThrows_whenReading_thenItFailsWithoutPropagating() = runTest {
        every { buildService.getBuildOutput() } throws IllegalStateException("boom")

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.error_details.orEmpty().contains("boom"))
    }
}
