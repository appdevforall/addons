package com.itsaky.androidide.plugins.aicore.shortcuts

import android.view.KeyEvent

/**
 * One hardware-keyboard chord: a key, and exactly the modifiers that must be held with it.
 *
 * @property keyCode the `KeyEvent.KEYCODE_*` this chord ends on.
 * @property modifiers the `KeyEvent.META_*_ON` bits that must be held, or 0 for the bare key.
 */
internal data class KeyboardShortcut(
    val keyCode: Int,
    val modifiers: Int = 0,
) {

    /**
     * Whether a key event is this chord.
     *
     * Exact on the modifiers, so Shift+Enter and Ctrl+Shift+Enter stay two different chords and a
     * shortcut can never fire for a chord it does not describe. Lock keys are outside the mask:
     * Caps Lock changes what a key types, not which chord it is.
     *
     * @param keyCode the event's key code.
     * @param metaState the event's whole meta state, lock keys and left/right bits included.
     * @return true when the chord matches and the action behind it should run.
     */
    fun matches(keyCode: Int, metaState: Int): Boolean =
        keyCode == this.keyCode && (metaState and MODIFIER_MASK) == modifiers

    private companion object {
        /** The modifiers a chord may ask for; anything else in the meta state is ignored. */
        const val MODIFIER_MASK =
            KeyEvent.META_SHIFT_ON or
                KeyEvent.META_CTRL_ON or
                KeyEvent.META_ALT_ON or
                KeyEvent.META_META_ON
    }
}
