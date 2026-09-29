package com.itsaky.androidide.plugins.aiagentopenai.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.aiagentopenai.R

/** How much a pane button stands out: one filled action per section, the rest outlined. */
private enum class ButtonEmphasis { FILLED, OUTLINED }

/**
 * Gives every Material button, text field and divider under [this] its Material 3 colours, outline
 * and ripple in code. The styles' `app:` items are dropped inside the host, so XML alone leaves
 * these controls on the host theme's values.
 *
 * @param outlinedButtonIds the buttons that are secondary actions; every other button is filled.
 */
internal fun View.applyPaneStyling(outlinedButtonIds: Set<Int>) {
    when (this) {
        is MaterialButton -> applyEmphasis(
            if (id in outlinedButtonIds) ButtonEmphasis.OUTLINED else ButtonEmphasis.FILLED
        )
        is TextInputLayout -> applyOutline()
        is MaterialDivider -> applyHairline()
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).applyPaneStyling(outlinedButtonIds)
    }
}

/** Container, label, icon, border and ripple for [emphasis], each with its disabled state. */
private fun MaterialButton.applyEmphasis(emphasis: ButtonEmphasis) {
    val filled = emphasis == ButtonEmphasis.FILLED
    val content = colors(
        if (filled) R.color.plugin_button_filled_content else R.color.plugin_button_outlined_content
    )
    backgroundTintList = if (filled) {
        colors(R.color.plugin_button_filled_container)
    } else {
        ColorStateList.valueOf(Color.TRANSPARENT)
    }
    setTextColor(content)
    iconTint = content
    rippleColor = colors(
        if (filled) R.color.plugin_button_filled_ripple else R.color.plugin_button_outlined_ripple
    )
    strokeColor = colors(R.color.plugin_button_outlined_stroke)
    strokeWidth = if (filled) 0 else resources.getDimensionPixelSize(R.dimen.button_stroke_width)
    cornerRadius = resources.getDimensionPixelSize(R.dimen.radius_md)
}

/** Outline, corners, hint and end icon of an outlined-box field. */
private fun TextInputLayout.applyOutline() {
    setBoxStrokeColorStateList(colors(R.color.plugin_box_stroke))
    setBoxStrokeErrorColor(colors(R.color.plugin_error))
    val radius = resources.getDimension(R.dimen.radius_md)
    setBoxCornerRadii(radius, radius, radius, radius)
    val hint = colors(R.color.plugin_text_muted)
    defaultHintTextColor = hint
    hintTextColor = hint
    setEndIconTintList(colors(R.color.plugin_on_surface_variant))
}

private fun MaterialDivider.applyHairline() {
    setDividerColorResource(R.color.plugin_outline_variant)
    setDividerThicknessResource(R.dimen.divider_thickness)
}

/** Resolved against this view's context, which carries the plugin's resources. */
private fun View.colors(@ColorRes id: Int): ColorStateList =
    requireNotNull(ContextCompat.getColorStateList(context, id))
