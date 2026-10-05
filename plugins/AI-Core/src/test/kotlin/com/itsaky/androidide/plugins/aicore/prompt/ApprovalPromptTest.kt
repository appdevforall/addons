package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [ApprovalPrompt], what the agent is told when the user does not approve a call. */
class ApprovalPromptTest {

    @Test
    fun givenTheShippedConfig_whenChecked_thenEveryMessageRenders() {
        assertEquals(emptyList<String>(), ApprovalPrompt.problems(shippedConfig))
    }

    @Test
    fun givenADenial_whenRendering_thenItNamesTheTool() {
        assertEquals(
            "User denied permission to execute edit_file",
            ApprovalPrompt.denied(shippedConfig, "edit_file"),
        )
    }

    @Test
    fun givenACorrectionWithText_whenRendering_thenTheInstructionIsRelayedTrimmed() {
        assertEquals(
            "User rejected this edit_file call and asked you to revise it: \"keep the name\". " +
                "Apply that instruction and try again.",
            ApprovalPrompt.corrected(shippedConfig, "edit_file", "  keep the name "),
        )
    }

    @Test
    fun givenACorrectionWithNoText_whenRendering_thenItStillAsksForARevision() {
        assertEquals(
            "User rejected this edit_file call and asked you to revise it.",
            ApprovalPrompt.corrected(shippedConfig, "edit_file", "   "),
        )
    }

    @Test
    fun givenAnInstructionThatLooksLikeATemplate_whenRendering_thenItIsRelayedVerbatim() {
        // What the user typed is data; rendering it would throw on a stray tag.
        val message = ApprovalPrompt.corrected(shippedConfig, "edit_file", "use {{NAME}}")

        assertEquals(true, message.contains("\"use {{NAME}}\""))
    }

    @Test
    fun givenATimeout_whenRendering_thenItStatesTheWait() {
        assertEquals(
            "Approval request timed out (no response within 5 minutes). Please try again.",
            ApprovalPrompt.timedOut(shippedConfig, "edit_file", 5),
        )
    }

    @Test
    fun givenANameTypo_whenChecked_thenTheProblemNamesTheText() {
        val config = shippedWith("agent_loop.yml") { it.replace("{{MINUTES}}", "{{MINUTE}}") }

        val problems = ApprovalPrompt.problems(config)

        assertEquals(listOf("agent_loop.yml: approval.timed_out: unknown name {{MINUTE}}"), problems)
    }
}
