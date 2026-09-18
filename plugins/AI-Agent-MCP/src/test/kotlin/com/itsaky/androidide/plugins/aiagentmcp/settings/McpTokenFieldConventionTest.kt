package com.itsaky.androidide.plugins.aiagentmcp.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the one rule the token field's placeholder promises: an empty field on an existing
 * server leaves the stored token alone.
 *
 * It used to be written twice in the settings screen, in two shapes — one trimmed, one did not — so
 * it is pinned here now that [McpSettingsViewModel] owns it.
 */
class McpTokenFieldConventionTest {

    private val viewModel = McpSettingsViewModel { null }

    @Test
    fun givenAnEmptyTokenField_whenSaving_thenTheStoredTokenIsLeftAlone() {
        assertNull(viewModel.tokenToStore(""))
        assertNull(viewModel.tokenToStore("   "))
    }

    @Test
    fun givenATypedToken_whenSaving_thenItIsStoredTrimmed() {
        // Untrimmed was the other half of the inconsistency: a pasted token often carries spaces.
        assertEquals("secret", viewModel.tokenToStore("  secret  "))
    }
}
