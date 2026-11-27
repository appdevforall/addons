package org.appdevforall.sqliteexplorer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.sqliteexplorer.core.Cell
import org.appdevforall.sqliteexplorer.core.Database
import org.appdevforall.sqliteexplorer.core.Endpoint
import org.appdevforall.sqliteexplorer.core.EndpointChoice
import org.appdevforall.sqliteexplorer.core.Explorer
import org.appdevforall.sqliteexplorer.core.McpException
import org.appdevforall.sqliteexplorer.core.Problems
import org.appdevforall.sqliteexplorer.core.QueryPage
import org.appdevforall.sqliteexplorer.core.Schema
import org.appdevforall.sqliteexplorer.core.Sql

/**
 * The SQLite Explorer screen. Views are built in code, so there is no layout to inflate against the host's
 * resources; every text size is in sp and every gap in dp, inside scroll views, so the screen holds up at 2x font
 * scale. It shows one page at a time from a small back stack: connect, databases, one database, rows or text.
 */
class ExplorerFragment : Fragment() {
    private sealed class Page {
        object Connect : Page()

        object Databases : Page()

        class OneDatabase(val database: Database) : Page()

        class Query(val database: Database) : Page()

        /** Rows of [sql]: a table's rows, or a typed query's result. */
        class Rows(val database: Database, val title: String, val sql: String, val offset: Long = 0) : Page()

        class Text(val title: String, val body: String, val monospace: Boolean) : Page()
    }

    private val stack = ArrayList<Page>()
    private var scope: CoroutineScope? = null
    private var running: Job? = null
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var backButton: Button
    private var queryText = ""
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        backButton = button(ctx, "Back", Help.BACK) { goBack() }
        spinner = ProgressBar(ctx).apply { visibility = View.GONE }
        status = text(ctx, "", 14f, tooltip = Help.STATUS)
        content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
            addView(backButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(spinner)
            addView(status)
            addView(content)
        }
        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(column)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        if (stack.isEmpty()) stack += if (Connection.explorer != null) Page.Databases else Page.Connect
        show()
    }

    override fun onDestroyView() {
        scope?.cancel()
        scope = null
        running = null
        super.onDestroyView()
    }

    private fun push(page: Page) {
        stack += page
        show()
    }

    /** Replaces the top page, for paging through rows. */
    private fun replace(page: Page) {
        stack[stack.size - 1] = page
        show()
    }

    private fun goBack() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        show()
    }

    private fun show() {
        if (view == null) return
        running?.cancel()
        running = null
        spinner.visibility = View.GONE
        status.text = ""
        content.removeAllViews()
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        back.isEnabled = stack.size > 1
        when (val page = stack.last()) {
            Page.Connect -> showConnect()
            Page.Databases -> showDatabases()
            is Page.OneDatabase -> showDatabase(page.database)
            is Page.Query -> showQuery(page.database)
            is Page.Rows -> showRows(page)
            is Page.Text -> showText(page)
        }
    }

    /**
     * Runs [work] on a background thread, with the spinner up, then [done] on the main thread. A failure shows its
     * message, with a Reconnect button when reconnecting can help.
     */
    private fun <T> load(what: String, work: (Explorer?) -> T, done: (T) -> Unit) {
        val scope = scope ?: return
        spinner.visibility = View.VISIBLE
        status.text = what
        running = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work(Connection.explorer) }
                spinner.visibility = View.GONE
                status.text = ""
                done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                spinner.visibility = View.GONE
                val problem = Problems.describe(e)
                status.text = problem.message
                if (problem.reconnect) {
                    content.addView(button(requireContext(), "Reconnect", Help.CONNECT) { reconnect() })
                }
            }
        }
    }

    /** The explorer, or a failure that offers to reconnect: the plugin was disabled, or the screen outlived it. */
    private fun connected(explorer: Explorer?): Explorer = explorer ?: throw McpException("Not connected to the app.")

    private fun reconnect() {
        Connection.forget()
        stack.clear()
        stack += Page.Connect
        show()
    }

    private fun showConnect() {
        val ctx = requireContext()
        content.addView(heading(ctx, "Connect", Help.CONNECT))
        content.addView(
            text(
                ctx,
                "Reads the databases of the open project's app while its debug build runs. It only reads: nothing here " +
                    "changes the app's data.",
                16f,
                tooltip = Help.MENU,
            ),
        )
        content.addView(button(ctx, "Connect", Help.CONNECT) { connect() })
    }

    private fun connect() {
        load("Looking for the app...", { Connection.endpoints() }) { endpoints ->
            when (val outcome = EndpointChoice.choose(endpoints, prefs().getString(PREF_APP, null))) {
                EndpointChoice.Outcome.NoneReported -> {
                    status.text = Problems.NO_PROJECT_OR_APP
                    content.addView(button(requireContext(), "Try again", Help.CONNECT) { connect() })
                }
                is EndpointChoice.Outcome.One -> choose(outcome.endpoint)
                is EndpointChoice.Outcome.Several -> {
                    val ctx = requireContext()
                    content.removeAllViews()
                    content.addView(heading(ctx, "Choose the app", Help.APP))
                    outcome.endpoints.forEach { endpoint -> content.addView(button(ctx, endpoint.applicationId, Help.APP) { choose(endpoint) }) }
                }
            }
        }
    }

    private fun choose(endpoint: Endpoint) {
        prefs().edit().putString(PREF_APP, endpoint.applicationId).apply()
        try {
            Connection.use(endpoint)
        } catch (e: IllegalArgumentException) {
            status.text = e.message
            return
        }
        stack.clear()
        stack += Page.Databases
        show()
    }

    private fun showDatabases() {
        val ctx = requireContext()
        content.addView(heading(ctx, "Databases of ${Connection.endpoint?.applicationId ?: "the app"}", Help.DATABASES))
        load("Reading the app's databases...", { connected(it).databases() }) { databases ->
            if (databases.isEmpty()) {
                content.addView(text(ctx, "The app has no database open yet. Use the app until it opens one, then refresh.", 16f, tooltip = Help.DATABASES))
            }
            databases.forEach { database ->
                if (database.available) {
                    content.addView(button(ctx, database.name, Help.DATABASES) { push(Page.OneDatabase(database)) })
                } else {
                    content.addView(text(ctx, "${database.name} (not available)", 16f, tooltip = Help.UNAVAILABLE))
                }
            }
            content.addView(button(ctx, "Refresh", Help.DATABASES) { show() })
            content.addView(button(ctx, "Switch app", Help.APP) { reconnect() })
        }
    }

    private fun showDatabase(database: Database) {
        val ctx = requireContext()
        content.addView(heading(ctx, database.name, Help.DATABASES))
        content.addView(text(ctx, database.path, 13f, tooltip = Help.DATABASES))
        content.addView(button(ctx, "Query", Help.QUERY) { push(Page.Query(database)) })
        content.addView(button(ctx, "Stats", Help.STATS) { loadStats(database) })
        content.addView(button(ctx, "Quick integrity check", Help.INTEGRITY) { loadIntegrity(database, full = false) })
        content.addView(button(ctx, "Full integrity check", Help.INTEGRITY) { loadIntegrity(database, full = true) })
        load("Reading the schema...", { connected(it).schema(database.path) }) { schema -> addSchema(ctx, database, schema) }
    }

    private fun addSchema(ctx: Context, database: Database, schema: Schema) {
        content.addView(heading(ctx, "Tables and views", Help.TABLES))
        val browsable = schema.tables + schema.views
        if (browsable.isEmpty()) content.addView(text(ctx, "None.", 16f, tooltip = Help.TABLES))
        browsable.forEach { entry ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                button(ctx, entry.name, Help.TABLES) { push(Page.Rows(database, entry.name, Sql.selectAll(entry.name))) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            entry.sql?.let { sql -> row.addView(button(ctx, "SQL", Help.SCHEMA_SQL) { push(Page.Text(entry.name, sql, monospace = true)) }) }
            content.addView(row)
        }
        val others = schema.indexes + schema.triggers
        if (others.isNotEmpty()) {
            content.addView(heading(ctx, "Indexes and triggers", Help.OTHER_OBJECTS))
            others.forEach { entry ->
                val label = "${entry.name} (${entry.table})"
                val sql = entry.sql
                if (sql == null) {
                    content.addView(text(ctx, "$label: made by SQLite, no statement", 15f, tooltip = Help.OTHER_OBJECTS))
                } else {
                    content.addView(button(ctx, label, Help.OTHER_OBJECTS) { push(Page.Text(entry.name, sql, monospace = true)) })
                }
            }
        }
        if (schema.truncated) content.addView(text(ctx, "The schema is too large to show in full: some entries or statements are cut.", 14f, tooltip = Help.SCHEMA_SQL))
    }

    private fun loadStats(database: Database) {
        load("Reading the stats...", { connected(it).stats(database.path) }) { stats ->
            val lines = ArrayList<String>()
            stats.sizeBytes?.let { lines += "Size: ${size(it)}" }
            if (stats.pageCount != null && stats.pageSize != null) lines += "Pages: ${stats.pageCount} of ${stats.pageSize} bytes"
            stats.freelistCount?.let { lines += "Free pages: $it" }
            stats.rowCounts?.let { counts ->
                lines += ""
                lines += "Rows per table:"
                counts.forEach { (table, n) -> lines += "  $table: $n" }
            }
            stats.rowCountsSkipped?.let { lines += ""; lines += it }
            push(Page.Text("Stats of ${database.name}", lines.joinToString("\n"), monospace = false))
        }
    }

    private fun loadIntegrity(database: Database, full: Boolean) {
        load(if (full) "Running the full check. On a large database this takes a while..." else "Running the quick check...", { connected(it).integrity(database.path, full) }) { report ->
            val verdict = if (report.ok) "No problems found." else "Problems found:"
            val body = if (report.ok) verdict else verdict + "\n\n" + report.messages.joinToString("\n")
            push(Page.Text("${if (full) "Full" else "Quick"} check of ${database.name}", body, monospace = !report.ok))
        }
    }

    private fun showQuery(database: Database) {
        val ctx = requireContext()
        content.addView(heading(ctx, "Query ${database.name}", Help.QUERY))
        val field = EditText(ctx).apply {
            id = View.generateViewId()
            setText(queryText)
            hint = "SELECT * FROM table_name WHERE ..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            withTooltip(this, Help.QUERY, consume = false)
        }
        content.addView(text(ctx, "One SQL statement", 14f, tooltip = Help.QUERY).apply { labelFor = field.id })
        content.addView(field)
        fun sql(): String? = field.text.toString().trim().also { queryText = it }.ifEmpty { null }
        content.addView(button(ctx, "Run", Help.RUN) { sql()?.let { push(Page.Rows(database, "Result", it)) } })
        content.addView(button(ctx, "Query plan", Help.PLAN) {
            val statement = sql() ?: return@button
            load("Reading the plan...", { connected(it).plan(database.path, statement) }) { plan ->
                push(Page.Text("Query plan", plan.asTree().joinToString("\n").ifEmpty { "SQLite gave no plan." }, monospace = true))
            }
        })
    }

    private fun showRows(page: Page.Rows) {
        val ctx = requireContext()
        content.addView(heading(ctx, page.title, Help.RESULT))
        load("Running the query...", { connected(it).query(page.database.path, page.sql, page.offset, Explorer.DEFAULT_PAGE_SIZE) }) { result ->
            addResult(ctx, page, result)
        }
    }

    private fun addResult(ctx: Context, page: Page.Rows, result: QueryPage) {
        if (result.columns.isEmpty()) {
            content.addView(text(ctx, "The statement returned no columns.", 16f, tooltip = Help.RESULT))
            return
        }
        val first = result.offset + 1
        val last = result.offset + result.rows.size
        val range = if (result.rows.isEmpty()) "No rows" + if (result.offset > 0) " after row ${result.offset}" else "" else "Rows $first to $last" + if (result.hasMore) " (more follow)" else ""
        content.addView(text(ctx, range, 14f, tooltip = Help.PAGING))
        content.addView(HorizontalScrollView(ctx).apply { addView(table(ctx, result)) })
        if (result.truncatedCells > 0) {
            content.addView(text(ctx, "${result.truncatedCells} values are cut at the server's limit; they end in an ellipsis.", 14f, tooltip = Help.RESULT))
        }
        val paging = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (page.offset > 0) {
            paging.addView(button(ctx, "Previous", Help.PAGING) { replace(Page.Rows(page.database, page.title, page.sql, maxOf(0, page.offset - Explorer.DEFAULT_PAGE_SIZE))) })
        }
        result.nextOffset?.let { next -> paging.addView(button(ctx, "Next", Help.PAGING) { replace(Page.Rows(page.database, page.title, page.sql, next)) }) }
        if (paging.childCount > 0) content.addView(paging)
    }

    private fun table(ctx: Context, result: QueryPage): TableLayout {
        val table = TableLayout(ctx)
        val header = TableRow(ctx)
        result.columns.forEachIndexed { i, name ->
            val type = result.columnTypes.getOrNull(i)
            header.addView(cellView(ctx, if (type.isNullOrEmpty()) name else "$name\n$type", bold = true).apply { withTooltip(this, Help.RESULT) })
        }
        table.addView(header)
        result.rows.forEach { row ->
            val tableRow = TableRow(ctx)
            row.forEachIndexed { c, cell ->
                val label = (if (cell.truncated) cell.display + "..." else cell.display).let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "..." else it }
                tableRow.addView(
                    cellView(ctx, label, italic = cell == Cell.Null).apply {
                        setOnClickListener { showCell(result.columns[c], cell) }
                        withTooltip(this, Help.CELL)
                    },
                )
            }
            table.addView(tableRow)
        }
        return table
    }

    private fun showCell(column: String, cell: Cell) {
        val body =
            when (cell) {
                is Cell.Blob -> "BLOB, ${cell.bytes} bytes${if (cell.truncated) ", first ${cell.hex.length / 2} shown" else ""}\n\n" + cell.hex.chunked(2).chunked(16).joinToString("\n") { it.joinToString(" ") }
                is Cell.Text -> cell.value + if (cell.truncated) "\n\n(Cut at the server's limit of 4 KiB.)" else ""
                else -> cell.display
            }
        push(Page.Text(column, body, monospace = cell is Cell.Blob))
    }

    private fun showText(page: Page.Text) {
        val ctx = requireContext()
        content.addView(heading(ctx, page.title, Help.RESULT))
        content.addView(
            text(ctx, page.body, 15f, tooltip = Help.RESULT, consume = false).apply {
                setTextIsSelectable(true)
                if (page.monospace) typeface = Typeface.MONOSPACE
            },
        )
    }

    private fun prefs() = requireContext().getSharedPreferences("sqlite_explorer", Context.MODE_PRIVATE)

    private fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1048576.0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(ctx: Context, content: String, sp: Float, bold: Boolean = false, tooltip: String? = null, consume: Boolean = true) = TextView(ctx).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
        if (tooltip != null) withTooltip(this, tooltip, consume)
    }

    private fun heading(ctx: Context, content: String, tooltip: String) =
        text(ctx, content, 18f, bold = true, tooltip = tooltip).apply { setPadding(0, dp(16), 0, dp(4)) }

    private fun button(ctx: Context, label: String, tooltip: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        withTooltip(this, tooltip)
    }

    /** One table cell. Its width grows with its text up to a limit; a longer value wraps, and a tap shows all of it. */
    private fun cellView(ctx: Context, content: String, bold: Boolean = false, italic: Boolean = false) = TextView(ctx).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, when {
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })
        maxWidth = dp(CELL_MAX_WIDTH_DP)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setBackgroundResource(android.R.drawable.editbox_background)
    }

    /** Long-press shows the tooltip. [consume] false lets the press carry on to the view's own long-press. */
    private fun withTooltip(view: View, tag: String, consume: Boolean = true) {
        view.setOnLongClickListener { v ->
            SqliteExplorerPlugin.tooltips()?.showTooltip(v, Help.CATEGORY, tag)
            consume
        }
    }

    private companion object {
        const val PREF_APP = "app"

        /** Characters of a value shown in its cell; the whole value is a tap away. */
        const val PREVIEW_CHARS = 200

        /** A cell's width limit, so one long value does not push the other columns off the screen. */
        const val CELL_MAX_WIDTH_DP = 240
    }
}
