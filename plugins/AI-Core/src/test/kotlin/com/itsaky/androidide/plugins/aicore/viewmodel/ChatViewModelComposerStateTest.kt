package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.Sender
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The composer's text, its attached files and an edit in progress live in the ViewModel, so the
 * bottom-sheet chat and the full-screen one show the same composer and the same edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelComposerStateTest {

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun givenADraftAndFiles_whenEditingAPrompt_thenTheEditIsSharedAndEndingItPutsTheDraftBack() {
        val viewModel = ChatViewModel { null }
        val attached = File.createTempFile("Draft", ".kt").apply { deleteOnExit() }
        val promptFile = File.createTempFile("Prompt", ".kt").apply { deleteOnExit() }
        viewModel.setComposerDraft("half-typed")
        viewModel.setContextFiles(listOf(attached))

        viewModel.beginEdit(prompt("u2", "second prompt", promptFile))

        assertEquals("u2", viewModel.editInProgress.value?.messageId)
        assertEquals("second prompt", viewModel.composerDraft.value)
        assertEquals(listOf(File(promptFile.absolutePath)), viewModel.contextFiles.value)

        viewModel.endEdit()

        assertNull(viewModel.editInProgress.value)
        assertEquals("half-typed", viewModel.composerDraft.value)
        assertEquals(listOf(attached), viewModel.contextFiles.value)
    }

    @Test
    fun givenAnEdit_whenItMovesToAnotherPrompt_thenEndingItPutsBackTheDraftFromBeforeTheFirst() {
        val viewModel = ChatViewModel { null }
        viewModel.setComposerDraft("half-typed")

        viewModel.beginEdit(prompt("u2", "second prompt"))
        viewModel.beginEdit(prompt("u1", "first prompt"))

        assertEquals("first prompt", viewModel.composerDraft.value)
        viewModel.endEdit()
        assertEquals("half-typed", viewModel.composerDraft.value)
    }

    @Test
    fun givenAttachedFiles_whenAttachingAndDetaching_thenEachFileIsAttachedOnce() {
        val viewModel = ChatViewModel { null }
        val a = File("/project/A.kt")
        val b = File("/project/B.kt")

        viewModel.addContextFiles(listOf(a, b, a))
        viewModel.addContextFiles(listOf(b))
        assertEquals(listOf(a, b), viewModel.contextFiles.value)

        viewModel.removeContextFile(a)
        assertEquals(listOf(b), viewModel.contextFiles.value)
    }

    private fun prompt(id: String, text: String, vararg files: File) = ChatMessage(
        id = id,
        text = text,
        sender = Sender.USER,
        contextFiles = files.map { it.absolutePath }.takeIf { it.isNotEmpty() },
    )
}
