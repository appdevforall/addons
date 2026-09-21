package com.itsaky.androidide.plugins.aiagentlocal.settings

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aiagentlocal.plugin.LocalLlmPlugin
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelMemoryGate
import com.itsaky.androidide.plugins.aiagentlocal.format.ByteSize
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService

/**
 * Warns that a selected model may be too large for this device, offering **Cancel** — the default
 * in every sense visible to the user — or **Proceed anyway** (ADFA-1798). Decisions go to [Host],
 * resolved per call so they still arrive after the framework recreates this on a rotation.
 */
class MemoryWarningDialogFragment : DialogFragment() {

    private val tooltipService: IdeTooltipService? by lazy {
        try {
            PluginFragmentHelper.getServiceRegistry(LocalLlmPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            LocalLlmPlugin.getContext()?.logger
                ?.warn("MemoryWarningDialogFragment: tooltip service unavailable", e)
            null
        }
    }

    /**
     * Receives this dialog's outcome. Implemented by the fragment that shows the dialog, which must
     * be its **parent** fragment (show it with `childFragmentManager`).
     */
    interface Host {
        /** @param proceed true to load the model anyway, false to abandon the selection. */
        fun onModelMemoryDecision(proceed: Boolean)
    }

    companion object {
        /** FragmentManager tag, not a log tag, so the `LOG_PREFIX` convention does not apply. */
        const val FRAGMENT_TAG = "ModelMemoryWarning"

        private const val ARG_MODEL_NAME = "model_name"
        private const val ARG_LOAD_BYTES = "load_bytes"
        private const val ARG_RUN_BYTES = "run_bytes"
        private const val ARG_AVAILABLE_BYTES = "available_bytes"
        private const val ARG_SEVERITY = "severity"

        /**
         * Builds the dialog. Everything it needs is in [getArguments], so the framework can recreate
         * it after a configuration change while the answer channel stays on the ViewModel.
         *
         * @param warning the model and the figures to show
         */
        fun newInstance(warning: ModelMemoryWarning): MemoryWarningDialogFragment =
            MemoryWarningDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, warning.modelName)
                    putLong(ARG_LOAD_BYTES, warning.loadBytes)
                    putLong(ARG_RUN_BYTES, warning.runBytes)
                    putLong(ARG_AVAILABLE_BYTES, warning.availableBytes)
                    putString(ARG_SEVERITY, warning.severity.name)
                }
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = arguments ?: Bundle.EMPTY
        val messageId = when (severity()) {
            ModelMemoryGate.Severity.INSUFFICIENT -> R.string.llm_memory_warning_insufficient
            ModelMemoryGate.Severity.TIGHT -> R.string.llm_memory_warning_tight
        }
        val message = getString(
            messageId,
            args.getString(ARG_MODEL_NAME).orEmpty(),
            ByteSize.format(args.getLong(ARG_LOAD_BYTES)),
            ByteSize.format(args.getLong(ARG_RUN_BYTES)),
            ByteSize.format(args.getLong(ARG_AVAILABLE_BYTES)),
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.llm_memory_warning_title))
            .setMessage(message)
            // Cancel takes the positive slot: Material emphasizes that button.
            .setPositiveButton(getString(R.string.llm_memory_warning_cancel)) { _, _ -> decide(false) }
            .setNegativeButton(getString(R.string.llm_memory_warning_proceed)) { _, _ -> decide(true) }
            .create()

        // Bound after show(): the buttons don't exist before it.
        dialog.setOnShowListener {
            val cancelButton = dialog.getButton(Dialog.BUTTON_POSITIVE)
            wireTooltip(cancelButton, LocalLlmPlugin.TOOLTIP_TAG_MEMORY_CANCEL)
            wireTooltip(
                dialog.getButton(Dialog.BUTTON_NEGATIVE),
                LocalLlmPlugin.TOOLTIP_TAG_MEMORY_PROCEED,
            )
            // So Enter, D-pad and a screen reader all reach the safe choice first.
            cancelButton?.requestFocus()
        }

        return dialog
    }

    /**
     * Back press and outside tap both mean "don't risk it". Overridden rather than set on the
     * builder: [DialogFragment] replaces a builder cancel listener in `prepareDialog`, so the
     * awaiting caller would never learn the model was declined.
     *
     * @param dialog the dialog that was cancelled
     */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        decide(false)
    }

    /** Falls back to the milder wording, so a bad argument can't overstate the risk. */
    private fun severity(): ModelMemoryGate.Severity = try {
        ModelMemoryGate.Severity.valueOf(
            arguments?.getString(ARG_SEVERITY) ?: ModelMemoryGate.Severity.TIGHT.name
        )
    } catch (e: IllegalArgumentException) {
        ModelMemoryGate.Severity.TIGHT
    }

    /**
     * Delivers the outcome to the host fragment. Resolved on each call rather than captured at
     * construction, so it still works on the instance the framework recreated after a rotation.
     */
    private fun decide(proceed: Boolean) {
        val host = parentFragment as? Host
        if (host == null) {
            // Nothing else reports this, and the load that raised the warning waits forever on it.
            LocalLlmPlugin.getContext()?.logger
                ?.warn("MemoryWarningDialogFragment: no Host parent; decision dropped")
            return
        }
        host.onModelMemoryDecision(proceed)
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide). */
    private fun wireTooltip(view: View?, tag: String) {
        view?.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, LocalLlmPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }
}
