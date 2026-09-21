package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SseChunk]. A server may answer the same POST with JSON or with a stream of it,
 * so a client that misreads the framing sees "no reply" from half the servers in the wild.
 */
class SseChunkTest {

    @Test
    fun givenADataLine_whenParsed_thenThePayloadIsReturnedWithoutTheFieldName() {
        val event = SseChunk.parse("""data: {"jsonrpc":"2.0"}""")

        assertEquals("""{"jsonrpc":"2.0"}""", (event as SseChunk.Event.Data).payload)
    }

    @Test
    fun givenADataLineWithNoSpace_whenParsed_thenThePayloadIsStillIntact() {
        val event = SseChunk.parse("""data:{"id":"1"}""")

        assertEquals("""{"id":"1"}""", (event as SseChunk.Event.Data).payload)
    }

    @Test
    fun givenAComment_whenParsed_thenItIsIgnoredRatherThanTreatedAsAPayload() {
        // Servers send bare-colon lines as keep-alives on an idle stream.
        assertTrue(SseChunk.parse(": keep-alive") is SseChunk.Event.Ignored)
    }

    @Test
    fun givenABlankLine_whenParsed_thenItDispatchesWhateverWasAccumulated() {
        assertTrue(SseChunk.parse("") is SseChunk.Event.Dispatch)
    }

    @Test
    fun givenAnEventName_whenParsed_thenItIsReportedSoAnUnusualOneCanBeLogged() {
        val event = SseChunk.parse("event: message")

        assertEquals("message", (event as SseChunk.Event.Named).name)
    }

    @Test
    fun givenAnUnknownField_whenParsed_thenItIsIgnored() {
        assertTrue(SseChunk.parse("retry: 3000") is SseChunk.Event.Ignored)
    }
}
