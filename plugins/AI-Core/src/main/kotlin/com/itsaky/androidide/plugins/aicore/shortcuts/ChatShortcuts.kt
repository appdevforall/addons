package com.itsaky.androidide.plugins.aicore.shortcuts

import android.view.KeyEvent

/**
 * Every hardware-keyboard chord the Agent chat answers to, in one place: a new shortcut is a
 * constant here plus a [runs] binding wherever the view is wired, and nothing else changes.
 *
 * Keep the chords in step with the in-app help — the chat input's tooltip and the Tier-3 guide
 * both name them, and a shortcut nothing documents is one nobody finds.
 */
internal object ChatShortcuts {

    /**
     * Sends the prompt. Shift+Enter rather than Enter: the field is multi-line, so plain Enter has
     * to stay the way a new line is added.
     */
    val SEND_MESSAGE = KeyboardShortcut(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
}
