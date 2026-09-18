package com.itsaky.androidide.plugins.aicore.fragments

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aicore.tool.ApprovalResult
import com.itsaky.androidide.plugins.aicore.tool.handlers.EditFileHandler
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService

/**
 * Dialog for approving tool execution; for an edit, a real review step — a before/after block with
 * **Accept / Correct / Decline** and no blanket "Always Allow". Decisions go to [Host], resolved
 * from [getParentFragment] each time, since a captured callback dies on recreation.
 */
class ApprovalDialogFragment : DialogFragment() {

    /**
     * Receives this dialog's outcome. Implemented by the fragment that shows the dialog, which
     * must be its **parent** fragment (show it with `childFragmentManager`).
     */
    interface Host {
        /**
         * @param result the user's choice.
         * @param correction the revision instruction for [ApprovalResult.CORRECTED], else null.
         */
        fun onApprovalDecision(result: ApprovalResult, correction: String?)
    }

    /** The correction prompt, tracked so it can never outlive this dialog. */
    private var correctionDialog: AlertDialog? = null

    private val tooltipService: IdeTooltipService? by lazy {
        try {
            PluginFragmentHelper.getServiceRegistry(AiCorePlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            AiCorePlugin.getContext()?.logger
                ?.warn("ApprovalDialogFragment: tooltip service unavailable", e)
            null
        }
    }

    companion object {
        private const val ARG_TOOL_NAME = "tool_name"
        private const val ARG_PROVIDER_NAME = "provider_name"
        private const val ARG_SOURCE = "source"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_ARGS = "args"
        private const val ARG_IS_EDIT = "is_edit"

        /**
         * Builds the dialog. Everything it needs is in [getArguments], so the framework can
         * recreate it after a configuration change without losing the decision channel.
         * @param request the pending approval to render.
         */
        fun newInstance(request: ApprovalRequest): ApprovalDialogFragment {
            val isEdit = request.toolName == EditFileHandler.TOOL_NAME
            return ApprovalDialogFragment().apply {
                arguments = Bundle().apply {
                    // The registered name, not the provider's: it is the tool that will actually
                    // run, and the one thing on the dialog a remote source cannot choose.
                    putString(ARG_TOOL_NAME, request.toolName)
                    putString(
                        ARG_PROVIDER_NAME,
                        request.displayName.takeIf { it != request.toolName },
                    )
                    putString(ARG_SOURCE, request.sourceLabel)
                    putString(ARG_DESCRIPTION, request.description)
                    putBoolean(ARG_IS_EDIT, isEdit)
                    putString(
                        ARG_ARGS,
                        if (isEdit) ApprovalTextFormatter.formatEdit(request.args)
                        else ApprovalTextFormatter.formatArgs(request.args)
                    )
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val toolName = arguments?.getString(ARG_TOOL_NAME) ?: "unknown"
        val providerName = arguments?.getString(ARG_PROVIDER_NAME)
        val source = arguments?.getString(ARG_SOURCE)
        val description = arguments?.getString(ARG_DESCRIPTION) ?: ""
        val argsText = arguments?.getString(ARG_ARGS) ?: "{}"
        val isEdit = arguments?.getBoolean(ARG_IS_EDIT) == true

        val message = buildString {
            append(getString(R.string.approval_header))
            append("\n\n")
            // Provenance before the description: a tool that leaves the device is a different
            // decision from a local edit, and only the source says which this is.
            if (!source.isNullOrBlank()) {
                append(getString(R.string.approval_source, source))
                append("\n")
                // Both names, when they differ: the title says what runs, this says what the
                // source calls it, so neither can be passed off as the other.
                if (!providerName.isNullOrBlank()) {
                    append(getString(R.string.approval_provider_name, providerName))
                    append("\n")
                }
                append("\n")
            }
            append(description)
            append("\n\n")
            append(getString(if (isEdit) R.string.approval_proposed_change else R.string.approval_args))
            append("\n")
            append(argsText)
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.approval_confirm_title, toolName))
            .setMessage(message)
            .setNegativeButton(getString(R.string.approval_decline)) { _, _ ->
                decide(ApprovalResult.DENIED)
                dismiss()
            }
            .setOnCancelListener {
                decide(ApprovalResult.DENIED)
            }

        if (isEdit) {
            // No "Always Allow": keyed by tool name, it would cover every future file.
            builder.setPositiveButton(getString(R.string.approval_accept)) { _, _ ->
                decide(ApprovalResult.APPROVED_ONCE)
                dismiss()
            }
            builder.setNeutralButton(getString(R.string.approval_correct), null)
        } else {
            builder.setPositiveButton(getString(R.string.approval_run_now)) { _, _ ->
                decide(ApprovalResult.APPROVED_ONCE)
                dismiss()
            }
            builder.setNeutralButton(getString(R.string.approval_always_allow)) { _, _ ->
                decide(ApprovalResult.APPROVED_FOR_SESSION)
                dismiss()
            }
        }

        val dialog = builder.create()

        // Bound after show(): buttons don't exist before it, and "Correct" must not auto-dismiss.
        dialog.setOnShowListener {
            if (isEdit) {
                dialog.getButton(Dialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    showCorrectionPrompt()
                }
            }
            // Long-press help on the consent gate: which button actually writes to the project.
            wireTooltip(
                dialog.getButton(Dialog.BUTTON_POSITIVE),
                if (isEdit) AiCorePlugin.TOOLTIP_TAG_APPROVAL_ACCEPT
                else AiCorePlugin.TOOLTIP_TAG_APPROVAL_RUN_NOW,
            )
            wireTooltip(
                dialog.getButton(Dialog.BUTTON_NEUTRAL),
                if (isEdit) AiCorePlugin.TOOLTIP_TAG_APPROVAL_CORRECT
                else AiCorePlugin.TOOLTIP_TAG_APPROVAL_ALWAYS_ALLOW,
            )
            wireTooltip(
                dialog.getButton(Dialog.BUTTON_NEGATIVE),
                AiCorePlugin.TOOLTIP_TAG_APPROVAL_DECLINE,
            )
        }

        // Prevent accidental dismissal
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    /**
     * Delivers the outcome to the host fragment.
     *
     * Resolved on each call rather than captured at construction, so it still works on the
     * instance the framework recreated after a rotation.
     * @param result the user's choice.
     * @param correction the revision instruction, for [ApprovalResult.CORRECTED] only.
     */
    private fun decide(result: ApprovalResult, correction: String? = null) {
        (parentFragment as? Host)?.onApprovalDecision(result, correction)
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide). */
    private fun wireTooltip(view: View?, tag: String) {
        view?.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, AiCorePlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    /**
     * Collects the revision instruction as an [ApprovalResult.CORRECTED] decision; cancelling leaves
     * the approval dialog untouched. Held in [correctionDialog] and torn down in [onDestroyView],
     * since a separate window would otherwise leak on rotation and outlive a stopped run.
     */
    private fun showCorrectionPrompt() {
        val context = context ?: return
        val input = EditText(context).apply {
            hint = getString(R.string.approval_correction_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            maxLines = 5
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        correctionDialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.approval_correction_title))
            .setView(container)
            .setPositiveButton(getString(R.string.approval_correction_send)) { _, _ ->
                decide(ApprovalResult.CORRECTED, input.text?.toString()?.trim().orEmpty())
                dismiss()
            }
            .setNegativeButton(getString(R.string.approval_correction_cancel), null)
            .setOnDismissListener { correctionDialog = null }
            .create()
            .apply {
                setOnShowListener {
                    // On Send, not the EditText, where it would eat the paste menu on long-press.
                    wireTooltip(
                        getButton(Dialog.BUTTON_POSITIVE),
                        AiCorePlugin.TOOLTIP_TAG_APPROVAL_CORRECTION_INPUT,
                    )
                }
                show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Takes the correction prompt down with this dialog; see showCorrectionPrompt().
        correctionDialog?.dismiss()
        correctionDialog = null
    }
}
