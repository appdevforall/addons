package com.itsaky.androidide.plugins.aicore.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [IdeContext], the value the prompt's IDE knowledge is carried in. */
class IdeContextTest {

    @Test
    fun givenAFocusedFile_whenChoosingTheExamplePath_thenItIsUsed() {
        // The examples then carry this project's own language and layout.
        val context = IdeContext("app/Main.kt", listOf("app/Other.kt"), emptyList())

        assertEquals("app/Main.kt", context.exampleFilePath)
    }

    @Test
    fun givenOnlyOtherOpenTabs_whenChoosingTheExamplePath_thenTheFirstTabIsUsed() {
        val context = IdeContext(null, listOf("lib/Util.kt", "app/Main.kt"), emptyList())

        assertEquals("lib/Util.kt", context.exampleFilePath)
    }

    @Test
    fun givenNothingOpen_whenChoosingTheExamplePath_thenTheFallbackIsUsed() {
        assertEquals(IdeContext.FALLBACK_EXAMPLE_PATH, IdeContext.EMPTY.exampleFilePath)
    }

    @Test
    fun givenNothingOpenAndNoModules_whenAskedIfEmpty_thenItIs() {
        assertTrue(IdeContext.EMPTY.isEmpty)
    }

    @Test
    fun givenModulesButNothingOpen_whenAskedIfEmpty_thenItIsNot() {
        // The module paths alone are worth a context block; they are what stops the tree walking.
        val context = IdeContext(null, emptyList(), listOf(module()))

        assertFalse(context.isEmpty)
    }

    private fun module() = ProjectLayout.Module(
        name = "app",
        sourceDir = "app/src/main/java/com/example",
        layoutDir = "app/src/main/res/layout",
        manifest = "app/src/main/AndroidManifest.xml",
    )
}
