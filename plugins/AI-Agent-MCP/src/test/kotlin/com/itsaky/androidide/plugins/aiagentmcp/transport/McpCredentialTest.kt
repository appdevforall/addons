package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [McpCredential.of], which decides which field a refusal sends the user to. */
class McpCredentialTest {

    @Test
    fun givenAnAuthorizationHeaderAndAToken_whenResolved_thenTheHeaderIsWhatWasSent() {
        // The header replaces the bearer token on the wire, so blaming the token misleads.
        val credential = McpCredential.of("stored-token", mapOf("authorization" to "Basic abc"))

        assertEquals(McpCredential.AuthorizationHeader("authorization"), credential)
    }

    @Test
    fun givenOnlyAToken_whenResolved_thenTheTokenIsWhatWasSent() {
        assertEquals(McpCredential.Token, McpCredential.of("stored-token", mapOf("X-Env" to "dev")))
    }

    @Test
    fun givenOnlyOtherHeaders_whenResolved_thenTheExtraHeadersAreWhatWasSent() {
        assertEquals(McpCredential.ExtraHeaders, McpCredential.of(" ", mapOf("X-Api-Key" to "k")))
    }

    @Test
    fun givenNothing_whenResolved_thenNoCredentialWasSent() {
        assertEquals(McpCredential.None, McpCredential.of("", emptyMap()))
    }
}
