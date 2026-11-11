package org.appdevforall.localstore.server

import java.io.ByteArrayOutputStream

/** Just enough of a database to run the help queries: rows of column values (String, Long, ByteArray, null). */
fun interface HelpSql {
    fun rows(sql: String, args: Array<String>): List<List<Any?>>
}

class HelpTooltip(val id: Long, val summary: String, val detail: String, val buttons: List<Pair<String, String>>)
class HelpPage(val mimeType: String, val bytes: ByteArray)
class HelpException(message: String) : Exception(message)

/**
 * Reads web_server_documentation.db, which uses CodeOnTheGo's documentation.db tables and
 * columns, with the same queries. Its pages are stored as plain HTML (compression 'none', no
 * template), so neither Brotli nor Pebble is needed; a row declaring either is refused clearly
 * rather than misread.
 */
class HelpStore(private val sql: HelpSql) {

    companion object {
        const val CATEGORY = "localstore_server"
        /** CodeOnTheGo's CONTENT_CHUNK_SIZE: a row this long continues as <path>-1, <path>-2, … */
        const val CHUNK_SIZE = 1024 * 1024

        private const val QUERY_TOOLTIP = "SELECT T.rowid, T.id, T.summary, T.detail FROM Tooltips AS T, " +
            "TooltipCategories AS TC WHERE T.categoryId = TC.id AND T.tag = ? AND TC.category = ?"
        private const val QUERY_BUTTONS = "SELECT description, uri FROM TooltipButtons WHERE tooltipId = ? ORDER BY buttonNumberId"
        private const val QUERY_CONTENT = "SELECT C.content, CT.value, CT.compression, C.templateId FROM Content C, " +
            "ContentTypes CT WHERE C.contentTypeID = CT.id AND C.path = ?"
        private const val QUERY_CHUNK = "SELECT content FROM Content WHERE path = ?"
    }

    fun tooltip(tag: String): HelpTooltip? {
        val row = sql.rows(QUERY_TOOLTIP, arrayOf(tag, CATEGORY)).firstOrNull() ?: return null
        val id = (row[1] as Number).toLong()
        val buttons = sql.rows(QUERY_BUTTONS, arrayOf(id.toString())).map { it[0] as String to it[1] as String }
        return HelpTooltip(id, row[2] as String, row[3] as? String ?: "", buttons)
    }

    /**
     * The documentation database rows behind a tooltip, for the ⓘ panel (as CodeOnTheGo's debug popup):
     * the database's version; the tooltip's row and category; its button rows; and, for each button's
     * page, its content row and any continuation chunks. Each section is a title and its lines.
     */
    fun debug(tag: String): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, List<String>>>()
        val version = runCatching { sql.rows("SELECT version FROM DocumentationDatabaseVersion", emptyArray()).firstOrNull()?.get(0) }.getOrNull()
        out += "Database" to listOf("web_server_documentation.db", "version ${version ?: "unknown"}", "category $CATEGORY")
        val row = sql.rows("SELECT T.rowid, T.id, T.categoryId, TC.category, T.tag, length(T.summary), length(T.detail) FROM Tooltips AS T, " +
            "TooltipCategories AS TC WHERE T.categoryId = TC.id AND T.tag = ? AND TC.category = ?", arrayOf(tag, CATEGORY)).firstOrNull()
        if (row == null) { out += "Tooltips" to listOf("no row for tag \"$tag\" in category $CATEGORY"); return out }
        out += "Tooltips" to listOf("rowid ${row[0]} · id ${row[1]}", "categoryId ${row[2]} (${row[3]})", "tag ${row[4]}",
            "summary ${row[5]} characters · detail ${row[6]} characters")
        val buttons = sql.rows("SELECT rowid, buttonNumberId, description, uri FROM TooltipButtons WHERE tooltipId = ? ORDER BY buttonNumberId",
            arrayOf(row[1].toString()))
        out += "TooltipButtons" to (buttons.map { "rowid ${it[0]} · #${it[1]} · \"${it[2]}\" → ${it[3]}" }.ifEmpty { listOf("none") })
        for (path in buttons.map { (it[3] as String).substringBefore('#') }.distinct()) {
            val c = sql.rows("SELECT C.rowid, C.path, CT.value, CT.compression, C.languageId, C.templateId, length(C.content) FROM Content C, " +
                "ContentTypes CT WHERE C.contentTypeID = CT.id AND C.path = ?", arrayOf(path)).firstOrNull()
            val chunks = sql.rows("SELECT count(*) FROM Content WHERE path LIKE ?", arrayOf("$path-%")).firstOrNull()?.get(0)
            out += "Content: $path" to (if (c == null) listOf("no row") else listOf("rowid ${c[0]} · ${c[2]}, compression ${c[3]}",
                "languageId ${c[4]} · templateId ${c[5]}", "${c[6]} bytes · ${chunks ?: 0} continuation chunks"))
        }
        return out
    }

    /** A Tier-3 page by path, or null if there's no such page. */
    fun page(path: String): HelpPage? {
        val row = sql.rows(QUERY_CONTENT, arrayOf(path)).firstOrNull() ?: return null
        val compression = row[2] as? String ?: "none"
        val templateId = (row[3] as? Number)?.toLong() ?: 0
        if (compression != "none") throw HelpException("$path is stored with $compression compression, which this app doesn't read")
        if (templateId != 0L) throw HelpException("$path uses a template, which this app doesn't render")
        val first = row[0] as ByteArray
        if (first.size != CHUNK_SIZE) return HelpPage(row[1] as String, first)
        val out = ByteArrayOutputStream().apply { write(first) }
        var n = 1
        var chunk = first
        while (chunk.size == CHUNK_SIZE) {
            chunk = sql.rows(QUERY_CHUNK, arrayOf("$path-$n")).firstOrNull()?.get(0) as? ByteArray ?: break
            out.write(chunk)
            n++
        }
        return HelpPage(row[1] as String, out.toByteArray())
    }
}
