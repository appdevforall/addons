package com.itsaky.androidide.plugins.aicore.fragments

import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ApprovalKind]: the dialog picks its presentation from the preview the handler
 * declares, never from the tool's name.
 */
class ApprovalKindTest {

    @Test
    fun givenEachPreview_whenItsKindIsPicked_thenTheMatchingKindComesBack() {
        assertEquals(ApprovalKind.EDIT, ApprovalKind.of(ApprovalPreview.EDIT))
        assertEquals(ApprovalKind.SHELL_COMMAND, ApprovalKind.of(ApprovalPreview.SHELL_COMMAND))
        assertEquals(ApprovalKind.OTHER, ApprovalKind.of(ApprovalPreview.ARGS))
    }

    @Test
    fun givenASavedKindName_whenRestored_thenTheSameKindComesBack() {
        assertEquals(ApprovalKind.SHELL_COMMAND, ApprovalKind.named(ApprovalKind.SHELL_COMMAND.name))
    }

    @Test
    fun givenAMissingOrUnknownName_whenRestored_thenItFallsBackToOther() {
        assertEquals(ApprovalKind.OTHER, ApprovalKind.named(null))
        assertEquals(ApprovalKind.OTHER, ApprovalKind.named("is_edit"))
    }
}
