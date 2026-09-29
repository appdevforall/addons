package com.itsaky.androidide.plugins.aicore.shortcuts

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the dispatch rules: one run per press, and every event of an owned press swallowed — the
 * half that is not swallowed is the newline a send shortcut used to leave behind.
 */
class KeyboardShortcutHandlerTest {

    private var sends = 0
    private var newChats = 0

    // Chords of this test's own, not the catalog's: the handler's rules are what is under test,
    // and they must not change meaning the day the chat rebinds a key.
    private val shiftEnter = KeyboardShortcut(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
    private val ctrlN = KeyboardShortcut(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)

    private val handler = KeyboardShortcutHandler(
        listOf(shiftEnter runs { sends++ }, ctrlN runs { newChats++ })
    )

    private fun press(
        keyCode: Int,
        keyAction: Int,
        metaState: Int = 0,
        repeatCount: Int = 0,
    ) = handler.handle(keyCode, keyAction, metaState, repeatCount)

    @Test
    fun givenAnOwnedChord_whenTheKeyGoesDown_thenItRunsOnceAndIsConsumed() {
        val consumed = press(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON)

        assertTrue(consumed)
        assertEquals(1, sends)
    }

    @Test
    fun givenAnOwnedChord_whenTheKeyComesUp_thenItIsConsumedWithoutRunningAgain() {
        press(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON)

        val consumed = press(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, KeyEvent.META_SHIFT_ON)

        assertTrue(consumed)
        assertEquals(1, sends)
    }

    @Test
    fun givenAnOwnedChord_whenTheKeyIsHeldDown_thenTheRepeatsDoNotRunItAgain() {
        press(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON)

        press(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON, repeatCount = 1)

        assertEquals(1, sends)
    }

    @Test
    fun givenAnUnownedKey_whenItIsPressed_thenNothingRunsAndTheEventIsLeftAlone() {
        val consumed = press(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN)

        // Not consumed: plain Enter belongs to the field, which adds the line.
        assertFalse(consumed)
        assertEquals(0, sends)
        assertEquals(0, newChats)
    }

    @Test
    fun givenSeveralChords_whenOneOfThemIsPressed_thenOnlyItsOwnActionRuns() {
        press(KeyEvent.KEYCODE_N, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON)

        assertEquals(1, newChats)
        assertEquals(0, sends)
    }
}
