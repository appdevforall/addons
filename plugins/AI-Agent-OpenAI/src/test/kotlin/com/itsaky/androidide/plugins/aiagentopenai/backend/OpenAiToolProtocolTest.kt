package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OpenAiToolProtocol]. Focus: ADFA-5410, where a tool call written as reply text
 * could not be read back once its arguments carried quotes. Declaring the tools is what stops
 * that, so the declaration and the `tool_calls` accumulation are both pinned down here.
 */
class OpenAiToolProtocolTest {

    private fun schema(vararg properties: Pair<String, Map<String, Any>>, required: List<String>) =
        mapOf(
            "type" to "object",
            "properties" to properties.toMap(),
            "required" to required,
        )

    private fun deltas(json: String) =
        OpenAiToolProtocol.toolCallDeltas(JSONObject(json))

    @Test
    fun givenAToolWithArguments_whenDeclared_thenItsSchemaTravelsWithIt() {
        val declarations = OpenAiToolProtocol.toolsArray(
            listOf(
                ToolDefinition(
                    "read_file",
                    "Read a file",
                    schema(
                        "file_path" to mapOf("type" to "string", "description" to "Path to read."),
                        required = listOf("file_path"),
                    ),
                )
            )
        )

        assertEquals(1, declarations.length())
        val entry = declarations.getJSONObject(0)
        assertEquals("function", entry.getString("type"))
        val function = entry.getJSONObject("function")
        assertEquals("read_file", function.getString("name"))
        assertEquals("Read a file", function.getString("description"))
        val parameters = function.getJSONObject("parameters")
        // Lower case, unlike the Gemini transport: this protocol takes plain JSON Schema.
        assertEquals("object", parameters.getString("type"))
        assertEquals(
            "string",
            parameters.getJSONObject("properties").getJSONObject("file_path").getString("type")
        )
        assertEquals("file_path", parameters.getJSONArray("required").getString(0))
    }

    @Test
    fun givenAToolWithNoSchema_whenDeclared_thenParametersIsStillAnObject() {
        // Omitting `parameters` declares a tool that takes none, and the model would then call it
        // with nothing at all.
        val declarations = OpenAiToolProtocol.toolsArray(
            listOf(ToolDefinition("run_app", "Build and run", emptyMap()))
        )

        val parameters = declarations.getJSONObject(0).getJSONObject("function")
            .getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
    }

    @Test
    fun givenASchemaCarryingKeywordsGeminiWouldReject_whenConverted_thenTheySurvive() {
        val parameters = OpenAiToolProtocol.parametersJson(
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "properties" to mapOf("q" to mapOf("type" to "string")),
                "required" to listOf("q"),
            )
        )

        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals("q", parameters.getJSONArray("required").getString(0))
    }

    @Test
    fun givenAnAbsurdlyNestedSchema_whenConverted_thenItStopsRatherThanRecursingForever() {
        // A contributed (MCP) schema is provider-supplied; unbounded recursion would take the host
        // process down with it.
        var schema = mapOf<String, Any>("type" to "string")
        repeat(200) { schema = mapOf("type" to "object", "properties" to mapOf("next" to schema)) }

        val parameters = OpenAiToolProtocol.parametersJson(schema)

        assertEquals("object", parameters.getString("type"))
    }

    @Test
    fun givenArgumentsSplitAcrossChunks_whenAccumulated_thenTheCallIsWholeAgain() {
        val accumulator = OpenAiToolProtocol.CallAccumulator()

        accumulator.accept(
            deltas("""{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"create_file","arguments":"{\"file_path\":\"a.kt\","}}]}""")
        )
        accumulator.accept(
            deltas("""{"tool_calls":[{"index":0,"function":{"arguments":"\"content\":\"val s = \\\"hi\\\"\"}"}}]}""")
        )
        val calls = accumulator.requests()

        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].callId)
        assertEquals("create_file", calls[0].name)
        assertEquals("a.kt", calls[0].args.orEmpty()["file_path"])
        // The payload ADFA-5410 lost: a quoted string inside an argument value.
        assertEquals("""val s = "hi"""", calls[0].args.orEmpty()["content"])
        assertEquals(0, accumulator.droppedCalls)
    }

    @Test
    fun givenTwoCallsInOneTurn_whenAccumulated_thenEachKeepsItsOwnArguments() {
        val accumulator = OpenAiToolProtocol.CallAccumulator()

        accumulator.accept(
            deltas("""{"tool_calls":[
                {"index":0,"id":"a","function":{"name":"read_file","arguments":"{\"file_path\":\"x\"}"}},
                {"index":1,"id":"b","function":{"name":"open_file","arguments":"{\"file_path\":\"y\"}"}}
            ]}""")
        )
        val calls = accumulator.requests()

        assertEquals(listOf("read_file", "open_file"), calls.map { it.name })
        assertEquals("x", calls[0].args.orEmpty()["file_path"])
        assertEquals("y", calls[1].args.orEmpty()["file_path"])
    }

    @Test
    fun givenWholeCallsWithNoIndex_whenAccumulated_thenANewIdStartsANewCall() {
        // Some compatible servers send a complete call per chunk and number none of them.
        val accumulator = OpenAiToolProtocol.CallAccumulator()

        accumulator.accept(
            deltas("""{"tool_calls":[{"id":"a","function":{"name":"read_file","arguments":"{}"}}]}""")
        )
        accumulator.accept(
            deltas("""{"tool_calls":[{"id":"b","function":{"name":"list_files","arguments":"{}"}}]}""")
        )

        assertEquals(listOf("read_file", "list_files"), accumulator.requests().map { it.name })
    }

    @Test
    fun givenACallCutOffMidArguments_whenAccumulated_thenItIsDroppedRatherThanRunOnNothing() {
        val accumulator = OpenAiToolProtocol.CallAccumulator()

        accumulator.accept(
            deltas("""{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"edit_file","arguments":"{\"file_path\":\"a"}}]}""")
        )
        val calls = accumulator.requests()

        assertTrue(calls.isEmpty())
        assertEquals(1, accumulator.droppedCalls)
    }

    @Test
    fun givenACallWithNoArguments_whenAccumulated_thenItRunsWithNone() {
        val accumulator = OpenAiToolProtocol.CallAccumulator()

        accumulator.accept(
            deltas("""{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"gradle_sync","arguments":""}}]}""")
        )
        val calls = accumulator.requests()

        assertEquals(1, calls.size)
        assertTrue(calls[0].args.orEmpty().isEmpty())
        assertEquals(0, accumulator.droppedCalls)
    }

    @Test
    fun givenACallWithNoId_whenAccumulated_thenItIsIdentifiedByName() {
        val accumulator = OpenAiToolProtocol.CallAccumulator()

        accumulator.accept(
            deltas("""{"tool_calls":[{"index":0,"function":{"name":"respond","arguments":"{\"message\":\"done\"}"}}]}""")
        )

        assertEquals("respond", accumulator.requests()[0].callId)
    }

    @Test
    fun givenAChunkWithNoToolCalls_whenRead_thenNoFragmentsComeBack() {
        assertTrue(deltas("""{"content":"Hello"}""").isEmpty())
        assertTrue(OpenAiToolProtocol.toolCallDeltas(null).isEmpty())
    }

    @Test
    fun givenMalformedArguments_whenRead_thenTheyAreRefusedRatherThanGuessedAt() {
        assertNull(OpenAiToolProtocol.argsOf("""{"file_path":}"""))
        assertEquals(emptyMap<String, Any>(), OpenAiToolProtocol.argsOf("  "))
    }
}
