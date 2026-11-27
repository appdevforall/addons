package org.appdevforall.sqliteexplorer

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * Tooltips for SQLite Explorer. The category is `plugin_` plus the plugin ID, exactly, and lookups pass it
 * explicitly to the three-argument showTooltip (plugins/README.md).
 */
object Help {
    const val CATEGORY = "plugin_" + SqliteExplorerPlugin.PLUGIN_ID

    const val MENU = "sqliteexplorer.menu"
    const val CONNECT = "sqliteexplorer.connect"
    const val APP = "sqliteexplorer.app"
    const val STATUS = "sqliteexplorer.status"
    const val BACK = "sqliteexplorer.back"
    const val DATABASES = "sqliteexplorer.databases"
    const val UNAVAILABLE = "sqliteexplorer.unavailable"
    const val TABLES = "sqliteexplorer.tables"
    const val SCHEMA_SQL = "sqliteexplorer.schemasql"
    const val OTHER_OBJECTS = "sqliteexplorer.otherobjects"
    const val QUERY = "sqliteexplorer.query"
    const val RUN = "sqliteexplorer.run"
    const val PLAN = "sqliteexplorer.plan"
    const val STATS = "sqliteexplorer.stats"
    const val INTEGRITY = "sqliteexplorer.integrity"
    const val RESULT = "sqliteexplorer.result"
    const val CELL = "sqliteexplorer.cell"
    const val PAGING = "sqliteexplorer.paging"

    private val more = listOf(PluginTooltipButton(description = "About SQLite Explorer", uri = "index.html", order = 0))

    private fun entry(tag: String, summary: String, detail: String) = PluginTooltipEntry(tag, summary, detail, more)

    val entries = listOf(
        entry(
            MENU,
            "Browse and query the databases of the app you are building.",
            "<p>Reads the databases of the open project's app while its debug build runs, through the database " +
                "debugger Code on the Go adds to debug builds. It only reads: it cannot change the app's data.</p>",
        ),
        entry(
            CONNECT,
            "Connect to the database debugger of the open project's app.",
            "<p>Code on the Go keeps the port and token each app reports when its debug build starts. If the app " +
                "restarted, reconnect to use its new ones.</p><p>The debugger is in debug builds of projects whose " +
                "<code>gradle.properties</code> has <code>cogo.sqliteDebugger=true</code>.</p>",
        ),
        entry(APP, "The app whose databases are shown.", "<p>A project with several apps lists each one that has reported a debugger. The choice is remembered.</p>"),
        entry(STATUS, "What SQLite Explorer is doing, or what went wrong.", "<p>When a connection fails, the message says why and whether reconnecting can help.</p>"),
        entry(BACK, "Go back one screen.", "<p>The system Back gesture does the same.</p>"),
        entry(
            DATABASES,
            "The databases the app has open.",
            "<p>Only databases the app has opened can be read. Code on the Go's build changes the app to use the " +
                "same SQLite as the debugger, so most databases are listed.</p>",
        ),
        entry(
            UNAVAILABLE,
            "A database the debugger cannot read.",
            "<p>The app has no open connection to it, or opened it through Android's own SQLite, which the build " +
                "change did not reach. Reading it from a second copy of SQLite could corrupt it, so it is not opened.</p>",
        ),
        entry(TABLES, "The database's tables and views. Tap one to see its rows.", "<p>Rows come a page at a time, in the table's own order.</p>"),
        entry(SCHEMA_SQL, "The CREATE statement that defines this table, view, index or trigger.", "<p>A statement longer than 64 KiB is cut.</p>"),
        entry(OTHER_OBJECTS, "The database's indexes and triggers. Tap one to see its CREATE statement.", "<p>An index SQLite makes for a UNIQUE or PRIMARY KEY constraint has no statement.</p>"),
        entry(
            QUERY,
            "Type one SQL statement to run against this database.",
            "<p>The connection is read-only: the app's server refuses any statement that writes, and more than " +
                "one statement at a time. Functions the app adds to its own connections do not exist here.</p>",
        ),
        entry(RUN, "Run the statement and show its result.", "<p>Results come a page at a time. Paging runs the statement again, so a later page can differ if the data changed.</p>"),
        entry(PLAN, "Show how SQLite would run the statement.", "<p>Shows EXPLAIN QUERY PLAN as a tree: SCAN reads every row, SEARCH uses an index.</p>"),
        entry(STATS, "The database's size and the row count of each table.", "<p>Rows are counted only in a database with fewer than 10 tables, so this stays quick.</p>"),
        entry(
            INTEGRITY,
            "Check the database for corruption.",
            "<p>The quick check reads every page. The full check also checks that indexes match their tables, and " +
                "can take much longer on a large database.</p>",
        ),
        entry(
            RESULT,
            "The rows the statement returned.",
            "<p>Under each column name is its declared type, empty for an expression. NULL is shown in italics. " +
                "A value cut at the server's limit (4 KiB of text, 1 KiB of a BLOB) ends in an ellipsis.</p>",
        ),
        entry(CELL, "Tap a value to see all of it.", "<p>A BLOB is shown as hexadecimal, with its full size.</p>"),
        entry(PAGING, "Move between pages of rows.", "<p>Each page holds up to 100 rows.</p>"),
    )
}
