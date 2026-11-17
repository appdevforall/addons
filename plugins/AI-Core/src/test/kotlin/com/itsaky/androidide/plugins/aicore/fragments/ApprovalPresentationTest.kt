package com.itsaky.androidide.plugins.aicore.fragments

import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ApprovalPresentation]: the dialog picks its presentation from the preview the
 * handler declares, never from the tool's name.
 */
class ApprovalPresentationTest {

    @Test
    fun givenEachPreview_whenItsLabelIsPicked_thenTheMatchingHeadingComesBack() {
        assertEquals(R.string.approval_proposed_change, ApprovalPresentation.argsLabel(ApprovalPreview.EDIT))
        assertEquals(R.string.approval_command, ApprovalPresentation.argsLabel(ApprovalPreview.SHELL_COMMAND))
        assertEquals(R.string.approval_command_to_stop, ApprovalPresentation.argsLabel(ApprovalPreview.STOP_COMMAND))
        assertEquals(R.string.approval_args, ApprovalPresentation.argsLabel(ApprovalPreview.ARGS))
    }

    @Test
    fun givenEverySavedPreviewName_whenRestored_thenTheSamePreviewComesBack() {
        ApprovalPreview.entries.forEach { preview ->
            assertEquals(preview, ApprovalPresentation.named(preview.name))
        }
    }

    @Test
    fun givenAMissingOrUnknownName_whenRestored_thenItFallsBackToArgs() {
        assertEquals(ApprovalPreview.ARGS, ApprovalPresentation.named(null))
        assertEquals(ApprovalPreview.ARGS, ApprovalPresentation.named("is_edit"))
    }

    // The phone at font_scale 2.0 (1440x3088): dialog 2270 px, title 377, buttons min 183 / full 549.
    // The layout offers the message 2270 - 377 - 183 = 1710.
    @Test
    fun givenThePhoneAtDoubleFontScale_whenTheMessageIsBounded_thenTheButtonsGetTheirFullHeight() {
        val cap = ApprovalPresentation.messageMaxHeightPx(3088, 1710, 549, 183, 288)
        assertEquals(1344, cap)
        assertEquals(2270, 377 + cap + 549)
    }

    @Test
    fun givenAModestFontScale_whenTheMessageIsBounded_thenOnlyTheExtraButtonHeightIsReserved() {
        // Roughly 1.3: dialog 2400, title 200, buttons min 120 / full 330 -> offered 2080.
        assertEquals(1200, ApprovalPresentation.messageMaxHeightPx(2400, 2080, 330, 120, 288)) // half window
        // A short window where the adaptive limit is the tighter one: 1200 - 200 - 330 = 670.
        assertEquals(670, ApprovalPresentation.messageMaxHeightPx(2400, 880, 330, 120, 288))
    }

    @Test
    fun givenSingleRowButtons_whenTheMessageIsBounded_thenNothingIsReserved() {
        assertEquals(1000, ApprovalPresentation.messageMaxHeightPx(2400, 1000, 120, 120, 288))
    }

    @Test
    fun givenNothingFits_whenTheMessageIsBounded_thenTheFloorWins() {
        assertEquals(288, ApprovalPresentation.messageMaxHeightPx(1000, 300, 549, 183, 288))
    }

    @Test
    fun givenNoButtonPanel_whenTheMessageIsBounded_thenItFallsBackToHalfTheWindow() {
        assertEquals(1200, ApprovalPresentation.messageMaxHeightPx(2400, 1710, null, 0, 288))
        assertEquals(0, ApprovalPresentation.messageMaxHeightPx(0, 0, null, 0, 288))
        assertEquals(0, ApprovalPresentation.messageMaxHeightPx(-5, 0, null, 0, 288))
    }
}
