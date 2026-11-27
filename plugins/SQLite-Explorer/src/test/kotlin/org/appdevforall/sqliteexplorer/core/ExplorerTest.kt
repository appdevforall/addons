package org.appdevforall.sqliteexplorer.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerTest {
    /** Records each tools/call and answers it with an empty query page. */
    private class Recorder : HttpTransport {
        val calls = mutableListOf<JSONObject>()

        override fun post(port: Int, headers: Map<String, String>, body: String): HttpReply {
            val json = JSONObject(body)
            return when (json.optString("method")) {
                "initialize" -> HttpReply(200, "s", reply(json, JSONObject().put("protocolVersion", "2025-06-18")))
                "notifications/initialized" -> HttpReply(202, null, "")
                else -> {
                    calls += json.getJSONObject("params")
                    val page = JSONObject().put("columns", JSONArray()).put("column_types", JSONArray()).put("rows", JSONArray()).put("storage_classes", JSONArray())
                    HttpReply(200, null, reply(json, JSONObject().put("structuredContent", page).put("isError", false)))
                }
            }
        }

        private fun reply(request: JSONObject, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString()
    }

    @Test
    fun `browsing a table quotes its name and pages with offset and max_rows`() {
        val recorder = Recorder()
        Explorer(McpClient(1, "t", recorder)).rows("/db/a.db", "odd \"name\"", offset = 200, pageSize = 100)

        val call = recorder.calls.single()
        assertEquals("query", call.getString("name"))
        val args = call.getJSONObject("arguments")
        assertEquals("/db/a.db", args.getString("database"))
        assertEquals("SELECT * FROM \"odd \"\"name\"\"\"", args.getString("sql"))
        assertEquals(200, args.getLong("offset"))
        assertEquals(100, args.getInt("max_rows"))
    }

    @Test
    fun `a page size outside the server's limit is refused before calling`() {
        val recorder = Recorder()
        val explorer = Explorer(McpClient(1, "t", recorder))
        assertThrows(IllegalArgumentException::class.java) { explorer.query("/d", "SELECT 1", 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { explorer.query("/d", "SELECT 1", 0, 1001) }
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `the integrity check asks for a full check only when told`() {
        val recorder = Recorder()
        val explorer = Explorer(McpClient(1, "t", recorder))
        runCatching { explorer.integrity("/d", full = false) }
        runCatching { explorer.integrity("/d", full = true) }
        assertEquals(listOf(false, true), recorder.calls.map { it.getJSONObject("arguments").getBoolean("full") })
    }

    @Test
    fun `the endpoint is chosen by the remembered app, else the only one, else the user picks`() {
        val old = Endpoint("com.example.old", 1000, "a", updatedAtMillis = 1)
        val new = Endpoint("com.example.new", 2000, "b", updatedAtMillis = 2)

        assertSame(EndpointChoice.Outcome.NoneReported, EndpointChoice.choose(emptyList(), null))
        assertSame(old, (EndpointChoice.choose(listOf(old), null) as EndpointChoice.Outcome.One).endpoint)
        assertSame(old, (EndpointChoice.choose(listOf(new, old), "com.example.old") as EndpointChoice.Outcome.One).endpoint)
        val several = EndpointChoice.choose(listOf(old, new), "com.example.gone") as EndpointChoice.Outcome.Several
        assertEquals(listOf(new, old), several.endpoints)
    }
}
