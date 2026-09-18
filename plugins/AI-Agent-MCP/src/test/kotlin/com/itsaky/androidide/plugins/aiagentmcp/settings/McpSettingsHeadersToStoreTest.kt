package com.itsaky.androidide.plugins.aiagentmcp.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the header rows mean when the dialog saves them.
 *
 * `setHeaders` replaces the whole stored map, so this predicate is the only thing standing between
 * a keystore that would not answer and a user losing headers they were just told were intact: the
 * rows on screen may replace what is stored only when the dialog drew what is stored.
 */
class McpSettingsHeadersToStoreTest {

    private val viewModel = McpSettingsViewModel { null }

    private val typed = mapOf("X-Api-Key" to "abc")

    @Test
    fun givenTheStoredHeadersWereDrawn_whenSavingRows_thenTheyReplaceThem() {
        assertEquals(typed, viewModel.headersToStore(typed, headersKnown = true))
    }

    @Test
    fun givenTheStoredHeadersWereDrawn_whenSavingNoRows_thenTheyAreDeleted() {
        // The user removed every row they could see, so an empty map is the intended replacement.
        assertEquals(emptyMap<String, String>(), viewModel.headersToStore(emptyMap(), headersKnown = true))
    }

    @Test
    fun givenTheStoredHeadersCouldNotBeRead_whenSavingNoRows_thenTheyAreLeftAlone() {
        // An empty list here means "never drawn", not "none": nothing may be written over them.
        assertNull(viewModel.headersToStore(emptyMap(), headersKnown = false))
    }

    @Test
    fun givenTheStoredHeadersCouldNotBeRead_whenSavingATypedRow_thenTheyAreStillLeftAlone() {
        // The regression this pins: one typed row used to be an implicit replacement of a set the
        // user never saw, which deleted the rest of it. The dialog refuses such a save instead.
        assertNull(viewModel.headersToStore(typed, headersKnown = false))
    }
}
