package org.appdevforall.shellhooks.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.shellhooks.R
import org.appdevforall.shellhooks.ShellHooksHelp
import org.appdevforall.shellhooks.ShellHooksPlugin
import org.appdevforall.shellhooks.engine.HookRun
import org.appdevforall.shellhooks.engine.Outcome
import org.appdevforall.shellhooks.engine.PendingTrust
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
        help(view.findViewById(R.id.hkRunsTitle), ShellHooksHelp.TAG_TAB)
        help(view.findViewById(R.id.hkPendingTitle), ShellHooksHelp.TAG_PROJECT_HOOKS)
        view.findViewById<Button>(R.id.hkClear).apply {
            help(this, ShellHooksHelp.TAG_TAB)
            setOnClickListener {
                expanded.clear()
                plugin.log.clearRuns()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(plugin.log.runs, plugin.log.pending) { runs, pending -> runs to pending }
                    .collect { (runs, pending) -> render(view, plugin, runs, pending) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The master switch lives on another screen; pick up a change made there.
        view?.findViewById<View>(R.id.hkOff)?.visibility = if (ShellHooksPlugin.getInstance()?.isOn() == true) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reviewDialog?.dismiss()
        reviewDialog = null
    }

    private fun render(
        root: View,
        plugin: ShellHooksPlugin,
        runs: List<HookRun>,
        pending: List<PendingTrust>,
    ) {
        root.findViewById<View>(R.id.hkOff).visibility = if (plugin.isOn()) View.GONE else View.VISIBLE

        val pendingList = root.findViewById<LinearLayout>(R.id.hkPendingList)
        pendingList.removeAllViews()
        root.findViewById<View>(R.id.hkPendingTitle).visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        for (entry in pending) {
            val row = layoutInflater.inflate(R.layout.item_pending_trust, pendingList, false)
            // Names come from the project, so they are revealed.
            val name =
                SpannableStringBuilder()
                    .append(RevealedNames.show(row.context, entry.relativePath))
                    .append(getString(R.string.hk_pending_in))
                    .append(RevealedNames.show(row.context, entry.projectDir.name))
            val reason = entry.notReviewable
            if (reason != null) name.append(getString(R.string.hk_pending_not_reviewable, reason))
            row.findViewById<TextView>(R.id.pendingName).text = name
            row.findViewById<Button>(R.id.pendingReview).apply {
                // A script that cannot be reviewed cannot be trusted, so it has nothing to review.
                visibility = if (reason == null) View.VISIBLE else View.GONE
                help(this, ShellHooksHelp.TAG_REVIEW)
                setOnClickListener { review(plugin, entry) }
            }
            help(row, ShellHooksHelp.TAG_REVIEW)
            pendingList.addView(row)
        }

        val runList = root.findViewById<LinearLayout>(R.id.hkRunList)
        runList.removeAllViews()
        root.findViewById<View>(R.id.hkEmpty).visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
        for (run in runs) runList.addView(runRow(runList, run))
    }

    private fun runRow(
        parent: ViewGroup,
        run: HookRun,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_hook_run, parent, false)
        // Script and file names can come from a project, so they are revealed.
        val title = SpannableStringBuilder(run.name).append("  ").append(RevealedNames.show(row.context, scriptLabel(run)))
        run.targetLabel?.let { title.append("  ").append(RevealedNames.show(row.context, it)) }
        row.findViewById<TextView>(R.id.runTitle).text = title

        val detail = row.findViewById<TextView>(R.id.runDetail)
        detail.text =
            getString(
                R.string.hk_run_detail,
                outcomeLabel(run),
                DateFormat.getTimeFormat(requireContext()).format(Date(run.startedAtMillis)),
                run.durationMillis,
            )
        detail.setTextColor(
            row.context.getColor(
                when (run.outcome) {
                    Outcome.SUCCEEDED -> R.color.plugin_success
                    Outcome.TIMED_OUT, Outcome.CANCELLED -> R.color.plugin_warning
                    Outcome.FAILED, Outcome.NOT_STARTED -> R.color.plugin_error
                },
            ),
        )

        // Up to 64 KB per run: set only while the row is open, so a refresh does not lay out
        // the output of every run in the list.
        val output = row.findViewById<TextView>(R.id.runOutput)
        val show = { open: Boolean ->
            output.text = if (open) run.output.ifBlank { getString(R.string.hk_no_output) } else ""
            output.visibility = if (open) View.VISIBLE else View.GONE
        }
        show(run.id in expanded)
        row.setOnClickListener {
            if (!expanded.add(run.id)) expanded.remove(run.id)
            show(run.id in expanded)
        }
        help(row, ShellHooksHelp.TAG_TAB)
        return row
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
                val (assessed, lines) =
                    withContext(Dispatchers.IO) {
                        val assessed = ScriptReview.assess(entry.file, entry.relativePath)
                        assessed to (assessed as? ScriptReview.Assessment.Reviewable)?.lines
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
                    is ScriptReview.Assessment.Reviewable -> showScript(plugin, entry, assessed, lines.orEmpty())
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
            )
        // One list, so at a large font the explanation scrolls away with the script.
        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = ScriptAdapter(intro, lines, ::help)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(padding, padding / 2, padding, 0)
                clipToPadding = false
            }
        reviewDialog?.dismiss()
        reviewDialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(title(entry))
                .setView(list)
                .setPositiveButton(R.string.hk_trust) { _, _ ->
                    if (plugin.trust.trust(entry.projectDir, entry.relativePath, script.bytes)) {
                        plugin.log.removePending(entry.projectDir, entry.relativePath)
                    }
                }.setNegativeButton(R.string.hk_cancel, null)
                .setOnDismissListener(::forget)
                .show()
                .also { dialog ->
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.let { help(it, ShellHooksHelp.TAG_REVIEW) }
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.let { help(it, ShellHooksHelp.TAG_REVIEW) }
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
}

/**
 * The review dialog's content: the explanation, then the script one numbered row per line. Line
 * numbers are padded to one width in a monospace font, so the text column starts in the same place
 * on every row and wrapped text stays clear of the number column.
 */
private class ScriptAdapter(
    private val intro: List<CharSequence>,
    private val lines: List<String>,
    private val help: (View, String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val numberWidth = lines.size.toString().length

    override fun getItemCount(): Int = intro.size + lines.size

    override fun getItemViewType(position: Int): Int = if (position < intro.size) INTRO else LINE

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val view =
            if (viewType == INTRO) {
                TextView(parent.context).apply {
                    val gap = resources.getDimensionPixelSize(R.dimen.space_sm)
                    setPadding(0, 0, 0, gap)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    help(this, ShellHooksHelp.TAG_REVIEW)
                }
            } else {
                LayoutInflater.from(parent.context).inflate(R.layout.item_script_line, parent, false)
            }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        if (position < intro.size) {
            (holder.itemView as TextView).text = intro[position]
            return
        }
        val index = position - intro.size
        holder.itemView.findViewById<TextView>(R.id.lineNumber).text = (index + 1).toString().padStart(numberWidth)
        holder.itemView.findViewById<TextView>(R.id.lineText).text = lines[index]
    }

    private companion object {
        const val INTRO = 0
        const val LINE = 1
    }
}

