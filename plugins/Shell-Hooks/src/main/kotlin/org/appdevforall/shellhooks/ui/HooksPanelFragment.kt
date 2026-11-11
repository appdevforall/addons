package org.appdevforall.shellhooks.ui

import android.os.Bundle
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.appdevforall.shellhooks.R
import org.appdevforall.shellhooks.ShellHooksHelp
import org.appdevforall.shellhooks.ShellHooksPlugin
import org.appdevforall.shellhooks.engine.HookRun
import org.appdevforall.shellhooks.engine.Outcome
import org.appdevforall.shellhooks.engine.PendingTrust
import org.appdevforall.shellhooks.engine.TrustStore
import java.io.File
import java.util.Date

/**
 * The Hooks bottom-sheet tab: project scripts awaiting review, then the recent runs. A run's
 * output is hidden until its row is tapped.
 */
class HooksPanelFragment : Fragment() {
    private var tooltips: IdeTooltipService? = null
    private var reviewDialog: AlertDialog? = null

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
            row.findViewById<TextView>(R.id.pendingName).text =
                getString(R.string.hk_pending_item, entry.relativePath, entry.projectDir.name)
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
        val key = run.startedAtMillis * 31 + run.script.path.hashCode()
        val target = run.target?.let { "  ${File(it).name.ifEmpty { it }}" }.orEmpty()
        row.findViewById<TextView>(R.id.runTitle).text = "${run.hook.id}  ${scriptLabel(run)}$target"

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

        val output = row.findViewById<TextView>(R.id.runOutput)
        output.text = run.output.ifBlank { getString(R.string.hk_no_output) }
        output.visibility = if (key in expanded) View.VISIBLE else View.GONE
        row.setOnClickListener {
            if (!expanded.add(key)) expanded.remove(key)
            output.visibility = if (key in expanded) View.VISIBLE else View.GONE
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
     * file changes after this, its hash no longer matches and it waits for review again.
     */
    private fun review(
        plugin: ShellHooksPlugin,
        entry: PendingTrust,
    ) {
        val context = requireContext()
        val bytes =
            try {
                entry.file.readBytes()
            } catch (e: Exception) {
                showMessage(entry, getString(R.string.hk_review_unreadable, e.message ?: e.javaClass.simpleName))
                return
            }
        if (bytes.size > MAX_REVIEW_BYTES) {
            showMessage(entry, getString(R.string.hk_review_too_large, MAX_REVIEW_BYTES / 1024))
            return
        }
        val sha = TrustStore.sha256(bytes)

        val padding = resources.getDimensionPixelSize(R.dimen.space_lg)
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(TextView(context).apply { text = getString(R.string.hk_review_intro, entry.projectDir.path) })
                addView(
                    TextView(context).apply {
                        text = String(bytes, Charsets.UTF_8)
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        setPadding(0, padding / 2, 0, 0)
                    },
                )
            }
        reviewDialog?.dismiss()
        reviewDialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.hk_review_title, entry.relativePath))
                .setView(ScrollView(context).apply { addView(body) })
                .setPositiveButton(R.string.hk_trust) { _, _ ->
                    plugin.trust.trust(entry.projectDir, entry.relativePath, sha)
                    plugin.log.removePending(entry.projectDir, entry.relativePath)
                }.setNegativeButton(R.string.hk_cancel, null)
                .setOnDismissListener { reviewDialog = null }
                .show()
    }

    private fun showMessage(
        entry: PendingTrust,
        message: String,
    ) {
        reviewDialog?.dismiss()
        reviewDialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.hk_review_title, entry.relativePath))
                .setMessage(message)
                .setNegativeButton(R.string.hk_cancel, null)
                .setOnDismissListener { reviewDialog = null }
                .show()
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

    companion object {
        private const val MAX_REVIEW_BYTES = 256 * 1024
    }
}
