package org.appdevforall.shellhooks.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
            // Names come from the project too, so they are revealed like the script.
            row.findViewById<TextView>(R.id.pendingName).text =
                getString(R.string.hk_pending_item, ScriptReview.reveal(entry.relativePath).text, ScriptReview.reveal(entry.projectDir.name).text)
            row.findViewById<Button>(R.id.pendingReview).apply {
                help(this, ShellHooksHelp.TAG_REVIEW)
                setOnClickListener { review(plugin, entry) }
            }
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
        // Script and file names can come from a project, so they are revealed like a script.
        val target = run.targetLabel?.let { "  ${ScriptReview.reveal(it).text}" }.orEmpty()
        row.findViewById<TextView>(R.id.runTitle).text = "${run.name}  ${ScriptReview.reveal(scriptLabel(run)).text}$target"

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
     * file changes after this, its hash no longer matches and it waits for review again. Read,
     * decoded and revealed off the main thread, and never more than [ScriptReview.MAX_BYTES].
     */
    private fun review(
        plugin: ShellHooksPlugin,
        entry: PendingTrust,
    ) {
        if (entry.tooLarge) {
            showMessage(entry, getString(R.string.hk_review_too_large, ScriptReview.MAX_BYTES / 1024))
            return
        }
        if (reviewLoading) return
        reviewLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (loaded, revealed) =
                    withContext(Dispatchers.IO) {
                        val loaded = ScriptReview.load(entry.file)
                        loaded to (loaded as? ScriptReview.Loaded.Script)?.let { ScriptReview.revealBytes(it.bytes) }
                    }
                when (loaded) {
                    is ScriptReview.Loaded.TooLarge ->
                        showMessage(entry, getString(R.string.hk_review_too_large, ScriptReview.MAX_BYTES / 1024))
                    is ScriptReview.Loaded.Unreadable ->
                        showMessage(entry, getString(R.string.hk_review_unreadable, loaded.reason))
                    is ScriptReview.Loaded.Script -> showScript(plugin, entry, loaded, revealed ?: ScriptReview.revealBytes(loaded.bytes))
                }
            } finally {
                reviewLoading = false
            }
        }
    }

    private fun showScript(
        plugin: ShellHooksPlugin,
        entry: PendingTrust,
        loaded: ScriptReview.Loaded.Script,
        revealed: ScriptReview.Revealed,
    ) {
        val context = requireContext()
        val padding = resources.getDimensionPixelSize(R.dimen.space_lg)
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(
                    TextView(context).apply {
                        text = getString(R.string.hk_review_intro, ScriptReview.reveal(entry.projectDir.path).text)
                        help(this, ShellHooksHelp.TAG_REVIEW)
                    },
                )
                // In full and wrapping here: the title may cut a long path off.
                addView(
                    TextView(context).apply {
                        text = getString(R.string.hk_review_path, ScriptReview.reveal(entry.relativePath).text)
                        leftToRight()
                        setPadding(0, padding / 2, 0, 0)
                    },
                )
                if (revealed.hidden > 0) {
                    addView(
                        TextView(context).apply {
                            text = resources.getQuantityString(R.plurals.hk_review_hidden, revealed.hidden, revealed.hidden)
                            setTextColor(context.getColor(R.color.plugin_error))
                            setPadding(0, padding / 2, 0, 0)
                            help(this, ShellHooksHelp.TAG_REVIEW)
                        },
                    )
                }
                addView(
                    TextView(context).apply {
                        text = highlighted(revealed)
                        typeface = android.graphics.Typeface.MONOSPACE
                        leftToRight()
                        setTextIsSelectable(true)
                        setPadding(0, padding / 2, 0, 0)
                    },
                )
            }
        reviewDialog?.dismiss()
        reviewDialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(title(entry))
                .setView(ScrollView(context).apply { addView(body) })
                .setPositiveButton(R.string.hk_trust) { _, _ ->
                    plugin.trust.trust(entry.projectDir, entry.relativePath, loaded.sha256)
                    plugin.log.removePending(entry.projectDir, entry.relativePath)
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
        message: String,
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

    // A script reads left to right whatever the device's language: in a right-to-left layout,
    // lines without letters would be aligned right and their punctuation reordered.
    private fun TextView.leftToRight() {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    // Marks each <U+XXXX> the reveal added, so it cannot be mistaken for the same six characters
    // typed in the script.
    private fun highlighted(revealed: ScriptReview.Revealed): CharSequence {
        if (revealed.escapes.isEmpty()) return revealed.text
        val color = requireContext().getColor(R.color.reveal_escape_background)
        return SpannableString(revealed.text).apply {
            for (range in revealed.escapes) {
                setSpan(BackgroundColorSpan(color), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // The path is the project's to choose, so it is revealed like the script.
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
