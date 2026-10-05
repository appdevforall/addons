package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClaudeToolProtocol]. Focus: ADFA-5410, where a tool call written as reply text
 * could not be read back once its arguments carried quotes. Declaring the tools is what stops
 * that, so the declaration and the `tool_use` accumulation are both pinned down here.
 */
class ClaudeToolProtocolTest {

    private fun schema(vararg properties: Pair<String, Map<String, Any>>, required: List<String>) =
        mapOf(
            "type" to "object",
            "properties" to properties.toMap(),
            "required" to required,
        )

    @Test
    fun givenAToolWithArguments_whenDeclared_thenItsSchemaTravelsAsInputSchema() {
        val declarations = ClaudeToolProtocol.toolsArray(
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
        assertEquals("read_file", entry.getString("name"))
        assertEquals("Read a file", entry.getString("description"))
        // Not OpenAI's {"type":"function","function":{...}} wrapper: the API rejects that shape.
        assertFalse(entry.has("function"))
        val inputSchema = entry.getJSONObject("input_schema")
        assertEquals("object", inputSchema.getString("type"))
        assertEquals(
            "string",
            inputSchema.getJSONObject("properties").getJSONObject("file_path").getString("type")
        )
        assertEquals("file_path", inputSchema.getJSONArray("required").getString(0))
    }

    @Test
    fun givenAToolWithNoSchema_whenDeclared_thenInputSchemaIsStillAnObject() {
        // The API requires an object schema; omitting it is a 400 for the whole request.
        val entry = ClaudeToolProtocol.toolsArray(listOf(ToolDefinition("list_files", "List", null)))
            .getJSONObject(0)

        assertEquals("object", entry.getJSONObject("input_schema").getString("type"))
    }

    @Test
    fun givenASchemaThatNamesNoType_whenDeclared_thenItIsMadeAnObject() {
        val json = ClaudeToolProtocol.inputSchemaJson(mapOf("properties" to mapOf<String, Any>()))

        assertEquals("object", json.getString("type"))
    }

    @Test
    fun givenAPathologicallyDeepSchema_whenDeclared_thenItIsCutOffRatherThanRecursedForever() {
        var deep: Map<String, Any> = mapOf("type" to "string")
        repeat(50) { deep = mapOf("type" to "object", "properties" to mapOf("x" to deep)) }

        // Completing at all is the assertion; a contributed schema must not kill the host.
        assertTrue(ClaudeToolProtocol.inputSchemaJson(deep).has("type"))
    }

    @Test
    fun givenAStreamedCall_whenItsFragmentsAreJoined_thenOneWholeCallComesBack() {
        val calls = ClaudeToolProtocol.CallAccumulator()
        calls.start(1, "toolu_01", "edit_file")
        calls.appendInput(1, "{\"file_path\": \"a.kt\", ")
        calls.appendInput(1, "\"old_string\": \"say \\\"hi\\\"\\n\"}")

        val request = calls.requests().single()
        assertEquals("toolu_01", request.callId)
        assertEquals("edit_file", request.name)
        assertEquals("a.kt", request.args?.get("file_path"))
        // Quotes and a newline inside a value survive, which is what text-mode calling lost.
        assertEquals("say \"hi\"\n", request.args?.get("old_string"))
        assertEquals(0, calls.droppedCalls)
    }

    @Test
    fun givenTwoCallsInOneMessage_whenJoined_thenEachKeepsItsOwnInputInOrder() {
        val calls = ClaudeToolProtocol.CallAccumulator()
        calls.start(1, "toolu_a", "read_file")
        calls.start(2, "toolu_b", "list_files")
        calls.appendInput(2, "{\"directory\": \"app\"}")
        calls.appendInput(1, "{\"file_path\": \"b.kt\"}")

        val requests = calls.requests()
        assertEquals(listOf("toolu_a", "toolu_b"), requests.map { it.callId })
        assertEquals("b.kt", requests[0].args?.get("file_path"))
        assertEquals("app", requests[1].args?.get("directory"))
    }

    @Test
    fun givenACallWithNoInput_whenJoined_thenItRunsWithNoArguments() {
        val calls = ClaudeToolProtocol.CallAccumulator()
        calls.start(0, "toolu_01", "sync_gradle")

        assertEquals(emptyMap<String, Any>(), calls.requests().single().args)
    }

    @Test
    fun givenACallCutOffMidInput_whenJoined_thenItIsDroppedAndCounted() {
        // Reporting it with empty arguments would run the tool on nothing.
        val calls = ClaudeToolProtocol.CallAccumulator()
        calls.start(0, "toolu_01", "edit_file")
        calls.appendInput(0, "{\"file_path\": \"a.k")

        assertTrue(calls.requests().isEmpty())
        assertEquals(1, calls.droppedCalls)
    }

    @Test
    fun givenAFragmentForABlockThatIsNotAToolCall_whenJoined_thenItIsIgnored() {
        val calls = ClaudeToolProtocol.CallAccumulator()
        calls.appendInput(5, "{\"stray\": true}")

        assertTrue(calls.requests().isEmpty())
        assertEquals(0, calls.droppedCalls)
    }

    @Test
    fun givenAFallback_whenDiscarded_thenEveryCallSoFarIsForgottenAndLaterOnesKept() {
        val calls = ClaudeToolProtocol.CallAccumulator()
        calls.start(0, "toolu_old", "read_file")
        calls.appendInput(0, "{}")

        assertEquals(1, calls.discardAll())
        calls.start(2, "toolu_new", "read_file")
        calls.appendInput(2, "{}")

        assertEquals(listOf("toolu_new"), calls.requests().map { it.callId })
        assertEquals(0, calls.droppedCalls)
    }

    @Test
    fun givenTheBlockCutOff_whenDropped_thenOnlyThatCallGoesAndIsCounted() {
        val calls = ClaudeToolProtocol.CallAccumulator()
        calls.start(0, "toolu_1", "list_files")
        calls.start(1, "toolu_2", "edit_file")

        assertTrue(calls.dropAt(1))
        assertFalse(calls.dropAt(7))
        assertEquals(listOf("toolu_1"), calls.requests().map { it.callId })
        assertEquals(1, calls.droppedCalls)
    }

    @Test
    fun givenInputThatIsNotAnObject_whenRead_thenItIsRejected() {
        assertNull(ClaudeToolProtocol.argsOf("[1, 2]"))
        assertNull(ClaudeToolProtocol.argsOf("{\"unterminated\": "))
        assertEquals(emptyMap<String, Any>(), ClaudeToolProtocol.argsOf("   "))
    }
}
