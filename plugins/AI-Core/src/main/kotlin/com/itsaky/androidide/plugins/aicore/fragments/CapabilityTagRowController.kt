package com.itsaky.androidide.plugins.aicore.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.capabilities.BackendState
import com.itsaky.androidide.plugins.aicore.capabilities.CapabilityMonitor
import com.itsaky.androidide.plugins.aicore.capabilities.CapabilityTag
import com.itsaky.androidide.plugins.aicore.capabilities.PluginScreen
import com.itsaky.androidide.plugins.aicore.capabilities.ToolSourceScreens
import com.itsaky.androidide.plugins.aicore.databinding.FragmentChatBinding
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.services.CapabilityStatus
import kotlinx.coroutines.launch

/**
 * The row of tags under the chat input naming what the agent is connected to.
 *
 * Read-only: a tap explains a tag and a long press shows its help; nothing here changes a
 * setting, bar the details offering the way into the settings of a backend that is not set up or
 * of a tool source that has a settings screen.
 *
 * @param binding the chat's views; the row lives in the input card.
 * @param monitor where the tags come from.
 * @param showTooltip shows this plugin's tooltip for a tag on an anchor, false when unavailable.
 * @param dialogContext the Activity-backed Context details dialogs need, or null once detached.
 * @param onOpenSettings opens the Agent settings screen.
 * @param onOpenPluginScreen opens a settings screen another plugin owns.
 */
class CapabilityTagRowController(
    private val binding: FragmentChatBinding,
    private val monitor: CapabilityMonitor,
    private val showTooltip: (View, String) -> Boolean,
    private val dialogContext: () -> Context?,
    private val onOpenSettings: () -> Unit,
    private val onOpenPluginScreen: (PluginScreen) -> Unit,
) {

    /** The details dialog on screen, so a new tap replaces it and [detach] can close it. */
    private var dialog: AlertDialog? = null

    /**
     * Collects tags while [owner] is started. The listeners behind the flow are dropped when the
     * fragment stops, so a chat nobody is looking at hears nothing.
     *
     * @param owner the fragment's view lifecycle.
     */
    fun attach(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                monitor.tags().collect(::render)
            }
        }
    }

    /** Closes the details dialog; the view it describes is going away. */
    fun detach() {
        dialog?.dismiss()
        dialog = null
    }

    /** Rebuilds the row. A handful of chips, so replacing them beats diffing them. */
    private fun render(tags: List<CapabilityTag>) {
        val group = binding.capabilityTagGroup
        group.removeAllViews()
        tags.forEachIndexed { index, tag -> group.addView(chipFor(tag), layoutParams(group, index)) }
    }

    private fun layoutParams(group: ViewGroup, index: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        if (index > 0) marginStart = group.resources.getDimensionPixelSize(R.dimen.capability_tag_spacing)
    }

    /**
     * Builds one tag. Styled in code rather than by a Chip style: `app:` attributes and theme
     * attributes both resolve against the host here, not against this plugin.
     */
    private fun chipFor(tag: CapabilityTag): Chip {
        // The group's Context: it carries this plugin's resources and the IDE's day/night mode.
        val context = binding.capabilityTagGroup.context
        val res = context.resources
        val colors = colorsFor(tag.status)
        val label = label(context, tag)
        return Chip(context).apply {
            text = label
            isCheckable = false
            setEnsureMinTouchTargetSize(false)
            chipMinHeight = res.getDimension(R.dimen.capability_tag_min_height)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.capability_tag_text_size))
            chipStartPadding = res.getDimension(R.dimen.capability_tag_padding_horizontal)
            chipEndPadding = res.getDimension(R.dimen.capability_tag_padding_horizontal)
            chipBackgroundColor = ColorStateList.valueOf(context.getColor(colors.background))
            chipStrokeColor = ColorStateList.valueOf(context.getColor(colors.stroke))
            chipStrokeWidth = res.getDimension(R.dimen.capability_tag_stroke_width)
            setTextColor(context.getColor(colors.text))
            setChipIconResource(R.drawable.ic_status_dot)
            chipIconSize = res.getDimension(R.dimen.capability_tag_icon_size)
            chipIconTint = ColorStateList.valueOf(context.getColor(colors.dot))
            isChipIconVisible = true
            contentDescription = context.getString(
                R.string.desc_capability_tag, label, context.getString(statusLabel(tag.status))
            )
            setOnClickListener { showDetails(tag) }
            setOnLongClickListener { anchor -> showTooltip(anchor, tooltipTagFor(tag)) }
        }
    }

    /** The tag's own text; everything longer lives in its details. */
    private fun label(context: Context, tag: CapabilityTag): String = when (tag) {
        is CapabilityTag.Backend -> when (tag.state) {
            BackendState.NONE_INSTALLED -> context.getString(R.string.backend_none_installed_short)
            BackendState.NOT_INSTALLED -> context.getString(R.string.backend_selected_missing_short)
            else -> tag.name.orEmpty().let { name ->
                tag.modelName?.let { context.getString(R.string.capability_backend_model, name, it) } ?: name
            }
        }
        is CapabilityTag.Web -> context.getString(
            if (tag.online) R.string.capability_web else R.string.capability_web_offline
        )
        is CapabilityTag.Tools -> tag.name
    }

    /** Explains one tag: where it comes from, how many tools it stands for and, if broken, why. */
    private fun showDetails(tag: CapabilityTag) {
        val context = dialogContext() ?: return
        val status = context.getString(
            R.string.capability_detail_status, context.getString(statusLabel(tag.status))
        )
        val lines = mutableListOf<String>()
        var openSettings: (() -> Unit)? = null
        when (tag) {
            is CapabilityTag.Backend -> {
                lines += context.getString(R.string.capability_detail_kind_backend)
                tag.name?.let { lines += context.getString(R.string.capability_detail_source, it) }
                if (tag.state == BackendState.READY || tag.state == BackendState.NOT_CONFIGURED) {
                    lines += tag.modelName?.let { context.getString(R.string.capability_detail_model, it) }
                        ?: context.getString(R.string.capability_detail_model_unknown)
                }
                lines += status
                backendReason(tag.state)?.let { reason ->
                    lines += context.getString(R.string.capability_detail_reason, context.getString(reason))
                }
                tag.statusMessage?.let { lines += context.getString(R.string.capability_detail_reason, it) }
                if (tag.status != CapabilityStatus.AVAILABLE) openSettings = onOpenSettings
            }
            is CapabilityTag.Web -> {
                lines += context.getString(R.string.capability_detail_kind_web)
                lines += status
                lines += context.getString(
                    if (tag.online) R.string.capability_web_online_detail else R.string.capability_web_offline_detail
                )
            }
            is CapabilityTag.Tools -> {
                lines += context.getString(R.string.capability_detail_source, tag.sourceName)
                lines += context.resources.getQuantityString(
                    R.plurals.capability_detail_tools, tag.toolCount, tag.toolCount
                )
                lines += status
                if (tag.status != CapabilityStatus.AVAILABLE) {
                    tag.statusMessage?.let { lines += context.getString(R.string.capability_detail_reason, it) }
                }
                ToolSourceScreens.settingsFor(tag.providerId)?.let { screen ->
                    openSettings = { onOpenPluginScreen(screen) }
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(label(binding.capabilityTagGroup.context, tag))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
        openSettings?.let { open ->
            builder.setNeutralButton(R.string.capability_detail_open_settings) { _, _ -> open() }
        }
        dialog?.dismiss()
        dialog = builder.show()
    }

    private fun backendReason(state: BackendState): Int? = when (state) {
        BackendState.READY -> null
        BackendState.NOT_CONFIGURED -> R.string.capability_backend_not_configured
        BackendState.NOT_INSTALLED -> R.string.capability_backend_not_installed
        BackendState.NONE_INSTALLED -> R.string.capability_backend_none_installed
    }

    private fun statusLabel(status: CapabilityStatus): Int = when (status) {
        CapabilityStatus.AVAILABLE -> R.string.capability_status_available
        CapabilityStatus.CONNECTING -> R.string.capability_status_connecting
        CapabilityStatus.DEGRADED -> R.string.capability_status_degraded
    }

    private fun tooltipTagFor(tag: CapabilityTag): String = when (tag) {
        is CapabilityTag.Backend -> AiCorePlugin.TOOLTIP_TAG_CAPABILITY_BACKEND
        is CapabilityTag.Web -> AiCorePlugin.TOOLTIP_TAG_CAPABILITY_WEB
        is CapabilityTag.Tools -> AiCorePlugin.TOOLTIP_TAG_CAPABILITY_TOOLS
    }

    /** Colour resources for one status; degraded is the one that has to stand out. */
    private class TagColors(val background: Int, val stroke: Int, val text: Int, val dot: Int)

    private fun colorsFor(status: CapabilityStatus): TagColors = when (status) {
        CapabilityStatus.AVAILABLE -> TagColors(
            background = R.color.plugin_surface_variant,
            stroke = R.color.plugin_outline_variant,
            text = R.color.plugin_on_surface_variant,
            dot = R.color.plugin_success,
        )
        CapabilityStatus.CONNECTING -> TagColors(
            background = R.color.plugin_surface_variant,
            stroke = R.color.plugin_outline_variant,
            text = R.color.plugin_text_muted,
            dot = R.color.plugin_outline,
        )
        CapabilityStatus.DEGRADED -> TagColors(
            background = R.color.plugin_error_container,
            stroke = R.color.plugin_error,
            text = R.color.plugin_on_error_container,
            dot = R.color.plugin_error,
        )
    }
}
