package com.itsaky.androidide.plugins.aicore.shortcuts

import android.view.KeyEvent
import android.view.View

/**
 * Hands this view's hardware-keyboard chords to a [KeyboardShortcutHandler].
 *
 * The only place in the feature that touches a view or a `KeyEvent` instance: everything about
 * which chord is which, and what it runs, is decided by the values passed in.
 *
 * Replaces any key listener the view already had, so one call carries every chord it is to answer.
 *
 * @param bindings the chords, in priority order; see [runs].
 */
internal fun View.bindShortcuts(vararg bindings: ShortcutBinding) {
    val handler = KeyboardShortcutHandler(bindings.toList())
    setOnKeyListener { _, keyCode, event ->
        // Normalized: a keyboard that reports only META_CTRL_LEFT_ON still counts as Ctrl.
        val metaState = KeyEvent.normalizeMetaState(event.metaState)
        handler.handle(keyCode, event.action, metaState, event.repeatCount)
    }
}
