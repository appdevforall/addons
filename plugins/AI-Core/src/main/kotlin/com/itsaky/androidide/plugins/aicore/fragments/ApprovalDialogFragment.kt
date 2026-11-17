package com.itsaky.androidide.plugins.aicore.fragments

import android.app.Dialog
import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview
import com.itsaky.androidide.plugins.aicore.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aicore.tool.ApprovalResult
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService

/**
 * Dialog for approving tool execution; for an edit, a before/after block with **Accept / Correct /
 * Decline**. "Always Allow" shows only when the request allows a session grant. Decisions go
 * to [Host], resolved from [getParentFragment] each time, since a captured callback dies on recreation.
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
        private const val ARG_PREVIEW = "preview"
        private const val ARG_ALLOWS_ALWAYS_ALLOW = "allows_always_allow"
        private const val ARG_READ_ONLY_ON_PROVIDERS_WORD = "read_only_on_providers_word"

        /**
         * Builds the dialog. Everything it needs is in [getArguments], so the framework can
         * recreate it after a configuration change without losing the decision channel.
         * @param request the pending approval to render.
         * @param resources words the labels inside the formatted arguments.
         */
        fun newInstance(request: ApprovalRequest, resources: Resources): ApprovalDialogFragment {
            val preview = request.preview
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
                    putString(ARG_PREVIEW, preview.name)
                    putString(ARG_ARGS, ApprovalPresentation.format(preview, request.args, resources))
                    putBoolean(ARG_ALLOWS_ALWAYS_ALLOW, request.allowsSessionApproval)
                    putBoolean(ARG_READ_ONLY_ON_PROVIDERS_WORD, request.readOnlyOnProvidersWord)
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
        val preview = ApprovalPresentation.named(arguments?.getString(ARG_PREVIEW))
        val isEdit = preview == ApprovalPreview.EDIT
        val allowsAlwaysAllow = arguments?.getBoolean(ARG_ALLOWS_ALWAYS_ALLOW) ?: false
        // An edit never offers it, whatever the request allows; see the buttons below.
        val offersAlwaysAllow = allowsAlwaysAllow && !isEdit
        val readOnlyOnProvidersWord = arguments?.getBoolean(ARG_READ_ONLY_ON_PROVIDERS_WORD) ?: false

        // Provenance before the description: a tool that leaves the device is a different
        // decision from a local edit, and only the source says which this is.
        val provenance = buildList {
            if (!source.isNullOrBlank()) {
                add(getString(R.string.approval_source, source))
                // Both names, when they differ: the title says what runs, this says what the
                // source calls it, so neither can be passed off as the other.
                if (!providerName.isNullOrBlank()) {
                    add(getString(R.string.approval_provider_name, providerName))
                }
            }
            // "Always Allow" rests on the plugin's own word here, so the user is told whose it is.
            if (offersAlwaysAllow && readOnlyOnProvidersWord) {
                add(getString(R.string.approval_read_only_claim))
            }
        }

        val message = buildString {
            append(getString(R.string.approval_header))
            append("\n\n")
            if (provenance.isNotEmpty()) {
                provenance.forEach { append(it).append("\n") }
                append("\n")
            }
            append(description)
            append("\n\n")
            append(getString(ApprovalPresentation.argsLabel(preview)))
            append("\n")
            append(argsText)
        }

        // Not the stock AlertDialog title/message/buttons: with arguments and a large font its
        // button bar was pushed off the window. Title and text scroll inside a bounded area; the
        // buttons sit below it and stack when they do not fit in a row (ApprovalDialogLayout).
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val titleView = TextView(context).apply {
            text = getString(R.string.approval_confirm_title, toolName)
            applyTextAppearance(this, com.google.android.material.R.attr.textAppearanceHeadlineSmall)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val messageView = TextView(context).apply {
            text = message
            applyTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(8))
            addView(titleView)
            addView(
                messageView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }
        val scroll = BoundedScrollView(context).apply { addView(textColumn) }

        fun button(label: String, onClick: () -> Unit) =
            MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = label
                setOnClickListener { onClick() }
            }

        val decline = button(getString(R.string.approval_decline)) {
            decide(ApprovalResult.DENIED)
            dismiss()
        }
        val positive: MaterialButton
        var neutral: MaterialButton? = null
        if (isEdit) {
            // No "Always Allow": keyed by tool name, it would cover every future file.
            positive = button(getString(R.string.approval_accept)) {
                decide(ApprovalResult.APPROVED_ONCE)
                dismiss()
            }
            // "Correct" must not auto-dismiss.
            neutral = button(getString(R.string.approval_correct)) { showCorrectionPrompt() }
        } else {
            positive = button(getString(R.string.approval_run_now)) {
                decide(ApprovalResult.APPROVED_ONCE)
                dismiss()
            }
            if (offersAlwaysAllow) {
                neutral = button(getString(R.string.approval_always_allow)) {
                    decide(ApprovalResult.APPROVED_FOR_SESSION)
                    dismiss()
                }
            }
        }

        val bar = ApprovalButtonBar(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            // Side by side: neutral at the start, then decline, then the primary. Stacked, the bar reverses this.
            setButtons(listOfNotNull(neutral, decline, positive), leading = if (neutral != null) 1 else 0)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(ApprovalDialogLayout(context, scroll, bar))
            .setOnCancelListener {
                decide(ApprovalResult.DENIED)
            }
            .create()

        // Long-press help on the consent gate: which button actually writes to the project.
        wireTooltip(
            positive,
            when (preview) {
                ApprovalPreview.EDIT -> AiCorePlugin.TOOLTIP_TAG_APPROVAL_ACCEPT
                ApprovalPreview.SHELL_COMMAND -> AiCorePlugin.TOOLTIP_TAG_APPROVAL_RUN_COMMAND
                ApprovalPreview.STOP_COMMAND, ApprovalPreview.ARGS -> AiCorePlugin.TOOLTIP_TAG_APPROVAL_RUN_NOW
            },
        )
        wireTooltip(
            neutral,
            if (isEdit) AiCorePlugin.TOOLTIP_TAG_APPROVAL_CORRECT
            else AiCorePlugin.TOOLTIP_TAG_APPROVAL_ALWAYS_ALLOW,
        )
        wireTooltip(decline, AiCorePlugin.TOOLTIP_TAG_APPROVAL_DECLINE)

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

    private fun applyTextAppearance(view: TextView, attr: Int) {
        val tv = TypedValue()
        if (view.context.theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
            TextViewCompat.setTextAppearance(view, tv.resourceId)
        }
    }

    private fun themeColor(attr: Int): Int =
        MaterialColors.getColor(requireContext(), attr, Color.GRAY)

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
