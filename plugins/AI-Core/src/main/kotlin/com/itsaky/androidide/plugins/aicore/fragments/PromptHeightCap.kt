package com.itsaky.androidide.plugins.aicore.fragments

import android.view.View
import android.widget.EditText

/** Most of [container]'s height a grown prompt may take; the rest stays with the transcript. */
private const val MAX_CONTAINER_FRACTION = 0.4f

/** Never shrink below this, however cramped the sheet: a one-line field cannot show a newline. */
private const val MIN_LINES = 2

/**
 * Lets the prompt grow with what is typed up to [maxLines], but never past a share of [container].
 *
 * The field wraps its content, so on its own it grows to [maxLines]. In the host's bottom sheet,
 * in landscape or behind the keyboard, eight lines alone can outgrow the space the chat column
 * has, and the weighted transcript is then measured at zero (ADFA-3070). This trims the line cap
 * whenever [container] is resized, so the prompt scrolls instead.
 */
internal fun EditText.growUpTo(maxLines: Int, container: View) {
    container.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
        val budget = (bottom - top) * MAX_CONTAINER_FRACTION - paddingTop - paddingBottom
        if (budget <= 0 || lineHeight <= 0) return@addOnLayoutChangeListener
        val lines = (budget / lineHeight).toInt().coerceIn(MIN_LINES, maxLines)
        // Posted: setMaxLines requests a layout, which must not happen inside this one.
        if (lines != this.maxLines) post { this.maxLines = lines }
    }
}
