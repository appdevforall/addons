package com.itsaky.androidide.plugins.aicore.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTitleTest {

    @Test
    fun givenAPlainTitle_whenSanitized_thenItIsKeptAsIs() {
        assertEquals("Fix Gradle sync failure", ChatTitle.sanitize("Fix Gradle sync failure"))
    }

    @Test
    fun givenAQuotedLabelledMarkdownTitle_whenSanitized_thenOnlyTheWordsRemain() {
        assertEquals("Add dark mode toggle", ChatTitle.sanitize("**Title:** \"Add dark mode toggle.\""))
    }

    @Test
    fun givenAReasoningBlockAndExtraLines_whenSanitized_thenTheFirstAnswerLineIsTheTitle() {
        val raw = "<think>\nThe user wants a RecyclerView.\n</think>\n\nBuild a RecyclerView list\nThis title..."

        assertEquals("Build a RecyclerView list", ChatTitle.sanitize(raw))
    }

    @Test
    fun givenAQuestionTitle_whenSanitized_thenItsQuestionMarkIsKept() {
        assertEquals("Why does the build fail?", ChatTitle.sanitize("Why does the build fail?"))
    }

    @Test
    fun givenAnOverlongAnswer_whenSanitized_thenItIsCappedInWordsAndCharacters() {
        val title = ChatTitle.sanitize("one two three four five six seven eight nine ten")!!

        assertEquals("one two three four five six seven eight", title)
        assertTrue(ChatTitle.sanitize("x".repeat(200))!!.length <= ChatTitle.MAX_CHARS)
    }

    @Test
    fun givenABlankOrPunctuationOnlyAnswer_whenSanitized_thenThereIsNoTitle() {
        assertNull(ChatTitle.sanitize("  \n\n "))
        assertNull(ChatTitle.sanitize("\"...\""))
        assertNull(ChatTitle.sanitize("<think>only reasoning</think>"))
    }

    @Test
    fun givenALongExchange_whenBuildingThePrompt_thenEachSideIsCutAndItEndsOnTheTitleCue() {
        // Letters that appear in none of the prompt's own labels.
        val prompt = ChatTitle.prompt("q".repeat(5_000), "z".repeat(5_000))

        assertTrue(prompt.endsWith("Title:"))
        assertEquals(ChatTitle.EXCERPT_CHARS, prompt.count { it == 'q' })
        assertEquals(ChatTitle.EXCERPT_CHARS, prompt.count { it == 'z' })
    }
}
