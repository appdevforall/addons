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
    fun givenALongMessage_whenBuildingThePrompt_thenItIsCutAndEndsOnTheTitleCue() {
        // A letter that appears in none of the prompt's own labels.
        val prompt = ChatTitle.prompt(shippedConfig, "q".repeat(5_000))

        assertTrue(prompt.endsWith("Title:"))
        assertEquals(ChatTitle.EXCERPT_CHARS, prompt.count { it == 'q' })
    }

    @Test
    fun givenTheSourceKey_whenRead_thenItMatchesTheLiteralTheLocalBackendReads() {
        // Duplicated in AI-Agent-Local (LocalLlmBackend.EXTRA_PARAM_CHAT_TITLE_SOURCE); a rename on
        // one side alone would silently send every title back through the model.
        assertEquals("chat_title_source", ChatTitle.EXTRA_PARAM_SOURCE)
    }

    @Test
    fun givenAPaddedLongMessage_whenTakingItsExcerpt_thenItIsTrimmedAndCut() {
        assertEquals("fix the build", ChatTitle.excerpt("  fix the build \n"))
        assertEquals(ChatTitle.EXCERPT_CHARS, ChatTitle.excerpt("q".repeat(5_000)).length)
    }

    @Test
    fun givenABackendThatEchoesTheExcerpt_whenSanitized_thenTheTitleIsThePromptsFirstWords() {
        // What the local backend answers: the excerpt itself, with no model call behind it.
        val echoed = ChatTitle.excerpt("add a dark mode toggle to the settings screen and persist it\nthanks")

        assertEquals("add a dark mode toggle to the settings", ChatTitle.sanitize(echoed))
    }

    @Test
    fun givenTheShippedConfig_whenChecked_thenBothTextsRender() {
        assertEquals(emptyList<String>(), ChatTitle.problems(shippedConfig))
    }

    @Test
    fun givenAMessage_whenBuildingThePrompt_thenOnlyTheDevelopersWordsAreFramed() {
        assertEquals(
            "Developer: fix my build\n\nTitle:",
            ChatTitle.prompt(shippedConfig, "  fix my build "),
        )
    }

    @Test
    fun givenAMessageThatLooksLikeATemplate_whenBuildingThePrompt_thenItIsInsertedVerbatim() {
        // The user's own words are data; rendering them would throw on a stray tag.
        assertTrue(ChatTitle.prompt(shippedConfig, "{{#X}} name").contains("{{#X}} name"))
    }

    @Test
    fun givenTheShippedConfig_whenBuildingTheSystemPrompt_thenItAsksForAShortPlainTitle() {
        assertTrue(ChatTitle.systemPrompt(shippedConfig).startsWith("You name chat conversations"))
    }

    @Test
    fun givenANameTypoInTheLayout_whenChecked_thenTheProblemNamesTheText() {
        val config = shippedWith("layout.yml") { it.replace("{{USER_TEXT}}", "{{USER_TXT}}") }

        val problems = ChatTitle.problems(config)

        assertEquals(listOf("layout.yml: layout.chat_title: unknown name {{USER_TXT}}"), problems)
    }
}
