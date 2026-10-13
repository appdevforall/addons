package com.itsaky.androidide.plugins.aiagentmcp.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log view's display shaping: badges read at a glance, bodies read at all. */
class McpLogFormatTest {

    @Test
    fun givenAnUnauthorizedStatusLine_whenToned_thenItReadsAsAnError() {
        assertEquals(McpLogFormat.Tone.ERROR, McpLogFormat.tone("HTTP/1.1 401 Unauthorized"))
    }

    @Test
    fun givenAnOkStatusLine_whenToned_thenItReadsAsSuccess() {
        // The `1.1` in front must not be mistaken for the code.
        assertEquals(McpLogFormat.Tone.SUCCESS, McpLogFormat.tone("HTTP/1.1 200 OK"))
    }

    @Test
    fun givenARedirect_whenToned_thenItIsNeitherSuccessNorError() {
        assertEquals(McpLogFormat.Tone.REDIRECT, McpLogFormat.tone("HTTP/2 307"))
    }

    @Test
    fun givenNoAnswer_whenToned_thenItReadsAsAnError() {
        assertEquals(McpLogFormat.Tone.ERROR, McpLogFormat.tone(null))
    }

    @Test
    fun givenAStatusLine_whenBadged_thenTheProtocolIsDropped() {
        assertEquals("401 Unauthorized", McpLogFormat.badge("HTTP/1.1 401 Unauthorized"))
    }

    @Test
    fun givenAJsonBody_whenPrettied_thenItIsIndentedAndSlashesAreLeftAlone() {
        // Exact whitespace differs between Android's org.json and the JVM one these tests run on.
        val pretty = McpLogFormat.pretty("""{"url":"https://a.test/x","id":1}""")

        assertTrue(pretty.contains("\n  \"url\": \"https://a.test/x\""))
        assertFalse(pretty.contains("\\/"))
    }

    @Test
    fun givenAnSseStream_whenPrettied_thenEachJsonDataLineIsIndented() {
        val pretty = McpLogFormat.pretty("event: message\ndata: {\"id\":1,\"ok\":true}\n\n")

        assertTrue(pretty.startsWith("event: message\ndata:\n{\n"))
        assertTrue(pretty.contains("\n  \"id\": 1"))
    }

    @Test
    fun givenACappedBody_whenPrettied_thenItIsLeftAsKept() {
        // Cut mid-document, it is not JSON any more; showing it as kept beats showing nothing.
        val capped = """{"tools":[{"name":"a"""

        assertEquals(capped, McpLogFormat.pretty(capped))
    }
}
