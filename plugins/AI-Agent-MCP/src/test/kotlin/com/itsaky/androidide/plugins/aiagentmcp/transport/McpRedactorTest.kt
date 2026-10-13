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
}
