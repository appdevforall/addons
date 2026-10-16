package com.itsaky.androidide.plugins.aiagentmcp.settings

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.itsaky.androidide.plugins.aiagentmcp.R

/**
 * Material 3 button looks applied in code. The host's Material classes drop this plugin's `app:`
 * attributes, so a style alone draws them in the IDE's own grey; calls here cannot be dropped.
 */
object McpButtons {

    /** Material 3's pressed state layer over the content colour. */
    private const val STATE_LAYER_ALPHA = 0x1F

    /** Material 3's disabled content opacity, 38%. */
    private const val DISABLED_ALPHA = 0x61

    private const val ICON_SIZE_DP = 18
    private const val ICON_PADDING_DP = 8
    private const val PADDING_DP = 12
    private const val PADDING_AFTER_ICON_DP = 16

    /**
     * A text button: no container, primary-coloured label, and a leading icon when one is given.
     * @param button a button inflated from this plugin's layouts, so its context reads plugin colours.
     * @param icon the leading icon, or null for a label alone.
     */
    fun text(button: MaterialButton, @DrawableRes icon: Int? = null) {
        val context = button.context
        val primary = ContextCompat.getColor(context, R.color.plugin_primary)
        val disabled = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(context, R.color.plugin_on_surface),
            DISABLED_ALPHA,
        )
        val content = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(disabled, primary),
        )
        button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        button.strokeWidth = 0
        button.elevation = 0f
        button.stateListAnimator = null
        button.setTextColor(content)
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, STATE_LAYER_ALPHA))
        val end = if (icon == null) PADDING_DP else PADDING_AFTER_ICON_DP
        button.setPaddingRelative(px(button, PADDING_DP), button.paddingTop, px(button, end), button.paddingBottom)
        if (icon == null) return
        button.setIconResource(icon)
        button.iconTint = content
        button.iconSize = px(button, ICON_SIZE_DP)
        button.iconPadding = px(button, ICON_PADDING_DP)
        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    }

    private fun px(button: MaterialButton, dp: Int): Int =
        (dp * button.resources.displayMetrics.density).toInt()
}
