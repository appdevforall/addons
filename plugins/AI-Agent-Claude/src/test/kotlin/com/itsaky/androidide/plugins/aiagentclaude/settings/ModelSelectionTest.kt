package com.itsaky.androidide.plugins.aiagentclaude.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Retiring a model the API no longer offers. The failure this prevents is silent: the settings
 * pane looks configured, and the 404 only lands on the first message.
 */
class ModelSelectionTest {

    private val catalog = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5")

    private fun adopt(current: String, models: List<String>, isLive: Boolean = true) =
        ModelSelection.adopt(current, models, isLive, preferred = "claude-opus-5-5")

    @Test
    fun givenAModelTheCatalogOffers_whenAdopting_thenItIsKept() {
        assertNull(adopt("claude-haiku-4-5", catalog))
    }

    @Test
    fun givenNoCatalog_whenAdopting_thenTheModelIsKept() {
        // An empty list means nothing was discovered, which says nothing about the saved model.
        assertNull(adopt("claude-opus-4-1", emptyList()))
    }

    @Test
    fun givenALiveCatalogWithoutTheModel_whenAdopting_thenThePreferredOneIsTaken() {
        assertEquals("claude-opus-5-5", adopt("claude-retired-model", catalog))
    }

    @Test
    fun givenALiveCatalogWithoutThePreferredModel_whenAdopting_thenTheFirstIsTaken() {
        assertEquals("claude-sonnet-5-5", adopt("claude-retired-model", listOf("claude-sonnet-5-5", "claude-haiku-4-5")))
    }

    @Test
    fun givenAnAliasTheCatalogListsByDatedId_whenAdopting_thenItIsKept() {
        // The real catalog lists Haiku only as claude-haiku-4-5-20251001; retiring the alias the
        // user picked swapped in Opus 5.5, about four times the price (ADFA-6311, on device).
        assertNull(adopt("claude-haiku-4-5", listOf("claude-opus-5-5", "claude-haiku-4-5-20251001")))
    }

    @Test
    fun givenARememberedCatalog_whenAdopting_thenTheModelIsKept() {
        // A remembered list can be months old; a model missing from it may still work, and the
        // fallback list is not a catalog at all.
        assertNull(adopt("claude-opus-4-8", catalog, isLive = false))
    }
}
