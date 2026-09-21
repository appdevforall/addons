package com.itsaky.androidide.plugins.aicore.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Guards the retention contract behind the tab-switch fix: the Agent tab's ViewModel — and with it
 * the run in flight — must outlive the fragment the host removes on every tab switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStoreTest {

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        ChatViewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun givenTheAgentTabIsReopened_whenTheViewModelIsResolved_thenTheSameInstanceComesBack() {
        val fromFirstFragment = ChatViewModelStore.get()
        val fromSecondFragment = ChatViewModelStore.get()

        assertSame(fromFirstFragment, fromSecondFragment)
    }

    @Test
    fun givenARetainedViewModel_whenTheStoreIsCleared_thenTheNextResolveBuildsANewOne() {
        val beforeDispose = ChatViewModelStore.get()

        ChatViewModelStore.clear()

        assertNotSame(beforeDispose, ChatViewModelStore.get())
    }
}
