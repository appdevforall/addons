package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.itsaky.androidide.plugins.net.ServerUrlParseResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [McpUrlPolicy], which allows cleartext only to a server on this device. */
class McpUrlPolicyTest {

    private fun assertVerdict(expected: McpUrlPolicy.Verdict, vararg urls: String) {
        for (url in urls) assertEquals(url, expected, McpUrlPolicy.verdict(url))
    }

    @Test
    fun givenAnHttpUrlOnThisDevice_whenJudged_thenItIsUsable() {
        assertVerdict(
            McpUrlPolicy.Verdict.Usable,
            "http://localhost/mcp",
            "http://localhost:8080/mcp",
            "http://127.0.0.1/mcp",
            "http://127.0.0.1:3000/mcp",
            "http://127.45.6.7/mcp",
            "http://127.45.6.7:9000/mcp",
            "http://[::1]/mcp",
            "http://[::1]:8080/mcp",
        )
    }

    @Test
    fun givenAnHttpUrlOnThePrivateNetwork_whenJudged_thenItIsRefused() {
        // Stricter than the OpenAI plugin on purpose: home Wi-Fi is still a shared network.
        assertVerdict(
            McpUrlPolicy.Verdict.CleartextOffDevice,
            "http://192.168.1.20:8080/mcp",
            "http://10.0.2.2/mcp",
            "http://printer.local/mcp",
        )
    }

    @Test
    fun givenAnHttpUrlOnThePublicInternet_whenJudged_thenItIsRefused() {
        assertVerdict(
            McpUrlPolicy.Verdict.CleartextOffDevice,
            "http://wycro.example/mcp",
            "http://93.184.216.34/mcp",
        )
    }

    @Test
    fun givenANameThatOnlyLooksLocal_whenJudged_thenItIsRefused() {
        // Judged on the URL as typed: no DNS lookup, and no prefix match on the host.
        assertVerdict(
            McpUrlPolicy.Verdict.CleartextOffDevice,
            "http://127.0.0.1.wycro.example/mcp",
            "http://localhost.wycro.example/mcp",
            "http://localhost@wycro.example/mcp",
        )
    }

    @Test
    fun givenAnUppercaseScheme_whenJudged_thenItIsReadLikeALowercaseOne() {
        assertVerdict(McpUrlPolicy.Verdict.Usable, "HTTP://127.0.0.1/mcp", "HTTPS://wycro.example/mcp")
        assertVerdict(McpUrlPolicy.Verdict.CleartextOffDevice, "HTTP://wycro.example/mcp")
    }

    @Test
    fun givenAnHttpsUrl_whenJudged_thenItIsUsableOnOrOffTheDevice() {
        assertVerdict(
            McpUrlPolicy.Verdict.Usable,
            "https://wycro.example/mcp",
            "https://192.168.1.20/mcp",
            "https://127.0.0.1:8443/mcp",
        )
    }

    @Test
    fun givenAMalformedUrl_whenJudged_thenTheHostsReasonIsKept() {
        assertEquals(
            McpUrlPolicy.Verdict.Invalid(ServerUrlParseResult.Invalid.Reason.UNSUPPORTED_SCHEME),
            McpUrlPolicy.verdict("ftp://wycro.example/mcp"),
        )
        assertEquals(
            McpUrlPolicy.Verdict.Invalid(ServerUrlParseResult.Invalid.Reason.BLANK),
            McpUrlPolicy.verdict(""),
        )
    }
}
