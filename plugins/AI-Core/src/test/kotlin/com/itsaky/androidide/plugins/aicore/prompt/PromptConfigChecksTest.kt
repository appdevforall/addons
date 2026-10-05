package com.itsaky.androidide.plugins.aicore.prompt

import android.content.res.AssetManager
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ai.prompt.AssetPromptConfigSource
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfigParser
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.BuiltInToolHandlers
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config as activation loads and checks it: through the plugin's assets, then every render
 * check at once, so a shipped YAML edit that would break a chat turn fails here instead.
 */
class PromptConfigChecksTest {

    /** The catalogue activation checks against, not a copy of it. */
    private val builtIns: List<ToolHandler> =
        BuiltInToolHandlers.create(mockk<PluginContext>(relaxed = true))

    /** The module's assets directory behind an [AssetManager], recording every path opened. */
    private fun shippedAssets(opened: MutableList<String> = mutableListOf()): AssetManager =
        mockk<AssetManager>().also { assets ->
            every { assets.open(any()) } answers {
                val path = firstArg<String>().also(opened::add)
                val file = File("src/main/assets", path)
                if (!file.isFile) throw FileNotFoundException(path)
                file.inputStream()
            }
        }

    @Test
    fun givenTheShippedAssets_whenLoadedAsActivationDoes_thenEveryCheckPasses() {
        val opened = mutableListOf<String>()

        val source = AssetPromptConfigSource(shippedAssets(opened))
        val config = runBlocking { PromptConfigLoader.load(source, AgentPromptConfigParser) }

        assertEquals(emptyList<String>(), PromptConfigChecks.problems(config, builtIns))
        assertTrue("read outside prompts/: $opened", opened.isNotEmpty() && opened.all { it.startsWith("prompts/") })
    }

    @Test
    fun givenAnIncludedAssetMissing_whenLoadedAsActivationDoes_thenTheLoadIsRefused() {
        val assets = shippedAssets()
        every { assets.open("prompts/rules.yml") } throws FileNotFoundException("prompts/rules.yml")

        val error = assertThrows(PromptConfigException::class.java) {
            runBlocking { PromptConfigLoader.load(AssetPromptConfigSource(assets), AgentPromptConfigParser) }
        }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("rules.yml"))
    }

    @Test
    fun givenATypoInALayout_whenChecked_thenActivationReportsItByFileAndPath() {
        val config = shippedWith("layout.yml") { it.replace("{{DRAFT}}", "{{DRAFTT}}") }

        assertEquals(
            listOf("layout.yml: layout.answer_review: unknown name {{DRAFTT}}"),
            PromptConfigChecks.problems(config, builtIns),
        )
    }
}
