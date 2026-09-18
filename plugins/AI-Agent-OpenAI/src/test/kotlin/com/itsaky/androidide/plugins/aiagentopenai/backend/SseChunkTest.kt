package com.itsaky.androidide.plugins.aiagentopenai.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE framing. A mistake here either truncates a reply silently or aborts a working stream on one
 * odd chunk, and neither shows up in a build.
 */
class SseChunkTest {

    @Test
    fun givenADeltaChunk_whenParsed_thenItsContentIsTheToken() {
        val line = """data: {"choices":[{"delta":{"content":"Hello"}}]}"""
        assertEquals(SseChunk.Event.Token("Hello"), SseChunk.parse(line))
    }

    @Test
    fun givenNoSpaceAfterTheDataPrefix_whenParsed_thenItStillParses() {
        val line = """data:{"choices":[{"delta":{"content":"Hi"}}]}"""
        assertEquals(SseChunk.Event.Token("Hi"), SseChunk.parse(line))
    }

    @Test
    fun givenTheDoneSentinel_whenParsed_thenTheStreamIsOver() {
        assertEquals(SseChunk.Event.Done, SseChunk.parse("data: [DONE]"))
    }

    @Test
    fun givenABlankLine_whenParsed_thenItIsIgnored() {
        assertEquals(SseChunk.Event.Ignored, SseChunk.parse(""))
        assertEquals(SseChunk.Event.Ignored, SseChunk.parse("   "))
    }

    @Test
    fun givenAnSseComment_whenParsed_thenItIsIgnored() {
        // Some servers send `: keep-alive` comments to hold the connection open.
        assertEquals(SseChunk.Event.Ignored, SseChunk.parse(": keep-alive"))
        assertEquals(SseChunk.Event.Ignored, SseChunk.parse("event: message"))
    }

    @Test
    fun givenTheRoleOnlyOpeningChunk_whenParsed_thenItIsIgnored() {
        // OpenAI's first chunk carries the role and an empty content; it is not a token.
        val line = """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}"""
        assertEquals(SseChunk.Event.Ignored, SseChunk.parse(line))
    }

    @Test
    fun givenTheFinalChunkWithAFinishReason_whenParsed_thenTheFinishIsReported() {
        // Reported rather than ignored: with no content, the finish reason is the only thing that
        // explains why. The reader loop records it and otherwise carries on.
        val line = """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        assertEquals(SseChunk.Event.Finish("stop"), SseChunk.parse(line))
    }

    @Test
    fun givenAFinishReasonAlongsideContent_whenParsed_thenTheContentWins() {
        val line = """data: {"choices":[{"delta":{"content":"end"},"finish_reason":"stop"}]}"""
        assertEquals(SseChunk.Event.Token("end"), SseChunk.parse(line))
    }

    @Test
    fun givenAUsageOnlyChunk_whenParsed_thenItIsIgnored() {
        val line = """data: {"choices":[],"usage":{"total_tokens":42}}"""
        assertEquals(SseChunk.Event.Ignored, SseChunk.parse(line))
    }

    @Test
    fun givenAUsageChunkWithNoChoicesKey_whenParsed_thenItIsIgnored() {
        assertEquals(SseChunk.Event.Ignored, SseChunk.parse("""data: {"usage":{"total_tokens":9}}"""))
    }

    @Test
    fun givenReasoningContent_whenParsed_thenItIsReasoningNotAToken() {
        // LM Studio's spelling. Read so a thinking-only reply is diagnosable instead of looking
        // like an empty stream, but never appended to the visible reply.
        val line = """data: {"choices":[{"delta":{"reasoning_content":"let me think"}}]}"""
        assertEquals(SseChunk.Event.Reasoning("let me think"), SseChunk.parse(line))
    }

    @Test
    fun givenOpenRoutersReasoningField_whenParsed_thenItIsAlsoRecognised() {
        val line = """data: {"choices":[{"delta":{"reasoning":"hmm"}}]}"""
        assertEquals(SseChunk.Event.Reasoning("hmm"), SseChunk.parse(line))
    }

    @Test
    fun givenBothContentAndReasoning_whenParsed_thenContentWins() {
        val line =
            """data: {"choices":[{"delta":{"reasoning_content":"think","content":"answer"}}]}"""
        assertEquals(SseChunk.Event.Token("answer"), SseChunk.parse(line))
    }

    @Test
    fun givenAnErrorObjectInsideA200_whenParsed_thenItIsAFailure() {
        // LM Studio answers stream:true with 200 and puts a context-overflow error in the body.
        val line =
            """data: {"error":{"message":"Context length exceeded","type":"invalid_request_error"}}"""
        assertEquals(SseChunk.Event.Failure("Context length exceeded"), SseChunk.parse(line))
    }

    @Test
    fun givenABareErrorString_whenParsed_thenItIsAlsoAFailure() {
        val line = """data: {"error":"model unloaded"}"""
        assertEquals(SseChunk.Event.Failure("model unloaded"), SseChunk.parse(line))
    }

    @Test
    fun givenAnErrorAlongsideChoices_whenParsed_thenTheErrorWins() {
        val line = """data: {"error":{"message":"boom"},"choices":[{"delta":{"content":"x"}}]}"""
        assertEquals(SseChunk.Event.Failure("boom"), SseChunk.parse(line))
    }

    @Test
    fun givenALengthFinishReason_whenParsed_thenTheFinishIsReported() {
        // The token cap truncated the turn; with no content this is why the reply is empty.
        val line = """data: {"choices":[{"delta":{},"finish_reason":"length"}]}"""
        assertEquals(SseChunk.Event.Finish("length"), SseChunk.parse(line))
    }

    @Test
    fun givenAnUnrecognisedShape_whenParsed_thenItIsMalformedSoItGetsLogged() {
        // A well-formed payload this parser cannot use is exactly what makes a reply silently
        // empty, so it must be reported rather than ignored.
        val event = SseChunk.parse("""data: {"unexpected":"shape"}""")
        assertTrue("expected Malformed, got $event", event is SseChunk.Event.Malformed)
    }

    @Test
    fun givenAServerThatAnswersAStreamRequestWholesale_whenParsed_thenTheMessageIsRead() {
        // A few compatible servers ignore stream:true and send one non-streamed choice; reading
        // only `delta` would report an empty reply.
        val line = """data: {"choices":[{"message":{"role":"assistant","content":"Done"}}]}"""
        assertEquals(SseChunk.Event.Token("Done"), SseChunk.parse(line))
    }

    @Test
    fun givenMalformedJson_whenParsed_thenItIsReportedNotThrown() {
        val event = SseChunk.parse("data: {not json")
        assertTrue("expected Malformed, got $event", event is SseChunk.Event.Malformed)
    }

    @Test
    fun givenMultipleChoices_whenParsed_thenTheirContentIsConcatenated() {
        val line =
            """data: {"choices":[{"delta":{"content":"a"}},{"delta":{"content":"b"}}]}"""
        assertEquals(SseChunk.Event.Token("ab"), SseChunk.parse(line))
    }

    @Test
    fun givenAToolCallDelta_whenParsed_thenItsFragmentsComeBackInsteadOfBeingIgnored() {
        // Such a delta carries no `content`, so it used to fall through to Ignored and the turn
        // came back empty — which is how a declared tool call ran nothing (ADFA-5410).
        val line = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1",""" +
            """"type":"function","function":{"name":"read_file","arguments":"{\"a\":1}"}}]}}]}"""

        val event = SseChunk.parse(line)

        assertTrue("expected ToolCalls, got $event", event is SseChunk.Event.ToolCalls)
        val deltas = (event as SseChunk.Event.ToolCalls).deltas
        assertEquals(1, deltas.size)
        assertEquals("read_file", deltas[0].name)
        assertEquals("call_1", deltas[0].id)
        assertEquals("""{"a":1}""", deltas[0].arguments)
    }

    @Test
    fun givenAChunkCarryingBothProseAndACall_whenParsed_thenNeitherIsLost() {
        val line = """data: {"choices":[{"delta":{"content":"On it. ","tool_calls":""" +
            """[{"index":0,"function":{"name":"run_app","arguments":"{}"}}]}}]}"""

        val event = SseChunk.parse(line) as SseChunk.Event.ToolCalls

        assertEquals("On it. ", event.text)
        assertEquals("run_app", event.deltas[0].name)
    }

    @Test
    fun givenAWholesaleAnswerCarryingACall_whenParsed_thenTheCallIsStillRead() {
        // The same servers that ignore stream:true answer a tool call in `message` too.
        val line = """data: {"choices":[{"message":{"role":"assistant","tool_calls":""" +
            """[{"index":0,"id":"c1","function":{"name":"gradle_sync","arguments":"{}"}}]}}]}"""

        val event = SseChunk.parse(line) as SseChunk.Event.ToolCalls

        assertEquals("gradle_sync", event.deltas[0].name)
    }
}
