package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [McpRedirects], which decides where a credentialled request may be repeated. */
class McpRedirectsTest {

    @Test
    fun givenANonRedirectStatus_whenRead_thenNothingIsFollowed() {
        assertEquals(
            McpRedirects.Verdict.NotARedirect,
            McpRedirects.verdict(200, "https://wycro.example/mcp", null)
        )
        // 304 is a 3xx that names no destination and is not a redirect.
        assertEquals(
            McpRedirects.Verdict.NotARedirect,
            McpRedirects.verdict(304, "https://wycro.example/mcp", "https://wycro.example/other")
        )
    }

    @Test
    fun givenARedirectToTheSameOrigin_whenRead_thenItIsFollowed() {
        val verdict = McpRedirects.verdict(307, "https://wycro.example/mcp", "/mcp/v2")

        assertEquals(McpRedirects.Verdict.Follow("https://wycro.example/mcp/v2"), verdict)
    }

    @Test
    fun givenARedirectToAnotherHost_whenRead_thenTheCredentialsStayHere() {
        // The finding: HttpURLConnection would have replayed the bearer token to this host.
        val verdict = McpRedirects.verdict(
            302,
            "https://wycro.example/mcp",
            "http://attacker.example/collect",
        )

        assertEquals(McpRedirects.Verdict.OtherOrigin, verdict)
    }

    @Test
    fun givenAnHttpsToHttpDowngrade_whenRead_thenItIsRefused() {
        val verdict = McpRedirects.verdict(
            301,
            "https://wycro.example/mcp",
            "http://wycro.example/mcp",
        )

        assertEquals(McpRedirects.Verdict.OtherOrigin, verdict)
    }

    @Test
    fun givenAnotherPortOnTheSameHost_whenRead_thenItIsAnotherOrigin() {
        val verdict = McpRedirects.verdict(
            308,
            "https://wycro.example/mcp",
            "https://wycro.example:8443/mcp",
        )

        assertEquals(McpRedirects.Verdict.OtherOrigin, verdict)
    }

    @Test
    fun givenTheSchemeDefaultPortSpeltOut_whenRead_thenItIsStillTheSameOrigin() {
        val verdict = McpRedirects.verdict(
            307,
            "https://wycro.example/mcp",
            "https://wycro.example:443/mcp",
        )

        assertEquals(McpRedirects.Verdict.Follow("https://wycro.example:443/mcp"), verdict)
    }

    @Test
    fun givenARedirectWithNoUsableDestination_whenRead_thenItIsRefused() {
        assertEquals(
            McpRedirects.Verdict.Unusable,
            McpRedirects.verdict(302, "https://wycro.example/mcp", null)
        )
        assertEquals(
            McpRedirects.Verdict.Unusable,
            McpRedirects.verdict(302, "https://wycro.example/mcp", "   ")
        )
        assertEquals(
            McpRedirects.Verdict.Unusable,
            McpRedirects.verdict(302, "https://wycro.example/mcp", "gopher://wycro.example/mcp")
        )
    }
}
