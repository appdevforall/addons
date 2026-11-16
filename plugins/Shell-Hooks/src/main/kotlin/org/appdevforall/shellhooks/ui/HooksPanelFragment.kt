package org.appdevforall.shellhooks.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.shellhooks.R
import org.appdevforall.shellhooks.ShellHooksHelp
import org.appdevforall.shellhooks.ShellHooksPlugin
import org.appdevforall.shellhooks.engine.HookRun
import org.appdevforall.shellhooks.engine.Outcome
import org.appdevforall.shellhooks.engine.PendingTrust
import org.appdevforall.shellhooks.engine.ScriptHints
import org.appdevforall.shellhooks.engine.ScriptReview
import java.util.Date

/**
 * The Hooks bottom-sheet tab: project scripts awaiting review, then the recent runs. A run's
 * output is hidden until its row is tapped.
 */
class HooksPanelFragment : Fragment() {
    private var tooltips: IdeTooltipService? = null
    private var reviewDialog: AlertDialog? = null

    /** A script is being read for review; further taps on Review wait for it. */
    private var reviewLoading = false

    /** Rows the user expanded, so a refresh keeps them open. */
    private val expanded = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tooltips =
            runCatching {
                PluginFragmentHelper.getServiceRegistry(ShellHooksPlugin.PLUGIN_ID)?.get(IdeTooltipService::class.java)
            }.getOrNull()
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        PluginFragmentHelper.getPluginInflater(ShellHooksPlugin.PLUGIN_ID, super.onGetLayoutInflater(savedInstanceState))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_hooks_panel, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val plugin = ShellHooksPlugin.getInstance() ?: return
        view.findViewById<RecyclerView>(R.id.hkList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = PanelAdapter(plugin)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(plugin.log.runs, plugin.log.pending) { runs, pending -> runs to pending }
                    .collect { (runs, pending) -> render(plugin, runs, pending) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The master switch lives on another screen; pick up a change made there.
        val plugin = ShellHooksPlugin.getInstance() ?: return
        render(plugin, plugin.log.runs.value, plugin.log.pending.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reviewDialog?.dismiss()
        reviewDialog = null
    }

    /** Hands the rows to the adapter, which works out off the main thread which ones changed. */
    private fun render(
        plugin: ShellHooksPlugin,
        runs: List<HookRun>,
        pending: List<PendingTrust>,
    ) {
        val list = view?.findViewById<RecyclerView>(R.id.hkList) ?: return
        val adapter = list.adapter as? PanelAdapter ?: return
        // The list keeps its first visible row in place, so a row added above it, such as the
        // first script awaiting review, would land out of sight even with the list at the top.
        val atTop = !list.canScrollVertically(-1)
        adapter.submitList(PanelRow.of(plugin.isOn(), runs, pending)) {
            if (atTop) list.scrollToPosition(0)
        }
    }

    /** The tab's rows, inflated with the fragment's inflater, which reads the plugin's resources. */
    private inner class PanelAdapter(
        private val plugin: ShellHooksPlugin,
    ) : ListAdapter<PanelRow, RecyclerView.ViewHolder>(PanelRowDiff) {
        override fun getItemViewType(position: Int): Int =
            when (getItem(position)) {
                PanelRow.Off -> OFF
                PanelRow.PendingTitle -> PENDING_TITLE
                is PanelRow.Pending -> PENDING
                is PanelRow.PendingMore -> PENDING_MORE
                PanelRow.RunsHeader -> RUNS_HEADER
                PanelRow.Empty -> EMPTY
                is PanelRow.Run -> RUN
            }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val row = layoutInflater.inflate(LAYOUTS.getValue(viewType), parent, false)
            when (viewType) {
                PENDING -> return PendingHolder(row)
                RUN -> return RunHolder(row)
                PENDING_TITLE -> help(row, ShellHooksHelp.TAG_PROJECT_HOOKS)
                PENDING_MORE -> help(row, ShellHooksHelp.TAG_REVIEW)
                EMPTY -> (row as TextView).setText(R.string.hk_empty)
                RUNS_HEADER -> {
                    help(row.findViewById(R.id.hkRunsTitle), ShellHooksHelp.TAG_TAB)
                    row.findViewById<Button>(R.id.hkClear).apply {
                        help(this, ShellHooksHelp.TAG_TAB)
                        setOnClickListener {
                            expanded.clear()
                            plugin.log.clearRuns()
                        }
                    }
                }
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (val row = getItem(position)) {
                is PanelRow.Pending -> (holder as PendingHolder).bind(row.entry)
                is PanelRow.Run -> (holder as RunHolder).bind(row.run)
                is PanelRow.PendingMore ->
                    (holder.itemView as TextView).text = resources.getQuantityString(R.plurals.hk_pending_more, row.count, row.count)
                else -> Unit
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            // Rebinding a recycled row sets its text while the row is off the window, and the
            // new layout then finds no window under it, which turns selection off
            // (Editor.prepareCursorControllers). Enabling it again once attached turns it back on.
            (holder as? RunHolder)?.output?.apply {
                isEnabled = false
                isEnabled = true
            }
        }

        inner class PendingHolder(
            row: View,
        ) : RecyclerView.ViewHolder(row) {
            private val name = row.findViewById<TextView>(R.id.pendingName)
            private val reviewButton = row.findViewById<Button>(R.id.pendingReview)
            private var entry: PendingTrust? = null

            init {
                help(row, ShellHooksHelp.TAG_REVIEW)
                help(reviewButton, ShellHooksHelp.TAG_REVIEW)
                reviewButton.setOnClickListener { entry?.let { review(plugin, it) } }
            }

            fun bind(entry: PendingTrust) {
                this.entry = entry
                val context = itemView.context
                // Names come from the project, so they are revealed.
                val text =
                    SpannableStringBuilder()
                        .append(RevealedNames.show(context, entry.relativePath))
                        .append(getString(R.string.hk_pending_in))
                        .append(RevealedNames.show(context, entry.projectDir.name))
                val reason = entry.notReviewable
                if (reason != null) text.append(getString(R.string.hk_pending_not_reviewable, reason))
                name.text = text
                // A script that cannot be reviewed cannot be trusted, so it has nothing to review.
                reviewButton.visibility = if (reason == null) View.VISIBLE else View.GONE
            }
        }

        inner class RunHolder(
            row: View,
        ) : RecyclerView.ViewHolder(row) {
            private val title = row.findViewById<TextView>(R.id.runTitle)
            private val detail = row.findViewById<TextView>(R.id.runDetail)
            val output: TextView = row.findViewById(R.id.runOutput)
            private var run: HookRun? = null

            init {
                help(row, ShellHooksHelp.TAG_TAB)
                row.setOnClickListener {
                    val run = run ?: return@setOnClickListener
                    if (!expanded.add(run.id)) expanded.remove(run.id)
                    showOutput(run)
                }
            }

            fun bind(run: HookRun) {
                this.run = run
                val context = itemView.context
                // Script and file names can come from a project, so they are revealed.
                val text = SpannableStringBuilder(run.name).append("  ").append(RevealedNames.show(context, scriptLabel(run)))
                run.targetLabel?.let { text.append("  ").append(RevealedNames.show(context, it)) }
                title.text = text
                detail.text =
                    getString(
                        R.string.hk_run_detail,
                        outcomeLabel(run),
                        DateFormat.getTimeFormat(context).format(Date(run.startedAtMillis)),
                        run.durationMillis,
                    )
                detail.setTextColor(
                    context.getColor(
                        when (run.outcome) {
                            Outcome.SUCCEEDED -> R.color.plugin_success
                            Outcome.TIMED_OUT, Outcome.CANCELLED -> R.color.plugin_warning
                            Outcome.FAILED, Outcome.NOT_STARTED -> R.color.plugin_error
                        },
                    ),
                )
                showOutput(run)
            }

            // Up to 64 KB per run: set only while the row is open, so binding a closed row does
            // not lay out its output.
            private fun showOutput(run: HookRun) {
                val open = run.id in expanded
                output.text = if (open) run.output.ifBlank { getString(R.string.hk_no_output) } else ""
                output.visibility = if (open) View.VISIBLE else View.GONE
            }
        }
    }

    private fun scriptLabel(run: HookRun): String {
        val name = run.script.name
        val parent = run.script.parentFile?.name.orEmpty()
        val shown = if (parent.endsWith(".d")) "$parent/$name" else name
        return if (run.source.id == "project") "$shown (project)" else shown
    }

    private fun outcomeLabel(run: HookRun): String =
        when (run.outcome) {
            Outcome.SUCCEEDED -> getString(R.string.hk_outcome_ok)
            Outcome.FAILED -> getString(R.string.hk_outcome_failed, run.exitCode ?: -1)
            Outcome.TIMED_OUT -> getString(R.string.hk_outcome_timed_out)
            Outcome.CANCELLED -> getString(R.string.hk_outcome_cancelled)
            Outcome.NOT_STARTED -> getString(R.string.hk_outcome_not_started)
        }

    /**
     * Shows the whole script before it can be trusted, and trusts exactly the bytes shown: if the
     * file changes after this, its hash no longer matches and it waits for review again. Only a
     * script [ScriptReview] finds reviewable is shown; it is read off the main thread, never more
     * than [ScriptReview.MAX_BYTES].
     */
    private fun review(
        plugin: ShellHooksPlugin,
        entry: PendingTrust,
    ) {
        if (reviewLoading) return
        reviewLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (assessed, lines, marks) =
                    withContext(Dispatchers.IO) {
                        val assessed = ScriptReview.assess(entry.file, entry.relativePath)
                        val lines = (assessed as? ScriptReview.Assessment.Reviewable)?.lines.orEmpty()
                        Triple(assessed, lines, ScriptHints.find(lines))
                    }
                when (assessed) {
                    is ScriptReview.Assessment.NotReviewable ->
                        showMessage(entry, getString(R.string.hk_review_not_reviewable, assessed.reason.message))
                    // Never the exception's message: it starts with the path, which the project chose.
                    is ScriptReview.Assessment.Unreadable ->
                        showMessage(
                            entry,
                            SpannableStringBuilder(getString(R.string.hk_review_unreadable))
                                .append(RevealedNames.show(requireContext(), entry.file.path)),
                        )
                    is ScriptReview.Assessment.Reviewable -> showScript(plugin, entry, assessed, lines, marks)
                }
            } finally {
                reviewLoading = false
            }
        }
    }

    private fun showScript(
        plugin: ShellHooksPlugin,
        entry: PendingTrust,
        script: ScriptReview.Assessment.Reviewable,
        lines: List<String>,
        marks: ScriptHints.Marks,
    ) {
        val context = requireContext()
        val padding = resources.getDimensionPixelSize(R.dimen.space_lg)
        val intro =
            listOf<CharSequence>(
                SpannableStringBuilder(getString(R.string.hk_review_intro_project))
                    .append(RevealedNames.show(context, entry.projectDir.path))
                    .append(getString(R.string.hk_review_intro)),
                // A reviewable name is letters, digits, '.', '_', '-' and '/' only: shown as it is.
                getString(R.string.hk_review_path, entry.relativePath),
            ) + checks(marks)
        // One list, so at a large font the explanation scrolls away with the script.
        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = ScriptAdapter(intro, lines, marks.lines, ::help)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(padding, padding / 2, padding, 0)
                clipToPadding = false
            }
        reviewDialog?.dismiss()
        reviewDialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(title(entry))
                .setView(list)
                .setPositiveButton(R.string.hk_trust) { _, _ -> trustShown(plugin, entry, script.bytes) }
                .setNegativeButton(R.string.hk_cancel, null)
                .setOnDismissListener(::forget)
                .show()
                .also { dialog ->
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.let { help(it, ShellHooksHelp.TAG_REVIEW) }
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.let { help(it, ShellHooksHelp.TAG_REVIEW) }
                }
    }

    /**
     * The best-effort checks above the script: one row per kind of syntax [ScriptHints] marks, with
     * every line that has it, so no kind can push another out of view. A kind marked on more than
     * [ScriptHints.MAX_LISTED] lines lists that many and counts the rest, which are marked `!` beside
     * their numbers all the same.
     */
    private fun checks(marks: ScriptHints.Marks): List<CharSequence> {
        if (marks.isEmpty) return emptyList()
        val title =
            SpannableString(getString(R.string.hk_review_checks_title)).apply {
                setSpan(ForegroundColorSpan(requireContext().getColor(R.color.plugin_warning)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        val rows =
            listOf(
                ScriptHints.Kind.HERE_DOCUMENT to R.string.hk_review_check_here_document,
                ScriptHints.Kind.CONTINUATION to R.string.hk_review_check_continuation,
                ScriptHints.Kind.COMMAND to R.string.hk_review_check_command,
            ).mapNotNull { (kind, text) ->
                val lines = marks.of(kind)
                if (lines.isEmpty()) return@mapNotNull null
                val (shown, more) = ScriptHints.listed(lines)
                val numbers =
                    shown.joinToString(", ") +
                        if (more > 0) resources.getQuantityString(R.plurals.hk_review_check_more, more, more) else ""
                "! " + getString(text, numbers)
            }
        return listOf<CharSequence>(title) + rows
    }

    /**
     * Trusts exactly the [bytes] the dialog showed. Off the main thread: the trust store resolves
     * the project's canonical path and writes its approval to disk before returning. In the
     * fragment's scope, not the view's, and not cancellable once started: the approval and taking
     * the script off the pending list both happen inside the non-cancellable block, so closing the
     * tab mid-way can neither drop an approval the user gave nor leave the script listed as waiting.
     */
    private fun trustShown(
        plugin: ShellHooksPlugin,
        entry: PendingTrust,
        bytes: ByteArray,
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                if (plugin.trust.trust(entry.projectDir, entry.relativePath, bytes)) {
                    plugin.log.removePending(entry.projectDir, entry.relativePath)
                }
            }
        }
    }

    private fun showMessage(
        entry: PendingTrust,
        message: CharSequence,
    ) {
        reviewDialog?.dismiss()
        reviewDialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title(entry))
                .setMessage(message)
                .setNegativeButton(R.string.hk_cancel, null)
                .setOnDismissListener(::forget)
                .show()
    }

    // The path is the project's to choose; one that cannot be reviewed may hold anything.
    private fun title(entry: PendingTrust): String = getString(R.string.hk_review_title, ScriptReview.reveal(entry.relativePath).text)

    // A dialog dismissed by showing the next one reports it after the next is already showing;
    // only the current one is forgotten, so onDestroyView can still dismiss that.
    private fun forget(dialog: DialogInterface) {
        if (reviewDialog === dialog) reviewDialog = null
    }

    private fun help(
        view: View,
        tag: String,
    ) {
        view.setOnLongClickListener { anchor ->
            val service = tooltips ?: return@setOnLongClickListener false
            service.showTooltip(anchor, ShellHooksPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    private companion object {
        const val OFF = 0
        const val PENDING_TITLE = 1
        const val PENDING = 2
        const val PENDING_MORE = 3
        const val RUNS_HEADER = 4
        const val EMPTY = 5
        const val RUN = 6

        val LAYOUTS =
            mapOf(
                OFF to R.layout.item_hooks_off,
                PENDING_TITLE to R.layout.item_pending_title,
                PENDING to R.layout.item_pending_trust,
                PENDING_MORE to R.layout.item_panel_note,
                RUNS_HEADER to R.layout.item_runs_header,
                EMPTY to R.layout.item_panel_note,
                RUN to R.layout.item_hook_run,
            )
    }
}

/**
 * The review dialog's content: the explanation and checks, then the script one row per line. Each
 * line has a [LineGutter] in front: its number, `!` before it when a check names the line, and a
 * continuation mark on every row the text wraps onto.
 */
private class ScriptAdapter(
    private val intro: List<CharSequence>,
    private val lines: List<String>,
    private val marked: Set<Int>,
    private val help: (View, String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val digits = lines.size.toString().length

    private class LineHolder(
        view: View,
        val gutter: LineGutter,
        val text: TextView,
    ) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = intro.size + lines.size

    override fun getItemViewType(position: Int): Int = if (position < intro.size) INTRO else LINE

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        if (viewType == INTRO) {
            val view =
                TextView(parent.context).apply {
                    val gap = resources.getDimensionPixelSize(R.dimen.space_sm)
                    setPadding(0, 0, 0, gap)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    help(this, ShellHooksHelp.TAG_REVIEW)
                }
            return object : RecyclerView.ViewHolder(view) {}
        }
        val row = LayoutInflater.from(parent.context).inflate(R.layout.item_script_line, parent, false) as LinearLayout
        val text = row.findViewById<TextView>(R.id.lineText)
        val gutter = LineGutter(parent.context, text, digits)
        row.addView(gutter, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return LineHolder(row, gutter, text)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        if (holder !is LineHolder) {
            (holder.itemView as TextView).text = intro[position]
            return
        }
        val number = position - intro.size + 1
        val context = holder.itemView.context
        val isMarked = number in marked
        holder.text.text = lines[number - 1]
        holder.gutter.bind(
            number = number,
            marked = isMarked,
            color = context.getColor(if (isMarked) R.color.plugin_warning else R.color.plugin_text_muted),
            description = context.getString(if (isMarked) R.string.hk_review_line_marked else R.string.hk_review_line, number),
        )
    }

    private companion object {
        const val INTRO = 0
        const val LINE = 1
    }
}
