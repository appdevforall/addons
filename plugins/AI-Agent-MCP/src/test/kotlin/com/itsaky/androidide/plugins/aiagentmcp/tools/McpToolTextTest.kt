package com.itsaky.androidide.plugins.aiagentmcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [McpToolText]. A remote tool name becomes a name the model is told to emit, so the
 * sanitising is a security boundary, not tidiness. Descriptions are the agent's to flatten and cap —
 * see `ContributedText` and `PromptToolBudget` in ai-core.
 */
class McpToolTextTest {

    @Test
    fun givenAServerAndTool_whenNamed_thenTheServerPrefixKeepsTwoServersApart() {
        assertEquals("github_create_issue", McpToolText.exposedName("GitHub", "create_issue"))
        assertEquals("gitlab_create_issue", McpToolText.exposedName("GitLab", "create_issue"))
    }

    @Test
    fun givenPunctuationInEitherPart_whenNamed_thenItIsReducedToTheSafeAlphabet() {
        val name = McpToolText.exposedName("Company Docs!", "search.docs")

        assertEquals("company_do_search_docs", name)
    }

    @Test
    fun givenAToolNameWithNothingUsable_whenNamed_thenItIsRejected() {
        // Registering it would leave a tool the model can read about but never call.
        assertNull(McpToolText.exposedName("Docs", "***"))
    }

    @Test
    fun givenTwoLongToolNamesSharingAPrefix_whenNamed_thenTruncationCollapsesThem() {
        // The premise of the numbering below: capping the name is what makes a collision possible.
        val first = McpToolText.exposedName("Git", "get_pull_request_comments_by_user")
        val second = McpToolText.exposedName("Git", "get_pull_request_comments_by_team")

        assertEquals(first, second)
    }

    @Test
    fun givenANameAlreadyPublished_whenDisambiguated_thenItIsNumberedRatherThanDropped() {
        // Dropping it would take away a tool the user switched on, with only a log line to say so.
        val name = McpToolText.exposedName("Git", "get_pull_request_comments_by_user")!!

        assertEquals("${name}_2", McpToolText.disambiguate(name, setOf(name)))
        assertEquals("${name}_3", McpToolText.disambiguate(name, setOf(name, "${name}_2")))
    }

    @Test
    fun givenAFreeName_whenDisambiguated_thenItIsLeftAlone() {
        assertEquals("git_search", McpToolText.disambiguate("git_search", setOf("git_other")))
    }

    @Test
    fun givenEveryVariantTaken_whenDisambiguated_thenItIsRejected() {
        val taken = (setOf("git_search") + (2..20).map { "git_search_$it" }).toSet()

        assertNull(McpToolText.disambiguate("git_search", taken))
    }
}
