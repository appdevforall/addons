package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.ToolResultsPrompt
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A natively calling backend's reply that calls tools and writes nothing (ADFA-7340). The provider is sent no
 * `tool_use` block, so without a turn of its own the model never sees that it made the call: the results reach
 * it as user text after its own request, and it asks for the same call again.
 */
class AgentLoopBareCallTest {
    private fun call(name: String, path: String) = """<tool_call>{"tool":"$name","args":{"path":"$path"}}</tool_call>"""

    private val answer = """<tool_call>{"tool":"respond","args":{"message":"Here it is."}}</tool_call>"""

    private fun loop() =
        AgentLoop(
            formatToolResults = ToolResultsPrompt({ shippedConfig }, "respond"),
            terminalTool = "respond",
            repeatedAfterSuccessTurn = { REPEATED },
            bareCallTurn = { calls -> "Calling " + calls.joinToString { "${it.name} ${it.args["path"]}" } + "." },
        )

    private suspend fun run(vararg replies: AgentLoop.ModelReply): List<ChatMessage> {
        var turn = 0
        val history = mutableListOf(ChatMessage(Role.USER, "read a.kt"))
        loop().run(
            history = history,
            generate = { replies[turn++] },
            executeTools = { calls -> calls.map { ToolResult.success("read", "contents") } },
        )
        return history
    }

    @Test
    fun aBareNativeCallIsStoredAsTheModelsTurnBeforeItsResults() = runTest {
        val history = run(AgentLoop.ModelReply(call("read_file", "a.kt"), historyText = ""), AgentLoop.ModelReply("All done."))

        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), history.map { it.role })
        assertEquals("Calling read_file a.kt.", history[1].content)
        assertTrue(history[2].content.startsWith("<tool_response>"))
    }

    @Test
    fun aNativeCallWithProseKeepsTheProseAsItsTurn() = runTest {
        val history =
            run(
                AgentLoop.ModelReply("Reading it. ${call("read_file", "a.kt")}", historyText = "Reading it."),
                AgentLoop.ModelReply("All done."),
            )

        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), history.map { it.role })
        assertEquals("Reading it.", history[1].content)
    }

    @Test
    fun aBareAnswerAddsNoTurn() = runTest {
        // The terminal tool alone ends the run with the answer; there is no later turn to show it to.
        val history = run(AgentLoop.ModelReply(answer, historyText = ""))

        assertEquals(listOf(Role.USER), history.map { it.role })
    }

    @Test
    fun aBareRepeatOfASuccessfulCallIsShownBeforeTheTurnThatAsksForTheAnswer() = runTest {
        val history =
            run(
                AgentLoop.ModelReply(call("read_file", "a.kt"), historyText = ""),
                AgentLoop.ModelReply(call("read_file", "a.kt"), historyText = ""),
                AgentLoop.ModelReply(answer, historyText = ""),
            )

        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER), history.map { it.role })
        assertEquals("Calling read_file a.kt.", history[3].content)
        assertEquals(REPEATED, history[4].content)
    }

    private companion object {
        const val REPEATED = "you repeated it, answer now"
    }
}
