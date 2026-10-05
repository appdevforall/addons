package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
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
        val prompt = ChatTitle.prompt(shippedConfig, "q".repeat(5_000), "z".repeat(5_000))

        assertTrue(prompt.endsWith("Title:"))
        assertEquals(ChatTitle.EXCERPT_CHARS, prompt.count { it == 'q' })
        assertEquals(ChatTitle.EXCERPT_CHARS, prompt.count { it == 'z' })
    }

    @Test
    fun givenTheShippedConfig_whenChecked_thenBothTextsRender() {
        assertEquals(emptyList<String>(), ChatTitle.problems(shippedConfig))
    }

    @Test
    fun givenAnExchange_whenBuildingThePrompt_thenItIsFramedAsAConversation() {
        assertEquals(
            "Conversation:\nDeveloper: fix my build\nAssistant: Sync Gradle first.\n\nTitle:",
            ChatTitle.prompt(shippedConfig, "  fix my build ", "Sync Gradle first."),
        )
    }

    @Test
    fun givenAnExchangeThatLooksLikeATemplate_whenBuildingThePrompt_thenItIsInsertedVerbatim() {
        // The user's own words are data; rendering them would throw on a stray tag.
        assertTrue(ChatTitle.prompt(shippedConfig, "{{#X}} name", "ok").contains("{{#X}} name"))
    }

    @Test
    fun givenTheShippedConfig_whenBuildingTheSystemPrompt_thenItAsksForAShortPlainTitle() {
        assertTrue(ChatTitle.systemPrompt(shippedConfig).startsWith("You name chat conversations"))
    }

    @Test
    fun givenANameTypoInTheLayout_whenChecked_thenTheProblemNamesTheText() {
        val config = shippedWith("layout.yml") { it.replace("{{REPLY_TEXT}}", "{{REPLY_TXT}}") }

        val problems = ChatTitle.problems(config)

        assertEquals(listOf("layout.yml: layout.chat_title: unknown name {{REPLY_TXT}}"), problems)
    }
}
