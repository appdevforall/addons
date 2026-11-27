package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpCallResult
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.services.RoomSchema
import com.itsaky.androidide.plugins.services.SqlTemplateException
import com.itsaky.androidide.plugins.services.SqlTemplateFunction
import com.itsaky.androidide.plugins.services.SqlTemplateService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * The SQLite debugger's `.read` with a Pebble template (ADFA-7302): a tool this plugin adds to each debugger entry whose
 * server has `get_template_variables`. It fetches the variables from the app, adds the project's Room schemas and
 * whether each database matches one, and has Code on the Go render the template ([SqlTemplateService]); the functions
 * a template calls are tool calls back to the same app. Nothing the SQL says is run: the model runs it with `query`
 * or `execute`, one statement at a time, until the user can manage transactions.
 *
 * @param templates the host's renderer.
 * @param callTool calls one of the entry's own tools, with its arguments.
 */
class SqlTemplateRead(
    private val templates: SqlTemplateService,
    private val callTool: (name: String, arguments: Map<String, Any?>) -> McpCallResult,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    companion object {
        const val TOOL_NAME = "read_sql_template"

        /** The server tool this one needs; an older debugger has no such tool, and its entry gets no `.read`. */
        const val VARIABLES_TOOL = "get_template_variables"

        /** The whole render, functions included: more than one server call's 50 s, as a template may call several. */
        const val TIMEOUT_MILLIS = 110_000L

        /**
         * `distinct_values` and `sample_rows` defaults, and the most they return. Either fails instead of returning
         * fewer rows than asked for when more exist, or a value the server cut.
         */
        const val DEFAULT_LIMIT = 100L
        const val MAX_LIMIT = 1000L

        val TOOL =
            McpTool(
                name = TOOL_NAME,
                description =
                    "Renders a SQL template of the open project (the sqlite3 shell's .read, with a Pebble template " +
                        "rendered by Code on the Go) and returns the SQL; nothing is run. Without template, lists the " +
                        "project's templates (*.sql.peb). A template sees app, device, databases (each as " +
                        "$VARIABLES_TOOL returns it, plus schema_matches: true when the project's Room schema for its " +
                        "version has its identity hash, false when it differs, null when the project exports none for " +
                        "that version; and room_schema), db (the database named by database, or the only one), " +
                        "room_schemas, project and render_time, and can call row_count(db, table), " +
                        "distinct_values(db, table, column, limit), sample_rows(db, table, n), integrity(db), " +
                        "suggest_indexes(db, sql) and lint_foreign_keys(db). Run the SQL with query or execute, " +
                        "one statement at a time, binding the values the user gives as args.",
                inputSchema =
                    mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "properties" to
                            mapOf(
                                "template" to
                                    mapOf(
                                        "type" to "string",
                                        "description" to "The template's path relative to the project root. Omit to list them.",
                                    ),
                                "database" to
                                    mapOf(
                                        "type" to "string",
                                        "description" to "The database the template's db is, as list_databases gives it.",
                                    ),
                            ),
                        "required" to emptyList<String>(),
                    ),
                readOnlyHint = true,
            )

        /** [tools] with [TOOL] after them when the server offers [VARIABLES_TOOL] and nothing of that name already. */
        fun withTemplateTool(tools: List<McpTool>): List<McpTool> =
            if (tools.any { it.name == VARIABLES_TOOL } && tools.none { it.name == TOOL_NAME }) tools + TOOL else tools

        /**
         * Whether [schemas] has a schema for the database's [version] with its [identityHash]: true when one does, false
         * when the project has schemas of that version and none matches, null when it has none of that version (Room
         * writes them only with `exportSchema` on) or the database is not Room's.
         */
        fun schemaMatches(
            version: Long?,
            identityHash: String?,
            schemas: List<RoomSchema>,
        ): Boolean? {
            if (version == null || identityHash == null) return null
            val ofVersion = schemas.filter { it.version.toLong() == version }
            if (ofVersion.isEmpty()) return null
            return ofVersion.any { it.identityHash == identityHash }
        }

        fun quoteIdentifier(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    }

    /**
     * Runs the tool.
     *
     * @return the SQL, or the template list, as the tool's text; a failure says what went wrong.
     * @throws InterruptedException if the calling thread is interrupted while the host renders.
     * @throws Exception what a template function's tool call threw, such as an IOException when the app went
     *   away, as the variables call's own failure does: the host would pass on only its message, so the
     *   caller could neither retry the call on the app's new address nor word the failure.
     */
    fun run(arguments: Map<String, Any?>): McpCallResult {
        val template = (arguments["template"] as? String)?.takeIf { it.isNotBlank() }
        if (template == null) {
            val list = templates.listTemplates()
            return McpCallResult(true, JSONObject().put("templates", JSONArray(list)).toString())
        }
        val database = (arguments["database"] as? String)?.takeIf { it.isNotBlank() }

        val fetched = callTool(VARIABLES_TOOL, if (database != null) mapOf("database" to database) else emptyMap())
        if (!fetched.success) return fetched
        val variables =
            try {
                plain(JSONObject(fetched.text)) as MutableMap<String, Any?>
            } catch (e: JSONException) {
                return McpCallResult(false, "", "$VARIABLES_TOOL answered something that is not JSON: ${e.message}")
            }

        val schemas = templates.getRoomSchemas()
        @Suppress("UNCHECKED_CAST")
        val databases = (variables["databases"] as? List<MutableMap<String, Any?>>).orEmpty()
        for (db in databases) {
            val room = db["room"] as? Map<*, *>
            val version = (room?.get("schema_version") as? Number)?.toLong()
            val hash = room?.get("identity_hash") as? String
            db["schema_matches"] = schemaMatches(version, hash, schemas)
            db["room_schema"] = schemas.firstOrNull { it.version.toLong() == version && it.identityHash == hash }
        }
        val selected =
            if (database != null) databases.firstOrNull { it["path"] == database } else databases.singleOrNull()
        val all = LinkedHashMap<String, Any?>()
        all["app"] = variables["app"]
        all["device"] = variables["device"]
        all["databases"] = databases
        if (selected != null) all["db"] = selected
        all["room_schemas"] = schemas
        // Kept out of databases above: a template cannot tell a database that failed from one it did not ask for.
        all["database_errors"] = variables["errors"]

        val callFailure = AtomicReference<Exception>()
        return try {
            val sql = templates.render(template, all, functions(callFailure), timeoutMillis)
            McpCallResult(true, sql)
        } catch (e: SqlTemplateException) {
            failed(e, callFailure)
        } catch (e: IllegalArgumentException) {
            failed(e, callFailure)
        } catch (e: IllegalStateException) {
            failed(e, callFailure)
        }
    }

    private fun failed(
        e: Exception,
        callFailure: AtomicReference<Exception>,
    ): McpCallResult {
        callFailure.get()?.let { throw it }
        return McpCallResult(false, "", e.message ?: "The template could not be rendered.")
    }

    /**
     * The template's functions: each one tool call to the app, made only when the template calls it.
     *
     * @param callFailure gets the first exception a tool call throws (not a failed result), for [run] to rethrow.
     */
    private fun functions(callFailure: AtomicReference<Exception>): Map<String, SqlTemplateFunction> =
        mapOf(
            "row_count" to
                SqlTemplateFunction { args ->
                    // count(*), not count(1): only count(*) gets SQLite's OP_Count fast path.
                    val result = query(callFailure, path(args, 0), "SELECT count(*) FROM ${table(args, 1)}", emptyList())
                    firstCell(result)
                },
            "distinct_values" to
                SqlTemplateFunction { args ->
                    val column = quoteIdentifier(string(args, 2, "column"))
                    val result =
                        queryUpTo(
                            callFailure,
                            path(args, 0),
                            "SELECT DISTINCT $column FROM ${table(args, 1)} ORDER BY 1 LIMIT ?",
                            limit(args, 3),
                        )
                    whole(result).map { it.firstOrNull() }
                },
            "sample_rows" to
                SqlTemplateFunction { args ->
                    val result = queryUpTo(callFailure, path(args, 0), "SELECT * FROM ${table(args, 1)} LIMIT ?", limit(args, 2))
                    @Suppress("UNCHECKED_CAST")
                    val columns = (result["columns"] as? List<String>).orEmpty()
                    whole(result).map { row -> columns.indices.associate { columns[it] to row.getOrNull(it) } }
                },
            "integrity" to SqlTemplateFunction { args -> tool(callFailure, "integrity_check", mapOf("database" to path(args, 0))) },
            "suggest_indexes" to
                SqlTemplateFunction { args ->
                    tool(callFailure, "suggest_indexes", mapOf("database" to path(args, 0), "sql" to string(args, 1, "sql")))
                },
            "lint_foreign_keys" to SqlTemplateFunction { args -> tool(callFailure, "lint_foreign_keys", mapOf("database" to path(args, 0))) },
        )

    private fun query(
        callFailure: AtomicReference<Exception>,
        database: String,
        sql: String,
        args: List<Any?>,
    ): Map<String, Any?> = tool(callFailure, "query", mapOf("database" to database, "sql" to sql, "args" to JSONArray(args)))

    /**
     * Runs [sql], whose one parameter is [limit], asking the server for that many rows: its own default is
     * 100, whatever LIMIT the SQL has.
     */
    private fun queryUpTo(
        callFailure: AtomicReference<Exception>,
        database: String,
        sql: String,
        limit: Long,
    ): Map<String, Any?> =
        tool(callFailure, "query", mapOf("database" to database, "sql" to sql, "args" to JSONArray(listOf(limit)), "max_rows" to limit))

    /**
     * [result]'s rows, which must be all of them and whole: a template that builds an IN list or a CASE from
     * them would otherwise be silently incomplete or splice a cut value.
     */
    private fun whole(result: Map<String, Any?>): List<List<Any?>> {
        val rows = rows(result)
        if (result["has_more"] == true) {
            throw IllegalStateException("the server returned only ${rows.size} rows: the rest did not fit in one result; ask for fewer")
        }
        val cut = (result["truncated_cells"] as? Number)?.toLong() ?: 0L
        if (cut > 0) throw IllegalStateException("the server cut $cut values to the length it returns, so they cannot be used whole")
        return rows
    }

    /**
     * Calls [name] and returns its structured result; a failed result stops the render with the server's message.
     * An exception from the call itself is kept in [callFailure] as well.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tool(
        callFailure: AtomicReference<Exception>,
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> {
        val result =
            try {
                callTool(name, arguments)
            } catch (e: Exception) {
                callFailure.compareAndSet(null, e)
                throw e
            }
        if (!result.success) throw IllegalStateException(result.errorMessage ?: "$name failed")
        return plain(JSONObject(result.text)) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(result: Map<String, Any?>): List<List<Any?>> = (result["rows"] as? List<List<Any?>>).orEmpty()

    private fun firstCell(result: Map<String, Any?>): Any? = rows(result).firstOrNull()?.firstOrNull()

    /** A database argument: its path, or a database map from the variables (its `path`). */
    private fun path(
        args: List<Any?>,
        index: Int,
    ): String =
        when (val v = args.getOrNull(index)) {
            is String -> v
            is Map<*, *> -> v["path"] as? String ?: throw IllegalArgumentException("argument ${index + 1} has no path")
            else -> throw IllegalArgumentException("argument ${index + 1} must be a database or its path")
        }

    private fun table(
        args: List<Any?>,
        index: Int,
    ): String =
        when (val v = args.getOrNull(index)) {
            is String -> quoteIdentifier(v)
            is Map<*, *> -> quoteIdentifier(v["name"] as? String ?: throw IllegalArgumentException("argument ${index + 1} has no name"))
            else -> throw IllegalArgumentException("argument ${index + 1} must be a table or its name")
        }

    private fun string(
        args: List<Any?>,
        index: Int,
        what: String,
    ): String = args.getOrNull(index) as? String ?: throw IllegalArgumentException("argument ${index + 1} ($what) must be a string")

    private fun limit(
        args: List<Any?>,
        index: Int,
    ): Long {
        val n = (args.getOrNull(index) as? Number)?.toLong() ?: return DEFAULT_LIMIT
        require(n in 1..MAX_LIMIT) { "argument ${index + 1} must be from 1 to $MAX_LIMIT" }
        return n
    }

    /** JSON as plain Kotlin values, mutable so database maps can take the derived keys. A JSON integer stays a Long. */
    private fun plain(value: Any?): Any? =
        when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> LinkedHashMap<String, Any?>().apply { for (k in value.keys()) put(k, plain(value.get(k))) }
            is JSONArray -> MutableList(value.length()) { plain(value.get(it)) }
            is Int -> value.toLong()
            is java.math.BigDecimal -> value.toDouble()
            is java.math.BigInteger -> value.toLong()
            else -> value
        }
}
