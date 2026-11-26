package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpCallResult
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.services.RoomSchema
import com.itsaky.androidide.plugins.services.SqlTemplateException
import com.itsaky.androidide.plugins.services.SqlTemplateFunction
import com.itsaky.androidide.plugins.services.SqlTemplateService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `.read` with a Pebble template (ADFA-7302): what the render is given, and what each function asks the app. */
class SqlTemplateReadTest {
    private fun schema(version: Int, hash: String) =
        RoomSchema("com.example.AppDatabase", version, hash, "app/schemas/com.example.AppDatabase/$version.json", emptyList(), emptyList())

    /** A host that records the render's input and runs [script] in place of a template. */
    private class FakeHost(
        val schemas: List<RoomSchema> = emptyList(),
        val script: (Map<String, *>, Map<String, SqlTemplateFunction>) -> String = { _, _ -> "SELECT 1" },
    ) : SqlTemplateService {
        var variables: Map<String, *>? = null
        var template: String? = null

        override fun getRoomSchemas(): List<RoomSchema> = schemas

        override fun listTemplates(): List<String> = listOf("sqlite/a.sql.peb")

        override fun render(
            templatePath: String,
            variables: Map<String, *>,
            functions: Map<String, SqlTemplateFunction>,
            timeoutMillis: Long,
        ): String {
            template = templatePath
            this.variables = variables
            return try {
                script(variables, functions)
            } catch (e: Exception) {
                throw SqlTemplateException("$templatePath: ${e.message}", e)
            }
        }
    }

    private val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    private fun variablesJson(identityHash: String? = "h2") =
        JSONObject()
            .put("app", JSONObject().put("package", "com.example"))
            .put("device", JSONObject().put("api_level", 34))
            .put(
                "databases",
                JSONArray().put(
                    JSONObject()
                        .put("path", "/data/app.db")
                        .put("name", "app.db")
                        .put(
                            "room",
                            if (identityHash == null) {
                                JSONObject.NULL
                            } else {
                                JSONObject().put("schema_version", 2).put("identity_hash", identityHash)
                            },
                        ),
                ),
            ).put("errors", JSONArray())

    private fun app(
        identityHash: String? = "h2",
        answers: Map<String, JSONObject> = emptyMap(),
    ): (String, Map<String, Any?>) -> McpCallResult = { name, args ->
        calls += name to args
        when (name) {
            SqlTemplateRead.VARIABLES_TOOL -> McpCallResult(true, variablesJson(identityHash).toString())
            else -> answers[name]?.let { McpCallResult(true, it.toString()) } ?: McpCallResult(false, "", "no such table: nope")
        }
    }

    @Test fun schemaMatchesIsTrueFalseOrUnknown() {
        val schemas = listOf(schema(1, "h1"), schema(2, "h2"))
        assertEquals(true, SqlTemplateRead.schemaMatches(2, "h2", schemas))
        assertEquals(false, SqlTemplateRead.schemaMatches(2, "other", schemas))
        assertNull("no schema exported for that version", SqlTemplateRead.schemaMatches(3, "h3", schemas))
        assertNull("not a Room database", SqlTemplateRead.schemaMatches(null, null, schemas))
    }

    @Test fun theRenderGetsTheAppsVariablesWithSchemaMatchesAndTheSelectedDatabase() {
        val host = FakeHost(schemas = listOf(schema(2, "h2")))
        val result = SqlTemplateRead(host, app()).run(mapOf("template" to "sqlite/a.sql.peb"))
        assertTrue(result.errorMessage, result.success)
        assertEquals("SELECT 1", result.text)
        val vars = host.variables!!
        assertEquals("com.example", (vars["app"] as Map<*, *>)["package"])
        val db = vars["db"] as Map<*, *>
        assertEquals("/data/app.db", db["path"])
        assertEquals(true, db["schema_matches"])
        assertEquals(schema(2, "h2"), db["room_schema"])
        assertEquals(listOf(schema(2, "h2")), vars["room_schemas"])
        // Only the variables were fetched: no function was called.
        assertEquals(listOf(SqlTemplateRead.VARIABLES_TOOL), calls.map { it.first })
    }

    @Test fun aDatabaseWhoseHashDiffersDoesNotMatch() {
        val host = FakeHost(schemas = listOf(schema(2, "h2")))
        SqlTemplateRead(host, app(identityHash = "changed")).run(mapOf("template" to "t.sql.peb"))
        val db = host.variables!!["db"] as Map<*, *>
        assertEquals(false, db["schema_matches"])
        assertNull(db["room_schema"])
    }

    @Test fun functionsAreToolCallsMadeOnlyWhenCalled() {
        val countResult = JSONObject().put("columns", JSONArray().put("count(*)")).put("rows", JSONArray().put(JSONArray().put(42)))
        val sample =
            JSONObject()
                .put("columns", JSONArray().put("id").put("name"))
                .put("rows", JSONArray().put(JSONArray().put(1).put("a")))
        var answers = mapOf("query" to countResult)
        val host =
            FakeHost { _, functions ->
                val n = functions.getValue("row_count").call(listOf(mapOf("path" to "/data/app.db"), "we\"ird"))
                "SELECT $n"
            }
        val result = SqlTemplateRead(host, { n, a -> app(answers = answers)(n, a) }).run(mapOf("template" to "t.sql.peb"))
        assertEquals("SELECT 42", result.text)
        val (name, args) = calls.last()
        assertEquals("query", name)
        assertEquals("/data/app.db", args["database"])
        // count(*), for SQLite's fast path, and the table name quoted
        assertEquals("SELECT count(*) FROM \"we\"\"ird\"", args["sql"])

        answers = mapOf("query" to sample)
        calls.clear()
        val rowsHost =
            FakeHost { _, functions ->
                val rows = functions.getValue("sample_rows").call(listOf("/data/app.db", "t", 5L)) as List<*>
                (rows.single() as Map<*, *>).entries.joinToString { "${it.key}=${it.value}" }
            }
        assertEquals("id=1, name=a", SqlTemplateRead(rowsHost, { n, a -> app(answers = answers)(n, a) }).run(mapOf("template" to "t.sql.peb")).text)
        assertEquals("SELECT * FROM \"t\" LIMIT ?", calls.last().second["sql"])
        assertEquals("[5]", calls.last().second["args"].toString())
    }

    @Test fun aFailingFunctionFailsTheReadWithTheServersMessage() {
        val host = FakeHost { _, functions -> functions.getValue("integrity").call(listOf("/data/app.db")).toString() }
        val result = SqlTemplateRead(host, app()).run(mapOf("template" to "t.sql.peb"))
        assertFalse(result.success)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("no such table: nope"))
    }

    @Test fun withoutATemplateItListsThem() {
        val result = SqlTemplateRead(FakeHost(), app()).run(emptyMap())
        assertEquals("""{"templates":["sqlite/a.sql.peb"]}""", result.text)
        assertTrue(calls.isEmpty())
    }

    @Test fun theToolIsAddedOnlyForAServerWithTemplateVariables() {
        val old = listOf(McpTool("query", "q", readOnlyHint = true))
        assertEquals(old, SqlTemplateRead.withTemplateTool(old))
        val current = old + McpTool(SqlTemplateRead.VARIABLES_TOOL, "v", readOnlyHint = true)
        assertEquals(current + SqlTemplateRead.TOOL, SqlTemplateRead.withTemplateTool(current))
        assertTrue(ReadOnlyTrust.isReadOnly(SqlTemplateRead.TOOL))
        assertTrue(SqlTemplateRead.TOOL_NAME in ReadOnlyTrust.enabledTools(SqlTemplateRead.withTemplateTool(current)))
    }
}
