package com.itsaky.androidide.plugins.aiagentmcp.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the merge behind Save.
 *
 * The settings dialog holds a snapshot taken when it opened, while the tool switches write straight
 * through as they are tapped. Saving that snapshot whole reverted every switch the user had just
 * set, which looked exactly like the switches not persisting at all.
 */
class McpServerStoreMergeTest {

    private val stored = McpServer(
        id = "server-1",
        name = "Test",
        url = "http://10.0.2.2:8002/mcp",
        enabled = true,
        knownTools = listOf("add", "greet", "now"),
        enabledTools = setOf("add", "greet"),
    )

    @Test
    fun givenStoredToggles_whenSavingADialogSnapshot_thenTogglesSurvive() {
        // What the dialog builds: its opening snapshot, which predates the switches.
        val edited = stored.copy(name = "Renamed", enabledTools = emptySet(), knownTools = emptyList())

        val merged = McpServerStore.mergeDetails(stored, edited)

        assertEquals(setOf("add", "greet"), merged.enabledTools)
        assertEquals(listOf("add", "greet", "now"), merged.knownTools)
    }

    @Test
    fun givenStoredServer_whenSavingADialogSnapshot_thenNameAndUrlAreTaken() {
        val edited = stored.copy(name = "Renamed", url = "https://example.test/mcp")

        val merged = McpServerStore.mergeDetails(stored, edited)

        assertEquals("Renamed", merged.name)
        assertEquals("https://example.test/mcp", merged.url)
        assertEquals(stored.id, merged.id)
    }

    @Test
    fun givenDisabledServer_whenSavingADialogSnapshot_thenTheServerSwitchIsNotTouched() {
        // The server switch lives on the list screen, so the dialog must never write it back.
        val disabled = stored.copy(enabled = false)

        val merged = McpServerStore.mergeDetails(disabled, stored.copy(enabled = true))

        assertTrue(!merged.enabled)
    }

    @Test
    fun givenNoStoredServer_whenSavingANewOne_thenItIsStoredAsGiven() {
        val fresh = McpServer(id = "server-2", name = "New", url = "http://localhost:8002/mcp")

        val merged = McpServerStore.mergeDetails(null, fresh)

        assertEquals(fresh, merged)
    }
}
