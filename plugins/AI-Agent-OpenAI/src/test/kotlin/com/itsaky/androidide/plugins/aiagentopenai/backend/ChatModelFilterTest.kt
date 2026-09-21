package com.itsaky.androidide.plugins.aiagentopenai.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-capability heuristic. `GET /v1/models` carries no capability flag, so this is the only
 * thing keeping embedding and audio models out of the picker.
 */
class ChatModelFilterTest {

    @Test
    fun givenChatModels_whenFiltered_thenTheyAreKept() {
        listOf("gpt-5", "gpt-4o", "o3-mini", "qwen2.5-coder:7b", "llama3.2", "mistral-small")
            .forEach { assertTrue("$it should be kept", ChatModelFilter.isPlausibleChatModel(it)) }
    }

    @Test
    fun givenEmbeddingModels_whenFiltered_thenTheyAreDropped() {
        listOf("text-embedding-3-small", "nomic-embed-text", "mxbai-embed-large")
            .forEach { assertFalse("$it should be dropped", ChatModelFilter.isPlausibleChatModel(it)) }
    }

    @Test
    fun givenAudioModels_whenFiltered_thenTheyAreDropped() {
        listOf("whisper-1", "tts-1-hd", "gpt-4o-audio-preview", "gpt-4o-realtime-preview")
            .forEach { assertFalse("$it should be dropped", ChatModelFilter.isPlausibleChatModel(it)) }
    }

    @Test
    fun givenImageModels_whenFiltered_thenTheyAreDropped() {
        listOf("dall-e-3", "stable-diffusion-xl", "flux-schnell")
            .forEach { assertFalse("$it should be dropped", ChatModelFilter.isPlausibleChatModel(it)) }
    }

    @Test
    fun givenModerationModels_whenFiltered_thenTheyAreDropped() {
        listOf("omni-moderation-latest", "llama-guard-3-8b")
            .forEach { assertFalse("$it should be dropped", ChatModelFilter.isPlausibleChatModel(it)) }
    }

    @Test
    fun givenAnUnknownModelId_whenFiltered_thenItIsKept() {
        // Denylist, not allowlist: a wrongly hidden model cannot be selected at all, while a
        // wrongly offered one fails once with a clear server error.
        assertTrue(ChatModelFilter.isPlausibleChatModel("some-new-model-2027"))
        assertTrue(ChatModelFilter.isPlausibleChatModel("my-finetune-abc123"))
    }

    @Test
    fun givenAMixedCatalog_whenFiltered_thenOnlyChatModelsRemainSorted() {
        val filtered = ChatModelFilter.chatModels(
            listOf("gpt-4o", "text-embedding-3-small", "gpt-5", "whisper-1", "dall-e-3")
        )
        assertEquals(listOf("gpt-4o", "gpt-5"), filtered)
    }

    @Test
    fun givenDuplicatesAndBlanks_whenFiltered_thenTheyAreRemoved() {
        val filtered = ChatModelFilter.chatModels(listOf("gpt-4o", " gpt-4o ", "", "   "))
        assertEquals(listOf("gpt-4o"), filtered)
    }

    @Test
    fun givenAnUppercaseId_whenFiltered_thenTheMarkerStillMatches() {
        assertFalse(ChatModelFilter.isPlausibleChatModel("TEXT-EMBEDDING-3-LARGE"))
    }

    @Test
    fun givenAnEmptyCatalog_whenFiltered_thenTheResultIsEmpty() {
        assertTrue(ChatModelFilter.chatModels(emptyList()).isEmpty())
    }
}
