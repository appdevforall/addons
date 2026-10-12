package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.ToolResultsPrompt
import com.itsaky.androidide.plugins.aicore.tool.normalizeToolArgs
import com.itsaky.androidide.plugins.services.GradleTaskInfo
import com.itsaky.androidide.plugins.services.IdeBuildService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ListGradleTasksHandler] — the tool that tells the agent which Gradle tasks the
 * project has, so it runs a task that exists rather than one it guessed.
 */
class ListGradleTasksHandlerTest {

    private lateinit var services: ServiceRegistry
    private lateinit var buildService: IdeBuildService
    private lateinit var handler: ListGradleTasksHandler

    private val greet = GradleTaskInfo(":greet", "greet", ":", "custom", "Prints a greeting")
    private val unitTests = GradleTaskInfo(":app:testDebugUnitTest", "testDebugUnitTest", ":app", "verification", "Run unit tests")
    private val lint = GradleTaskInfo(":app:lint", "lint", ":app", "verification", null)
    private val compile = GradleTaskInfo(":app:compileDebugKotlin", "compileDebugKotlin", ":app", null, null)

    @Before
    fun setup() {
        buildService = mockk(relaxed = true)
        services = mockk()
        val context = mockk<PluginContext>()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeBuildService::class.java) } returns buildService
        every { buildService.getTasks() } returns listOf(greet, unitTests, lint, compile)
        handler = ListGradleTasksHandler(context)
    }

    @Test
    fun givenNoBuildService_whenListing_thenItFails() = runTest {
        every { services.get(IdeBuildService::class.java) } returns null

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun givenUnsyncedProject_whenListing_thenItAsksForASync() = runTest {
        every { buildService.getTasks() } returns emptyList()

        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.error_details!!.contains("gradle_sync"))
    }

    @Test
    fun givenNoFilter_whenListing_thenGroupedTasksShowWithDescriptionsByGroup() = runTest {
        val result = handler.execute(emptyMap())

        assertTrue(result.success)
        assertEquals("3 Gradle tasks", result.message)
        assertTrue(
            result.data!!.startsWith(
                "custom:\n:greet — Prints a greeting\n\n" +
                    "verification:\n:app:testDebugUnitTest — Run unit tests\n:app:lint"
            )
        )
    }

    @Test
    fun givenNoFilter_whenListing_thenUngroupedTasksAreCountedNotShown() = runTest {
        val data = handler.execute(emptyMap()).data!!

        assertFalse(data.contains(":app:compileDebugKotlin"))
        assertTrue(data.contains("1 tasks with no group or description are not shown"))
    }

    @Test
    fun givenUngroupedTaskWithDescription_whenListingWithoutFilter_thenItShowsUnderOther() = runTest {
        val handshake = GradleTaskInfo(":app:secretHandshake", "secretHandshake", ":app", null, "Prints a hidden greeting")
        every { buildService.getTasks() } returns listOf(greet, compile, handshake)

        val result = handler.execute(emptyMap())

        assertEquals("2 Gradle tasks", result.message)
        assertTrue(result.data!!.contains("other:\n:app:secretHandshake — Prints a hidden greeting"))
        assertFalse(result.data!!.contains(":app:compileDebugKotlin"))
    }

    @Test
    fun givenFilter_whenListing_thenUngroupedTasksAreSearchedToo() = runTest {
        val result = handler.execute(mapOf("filter" to "compile"))

        assertEquals("1 Gradle tasks matching \"compile\"", result.message)
        assertEquals("other:\n:app:compileDebugKotlin", result.data)
    }

    @Test
    fun givenFilterMatchingDescription_whenListing_thenTheTaskIsFoundIgnoringCase() = runTest {
        val result = handler.execute(mapOf("filter" to "GREETING"))

        assertEquals("custom:\n:greet — Prints a greeting", result.data)
    }

    @Test
    fun givenModuleAlias_whenListing_thenItFiltersByPath() = runTest {
        val data = handler.execute(normalizeToolArgs(handler, mapOf("module" to ":app"))).data!!

        assertFalse(data.contains(":greet"))
        assertTrue(data.contains(":app:lint"))
    }

    @Test
    fun givenFilterMatchingNothing_whenListing_thenItSucceedsWithAHint() = runTest {
        val result = handler.execute(mapOf("filter" to "deploy"))

        assertTrue(result.success)
        assertTrue(result.data!!.contains("gradle_sync"))
    }

    @Test
    fun givenDescriptionsTooLongToFit_whenListing_thenEveryTaskKeepsItsPathAndOtherSurvives() {
        val many = (1..150).map { GradleTaskInfo(":app:task$it", "task$it", ":app", "build", "Does step $it of the build, at some length") }
        val handshake = GradleTaskInfo(":app:secretHandshake", "secretHandshake", ":app", null, "Prints a greeting")

        val result = ListGradleTasksHandler.resultFor(many + handshake, "")
        val data = result.data!!

        assertTrue(promptBody(result).length <= ToolResultsPrompt.DEFAULT_CHAR_LIMIT)
        assertTrue(data.contains("Descriptions left out"))
        assertTrue(data.contains("other:\n:app:secretHandshake"))
        assertTrue(data.contains(":app:task150\n"))
        assertFalse(data.contains("List cut short"))
    }

    @Test
    fun givenTooManyTasksEvenWithoutDescriptions_whenListing_thenTheListIsCutAtALineWithAHint() {
        val many = (1..1000).map { GradleTaskInfo(":feature:module:task$it", "task$it", ":feature:module", "build", null) }

        val result = ListGradleTasksHandler.resultFor(many, "")
        val data = result.data!!

        assertTrue(promptBody(result).length <= ToolResultsPrompt.DEFAULT_CHAR_LIMIT)
        assertTrue(data.contains("List cut short"))
        assertTrue(data.lines().filter { it.startsWith(":feature") }.all { it.matches(Regex(":feature:module:task\\d+")) })
    }

    @Test
    fun givenListBetweenPromptCapAndOldBudget_whenListing_thenTheLastTaskReachesTheModel() {
        // Shaped like a stock app module: about 6000 characters with descriptions, under the old 8000 budget.
        val agp = (1..70).map { GradleTaskInfo(":app:agpTask$it", "agpTask$it", ":app", "build", "Runs step $it of the Android build pipeline") }
        val handshake = GradleTaskInfo(":app:secretHandshake", "secretHandshake", ":app", null, "Prints a greeting")
        val hiddenTasks = (1..200).map { GradleTaskInfo(":app:internal$it", "internal$it", ":app", null, null) }

        val result = ListGradleTasksHandler.resultFor(agp + handshake + hiddenTasks, "")

        assertTrue(promptBody(result).length <= ToolResultsPrompt.DEFAULT_CHAR_LIMIT)
        assertTrue(result.data!!.contains(":app:secretHandshake"))
        assertTrue(result.data!!.contains("200 tasks with no group or description"))
    }

    // What ToolResultsPrompt hands the model for a successful result, before its own cut.
    private fun promptBody(result: ToolResult): String = result.message + "\n" + result.data
}
