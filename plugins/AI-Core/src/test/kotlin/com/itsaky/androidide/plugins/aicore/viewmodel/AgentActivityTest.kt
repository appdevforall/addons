package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [AgentActivity]. QA's complaint these answer: a run's tool calls arrived as a
 * wall of wrapped badges carrying whole file paths and whole `edit_file` snippets. Every line the
 * activity row shows has to fit on one line of a phone.
 */
class AgentActivityTest {

    @Test
    fun givenAFilePath_whenNamed_thenOnlyTheFileNameIsShown() {
        val call = ToolCall(
            "edit_file",
            mapOf("file_path" to "app/src/main/java/com/example/myapplication25/MainActivity.java"),
        )

        assertEquals("MainActivity.java", AgentActivity.subjectOf(call))
    }

    @Test
    fun givenAPathArgumentUnderTheAlias_whenNamed_thenItIsStillFound() {
        assertEquals("A.kt", AgentActivity.subjectOf(ToolCall("read_file", mapOf("path" to "src/A.kt"))))
    }

    @Test
    fun givenADirectoryWithATrailingSlash_whenNamed_thenTheLastSegmentIsShown() {
        val call = ToolCall("list_files", mapOf("directory" to "app/src/main/java/"))

        assertEquals("java", AgentActivity.subjectOf(call))
    }

    @Test
    fun givenBothAPathAndContent_whenNamed_thenThePathWinsOverTheSnippet() {
        // The bug in the badge this replaces: `new_string` put a whole class on the line.
        val call = ToolCall(
            "edit_file",
            mapOf("file_path" to "A.kt", "old_string" to "class A", "new_string" to "class A {\n}"),
        )

        assertEquals("A.kt", AgentActivity.subjectOf(call))
    }

    @Test
    fun givenAMultiLineQuery_whenNamed_thenItIsFlattenedAndCapped() {
        val call = ToolCall("search_project", mapOf("query" to "a".repeat(200) + "\nb"))

        val subject = AgentActivity.subjectOf(call)!!

        assertEquals(AgentActivity.SUBJECT_LIMIT + 1, subject.length)
        assertEquals("a".repeat(AgentActivity.SUBJECT_LIMIT) + "…", subject)
    }

    @Test
    fun givenACallWithNoRecognisableArgument_whenNamed_thenNothingIsShown() {
        assertNull(AgentActivity.subjectOf(ToolCall("gradle_sync", emptyMap())))
    }

    @Test
    fun givenTheProjectRootAsTheDirectory_whenNamed_thenNothingIsShown() {
        // `list_files(directory=".")` is the commonest call of the run; "· ." is not information.
        assertNull(AgentActivity.subjectOf(ToolCall("list_files", mapOf("directory" to "."))))
        assertNull(AgentActivity.subjectOf(ToolCall("list_files", mapOf("directory" to "./"))))
    }

    @Test
    fun givenABlankArgument_whenNamed_thenNothingIsShown() {
        assertNull(AgentActivity.subjectOf(ToolCall("list_files", mapOf("directory" to "   "))))
    }

    @Test
    fun givenARunThatReadSixFiles_whenSummarised_thenTheToolIsNamedOnce() {
        val names = listOf("read_file", "read_file", "edit_file", "read_file", "")

        assertEquals(listOf("read_file", "edit_file"), AgentActivity.distinctNames(names))
    }

    @Test
    fun givenNoToolsAtAll_whenSummarised_thenThereIsNothingToName() {
        assertEquals(emptyList<String>(), AgentActivity.distinctNames(listOf("", "  ")))
    }
}
