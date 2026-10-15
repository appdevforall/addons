package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** What an exchange record may show: credentials hidden, echoes of them masked, the rest kept. */
class McpRedactorTest {

    private fun redactor(vararg credentials: String, userHeaders: List<String> = emptyList()) =
        McpRedactor(credentials.toList(), userHeaders)

    @Test
    fun givenABearerAuthorization_whenShown_thenOnlyTheSchemeAndLengthRemain() {
        val header = redactor("abcdef1234").requestHeader("Authorization", "Bearer abcdef1234")

        assertEquals(McpExchange.Value.Hidden("Bearer", 10), header.value)
    }

    @Test
    fun givenAnAuthorizationWithNoScheme_whenShown_thenOnlyItsLengthRemains() {
        val pasted = "dXNlcjp0b2tlbg=="

        val header = redactor(pasted, userHeaders = listOf("Authorization")).requestHeader("Authorization", pasted)

        assertEquals(McpExchange.Value.Hidden(null, pasted.length), header.value)
    }

    @Test
    fun givenAUserHeader_whenShownUnderAnotherCase_thenItIsStillHidden() {
        val header = redactor(userHeaders = listOf("X-Api-Key")).requestHeader("x-api-key", "k-123456")

        assertEquals(McpExchange.Value.Hidden(null, 8), header.value)
    }

    @Test
    fun givenAProtocolHeader_whenShown_thenItIsKeptAsSent() {
        val header = redactor(userHeaders = listOf("X-Api-Key")).requestHeader("Content-Type", "application/json")

        assertEquals(McpExchange.Value.Shown("application/json"), header.value)
    }

    @Test
    fun givenAServerEchoingTheBareToken_whenScrubbed_thenEveryCopyIsMasked() {
        val scrubbed = redactor("Bearer secret-token").scrub("bad token secret-token (secret-token)")

        assertEquals("bad token ${McpRedactor.MASK} (${McpRedactor.MASK})", scrubbed)
    }

    @Test
    fun givenAResponseHeaderEchoingTheToken_whenShown_thenItIsMasked() {
        val header = redactor("secret-token").responseHeader("X-Debug", "got secret-token")

        assertEquals(McpExchange.Value.Shown("got ${McpRedactor.MASK}"), header.value)
    }

    @Test
    fun givenAShortCredential_whenEchoedAsAToken_thenItIsMasked() {
        val scrubbed = redactor("Bearer k9x").scrub("""{"error":"bad key k9x"}""")

        assertEquals("""{"error":"bad key ${McpRedactor.MASK}"}""", scrubbed)
    }

    @Test
    fun givenAShortCredential_whenInsideALongerWord_thenTheWordIsKept() {
        assertEquals("k9xyz and ak9x", redactor("k9x").scrub("k9xyz and ak9x"))
    }

    @Test
    fun givenABodyOverTheCap_whenKept_thenTheRestIsCountedNotKept() {
        val body = McpExchange.Body.capped("x".repeat(McpExchange.Body.MAX_CHARS + 5))

        assertEquals(McpExchange.Body.MAX_CHARS, body.text.length)
        assertEquals(5, body.omitted)
    }

    @Test
    fun givenAServerEchoingTheTokenJsonEscaped_whenScrubbed_thenTheEscapedCopyIsMasked() {
        val scrubbed = redactor("ab/cd+ef==").scrub("""{"echo":"ab\/cd\u002Bef=="}""")

        assertEquals("""{"echo":"${McpRedactor.MASK}"}""", scrubbed)
    }

    @Test
    fun givenAServerEchoingTheTokenUrlEncoded_whenScrubbed_thenTheEncodedCopyIsMasked() {
        val scrubbed = redactor("ab/cd+ef==").scrub("next=ab%2Fcd%2bef%3D%3D")

        assertEquals("next=${McpRedactor.MASK}", scrubbed)
    }

    @Test
    fun givenServerIssuedCredentials_whenShownAsResponseHeaders_thenTheyAreHidden() {
        val cookie = redactor().responseHeader("Set-Cookie", "session=abc123; Path=/")
        val session = redactor().responseHeader("Mcp-Session-Id", "3f2a9c")

        assertEquals(McpExchange.Value.Hidden(null, 22), cookie.value)
        assertEquals(McpExchange.Value.Hidden(null, 6), session.value)
    }

    @Test
    fun givenASessionIdSent_whenShownAsARequestHeader_thenItIsHidden() {
        val header = redactor().requestHeader("Mcp-Session-Id", "3f2a9c")

        assertEquals(McpExchange.Value.Hidden(null, 6), header.value)
    }

    @Test
    fun givenAUrlWithUserinfoAndAQueryKey_whenShown_thenBothAreMaskedAndThePathKept() {
        val url = "https://user:pa55word@mcp.example.test/v1/mcp?api_key=k-123456&v=&debug#top"

        val shown = McpRedactor.forRequest(url, emptyList(), emptyList()).url(url)

        val mask = McpRedactor.MASK
        assertEquals("https://$mask@mcp.example.test/v1/mcp?api_key=$mask&v=&debug#top", shown)
    }

    @Test
    fun givenAQueryKeyEchoedInABody_whenScrubbed_thenItIsMasked() {
        val url = "https://mcp.example.test/mcp?api_key=k-123456"
        val redactor = McpRedactor.forRequest(url, emptyList(), emptyList())

        assertEquals("bad key ${McpRedactor.MASK}", redactor.scrub("bad key k-123456"))
    }

    @Test
    fun givenAnErrorNamingTheUrl_whenShownAsAMessage_thenItsQueryIsMasked() {
        val message = redactor().message("https://mcp.example.test/mcp?t=abc redirected off its origin")

        assertEquals("https://mcp.example.test/mcp?t=${McpRedactor.MASK} redirected off its origin", message)
    }
}
