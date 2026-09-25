package com.itsaky.androidide.plugins.aicore.fragments

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.widget.EditText

/**
 * Keeps a vertical drag inside the prompt field instead of handing it to whatever the chat is
 * mounted in.
 *
 * The Agent chat sits in the host's bottom sheet, which claims vertical swipes to drag the sheet,
 * so a prompt taller than the field could not be scrolled at all. Asking the ancestors not to
 * intercept for the length of the gesture leaves the field its own scrolling and the sheet every
 * drag that starts anywhere else.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun EditText.keepVerticalDragsToItself() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            // Only while there is something to scroll to, or the sheet loses drags for nothing.
            MotionEvent.ACTION_DOWN -> {
                if (view.canScrollVertically(1) || view.canScrollVertically(-1)) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        // Never consumed: the caret, the selection and the paste menu stay the field's own business.
        false
    }
}
