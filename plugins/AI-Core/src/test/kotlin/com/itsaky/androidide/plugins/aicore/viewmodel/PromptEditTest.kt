package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which prompt an edit replaces rather than forks, and what a rewind to it keeps (ADFA-6276). */
class PromptEditTest {

    private val transcript = listOf(
        message("u1", Sender.USER),
        message("a1", Sender.AGENT),
        message("u2", Sender.USER),
        message("t2", Sender.TOOL),
        message("a2", Sender.AGENT),
        message("s2", Sender.SYSTEM),
    )

    @Test
    fun givenTheNewestUserPrompt_whenAskingIfItIsTheLatest_thenItIs() {
        assertTrue(PromptEdit.isLatestPrompt(transcript, "u2"))
    }

    @Test
    fun givenAnOlderPrompt_whenAskingIfItIsTheLatest_thenItIsNot() {
        assertFalse(PromptEdit.isLatestPrompt(transcript, "u1"))
    }

    @Test
    fun givenNoUserPrompt_whenAskingIfAMessageIsTheLatest_thenNoneIs() {
        val agentOnly = listOf(message("s1", Sender.SYSTEM), message("a1", Sender.AGENT))

        assertFalse(PromptEdit.isLatestPrompt(agentOnly, "a1"))
    }

    @Test
    fun givenTheNewestPrompt_whenCuttingBeforeIt_thenItAndEverythingAfterItAreDropped() {
        val kept = PromptEdit.messagesBefore(transcript, "u2")

        assertEquals(listOf("u1", "a1"), kept?.map { it.id })
    }

    @Test
    fun givenAnOlderPrompt_whenCuttingBeforeIt_thenNothingIsCut() {
        assertNull(PromptEdit.messagesBefore(transcript, "u1"))
    }

    @Test
    fun givenAnAgentMessage_whenCuttingBeforeIt_thenNothingIsCut() {
        assertNull(PromptEdit.messagesBefore(transcript, "a2"))
    }

    @Test
    fun givenTheOnlyPrompt_whenCuttingBeforeIt_thenTheTranscriptIsEmpty() {
        val single = listOf(message("u1", Sender.USER), message("a1", Sender.AGENT))

        assertEquals(emptyList<ChatMessage>(), PromptEdit.messagesBefore(single, "u1"))
    }

    private fun message(id: String, sender: Sender) =
        ChatMessage(id = id, text = "text of $id", sender = sender)
}
