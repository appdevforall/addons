package com.itsaky.androidide.plugins.vectorsearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReindexDecisionTest {

    @Test
    fun givenTheSameRootsAndEmbedder_whenDeciding_thenTheIndexIsReused() {
        val state = IndexState(ROOTS, OPENAI_SMALL, chunkCount = 120)

        assertTrue(ReindexDecision.isReusable(state, ROOTS, OPENAI_SMALL))
    }

    @Test
    fun givenNothingIndexedYet_whenDeciding_thenTheIndexIsBuilt() {
        assertFalse(ReindexDecision.isReusable(null, ROOTS, OPENAI_SMALL))
    }

    @Test
    fun givenDifferentRoots_whenDeciding_thenTheIndexIsBuilt() {
        val state = IndexState("/other/project", OPENAI_SMALL, chunkCount = 120)

        assertFalse(ReindexDecision.isReusable(state, ROOTS, OPENAI_SMALL))
    }

    @Test
    fun givenAnotherProvider_whenDeciding_thenTheIndexIsBuilt() {
        // Switching OpenAI to Gemini is the case the ticket names: the widths may even match, and
        // the vectors would still be mutually meaningless.
        val state = IndexState(ROOTS, GEMINI, chunkCount = 120)

        assertFalse(ReindexDecision.isReusable(state, ROOTS, OPENAI_SMALL))
    }

    @Test
    fun givenAnotherModelOfTheSameWidth_whenDeciding_thenTheIndexIsBuilt() {
        // text-embedding-3-small and ada-002 are both 1536-d and mutually incomparable, so width
        // alone cannot detect the swap — which is why the model id is part of the identity.
        val state = IndexState(ROOTS, OPENAI_ADA, chunkCount = 120)

        assertFalse(ReindexDecision.isReusable(state, ROOTS, OPENAI_SMALL))
    }

    @Test
    fun givenTheSameModelAtAnotherWidth_whenDeciding_thenTheIndexIsBuilt() {
        val narrowed = EmbedderIdentity(OPENAI_SMALL.key, dimensions = 512)
        val state = IndexState(ROOTS, narrowed, chunkCount = 120)

        assertFalse(ReindexDecision.isReusable(state, ROOTS, OPENAI_SMALL))
    }

    @Test
    fun givenABuildThatProducedNothing_whenDeciding_thenItIsNotAttemptedAgain() {
        // A backend that refuses every call would otherwise have a full project walk and a billed
        // embedding attempt launched by every search the user types.
        val state = IndexState(ROOTS, OPENAI_SMALL, chunkCount = 0)

        assertTrue(ReindexDecision.isReusable(state, ROOTS, OPENAI_SMALL))
    }

    private companion object {
        const val ROOTS = "/project/app|/project/lib"
        val OPENAI_SMALL =
            EmbedderIdentity(EmbedderKey("openai", "text-embedding-3-small"), 1536)
        val OPENAI_ADA =
            EmbedderIdentity(EmbedderKey("openai", "text-embedding-ada-002"), 1536)
        val GEMINI =
            EmbedderIdentity(EmbedderKey("gemini", "gemini-embedding-001"), 1536)
    }
}
