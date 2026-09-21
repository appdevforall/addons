package com.itsaky.androidide.plugins.aicore.tool.sources

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.AgentTools
import com.itsaky.androidide.plugins.aicore.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolSourceStore] and the [AgentTools] snapshot built from it — above all the
 * trap that a rebuilt router with a stale grammar forbids every contributed tool with no error.
 */
class ToolSourceStoreTest {

    private companion object {
        const val TERMINAL_TOOL = "respond"
        const val SEARCH_PLUGIN = "com.example.searchplugin"
        const val MCP = "com.itsaky.androidide.plugins.aiagentmcp"
    }

    private class BuiltInHandler(override val toolName: String) : ToolHandler {
        override val description = "built in"
        override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
    }

    private val builtIns = listOf(BuiltInHandler("edit_file"), BuiltInHandler("read_file"))

    /**
     * The exact alternative the grammar carries for one tool name.
     *
     * Quoted as `ToolCallGrammar` writes it — a bare `contains(name)` would also match a longer
     * name that starts the same way, which is how `remote_tool_1` passes for `remote_tool_13`.
     */
    private fun alternative(name: String) = "\\\"$name\\\""

    private fun toolsFrom(store: ToolSourceStore): AgentTools = AgentTools.build(
        builtInHandlers = builtIns,
        store = store,
        approvalManager = ToolApprovalManager(),
        terminalTool = TERMINAL_TOOL,
    )

    @Test
    fun givenARegisteredSource_whenTheToolSetIsRebuilt_thenRouterAndGrammarBothCarryTheTool() {
        // The grammar is the half that fails silently: a stale one masks the name out of sampling.
        val store = ToolSourceStore()
        store.register(
            FakeToolSource(SEARCH_PLUGIN, tools = listOf(contributedTool(SEARCH_PLUGIN, "code_search")))
        )

        val tools = toolsFrom(store)

        assertNotNull(
            "the router must route the contributed tool",
            tools.router.getHandler("code_search")
        )
        assertTrue(
            "the grammar must allow the contributed tool: ${tools.grammar}",
            tools.grammar.contains("code_search")
        )
    }

    @Test
    fun givenAnUnregisteredSource_whenTheToolSetIsRebuilt_thenItIsGoneFromRouterAndGrammar() {
        val store = ToolSourceStore()
        store.register(
            FakeToolSource(SEARCH_PLUGIN, tools = listOf(contributedTool(SEARCH_PLUGIN, "code_search")))
        )
        store.unregister(SEARCH_PLUGIN)

        val tools = toolsFrom(store)

        assertNull(tools.router.getHandler("code_search"))
        assertFalse(tools.grammar.contains("code_search"))
    }

    @Test
    fun givenAChangedSource_whenAListenerIsRegistered_thenEveryMutationNotifiesIt() {
        val store = ToolSourceStore()
        var notifications = 0
        val listener: () -> Unit = { notifications++ }
        store.addChangeListener(listener)

        val source = FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "create_issue")))
        store.register(source)
        store.toolsChanged(MCP)
        store.unregister(MCP)
        store.removeChangeListener(listener)
        store.register(source)

        assertEquals("register, change and unregister each rebuild; a removed listener does not", 3, notifications)
    }

    @Test
    fun givenASourceThatThrowsListingTools_whenHandlersAreBuilt_thenTheOtherSourcesSurvive() {
        // One bad .cgp costs the user its own tools, not the agent.
        val store = ToolSourceStore()
        store.register(FakeToolSource("com.example.broken", listThrows = IllegalStateException("boom")))
        store.register(
            FakeToolSource(SEARCH_PLUGIN, tools = listOf(contributedTool(SEARCH_PLUGIN, "code_search")))
        )

        val handlers = store.handlers(setOf("edit_file"))

        assertEquals(1, handlers.size)
        assertEquals("code_search", handlers.first().toolName)
    }

    @Test
    fun givenAToolNamedLikeABuiltIn_whenHandlersAreBuilt_thenTheBuiltInKeepsTheName() {
        // A provider id with nothing to make an alias from is the only way to reach a bare name.
        val store = ToolSourceStore()
        store.register(FakeToolSource("***", tools = listOf(contributedTool("***", "edit_file"))))

        val tools = toolsFrom(store)

        assertTrue(
            "edit_file must still route to the built-in handler",
            tools.router.getHandler("edit_file") is BuiltInHandler
        )
        assertTrue(tools.contributedHandlers.isEmpty())
    }

    @Test
    fun givenTwoSourcesOfferingTheSameToolName_whenHandlersAreBuilt_thenTheSecondIsQualified() {
        val store = ToolSourceStore()
        store.register(FakeToolSource("com.example.one", tools = listOf(contributedTool("com.example.one", "search"))))
        store.register(FakeToolSource("com.example.one2", tools = listOf(contributedTool("com.example.one2", "search"))))

        val handlers = store.handlers(emptySet())

        assertEquals(listOf("search", "one2_search"), handlers.map { it.toolName })
    }

    @Test
    fun givenAToolNamedLikeTheTerminalTool_whenHandlersAreBuilt_thenItIsDroppedRatherThanQualified() {
        // Qualified to `aiagentmcp_respond`, it stays reachable through the router's suffix pass:
        // a model emitting `Respond` would have its final answer dispatched to the remote server.
        val store = ToolSourceStore()
        store.register(FakeToolSource(MCP, tools = listOf(contributedTool(MCP, TERMINAL_TOOL))))

        val tools = toolsFrom(store)

        assertTrue("nothing may register under a reserved name", tools.contributedHandlers.isEmpty())
        assertNull(tools.router.getHandler(TERMINAL_TOOL))
        assertNull("no suffix match may resolve it either", tools.router.getHandler("Respond"))
    }

    @Test
    fun givenAToolNamedLikeABuiltInUnderARealProviderId_whenHandlersAreBuilt_thenItIsDropped() {
        val store = ToolSourceStore()
        store.register(FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "edit_file"))))

        val tools = toolsFrom(store)

        assertTrue(tools.contributedHandlers.isEmpty())
        assertNull(tools.router.getHandler("aiagentmcp_edit_file"))
        assertTrue(tools.router.getHandler("edit_file") is BuiltInHandler)
    }

    @Test
    fun givenMoreContributedToolsThanThePromptBudget_whenTheToolSetIsBuilt_thenGrammarAndPromptAgree() {
        // The trap: a grammar built from every handler permits names the prompt never mentioned,
        // so the local backend's token mask and the model's instructions describe different sets.
        val store = ToolSourceStore()
        val count = PromptToolBudget.MAX_CONTRIBUTED_TOOLS + 8
        store.register(
            FakeToolSource(
                MCP,
                tools = (1..count).map { contributedTool(MCP, "remote_tool_$it") },
            )
        )

        val tools = toolsFrom(store)
        val promptNames = tools.promptTools.definitions.map { it.name }

        assertEquals(
            "the budget admits exactly its cap of contributed tools",
            PromptToolBudget.MAX_CONTRIBUTED_TOOLS,
            promptNames.count { it.startsWith("remote_tool_") },
        )
        assertEquals(8, tools.promptTools.droppedTools.size)
        for (name in promptNames) {
            assertTrue("the grammar must allow $name", tools.grammar.contains(alternative(name)))
        }
        for (name in tools.promptTools.droppedTools) {
            assertFalse(
                "the grammar must not allow $name, which the prompt never mentioned",
                tools.grammar.contains(alternative(name)),
            )
        }
        assertTrue(
            "every contributed tool still routes, budget or not",
            tools.promptTools.droppedTools.all { tools.router.getHandler(it) != null },
        )
        for (name in tools.promptTools.droppedTools) {
            assertTrue(
                "a failure may not offer back $name, which the token mask forbids",
                tools.router.suggestionsFor(name).isEmpty(),
            )
        }
    }

    @Test
    fun givenAToolNamedLikeABuiltIn_whenHandlersAreBuilt_thenItIsDropped() {
        // Shadowing a built-in would route the model's `read_file` at a remote server instead.
        val store = ToolSourceStore()
        store.register(FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "read_file"))))

        val tools = toolsFrom(store)

        assertTrue(tools.contributedHandlers.isEmpty())
        assertSame(
            "the built-in must still own the name",
            builtIns.first { it.toolName == "read_file" },
            tools.router.getHandler("read_file"),
        )
        assertNull("no suffix match may resolve it either", tools.router.getHandler("aiagentmcp_read_file"))
    }

    @Test
    fun givenAToolForEveryReservedName_whenHandlersAreBuilt_thenNoneOfThemRegister() {
        // The invariant that survives the approval gate keying off the handler alone: what is
        // reserved is the built-ins plus the terminal tool, and nothing may claim one of them.
        val store = ToolSourceStore()
        val reserved = builtIns.map { it.toolName } + TERMINAL_TOOL
        store.register(FakeToolSource(MCP, tools = reserved.map { contributedTool(MCP, it) }))

        val tools = toolsFrom(store)

        assertTrue(
            "every reserved name must be refused: ${tools.contributedHandlers.map { it.toolName }}",
            tools.contributedHandlers.isEmpty(),
        )
    }

    @Test
    fun givenABlankProviderId_whenRegistered_thenItIsRefused() {
        val store = ToolSourceStore()

        store.register(FakeToolSource("   ", tools = listOf(contributedTool("   ", "search"))))

        assertTrue(store.sources().isEmpty())
    }

    @Test
    fun givenARegisteredSource_whenReRegisteredUnderTheSameId_thenItReplacesRatherThanDuplicates() {
        // Re-registration is how a provider recovers after this plugin restarts.
        val store = ToolSourceStore()
        store.register(FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "create_issue"))))
        store.register(FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "list_issues"))))

        val handlers = store.handlers(emptySet())

        assertEquals(1, store.sources().size)
        assertEquals(listOf("list_issues"), handlers.map { it.toolName })
    }
}
