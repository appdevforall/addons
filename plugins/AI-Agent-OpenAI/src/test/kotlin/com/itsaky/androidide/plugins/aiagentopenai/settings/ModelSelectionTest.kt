package com.itsaky.androidide.plugins.aiagentopenai.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Retiring a model the configured server cannot serve. The failure this prevents is silent: the
 * settings pane looks configured, and the 404 only lands on the first message.
 */
class ModelSelectionTest {

    private val openAiCatalog = listOf("gpt-5", "gpt-5-mini", "gpt-4o")
    private val localCatalog = listOf("qwen2.5-coder", "llama3.2")

    private fun adopt(
        current: String,
        models: List<String>,
        isLive: Boolean = true,
        savedForThisServer: Boolean = true,
    ) = ModelSelection.adopt(current, models, isLive, savedForThisServer, preferred = "gpt-5")

    @Test
    fun givenAModelTheCatalogOffers_whenAdopting_thenItIsKept() {
        assertNull(adopt("gpt-4o", openAiCatalog))
    }

    @Test
    fun givenNoCatalog_whenAdopting_thenTheModelIsKept() {
        // An empty list means nothing was discovered, which says nothing about the saved model.
        assertNull(adopt("qwen2.5-coder", emptyList()))
    }

    @Test
    fun givenALiveCatalogWithoutTheModel_whenAdopting_thenThePreferredOneIsTaken() {
        assertEquals("gpt-5", adopt("qwen2.5-coder", openAiCatalog))
    }

    @Test
    fun givenALiveCatalogWithoutThePreferredModel_whenAdopting_thenTheFirstIsTaken() {
        assertEquals("qwen2.5-coder", adopt("gpt-5", localCatalog))
    }

    @Test
    fun givenARememberedCatalogForThisServer_whenAdopting_thenTheModelIsKept() {
        // A remembered list can be months old; a model missing from it may still work.
        assertNull(adopt("gpt-4.1", openAiCatalog, isLive = false))
    }

    @Test
    fun givenARememberedCatalogAfterAServerChange_whenAdopting_thenTheModelIsReplaced() {
        // The model belongs to the previous server, so anything this one listed beats it.
        assertEquals(
            "qwen2.5-coder",
            adopt("gpt-5", localCatalog, isLive = false, savedForThisServer = false),
        )
    }
}
