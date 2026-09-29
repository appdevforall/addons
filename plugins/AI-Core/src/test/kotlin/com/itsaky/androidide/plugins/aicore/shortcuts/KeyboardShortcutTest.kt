package com.itsaky.androidide.plugins.aicore.shortcuts

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards what a chord accepts. The matching is exact on the modifiers, which is the whole reason
 * plain Enter can keep adding a line while Shift+Enter sends.
 */
class KeyboardShortcutTest {

    private val shiftEnter = KeyboardShortcut(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)

    @Test
    fun givenShiftEnter_whenShiftAndEnterAreHeld_thenItMatches() {
        // A real press also carries the side-specific bit, which must not spoil the match.
        val metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        assertTrue(shiftEnter.matches(KeyEvent.KEYCODE_ENTER, metaState))
    }

    @Test
    fun givenShiftEnter_whenEnterIsPressedAlone_thenItDoesNotMatch() {
        assertFalse(shiftEnter.matches(KeyEvent.KEYCODE_ENTER, 0))
    }

    @Test
    fun givenShiftEnter_whenAFurtherModifierIsHeld_thenItDoesNotMatch() {
        val metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON

        assertFalse(shiftEnter.matches(KeyEvent.KEYCODE_ENTER, metaState))
    }

    @Test
    fun givenShiftEnter_whenCapsLockIsOn_thenItStillMatches() {
        val metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_CAPS_LOCK_ON

        assertTrue(shiftEnter.matches(KeyEvent.KEYCODE_ENTER, metaState))
    }

    @Test
    fun givenShiftEnter_whenAnotherKeyCarriesShift_thenItDoesNotMatch() {
        assertFalse(shiftEnter.matches(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun givenAModifierlessChord_whenTheKeyIsPressedWithAModifier_thenItDoesNotMatch() {
        val escape = KeyboardShortcut(KeyEvent.KEYCODE_ESCAPE)

        assertTrue(escape.matches(KeyEvent.KEYCODE_ESCAPE, 0))
        assertFalse(escape.matches(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_ALT_ON))
    }
}
