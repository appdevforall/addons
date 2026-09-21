package com.itsaky.androidide.plugins.aicore.backends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the one answer the settings selector, the chat status line and the availability check all
 * have to agree on. They resolved it three different ways before, so a launch could route to one
 * backend while the label named another.
 */
class AiBackendTest {

    private val installed = listOf("gemini", "local", "openai")

    @Test
    fun givenAStoredSelectionThatIsInstalled_whenResolving_thenItWins() {
        assertEquals("gemini", AiBackend.preferredId("gemini", installed))
    }

    @Test
    fun givenNothingStored_whenResolving_thenTheDefaultWins() {
        assertEquals(AiBackend.DEFAULT_ID, AiBackend.preferredId(null, installed))
    }

    @Test
    fun givenNothingStored_whenResolving_thenTheOrderOfTheInstalledSetDoesNotMatter() {
        assertEquals(
            AiBackend.preferredId(null, installed),
            AiBackend.preferredId(null, installed.reversed())
        )
    }

    @Test
    fun givenAStoredSelectionWhoseBackendIsGone_whenResolving_thenThereIsNoBackend() {
        assertNull(AiBackend.preferredId("uninstalled", installed))
    }

    @Test
    fun givenNothingStoredAndNoDefaultInstalled_whenResolving_thenTheFirstOfferedWins() {
        assertEquals("gemini", AiBackend.preferredId(null, listOf("gemini", "openai")))
    }

    @Test
    fun givenNothingInstalled_whenResolving_thenThereIsNoBackend() {
        assertNull(AiBackend.preferredId("local", emptyList()))
    }
}
