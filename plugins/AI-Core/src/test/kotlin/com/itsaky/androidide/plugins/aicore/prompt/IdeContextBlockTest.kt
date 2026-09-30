package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the shipped IDE CONTEXT block (`ide_context.yml`, `layout.yml`), which every prompt ends with. */
class IdeContextBlockTest {

    @Test
    fun givenNothingOpenAndNoModules_whenRendering_thenOnlyTheSessionLinesRemain() {
        // No facts drops the IDE CONTEXT block, heading and all; the clock is always stated.
        val block = SystemPromptRenderer.renderIdeContext(shippedConfig, IdeContext.EMPTY, SESSION)

        assertEquals(2, block.lines().size)
        assertFalse(block.contains("IDE CONTEXT"))
    }

    @Test
    fun givenAnySession_whenRendering_thenTheDeviceTimeIsStatedFirst() {
        // Without it every model answers "what time is it" with "I have no access to the time".
        val block = SystemPromptRenderer.renderIdeContext(shippedConfig, IdeContext.EMPTY, SESSION)

        assertEquals("Current date and time on the user's device: $TIME", block.lines().first())
    }

    @Test
    fun givenAnyRun_whenRendering_thenTheWebToolsAreNamedAndRefusalIsForbidden() {
        val block = SystemPromptRenderer.renderIdeContext(shippedConfig, IdeContext.EMPTY, SESSION)

        assertTrue(block.contains("call web_search"))
        assertTrue(block.contains("call fetch_url"))
        assertTrue(block.contains("Never say you cannot access the internet."))
    }

    @Test
    fun givenOnlyAFocusedFile_whenRendering_thenNoModuleLineOrBlankLineAppears() {
        val block = facts(IdeContext("app/Main.kt", emptyList(), emptyList()))

        assertEquals(
            listOf(
                "IDE CONTEXT (real paths — use these verbatim, do not rewrite them):",
                "- File the user is viewing: app/Main.kt",
                "If the user names a file that appears above, use that exact path and do not " +
                    "guess a different folder or extension.",
            ),
            // Past the session lines and the blank line that separates them from the block.
            block.lines().drop(3),
        )
    }

    @Test
    fun givenAFocusedFile_whenRendering_thenItsExactPathIsNamed() {
        // Without it the model reconstructs a path, which is where invented `.java` paths for
        // Kotlin files came from.
        val block = facts(IdeContext("app/Main.kt", emptyList(), emptyList()))

        assertTrue(block.contains("- File the user is viewing: app/Main.kt"))
    }

    @Test
    fun givenOtherOpenTabs_whenRendering_thenTheyAreListedTogether() {
        val block = facts(
            IdeContext("app/Main.kt", listOf("lib/A.kt", "lib/B.kt"), emptyList())
        )

        assertTrue(block.contains("- Other open files: lib/A.kt, lib/B.kt"))
    }

    @Test
    fun givenAModule_whenRendering_thenItsSourceLayoutAndManifestAreNamed() {
        val block = facts(IdeContext(null, emptyList(), listOf(module())))

        assertTrue(block.contains("- New classes for module 'app' go in: app/src/main/java"))
        assertTrue(block.contains("- Layouts for module 'app': app/src/main/res/layout"))
        assertTrue(block.contains("- Manifest for module 'app': app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun givenAModule_whenRendering_thenRediscoveringItIsForbidden() {
        // The seven-of-sixteen-turn tree walk this block exists to prevent.
        val block = facts(IdeContext(null, emptyList(), listOf(module())))

        assertTrue(block.contains("do not call list_files to rediscover them"))
    }

    @Test
    fun givenAModuleWithNoLayoutDir_whenRendering_thenNoLayoutLineIsInvented() {
        val block = facts(
            IdeContext(null, emptyList(), listOf(module().copy(layoutDir = null)))
        )

        assertFalse(block.contains("Layouts for module"))
    }

    private fun facts(context: IdeContext): String =
        SystemPromptRenderer.renderIdeContext(shippedConfig, context, SESSION)

    private companion object {
        const val TIME = "Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)"
        val SESSION = SessionContext(TIME)
    }

    private fun module() = ProjectLayout.Module(
        name = "app",
        sourceDir = "app/src/main/java",
        layoutDir = "app/src/main/res/layout",
        manifest = "app/src/main/AndroidManifest.xml",
    )
}
