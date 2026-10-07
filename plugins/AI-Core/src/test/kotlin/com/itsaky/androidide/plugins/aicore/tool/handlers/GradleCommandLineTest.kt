package com.itsaky.androidide.plugins.aicore.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GradleCommandLine] — how the model's `tasks` and `arguments` become the task list
 * and Gradle arguments that [RunGradleTaskHandler] hands the host (ADFA-6337).
 */
class GradleCommandLineTest {

    @Test
    fun givenOptionsInsideTasks_whenParsing_thenTheyMoveToTheArguments() {
        val invocation = GradleCommandLine.parse("clean test --tests FooTest", "-Pci=true")

        assertEquals(listOf("clean", "test"), invocation.tasks)
        assertEquals(listOf("--tests", "FooTest", "-Pci=true"), invocation.arguments)
    }

    @Test
    fun givenAListOfTasks_whenParsing_thenEachEntryIsATask() {
        val invocation = GradleCommandLine.parse(listOf("lint", " test "), null)

        assertEquals(listOf("lint", "test"), invocation.tasks)
        assertTrue(invocation.arguments.isEmpty())
    }

    @Test
    fun givenListEntriesWithSpaces_whenParsing_thenEachEntryIsSplit() {
        val invocation = GradleCommandLine.parse(listOf("test"), listOf("--tests com.example.FooTest"))

        assertEquals(listOf("test"), invocation.tasks)
        assertEquals(listOf("--tests", "com.example.FooTest"), invocation.arguments)
    }

    @Test
    fun givenAnOptionBeforeTheTasks_whenParsing_thenTheTasksAreStillFound() {
        val invocation = GradleCommandLine.parse("-Pci=true test --info", null)

        assertEquals(listOf("test"), invocation.tasks)
        assertEquals(listOf("-Pci=true", "--info"), invocation.arguments)
    }

    @Test
    fun givenALeadingExclusion_whenParsing_thenTheExcludedTaskIsNotRun() {
        val invocation = GradleCommandLine.parse("-x lint build", null)

        assertEquals(listOf("build"), invocation.tasks)
        assertEquals(listOf("-x", "lint"), invocation.arguments)
    }

    @Test
    fun givenAQuotedValue_whenSplitting_thenItStaysOneToken() {
        val tokens = GradleCommandLine.tokensOf("--tests \"com.example.Foo*\" --tests 'Bar Baz'")

        assertEquals(listOf("--tests", "com.example.Foo*", "--tests", "Bar Baz"), tokens)
    }

    @Test
    fun givenAnEmptyQuotedValue_whenSplitting_thenNoEmptyTokenIsKept() {
        assertTrue(GradleCommandLine.tokensOf("\"\"  ''").isEmpty())
    }

    @Test
    fun givenOnlyOptions_whenParsing_thenNoTaskIsNamed() {
        val invocation = GradleCommandLine.parse("--info --stacktrace", null)

        assertTrue(invocation.tasks.isEmpty())
        assertEquals(listOf("--info", "--stacktrace"), invocation.arguments)
    }
}
