package com.itsaky.androidide.plugins.aicore.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fold state of user bubbles lives in the ViewModel, so it outlives the adapter that draws it:
 * a chat view rebuilt from scratch still shows the messages the reader unfolded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelMessageFoldTest {

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
    fun givenAFoldedMessage_whenToggled_thenItIsUnfoldedAndReportsSo() {
        val viewModel = ChatViewModel { null }

        assertFalse(viewModel.isUserMessageExpanded("m1"))
        assertTrue(viewModel.toggleUserMessageExpanded("m1"))
        assertTrue(viewModel.isUserMessageExpanded("m1"))
    }

    @Test
    fun givenAnUnfoldedMessage_whenToggledAgain_thenItFoldsWithoutTouchingOthers() {
        val viewModel = ChatViewModel { null }
        viewModel.toggleUserMessageExpanded("m1")
        viewModel.toggleUserMessageExpanded("m2")

        assertFalse(viewModel.toggleUserMessageExpanded("m1"))

        assertFalse(viewModel.isUserMessageExpanded("m1"))
        assertTrue(viewModel.isUserMessageExpanded("m2"))
    }
}
