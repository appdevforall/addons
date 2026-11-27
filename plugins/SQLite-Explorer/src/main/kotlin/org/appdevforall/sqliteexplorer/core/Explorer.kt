package org.appdevforall.sqliteexplorer.core

import org.json.JSONObject

/** The database debugger of one app: Code on the Go's stored port and token for it. */
class Endpoint(val applicationId: String, val port: Int, val token: String, val updatedAtMillis: Long)

/** Which app to explore, given the open project's endpoints. */
object EndpointChoice {
    sealed class Outcome {
        /** No app of this project has reported a debugger: not built with it, or never run. */
        object NoneReported : Outcome()

        class One(val endpoint: Endpoint) : Outcome()

        /** Several apps: the user picks. Most recently reported first. */
        class Several(val endpoints: List<Endpoint>) : Outcome()
    }

    fun choose(endpoints: List<Endpoint>, remembered: String?): Outcome {
        if (endpoints.isEmpty()) return Outcome.NoneReported
        endpoints.firstOrNull { it.applicationId == remembered }?.let { return Outcome.One(it) }
        if (endpoints.size == 1) return Outcome.One(endpoints[0])
        return Outcome.Several(endpoints.sortedByDescending { it.updatedAtMillis })
    }
}

/** The read-only tools of the app's database debugger, with their results parsed. Calls block. */
class Explorer(private val client: McpClient) {
    fun databases(): List<Database> = Database.parseList(client.callTool("list_databases"))

    fun schema(database: String): Schema = Schema.parse(client.callTool("get_schema", on(database)))

    /** One page of [table]'s rows, in the table's own order. */
    fun rows(database: String, table: String, offset: Long, pageSize: Int): QueryPage =
        query(database, Sql.selectAll(table), offset, pageSize)

    fun query(database: String, sql: String, offset: Long, pageSize: Int): QueryPage {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Page size $pageSize is out of range." }
        val args = on(database).put("sql", sql).put("offset", offset).put("max_rows", pageSize)
        return QueryPage.parse(client.callTool("query", args))
    }

    fun plan(database: String, sql: String): QueryPlan = QueryPlan.parse(client.callTool("explain_query_plan", on(database).put("sql", sql)))

    fun stats(database: String): DatabaseStats = DatabaseStats.parse(client.callTool("get_stats", on(database)))

    /** `quick_check` unless [full], which runs `integrity_check` and can take much longer on a large database. */
    fun integrity(database: String, full: Boolean): IntegrityReport =
        IntegrityReport.parse(client.callTool("integrity_check", on(database).put("full", full)))

    private fun on(database: String) = JSONObject().put("database", database)

    companion object {
        /** The server's own limit on `max_rows`. */
        const val MAX_PAGE_SIZE = 1000
        const val DEFAULT_PAGE_SIZE = 100
    }
}

object Sql {
    /** A double-quoted SQLite identifier: inner quotes doubled, so any table name is safe in SQL. */
    fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    fun selectAll(table: String): String = "SELECT * FROM ${quoteIdentifier(table)}"
}
