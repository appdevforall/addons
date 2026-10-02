package com.itsaky.androidide.plugins.aicore.shortcuts

import android.view.KeyEvent

/**
 * A chord and what it does.
 *
 * @property shortcut the chord to listen for.
 * @property onTriggered run once per press, never per repeat.
 */
internal data class ShortcutBinding(
    val shortcut: KeyboardShortcut,
    val onTriggered: () -> Unit,
)

/** Reads as `SEND_MESSAGE runs ::sendPrompt` at the call site. */
internal infix fun KeyboardShortcut.runs(action: () -> Unit) = ShortcutBinding(this, action)

/**
 * Decides which of [bindings] a key event belongs to, and runs it.
 *
 * Free of `KeyEvent` instances on purpose — it takes the four fields it reads — so the matching and
 * the swallowing rules are unit-testable without a view or an Android runtime.
 *
 * @param bindings the chords this handler owns, in priority order.
 */
internal class KeyboardShortcutHandler(private val bindings: List<ShortcutBinding>) {

    /**
     * Handles one key event.
     *
     * Every event of a press this handler owns is swallowed, not only the one that ran the action:
     * letting the rest through has the field act on the key as well, which is how a send shortcut
     * still left a newline behind.
     *
     * @param keyCode the event's key code.
     * @param keyAction the event's `KeyEvent.ACTION_*`.
     * @param metaState the event's meta state.
     * @param repeatCount the event's repeat count; a held key repeats and must not re-send.
     * @return true when the event was consumed, which is what the key listener reports.
     */
    fun handle(keyCode: Int, keyAction: Int, metaState: Int, repeatCount: Int): Boolean {
        val binding = bindings.firstOrNull { it.shortcut.matches(keyCode, metaState) }
            ?: return false
        if (keyAction == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            binding.onTriggered()
        }
        return true
    }
}
