package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each line here is the shape the Messages API streams; what it maps to decides the transcript. */
class ClaudeStreamEventTest {

    private fun parse(data: String) = ClaudeStreamEvent.parse("data: $data")

    @Test
    fun givenFramingLines_whenParsed_thenTheyAreIgnored() {
        assertEquals(ClaudeStreamEvent.Ignored, ClaudeStreamEvent.parse("event: content_block_delta"))
        assertEquals(ClaudeStreamEvent.Ignored, ClaudeStreamEvent.parse(""))
        assertEquals(ClaudeStreamEvent.Ignored, parse("""{"type":"ping"}"""))
        assertEquals(ClaudeStreamEvent.Ignored, parse("""{"type":"content_block_stop","index":0}"""))
    }

    @Test
    fun givenMessageStart_whenParsed_thenTheServingModelIsRead() {
        assertEquals(
            ClaudeStreamEvent.Started("claude-opus-5-5"),
            parse("""{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}""")
        )
    }

    @Test
    fun givenATextDelta_whenParsed_thenItIsReplyText() {
        assertEquals(
            ClaudeStreamEvent.Text("Hello"),
            parse("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""")
        )
    }

    @Test
    fun givenAToolUseBlock_whenParsed_thenItsIdNameAndIndexAreRead() {
        assertEquals(
            ClaudeStreamEvent.ToolStart(1, "toolu_01", "read_file"),
            parse("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"read_file","input":{}}}""")
        )
    }

    @Test
    fun givenAnInputJsonDelta_whenParsed_thenItIsAFragmentForThatIndex() {
        assertEquals(
            ClaudeStreamEvent.ToolInput(1, "{\"file_path\": \"app/"),
            parse("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"file_path\": \"app/"}}""")
        )
    }

    @Test
    fun givenAThinkingBlock_whenParsed_thenItIsCountedButNotShown() {
        assertEquals(
            ClaudeStreamEvent.BlockOpened(0, "thinking"),
            parse("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""")
        )
        // Thinking text and its signature never reach the transcript.
        assertEquals(
            ClaudeStreamEvent.Ignored,
            parse("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""")
        )
        assertEquals(
            ClaudeStreamEvent.Ignored,
            parse("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"abc"}}""")
        )
    }

    @Test
    fun givenAFallbackBlock_whenParsed_thenTheNewModelIsRead() {
        assertEquals(
            ClaudeStreamEvent.FallbackSwitch(0, "claude-opus-4-8"),
            parse("""{"type":"content_block_start","index":0,"content_block":{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-4-8"}}}""")
        )
    }

    @Test
    fun givenAMessageDelta_whenParsed_thenTheStopReasonIsRead() {
        assertEquals(
            ClaudeStreamEvent.Stop("refusal"),
            parse("""{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}},"usage":{"output_tokens":0}}""")
        )
        assertEquals(
            ClaudeStreamEvent.Ignored,
            parse("""{"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":3}}""")
        )
    }

    @Test
    fun givenMessageStop_whenParsed_thenTheStreamIsDone() {
        assertEquals(ClaudeStreamEvent.Done, parse("""{"type":"message_stop"}"""))
    }

    @Test
    fun givenAnErrorEvent_whenParsed_thenItsTypeIsKept() {
        // Arrives inside a 200; the type is what classifies it like the HTTP failure it stands for.
        assertEquals(
            ClaudeStreamEvent.Failure("overloaded_error", "Overloaded"),
            parse("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        )
    }

    @Test
    fun givenAPayloadThatIsNotJson_whenParsed_thenItIsMalformedRatherThanThrown() {
        assertTrue(parse("{not json") is ClaudeStreamEvent.Malformed)
    }

    @Test
    fun givenAnEventTypeAddedLater_whenParsed_thenItIsIgnored() {
        // A new event must not abort a stream that is otherwise producing text.
        assertEquals(ClaudeStreamEvent.Ignored, parse("""{"type":"message_progress","value":1}"""))
    }

    @Test
    fun givenABlockTypeAddedLater_whenParsed_thenItsIndexIsStillKept() {
        // Kept rather than ignored: a max_tokens stop cuts off whichever block opened last, and
        // the turn can only tell that a tool call was not the one cut off if it saw this one open.
        assertEquals(
            ClaudeStreamEvent.BlockOpened(2, "server_tool_use"),
            parse("""{"type":"content_block_start","index":2,"content_block":{"type":"server_tool_use","id":"srv_1"}}""")
        )
    }

    @Test
    fun givenATextBlockStart_whenParsed_thenItOpensATextBlock() {
        assertEquals(
            ClaudeStreamEvent.BlockOpened(0, "text"),
            parse("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        )
    }
}
