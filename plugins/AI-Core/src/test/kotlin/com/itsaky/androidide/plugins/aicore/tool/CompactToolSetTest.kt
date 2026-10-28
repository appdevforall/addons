package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.prompt.PromptToolCatalog
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.tool.handlers.BuiltInToolHandlers
import com.itsaky.androidide.plugins.aicore.tool.sources.FakeToolSource
import com.itsaky.androidide.plugins.aicore.tool.sources.PromptToolBudget
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore
import com.itsaky.androidide.plugins.aicore.tool.sources.contributedTool
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact tool set a small on-device model is offered (ADFA-6757): which runs get it, that its
 * prompt and grammar agree, and that it stays small.
 */
class CompactToolSetTest {

    /** The catalogue the chat registers, so a built-in added or renamed later is seen here too. */
    private val builtIns: List<ToolHandler> =
        BuiltInToolHandlers.create(mockk<PluginContext>(relaxed = true))

    private val full =
        AgentTools.build(builtIns, ToolSourceStore(), ToolApprovalManager({ shippedConfig }), terminalTool = "respond")

    private val compact = full.forBackend(callsToolsNatively = false, hasOwnPrompt = true, contextWindowTokens = 16_384)

    private fun namesIn(tools: AgentTools) = tools.promptTools.definitions.map { it.name }

    /** The grammar spells each tool as a quoted alternative, so this is exact, not a substring of a longer name. */
    private fun grammarAllows(tools: AgentTools, tool: String) = tools.grammar.contains("\\\"$tool\\\"")

    @Test
    fun givenTheRegisteredBuiltIns_whenTheCompactSetIsChecked_thenEveryNameInItIsARealTool() {
        // A rename in a handler would otherwise shrink the compact set silently, leaving the model
        // a tool short with nothing failing.
        val registered = builtIns.map { it.toolName }.toSet()

        assertEquals(emptySet<String>(), PromptToolBudget.COMPACT_BUILT_INS - registered)
    }

    @Test
    fun givenASmallTextProtocolBackend_whenToolsAreChosen_thenOnlyTheCompactBuiltInsAreOffered() {
        assertEquals(PromptToolBudget.COMPACT_BUILT_INS, namesIn(compact).toSet())
        assertTrue(namesIn(full).size > namesIn(compact).size)
    }

    @Test
    fun givenTheCompactSet_whenItsGrammarIsRead_thenItAllowsExactlyTheToolsThePromptDescribes() {
        for (tool in namesIn(compact)) assertTrue("$tool is described but forbidden", grammarAllows(compact, tool))
        assertTrue(grammarAllows(compact, "respond"))
        // The point of building a second grammar: a tool the prompt omits must not be callable.
        for (tool in namesIn(full) - namesIn(compact).toSet()) {
            assertFalse("$tool is omitted from the prompt but allowed", grammarAllows(compact, tool))
        }
    }

    @Test
    fun givenANativelyCallingBackend_whenToolsAreChosen_thenItKeepsEveryTool() {
        // The OpenAI backend takes its window from the user, so a small one is not the on-device model.
        assertSame(full, full.forBackend(callsToolsNatively = true, hasOwnPrompt = true, contextWindowTokens = 8_192))
    }

    @Test
    fun givenABackendWithoutItsOwnShortPrompt_whenToolsAreChosen_thenTheFullSetIsKept() {
        // The user turned the short prompt off to run a larger model; the window alone cannot say so.
        assertSame(full, full.forBackend(callsToolsNatively = false, hasOwnPrompt = false, contextWindowTokens = 16_384))
    }

    @Test
    fun givenAnUnknownOrLargeWindow_whenToolsAreChosen_thenTheFullSetIsKept() {
        assertSame(full, full.forBackend(callsToolsNatively = false, hasOwnPrompt = true, contextWindowTokens = 0))
        assertSame(full, full.forBackend(callsToolsNatively = false, hasOwnPrompt = true, contextWindowTokens = 200_000))
    }

    @Test
    fun givenTheSameAnswerTwice_whenToolsAreChosen_thenTheSameSnapshotComesBack() {
        // Built once per tool-set rebuild, not per message, or every send would rebuild a grammar.
        assertSame(compact, full.forBackend(callsToolsNatively = false, hasOwnPrompt = true, contextWindowTokens = 16_384))
        assertNotSame(full, compact)
    }

    @Test
    fun givenTheCompactSnapshot_whenAskedAgain_thenItIsItsOwnCompactVariant() {
        assertSame(compact, compact.forBackend(callsToolsNatively = false, hasOwnPrompt = true, contextWindowTokens = 16_384))
    }

    @Test
    fun givenTheCompactSet_whenItsToolTextIsMeasured_thenItStaysUnderTheCeiling() {
        // The whole point is fewer tokens read before the model answers. An allowlist keeps new
        // tools out; this keeps the ones inside from being reworded back up to the full list's size.
        fun toolTextChars(tools: AgentTools) = PromptToolCatalog.definitions(tools, "respond", shippedConfig)
            .sumOf { "- ${it.name}: ${it.description}\n".length }

        val compactChars = toolTextChars(compact)

        assertTrue("compact tool text is $compactChars characters", compactChars <= COMPACT_TOOL_TEXT_CEILING)
        // Guards the measurement itself: if both were tiny, the ceiling above would prove nothing.
        assertTrue(toolTextChars(full) > 2 * compactChars)
    }

    @Test
    fun givenTheCompactSet_whenAskedWhatItCanDo_thenItCanSyncRunAGradleTaskAndListThem() {
        // Without run_gradle_task a small model asked to run the tests calls another tool with an
        // invented argument. Its description tells the model to call list_gradle_tasks when unsure of
        // a task's name, so the grammar has to allow that too. list_gradle_tasks sends it to gradle_sync
        // when the project has not synced.
        for (tool in listOf("run_gradle_task", "list_gradle_tasks", "gradle_sync")) {
            assertTrue("$tool is missing", tool in namesIn(compact))
            assertTrue("$tool is described but forbidden", grammarAllows(compact, tool))
        }
    }

    @Test
    fun givenSeveralContributedTools_whenTheCompactSetIsChosen_thenOnlyTheFirstFewAreOffered() {
        // Twelve contributed descriptions can outweigh the whole compact built-in list.
        val provider = "com.example.manytools"
        val store = ToolSourceStore().apply {
            register(FakeToolSource(provider, tools = (1..6).map { contributedTool(provider, "extra_$it") }))
        }
        val withSources =
            AgentTools.build(builtIns, store, ToolApprovalManager({ shippedConfig }), terminalTool = "respond")
        val compactWithSources = withSources.forBackend(callsToolsNatively = false, hasOwnPrompt = true, contextWindowTokens = 16_384)

        fun contributedOffered(tools: AgentTools): Int {
            val contributed = tools.contributedHandlers.map { it.toolName }.toSet()
            return namesIn(tools).count { it in contributed }
        }

        assertEquals(6, contributedOffered(withSources))
        assertEquals(PromptToolBudget.COMPACT_MAX_CONTRIBUTED_TOOLS, contributedOffered(compactWithSources))
        assertEquals(
            "what the cap left out must be nameable",
            6 - PromptToolBudget.COMPACT_MAX_CONTRIBUTED_TOOLS,
            compactWithSources.promptTools.droppedTools.size,
        )
    }

    private companion object {
        /** The compact set measures about 1,560 characters; this leaves room for rewording, not for a new tool. */
        const val COMPACT_TOOL_TEXT_CEILING = 1_800
    }
}
