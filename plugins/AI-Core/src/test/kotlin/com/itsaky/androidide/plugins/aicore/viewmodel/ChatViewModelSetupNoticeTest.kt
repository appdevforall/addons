package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aicore.models.Sender
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"

/**
 * Covers what happens to a prompt, and to the warning it raises, when no backend is configured
 * (ADFA-6214): the prompt is refused rather than swallowed, and the warning does not outlive the
 * problem it reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSetupNoticeTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // No inference service is reachable, which is the pre-flight path under test; another
        // test's leftover router in the process-global registry would take it away.
        SharedServices.clear()
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
        SharedServices.clear()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = ChatViewModel { null }.apply {
        initializeStorage(context, TEST_PROJECT_KEY)
    }

    @Test
    fun givenNoBackend_whenSendingAMessage_thenItIsRefusedAndOnlyASetupNoticeIsAdded() {
        val viewModel = newViewModel()

        val accepted = viewModel.sendMessage("hello")

        // Refused is what the composer reads to keep the typed prompt in place.
        assertFalse(accepted)
        val messages = viewModel.messages.value
        assertEquals(1, messages.size)
        assertEquals(Sender.SYSTEM, messages[0].sender)
        assertTrue(messages[0].isSetupError)
    }

    @Test
    fun givenASetupNotice_whenTheBackendBecomesReady_thenTheNoticeLeavesTheTranscriptAndTheSession() {
        val viewModel = newViewModel()
        viewModel.sendMessage("hello")

        viewModel.clearBackendSetupNotices()

        assertTrue(viewModel.messages.value.isEmpty())
        // Both, or the next sync republishes the transcript from the session and brings it back.
        assertTrue(viewModel.sessions.value.single().messages.isEmpty())
    }

    @Test
    fun givenTheBackendIsStillUnready_whenTheChatHoldingANoticeComesBack_thenItStays() {
        val viewModel = newViewModel()
        viewModel.sendMessage("hello")
        val noticed = requireNotNull(viewModel.currentSessionId.value)

        viewModel.createNewSession()
        viewModel.switchToSession(noticed)

        // Restoring a transcript drops these notices, but only once something answers as ready:
        // nothing has been configured here, so the warning is still the truth.
        val restored = viewModel.messages.value
        assertEquals(1, restored.size)
        assertTrue(restored.single().isSetupError)
    }

    @Test
    fun givenNoSetupNotice_whenTheBackendBecomesReady_thenTheTranscriptIsLeftAlone() {
        val viewModel = newViewModel()
        val before = viewModel.messages.value
        val sessionsBefore = viewModel.sessions.value

        viewModel.clearBackendSetupNotices()

        // Same values, not merely equal ones: every availability check runs this, and collectors
        // must not be woken for a transcript nothing was removed from.
        assertSame(before, viewModel.messages.value)
        assertSame(sessionsBefore, viewModel.sessions.value)
    }
}
