package org.appdevforall.sqliteexplorer.core

import org.json.JSONArray
import org.json.JSONObject

/** The app's server predates the result shapes this plugin reads (ADFA-7297). */
class UnsupportedServerException(what: String) :
    McpException("The app's database debugger is too old ($what). Rebuild the app with a newer Code on the Go.")

class Database(val path: String, val available: Boolean, val connections: Int) {
    /** The file name, which is what a person recognises. */
    val name: String get() = path.substringAfterLast('/')

    companion object {
        fun parseList(result: JSONObject): List<Database> {
            val list = result.optJSONArray("databases") ?: throw UnsupportedServerException("no databases list")
            return objects(list).map { Database(it.getString("path"), it.optBoolean("available"), it.optInt("connections")) }
        }
    }
}

/** One schema entry: a table, index, view or trigger. [table] is the table it belongs to. */
class SchemaEntry(val name: String, val table: String, val sql: String?, val truncated: Boolean)

class Schema(
    val tables: List<SchemaEntry>,
    val views: List<SchemaEntry>,
    val indexes: List<SchemaEntry>,
    val triggers: List<SchemaEntry>,
    /** Entries were left out or a CREATE statement was cut. */
    val truncated: Boolean,
) {
    companion object {
        fun parse(result: JSONObject): Schema {
            fun group(key: String) =
                objects(result.optJSONArray(key) ?: JSONArray()).map {
                    SchemaEntry(it.optString("name"), it.optString("table"), it.optStringOrNull("sql"), it.optBoolean("truncated"))
                }
            return Schema(group("tables"), group("views"), group("indexes"), group("triggers"), result.optBoolean("truncated"))
        }
    }
}

/** One SQLite value, as the server sent it. */
sealed class Cell {
    /** What the cell shows in a table. */
    abstract val display: String

    /** The server cut this value at its size limit. */
    open val truncated: Boolean get() = false

    object Null : Cell() {
        override val display = "NULL"
    }

    /** An INTEGER, kept as text: one beyond 2^53 arrives as a decimal string. */
    class Integer(override val display: String) : Cell()

    /** A REAL, including "NaN", "Infinity" and "-Infinity". */
    class Real(override val display: String) : Cell()

    class Text(val value: String, override val truncated: Boolean) : Cell() {
        override val display get() = value
    }

    /** A BLOB: [hex] holds at most the server's limit, [bytes] is the whole value's size. */
    class Blob(val hex: String, val bytes: Long, override val truncated: Boolean) : Cell() {
        override val display get() = "BLOB, $bytes bytes" + if (hex.isEmpty()) "" else ": " + hex.take(PREVIEW_HEX_CHARS) + if (hex.length > PREVIEW_HEX_CHARS || truncated) "..." else ""
    }

    companion object {
        private const val PREVIEW_HEX_CHARS = 32

        /** Reads one value of storage class [storageClass] (i, r, t, b, n). */
        fun parse(value: Any?, storageClass: Char, truncated: Boolean): Cell =
            when (storageClass) {
                'n' -> Null
                'i' -> Integer(value.toString())
                'r' -> Real(realText(value))
                't' -> Text(value.toString(), truncated)
                'b' -> {
                    val blob = value as? JSONObject ?: throw UnsupportedServerException("a BLOB is not {hex, bytes, truncated}")
                    if (!blob.has("hex")) throw UnsupportedServerException("a BLOB has no hex")
                    Blob(blob.getString("hex"), blob.optLong("bytes"), blob.optBoolean("truncated") || truncated)
                }
                else -> throw UnsupportedServerException("unknown storage class '$storageClass'")
            }

        /** JSON writes a REAL such as 2.0 as 2; show it as SQLite would. */
        private fun realText(value: Any?): String =
            when (value) {
                is Number -> {
                    val d = value.toDouble()
                    if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) "${d.toLong()}.0" else d.toString()
                }
                else -> value.toString()
            }
    }
}

/** One page of a query's result. */
class QueryPage(
    val columns: List<String>,
    /** Each column's declared type, or null for an expression. */
    val columnTypes: List<String?>,
    val rows: List<List<Cell>>,
    val offset: Long,
    val hasMore: Boolean,
    val nextOffset: Long?,
    val truncatedCells: Int,
) {
    companion object {
        fun parse(result: JSONObject): QueryPage {
            val types = result.optJSONArray("column_types") ?: throw UnsupportedServerException("no column types")
            val classes = result.optJSONArray("storage_classes") ?: throw UnsupportedServerException("no storage classes")
            val columns = strings(result.getJSONArray("columns"))
            val cut = HashSet<Pair<Int, Int>>()
            result.optJSONArray("truncated_cell_indexes")?.let { list ->
                for (i in 0 until list.length()) list.optJSONArray(i)?.let { cut += it.getInt(0) to it.getInt(1) }
            }
            val jsonRows = result.getJSONArray("rows")
            if (classes.length() != jsonRows.length()) throw McpException("The app's server sent ${jsonRows.length()} rows but ${classes.length()} storage-class strings.")
            val rows =
                (0 until jsonRows.length()).map { r ->
                    val row = jsonRows.getJSONArray(r)
                    val letters = classes.getString(r)
                    if (letters.length != row.length() || row.length() != columns.size) {
                        throw McpException("Row $r has ${row.length()} values for ${columns.size} columns.")
                    }
                    (0 until row.length()).map { c -> Cell.parse(row.opt(c), letters[c], (r to c) in cut) }
                }
            return QueryPage(
                columns = columns,
                columnTypes = (0 until types.length()).map { types.optStringOrNull(it) },
                rows = rows,
                offset = result.optLong("offset"),
                hasMore = result.optBoolean("has_more"),
                nextOffset = if (result.has("next_offset")) result.optLong("next_offset") else null,
                truncatedCells = result.optInt("truncated_cells"),
            )
        }
    }
}

/** EXPLAIN QUERY PLAN's rows: each a column-to-value map, in the order SQLite gave them. */
class QueryPlan(val rows: List<Map<String, String>>) {
    /** The plan as an indented tree, one step per line, built from each row's id and parent. */
    fun asTree(): List<String> {
        val byParent = rows.groupBy { it["parent"] }
        val out = ArrayList<String>()
        fun walk(parent: String, depth: Int) {
            byParent[parent].orEmpty().forEach { row ->
                out += "  ".repeat(depth) + (row["detail"] ?: row.values.joinToString(" "))
                row["id"]?.let { if (it != parent) walk(it, depth + 1) }
            }
        }
        walk("0", 0)
        // Rows whose parent is missing would otherwise vanish.
        if (out.size < rows.size) return rows.map { it["detail"] ?: it.values.joinToString(" ") }
        return out
    }

    companion object {
        fun parse(result: JSONObject): QueryPlan =
            QueryPlan(objects(result.optJSONArray("plan") ?: JSONArray()).map { row -> row.keys().asSequence().associateWith { row.opt(it)?.toString().orEmpty() } })
    }
}

class DatabaseStats(
    val pageCount: Long?,
    val pageSize: Long?,
    val freelistCount: Long?,
    val sizeBytes: Long?,
    /** Rows per table, or null when the server skipped counting ([rowCountsSkipped] says why). */
    val rowCounts: Map<String, String>?,
    val rowCountsSkipped: String?,
) {
    companion object {
        fun parse(result: JSONObject): DatabaseStats {
            fun long(key: String) = if (result.isNull(key)) null else result.opt(key)?.toString()?.toLongOrNull()
            val counts = result.optJSONObject("row_counts")?.let { o -> o.keys().asSequence().sorted().associateWith { o.opt(it)?.toString().orEmpty() } }
            return DatabaseStats(long("page_count"), long("page_size"), long("freelist_count"), long("size_bytes"), counts, result.optStringOrNull("row_counts_skipped"))
        }
    }
}

class IntegrityReport(val check: String, val ok: Boolean, val messages: List<String>) {
    companion object {
        fun parse(result: JSONObject) =
            IntegrityReport(result.optString("check"), result.optBoolean("ok"), strings(result.optJSONArray("messages") ?: JSONArray()))
    }
}

private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.opt(it).toString() }

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

private fun JSONArray.optStringOrNull(index: Int): String? = if (isNull(index)) null else optString(index)
