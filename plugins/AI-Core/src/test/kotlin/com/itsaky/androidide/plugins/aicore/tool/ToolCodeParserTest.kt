package com.itsaky.androidide.plugins.aicore.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolCodeParser]. The reply this exists for ended a 13-turn run one call short of
 * finishing: Gemini asked to run the app in its own dialect, nothing read it, and the loop reported
 * the run COMPLETED with the markup pasted into the chat (ADFA-5410).
 */
class ToolCodeParserTest {

    /** The exact turn-13 reply from the reported run, closing tag and all missing. */
    private val reportedReply = "<tool_code>\nprint(default_api.run_app())"

    @Test
    fun givenTheReplyThatEndedTheRun_whenParsed_thenTheCallIsRecovered() {
        val calls = ToolCodeParser.parse(reportedReply)

        assertEquals(1, calls.size)
        assertEquals("run_app", calls[0].name)
        assertTrue(calls[0].args.isEmpty())
    }

    @Test
    fun givenTheReplyThatEndedTheRun_whenExtracting_thenTheAgentRunsItLikeAnyOtherCall() {
        val calls = ToolCallExtractor.extractToolCalls(reportedReply)

        assertEquals(1, calls.size)
        assertEquals("run_app", calls[0].name)
    }

    @Test
    fun givenKeywordArguments_whenParsed_thenEachOneIsRead() {
        val calls = ToolCodeParser.parse(
            """print(default_api.edit_file(file_path="A.kt", old_string="a", new_string="b"))"""
        )

        assertEquals(1, calls.size)
        assertEquals(mapOf("file_path" to "A.kt", "old_string" to "a", "new_string" to "b"), calls[0].args)
    }

    @Test
    fun givenAValueHoldingCommasAndBrackets_whenParsed_thenTheArgumentsAreNotSplitInsideIt() {
        val calls = ToolCodeParser.parse(
            """default_api.create_file(file_path="A.kt", content="fun f(a, b) { g(1, 2) }")"""
        )

        assertEquals("fun f(a, b) { g(1, 2) }", calls[0].args["content"])
    }

    @Test
    fun givenAnEscapedValue_whenParsed_thenTheEscapesAreResolved() {
        val calls = ToolCodeParser.parse(
            """default_api.create_file(content="line\nwith \"quotes\" and a \\ backslash")"""
        )

        assertEquals("line\nwith \"quotes\" and a \\ backslash", calls[0].args["content"])
    }

    @Test
    fun givenATripleQuotedValue_whenParsed_thenTheWholePayloadSurvives() {
        // A model writing a file's contents reaches for these; reading '' as empty would truncate it.
        val calls = ToolCodeParser.parse(
            "default_api.create_file(content='''<View a=\"b\"/>\nsecond line''')"
        )

        assertEquals("<View a=\"b\"/>\nsecond line", calls[0].args["content"])
    }

    @Test
    fun givenNonStringLiterals_whenParsed_thenTheyKeepTheirTypes() {
        val calls = ToolCodeParser.parse(
            "default_api.search_project(query=\"x\", search_in_contents=True, limit=10, cursor=None)"
        )

        assertEquals(true, calls[0].args["search_in_contents"])
        assertEquals(10L, calls[0].args["limit"])
        assertEquals(null, calls[0].args["cursor"])
    }

    @Test
    fun givenAPositionalArgument_whenParsed_thenTheCallIsRefusedRatherThanGuessed() {
        // Without the tool's schema the value cannot be named, and a wrong slot is worse than no run.
        assertTrue(ToolCodeParser.parse("""default_api.read_file("A.kt")""").isEmpty())
    }

    @Test
    fun givenAPositionalArgument_whenDiagnosed_thenItIsStillReportedRatherThanReadAsProse() {
        assertEquals(
            ToolCallExtractor.UnparsedReply.MALFORMED,
            ToolCallExtractor.diagnoseUnparsedReply("""<tool_code>default_api.read_file("A.kt")"""),
        )
    }

    @Test
    fun givenAnUnterminatedCall_whenParsed_thenNothingIsRecovered() {
        val cutOff = """default_api.create_file(file_path="A.kt", content="cl"""

        assertTrue(ToolCodeParser.parse(cutOff).isEmpty())
    }

    @Test
    fun givenTwoCallsInOneBlock_whenParsed_thenBothAreRead() {
        val calls = ToolCodeParser.parse(
            "<tool_code>\nprint(default_api.gradle_sync())\nprint(default_api.run_app())\n</tool_code>"
        )

        assertEquals(listOf("gradle_sync", "run_app"), calls.map { it.name })
    }

    @Test
    fun givenOrdinaryProse_whenAskedIfItIsToolCode_thenItIsNot() {
        assertFalse(ToolCodeParser.looksLikeToolCode("I added a binary search class and a screen for it."))
    }

    @Test
    fun givenAPythonPrefixedStringLiteral_whenParsed_thenThePrefixIsNotPartOfTheValue() {
        // `r"…"` and `f"…"` are Python string literals; keeping the prefix and quotes in a path
        // argument fails the file operation that receives it.
        val calls = ToolCodeParser.parse(
            """default_api.read_file(file_path=r"app/src/main/AndroidManifest.xml")"""
        )

        assertEquals("app/src/main/AndroidManifest.xml", calls[0].args["file_path"])
    }

    @Test
    fun givenAnFStringLiteral_whenParsed_thenItIsUnquotedLikeAnyOther() {
        val calls = ToolCodeParser.parse("""default_api.open_file(file_path=f"app/Main.kt")""")

        assertEquals("app/Main.kt", calls[0].args["file_path"])
    }

    @Test
    fun givenAnEnvelopeReply_whenExtracting_thenTheEnvelopeStillWins() {
        // Strategy 3 is a fallback; a well-formed envelope must never be re-read as tool_code.
        val calls = ToolCallExtractor.extractToolCalls(
            """<tool_call>{"tool":"run_app","args":{}}</tool_call> default_api.gradle_sync()"""
        )

        assertEquals(listOf("run_app"), calls.map { it.name })
    }
}
