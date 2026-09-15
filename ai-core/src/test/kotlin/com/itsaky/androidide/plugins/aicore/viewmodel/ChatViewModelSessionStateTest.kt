package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"

/**
 * Guards the sessions flow's immutability: a message reaches a session as a replacement value, not
 * as an edit to the list a collector is already holding (ADFA-5583).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSessionStateTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.getString(any(), any()) } returns null
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun givenAMessageAddedToTheSession_whenReadingTheSessionsFlow_thenItHoldsAReplacedSession() {
        val viewModel = ChatViewModel { null }
        viewModel.initializeStorage(context, TEST_PROJECT_KEY)
        val before = viewModel.sessions.value

        // No LLM service is reachable here, so this takes the pre-flight error path, which is the
        // shortest public route to a message landing in the current session.
        viewModel.sendMessage("hello")

        val after = viewModel.sessions.value
        assertEquals(1, before.size)
        assertEquals(1, after.size)
        assertNotSame(before[0], after[0])
        // The value a collector took before the message must not have grown underneath it.
        assertTrue(before[0].messages.isEmpty())
        assertEquals(1, after[0].messages.size)
    }
}
