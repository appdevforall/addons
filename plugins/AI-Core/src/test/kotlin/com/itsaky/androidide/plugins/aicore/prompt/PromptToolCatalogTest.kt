package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.tool.AgentTools
import com.itsaky.androidide.plugins.aicore.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [PromptToolCatalog], the one tool list both halves of the protocol are built from. */
class PromptToolCatalogTest {

    private val tools = AgentTools.build(
        builtInHandlers = emptyList(),
        store = ToolSourceStore(),
        approvalManager = ToolApprovalManager({ shippedConfig }),
        terminalTool = "respond",
    )

    @Test
    fun givenASnapshot_whenListingForThePrompt_thenTheTerminalToolIsAppended() {
        // It is not a handler, so the snapshot does not carry it, but the model must be told it exists.
        val names = PromptToolCatalog.definitions(tools, "respond", shippedConfig).map { it.name }

        assertEquals(listOf("respond"), names.takeLast(1))
    }

    @Test
    fun givenASnapshot_whenListingForThePrompt_thenTheTerminalToolCarriesAMessageParameter() {
        // A parameterless declaration is one a natively calling model cannot put the answer in,
        // which is the empty respond the description warns about.
        val respond = PromptToolCatalog.definitions(tools, "respond", shippedConfig).last()

        @Suppress("UNCHECKED_CAST")
        val schema = respond.parametersSchema!!
        val properties = schema["properties"] as Map<String, Any?>
        assertTrue(properties.containsKey("message"))
        assertEquals(listOf("message"), schema["required"])
    }

    @Test
    fun givenARenamedTerminalTool_whenListingForThePrompt_thenTheNewNameIsDeclared() {
        val names = PromptToolCatalog.definitions(tools, "answer", shippedConfig).map { it.name }

        assertTrue(names.contains("answer"))
    }

    @Test
    fun givenARenamedTerminalTool_whenListingForThePrompt_thenItsDescriptionUsesThatName() {
        // A description that still says "respond" names a tool the run does not offer.
        val answer = PromptToolCatalog.definitions(tools, "answer", shippedConfig).last()

        assertTrue(answer.description.contains("calling answer with no"))
        assertFalse(answer.description.contains("respond"))
    }
}
