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
}
