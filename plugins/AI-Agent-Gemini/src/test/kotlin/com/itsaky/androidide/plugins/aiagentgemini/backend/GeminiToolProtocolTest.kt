package com.itsaky.androidide.plugins.aiagentgemini.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GeminiToolProtocol]. Focus: ADFA-5410, where Gemini wrote its tool calls as text
 * and a payload containing quotes could not be read back. Declaring the tools is what stops that,
 * so the declaration and the `functionCall` parsing are both pinned down here.
 */
class GeminiToolProtocolTest {

    private fun schema(vararg properties: Pair<String, Map<String, Any>>, required: List<String>) =
        mapOf(
            "type" to "object",
            "properties" to properties.toMap(),
            "required" to required,
        )

    @Test
    fun givenAToolWithArguments_whenDeclared_thenItsSchemaTravelsWithIt() {
        val declarations = GeminiToolProtocol.functionDeclarations(
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
        val declaration = declarations.getJSONObject(0)
        assertEquals("read_file", declaration.getString("name"))
        assertEquals("Read a file", declaration.getString("description"))
        val parameters = declaration.getJSONObject("parameters")
        assertEquals("OBJECT", parameters.getString("type"))
        assertEquals("STRING", parameters.getJSONObject("properties").getJSONObject("file_path").getString("type"))
        assertEquals("file_path", parameters.getJSONArray("required").getString(0))
    }

    @Test
    fun givenAFreeFormObjectArgument_whenDeclared_thenItIsDeclaredAsJsonText() {
        // Gemini rejects an OBJECT with no properties ("should be non-empty for OBJECT type"),
        // and that 400 fails the whole request — every other tool in it included.
        val declarations = GeminiToolProtocol.functionDeclarations(
            listOf(
                ToolDefinition(
                    "generate_from_template",
                    "Generate from a template",
                    schema(
                        "template_name" to mapOf("type" to "string"),
                        "variables" to mapOf("type" to "object", "description" to "Variables."),
                        required = listOf("template_name"),
                    ),
                )
            )
        )

        val variables = declarations.getJSONObject(0)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("variables")
        assertEquals("STRING", variables.getString("type"))
        assertTrue(variables.getString("description").contains("JSON object"))
    }

    @Test
    fun givenAnArrayOfFreeFormObjects_whenDeclared_thenItsItemsAreDeclaredAsJsonText() {
        // The same propertyless OBJECT, one level down: an items schema left as-is 400s the
        // request just as a property would, and the streaming path does not catch it.
        val declarations = GeminiToolProtocol.functionDeclarations(
            listOf(
                ToolDefinition(
                    "add_dependency",
                    "Add dependencies",
                    schema(
                        "entries" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "object", "description" to "One entry."),
                        ),
                        required = listOf("entries"),
                    ),
                )
            )
        )

        val items = declarations.getJSONObject(0)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("entries")
            .getJSONObject("items")
        assertEquals("STRING", items.getString("type"))
        assertTrue(items.getString("description").contains("JSON object"))
    }

    @Test
    fun givenASchemaThatNamesNoProperties_whenDeclared_thenNoParametersAreSent() {
        // Same rejection at the top level, where there is nothing to degrade it to.
        val declarations = GeminiToolProtocol.functionDeclarations(
            listOf(ToolDefinition("gradle_sync", "Sync", mapOf("type" to "object")))
        )

        assertFalse(declarations.getJSONObject(0).has("parameters"))
    }

    @Test
    fun givenAToolThatTakesNoArguments_whenDeclared_thenNoParametersAreSent() {
        val declarations = GeminiToolProtocol.functionDeclarations(
            listOf(ToolDefinition("run_app", "Build and run", emptyMap()))
        )

        assertFalse(declarations.getJSONObject(0).has("parameters"))
    }

    @Test
    fun givenASchemaCarryingKeywordsGeminiRejects_whenConverted_thenTheyAreDropped() {
        // A contributed (MCP) tool is free to send these; the API 400s on them.
        val json = GeminiToolProtocol.schemaJson(
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "\$schema" to "https://json-schema.org/draft/2020-12/schema",
                "properties" to mapOf("q" to mapOf("type" to "string")),
            )
        )

        assertFalse(json.has("additionalProperties"))
        assertFalse(json.has("\$schema"))
        assertEquals("STRING", json.getJSONObject("properties").getJSONObject("q").getString("type"))
    }

    @Test
    fun givenASchemaWithNoRequiredArguments_whenConverted_thenRequiredIsOmitted() {
        val json = GeminiToolProtocol.schemaJson(
            mapOf("type" to "object", "properties" to mapOf("directory" to mapOf("type" to "string")))
        )

        assertFalse(json.has("required"))
    }

    @Test
    fun givenAFunctionCallPart_whenParsed_thenItsArgumentsArriveAlreadyStructured() {
        // The edit ADFA-5410 lost: the value carries the quotes and newlines that broke text mode.
        val layout = "<ScrollView android:text=\"Binary Search\">\n</ScrollView>"
        val chunk = GeminiToolProtocol.parseChunk(
            JSONObject()
                .put(
                    "candidates",
                    JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "functionCall",
                                        JSONObject()
                                            .put("name", "edit_file")
                                            .put("args", JSONObject().put("new_string", layout)),
                                    )
                                )
                            )
                        )
                    )
                )
        )

        assertEquals(1, chunk.calls.size)
        assertEquals("edit_file", chunk.calls[0].name)
        assertEquals(layout, chunk.calls[0].args!!["new_string"])
        assertTrue(chunk.text.isEmpty())
    }

    @Test
    fun givenAFunctionCallWithNoIdOfItsOwn_whenParsed_thenItIsIdentifiedByName() {
        // Gemini pairs a functionResponse by name, so the name is the only id there is.
        val call = GeminiToolProtocol.toolCallOf(JSONObject().put("name", "gradle_sync"))

        assertEquals("gradle_sync", call.callId)
        assertEquals("gradle_sync", call.name)
        assertTrue(call.args!!.isEmpty())
    }

    @Test
    fun givenATextPart_whenParsed_thenItIsReportedAsTextWithNoCalls() {
        val chunk = GeminiToolProtocol.parseChunk(
            JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Working on it."))),
                    )
                )
            )
        )

        assertEquals("Working on it.", chunk.text)
        assertTrue(chunk.calls.isEmpty())
    }

    @Test
    fun givenAReplyStoppedAtTheOutputCap_whenParsed_thenTheFinishReasonIsReported() {
        val chunk = GeminiToolProtocol.parseChunk(
            JSONObject().put(
                "candidates",
                JSONArray().put(JSONObject().put("finishReason", "MAX_TOKENS")),
            )
        )

        assertEquals("MAX_TOKENS", chunk.finishReason)
    }

    @Test
    fun givenAChunkWithNoCandidates_whenParsed_thenNothingIsReported() {
        val chunk = GeminiToolProtocol.parseChunk(JSONObject())

        assertTrue(chunk.text.isEmpty())
        assertTrue(chunk.calls.isEmpty())
        assertNull(chunk.finishReason)
    }
}
