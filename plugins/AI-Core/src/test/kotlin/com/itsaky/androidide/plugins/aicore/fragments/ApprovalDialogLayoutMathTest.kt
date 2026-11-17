package com.itsaky.androidide.plugins.aicore.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalDialogLayoutMathTest {

    @Test
    fun `buttons that fit in one row stay side by side`() {
        assertTrue(ApprovalDialogLayoutMath.fitsSideBySide(listOf(300, 250, 280), 900))
        assertTrue(ApprovalDialogLayoutMath.fitsSideBySide(listOf(300, 600), 900))
    }

    @Test
    fun `buttons wider than the row stack`() {
        // Three buttons at font scale 1.3 on a 360dp-wide phone (about 1000px of 1080 minus padding).
        assertFalse(ApprovalDialogLayoutMath.fitsSideBySide(listOf(420, 330, 360), 900))
    }

    @Test
    fun `stacked buttons put the primary first and Always Allow last`() {
        assertEquals(
            listOf("Run now", "Decline", "Always Allow"),
            ApprovalDialogLayoutMath.stackedOrder(listOf("Always Allow", "Decline", "Run now")),
        )
        assertEquals(
            listOf("Accept", "Decline", "Correct"),
            ApprovalDialogLayoutMath.stackedOrder(listOf("Correct", "Decline", "Accept")),
        )
        assertEquals(
            listOf("Run now", "Decline"),
            ApprovalDialogLayoutMath.stackedOrder(listOf("Decline", "Run now")),
        )
    }

    @Test
    fun `scroll area gets what the buttons leave`() {
        assertEquals(700, ApprovalDialogLayoutMath.scrollBudget(1000, 300, 100))
    }

    @Test
    fun `scroll area never drops below its minimum, however tall the buttons`() {
        assertEquals(100, ApprovalDialogLayoutMath.scrollBudget(1000, 950, 100))
        assertEquals(100, ApprovalDialogLayoutMath.scrollBudget(400, 900, 100))
    }

    @Test
    fun `dialog height is capped by the screen even when the parent offers more`() {
        assertEquals(1800, ApprovalDialogLayoutMath.maxTotalHeight(5000, 2000))
        assertEquals(1200, ApprovalDialogLayoutMath.maxTotalHeight(1200, 2000))
        assertEquals(1800, ApprovalDialogLayoutMath.maxTotalHeight(null, 2000))
    }
}
