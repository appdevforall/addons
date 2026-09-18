package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import com.itsaky.androidide.plugins.services.IdeEditorService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * Unit tests for [EditorBufferApplier] — specifically that the save targets the *file* and never the
 * focused tab. Saving by focus persisted whichever tab the user happened to be in (ADFA-5215), so the
 * negative verifications on `openFile`/`saveCurrentFile` below are the regression lock.
 */
class EditorBufferApplierTest {

    private companion object {
        const val EDITOR_THREAD = "test-editor"
    }

    private lateinit var editorService: IdeEditorService
    private lateinit var applier: EditorBufferApplier

    private val target = File("/project/A.kt")
    private val buffer = "line0\nline1\nline2\n"

    @Before
    fun setup() {
        editorService = mockk(relaxed = true)
        every { editorService.getFileContent(target) } returns buffer
        every { editorService.replaceRange(any(), any(), any()) } returns true
        coEvery { editorService.saveFile(any()) } returns true
        applier = EditorBufferApplier(editorService, Dispatchers.Unconfined)
    }

    /** The running thread, without the ` @coroutine#N` suffix the test JVM's debug mode appends. */
    private fun currentThreadName() = Thread.currentThread().name.substringBefore(" @")

    private fun apply(
        matched: String = buffer,
        applier: EditorBufferApplier = this.applier,
    ): EditorBufferApplier.Outcome =
        runBlocking {
            applier.apply(
                file = target,
                displayPath = "A.kt",
                matched = matched,
                updated = matched.replace("line1", "LINE1"),
                oldString = "line1",
                newString = "LINE1",
                occurrences = 1,
            )
        }

    @Test
    fun givenAnUnfocusedFile_whenApplied_thenItIsSavedByFileWithoutStealingFocus() {
        // The user is looking at another tab; the edit must neither follow nor move focus.
        every { editorService.getCurrentFile() } returns File("/project/B.kt")

        val outcome = apply()

        assertEquals(EditorBufferApplier.Outcome.Applied(saved = true), outcome)
        coVerify { editorService.saveFile(target) }
        verify(exactly = 0) { editorService.openFile(any()) }
        verify(exactly = 0) { editorService.saveCurrentFile() }
    }

    @Test
    fun givenTheHostSavesTheFile_whenApplied_thenTheOutcomeReportsSaved() {
        assertEquals(EditorBufferApplier.Outcome.Applied(saved = true), apply())
    }

    @Test
    fun givenTheHostCannotSaveTheFile_whenApplied_thenTheEditStillCountsAsApplied() {
        coEvery { editorService.saveFile(any()) } returns false

        assertEquals(EditorBufferApplier.Outcome.Applied(saved = false), apply())
    }

    @Test
    fun givenSaveDeniedByPermissions_whenApplied_thenItIsReportedAsUnsavedNotPropagated() {
        // Letting a permission miss escape would abort the turn over an edit already in the buffer.
        coEvery { editorService.saveFile(any()) } throws SecurityException("FILESYSTEM_WRITE denied")

        assertEquals(EditorBufferApplier.Outcome.Applied(saved = false), apply())
    }

    @Test
    fun givenSaveFailingUnexpectedly_whenApplied_thenTheErrorIsNotSwallowed() {
        // Only SecurityException means "unsaved"; a defect must not pass as an applied-but-unsaved edit.
        coEvery { editorService.saveFile(any()) } throws IllegalStateException("host defect")

        assertThrows(IllegalStateException::class.java) { apply() }
    }

    @Test
    fun givenAnEditorDispatcher_whenApplied_thenOnlyTheBufferEditRunsOnIt() {
        // saveFile is a suspending host call that reaches the editor thread on its own; re-dispatching
        // it here - or saving inside the editor block - would put the applier back in that business.
        val editorExecutor = Executors.newSingleThreadExecutor { Thread(it, EDITOR_THREAD) }
        var replaceThread: String? = null
        var saveThread: String? = null
        every { editorService.replaceRange(any(), any(), any()) } answers {
            replaceThread = currentThreadName()
            true
        }
        coEvery { editorService.saveFile(any()) } answers {
            saveThread = currentThreadName()
            true
        }

        try {
            val outcome = apply(
                applier = EditorBufferApplier(editorService, editorExecutor.asCoroutineDispatcher()),
            )

            assertEquals(EditorBufferApplier.Outcome.Applied(saved = true), outcome)
            assertEquals(EDITOR_THREAD, replaceThread)
            assertEquals(currentThreadName(), saveThread)
        } finally {
            editorExecutor.shutdownNow()
        }
    }

    @Test
    fun givenAStaleBuffer_whenApplied_thenNothingIsEditedOrSaved() {
        every { editorService.getFileContent(target) } returns "the user typed this instead\n"

        val outcome = apply()

        assertTrue("Expected a refusal, got: $outcome", outcome is EditorBufferApplier.Outcome.Failed)
        verify(exactly = 0) { editorService.replaceRange(any(), any(), any()) }
        coVerify(exactly = 0) { editorService.saveFile(any()) }
    }

    @Test
    fun givenReplaceRangeRejectingTheEdit_whenApplied_thenNothingIsSaved() {
        every { editorService.replaceRange(any(), any(), any()) } returns false

        val outcome = apply()

        assertTrue("Expected a refusal, got: $outcome", outcome is EditorBufferApplier.Outcome.Failed)
        coVerify(exactly = 0) { editorService.saveFile(any()) }
    }
}
