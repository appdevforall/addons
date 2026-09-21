package com.itsaky.androidide.plugins.aicore.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolCallExtractor]. Focus: only explicit tool calls are honored,
 * and prose never fires a tool (the "build Android apps" -> run_app regression).
 */
class ToolCallExtractorTest {

    @Test
    fun givenAnExplicitToolCallTag_whenExtracting_thenTheCallIsExtractedWithArgs() {
        val calls = ToolCallExtractor.extractToolCalls(
            """Sure. <tool_call>{"tool":"open_file","args":{"file_path":"app/Main.java"}}</tool_call>"""
        )
        assertEquals(1, calls.size)
        assertEquals("open_file", calls[0].name)
        assertEquals("app/Main.java", calls[0].args["file_path"])
    }

    @Test
    fun givenABareJsonToolCall_whenExtracting_thenItIsExtracted() {
        val calls = ToolCallExtractor.extractToolCalls(
            """{"tool":"list_files","args":{"directory":"src"}}"""
        )
        assertEquals(1, calls.size)
        assertEquals("list_files", calls[0].name)
        assertEquals("src", calls[0].args["directory"])
    }

    @Test
    fun givenABareJsonToolCallWhoseValueContainsBraces_whenExtracting_thenItIsExtractedIntact() {
        // The brace counter must ignore braces inside string values.
        val calls = ToolCallExtractor.extractToolCalls(
            """{"tool":"create_file","args":{"file_path":"A.kt","content":"fun f() { if (x) { y() } }"}}"""
        )
        assertEquals(1, calls.size)
        assertEquals("create_file", calls[0].name)
        assertEquals("A.kt", calls[0].args["file_path"])
        assertEquals("fun f() { if (x) { y() } }", calls[0].args["content"])
    }

    @Test
    fun givenAChattyReplyMentioningBuildingApps_whenExtracting_thenNoToolIsFired() {
        val calls = ToolCallExtractor.extractToolCalls(
            "Hi! I can help you build Android apps. What would you like to run or create next?"
        )
        assertTrue("prose must not produce tool calls, got $calls", calls.isEmpty())
    }

    @Test
    fun givenAPlainGreeting_whenExtracting_thenNoToolCallsAreProduced() {
        assertTrue(ToolCallExtractor.extractToolCalls("Hello, how can I help?").isEmpty())
    }

    @Test
    fun givenNarratedIntentWithoutATag_whenExtracting_thenNoToolCallsAreProduced() {
        // The model describing what it would do must NOT be treated as a tool call.
        val calls = ToolCallExtractor.extractToolCalls(
            "Let me list the files in src and then read MainActivity.kt for you."
        )
        assertTrue("narration must not produce tool calls, got $calls", calls.isEmpty())
    }

    @Test
    fun givenProseBesideATaggedCall_whenReadingTheProse_thenOnlyTheProseComesBack() {
        val prose = ToolCallExtractor.proseOutsideToolCalls(
            "I renamed count to itemCount.\n<tool_call>{\"tool\":\"respond\",\"args\":{}}</tool_call>"
        )
        assertEquals("I renamed count to itemCount.", prose)
    }

    @Test
    fun givenProseBetweenTwoTaggedCalls_whenReadingTheProse_thenBothEnvelopesAreRemoved() {
        val prose = ToolCallExtractor.proseOutsideToolCalls(
            "<tool_call>{\"tool\":\"a\"}</tool_call>Working on it.<tool_call>{\"tool\":\"b\"}</tool_call>"
        )
        assertEquals("Working on it.", prose)
    }

    @Test
    fun givenNothingButATaggedCall_whenReadingTheProse_thenThereIsNone() {
        assertNull(
            ToolCallExtractor.proseOutsideToolCalls(
                "<tool_call>{\"tool\":\"respond\",\"args\":{\"message\":\"hi\"}}</tool_call>"
            )
        )
    }

    @Test
    fun givenAnUntaggedCallLeftOver_whenReadingTheProse_thenRawJsonIsNotTreatedAsProse() {
        // Showing the user an untagged tool call is worse than showing nothing.
        assertNull(
            ToolCallExtractor.proseOutsideToolCalls("{\"tool\":\"read_file\",\"args\":{\"file_path\":\"A.kt\"}}")
        )
    }

    @Test
    fun givenPlainProse_whenReadingTheProse_thenItComesBackUnchanged() {
        assertEquals("Hello, how can I help?", ToolCallExtractor.proseOutsideToolCalls("Hello, how can I help?"))
    }

    /**
     * The reply shape that ran 12 tools for a "read the build output" prompt: the model answered
     * its own first call and role-played the rest of the session against a project that did not
     * exist. Only the call before the invented result is real.
     */
    private val hallucinatedTranscript =
        """
        <tool_call>{"tool":"read_build_output","args":{}}</tool_call>
        ```tool_response
        The build has not been executed.
        ```
        <tool_call>{"tool":"list_files","args":{"directory":""}}</tool_call>
        ```tool_response
        build.gradle
        settings.gradle
        ```
        <tool_call>{"tool":"run_app","args":{}}</tool_call>
        """.trimIndent()

    @Test
    fun givenAReplyThatAnswersItsOwnToolCall_whenExtracting_thenOnlyCallsBeforeTheFakeResultRun() {
        val calls = ToolCallExtractor.extractToolCalls(hallucinatedTranscript)

        assertEquals(1, calls.size)
        assertEquals("read_build_output", calls[0].name)
    }

    @Test
    fun givenAReplyThatAnswersItsOwnToolCall_whenReadingTheProse_thenTheInventedTranscriptIsHidden() {
        val prose = ToolCallExtractor.proseOutsideToolCalls(hallucinatedTranscript)

        assertNull(prose)
    }

    @Test
    fun givenAFabricatedResultInATag_whenExtracting_thenItTruncatesTheSameWayAsAFence() {
        val calls = ToolCallExtractor.extractToolCalls(
            """
            <tool_call>{"tool":"read_file","args":{"file_path":"A.kt"}}</tool_call>
            <tool_response>class A</tool_response>
            <tool_call>{"tool":"edit_file","args":{"file_path":"A.kt"}}</tool_call>
            """.trimIndent()
        )

        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
    }

    @Test
    fun givenAReplyWithNoFabricatedResult_whenExtracting_thenEveryCallSurvives() {
        val calls = ToolCallExtractor.extractToolCalls(
            """
            <tool_call>{"tool":"read_file","args":{"file_path":"A.kt"}}</tool_call>
            <tool_call>{"tool":"read_file","args":{"file_path":"B.kt"}}</tool_call>
            """.trimIndent()
        )

        assertEquals(2, calls.size)
    }

    /**
     * The reply from ADFA-5410: Gemini wrote a whole layout into `new_string` without escaping the
     * quotes in it, so the envelope closed but its JSON did not parse and nothing ran.
     */
    private val malformedLayoutEdit =
        """<tool_call>{"tool":"edit_file","args":{"file_path":"app/src/main/res/layout/fragment_home.xml",""" +
            """"new_string":"<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"/>"}}</tool_call>"""

    @Test
    fun givenAnEnvelopeWhoseJsonDoesNotParse_whenExtracting_thenNothingIsExtracted() {
        assertTrue(ToolCallExtractor.extractToolCalls(malformedLayoutEdit).isEmpty())
    }

    @Test
    fun givenAnEnvelopeWhoseJsonDoesNotParse_whenDiagnosed_thenItReadsAsMalformed() {
        assertEquals(
            ToolCallExtractor.UnparsedReply.MALFORMED,
            ToolCallExtractor.diagnoseUnparsedReply(malformedLayoutEdit),
        )
    }

    @Test
    fun givenAnEnvelopeThatWasNeverClosed_whenDiagnosed_thenItReadsAsTruncated() {
        assertEquals(
            ToolCallExtractor.UnparsedReply.TRUNCATED,
            ToolCallExtractor.diagnoseUnparsedReply(
                """<tool_call>{"tool":"edit_file","args":{"file_path":"A.kt","new_string":"class"""
            ),
        )
    }

    @Test
    fun givenABareCallTheJsonStrategyCouldNotRead_whenDiagnosed_thenItReadsAsMalformed() {
        assertEquals(
            ToolCallExtractor.UnparsedReply.MALFORMED,
            ToolCallExtractor.diagnoseUnparsedReply("""{"tool":"open_file","args":{"file_path":"A"}"""),
        )
    }

    @Test
    fun givenAnOrdinaryProseReply_whenDiagnosed_thenNothingIsReportedAsWrong() {
        assertNull(ToolCallExtractor.diagnoseUnparsedReply("Hello! What would you like to build?"))
    }

    @Test
    fun givenProseThatQuotesTheWordTool_whenDiagnosed_thenNothingIsReportedAsWrong() {
        // Without the colon there is no key, so this is an answer and not a broken call. Reading
        // it as one replaces the reply with an error message.
        val reply = """I used the "tool" you asked about; the {curly} braces are just prose."""

        assertNull(ToolCallExtractor.diagnoseUnparsedReply(reply))
    }

    @Test
    fun givenProseThatQuotesTheWordTool_whenStrippingCalls_thenTheProseSurvives() {
        val reply = """The "tool" finished."""

        assertEquals(reply, ToolCallExtractor.proseOutsideToolCalls(reply))
    }

    @Test
    fun givenArgumentsWithQuotesAndNewlines_whenRenderedAsAnEnvelope_thenTheyExtractBackUnchanged() {
        // The payload that cannot survive the model writing it by hand; rendering escapes it.
        val layout = "<ScrollView android:text=\"Binary Search\">\n  <TextView/>\n</ScrollView>"

        val envelope = ToolCallExtractor.renderEnvelope(
            "edit_file",
            mapOf("file_path" to "app/src/main/res/layout/fragment_home.xml", "new_string" to layout),
        )
        val calls = ToolCallExtractor.extractToolCalls(envelope)

        assertEquals(1, calls.size)
        assertEquals("edit_file", calls[0].name)
        assertEquals(layout, calls[0].args["new_string"])
    }

    @Test
    fun givenARenderedEnvelopeBesideProse_whenExtracting_thenTheCallStillRuns() {
        val envelope = ToolCallExtractor.renderEnvelope("list_files", mapOf("directory" to ""))

        val calls = ToolCallExtractor.extractToolCalls("Let me look at the project.\n" + envelope)

        assertEquals(1, calls.size)
        assertEquals("list_files", calls[0].name)
    }
}
