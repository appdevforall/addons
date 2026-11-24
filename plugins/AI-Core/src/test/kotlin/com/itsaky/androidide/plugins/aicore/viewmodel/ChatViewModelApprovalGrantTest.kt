package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aicore.models.ChatSession
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.SHIPPED_ROOT
import com.itsaky.androidide.plugins.aicore.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.aicore.tool.ApprovalResult
import com.itsaky.androidide.plugins.aicore.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace, as in [ChatViewModelSessionListTest]. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"

/**
 * "Always Allow" lasts for one chat, as its tooltip says (review F13 on addons#167). The
 * ViewModel, and the approval manager in it, live as long as the plugin, so without a clear at
 * each chat change a grant carried on into every other chat in every project.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelApprovalGrantTest {

    private lateinit var context: Context

    /** Backs the preferences mock, so a write is visible to the next read. */
    private val stored = ConcurrentHashMap<String, String>()

    private val viewModels = mutableListOf<ChatViewModel>()

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A built-in that may be allowed for the session: the change covers built-ins too. */
    private val tool = object : ToolHandler {
        override val toolName = "add_dependency"
        override val description = "fake"
        override val requiresApproval = true
        override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
    }

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        // A relaxed mock answers a String getter with "", which would read as a stored blob.
        every { sharedPreferences.getString(any(), any()) } answers {
            stored[firstArg<String>()] ?: secondArg<String?>()
        }
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            val value = secondArg<String?>()
            if (value == null) stored.remove(firstArg<String>()) else stored[firstArg<String>()] = value
            editor
        }
        every { editor.remove(any()) } answers {
            stored.remove(firstArg<String>())
            editor
        }
        // The dialog's description comes from this config, which the plugin loads on activation.
        runBlocking { sharedPromptConfig.preload(configScope, DirectoryPromptConfigSource(SHIPPED_ROOT)).await() }
    }

    @After
    fun tearDown() {
        clearAndAwait(viewModels)
        Dispatchers.resetMain()
        sharedPromptConfig.clear()
        configScope.cancel()
    }

    @Test
    fun givenAnAlwaysAllowGrant_whenTheSameChatGoesOn_thenTheToolRunsWithNoDialog() {
        // The control: proves asksAgain() sees a grant that still holds, and that reselecting or
        // renaming the open chat is not a change of chat.
        val viewModel = viewModelWithGrant()
        val current = viewModel.currentSessionId.value!!

        viewModel.switchToSession(current)
        viewModel.renameSession(current, "Build failure")

        assertFalse("the grant holds for the rest of the chat", asksAgain(viewModel.approvalManager))
    }

    @Test
    fun givenAnAlwaysAllowGrant_whenANewChatIsStarted_thenTheToolAsksAgain() {
        val viewModel = viewModelWithGrant()

        viewModel.createNewSession()

        assertTrue("a new chat must not inherit the grant", asksAgain(viewModel.approvalManager))
    }

    @Test
    fun givenAnAlwaysAllowGrant_whenAnotherChatIsOpened_thenTheToolAsksAgain() {
        val viewModel = restoredViewModel()
        val earlier = viewModel.currentSessionId.value!!
        viewModel.createNewSession()
        grantAlwaysAllow(viewModel.approvalManager)

        viewModel.switchToSession(earlier)

        assertTrue("another chat must not inherit the grant", asksAgain(viewModel.approvalManager))
    }

    @Test
    fun givenAnAlwaysAllowGrant_whenTheChatIsDeleted_thenTheChatTakingItsPlaceAsksAgain() {
        val viewModel = restoredViewModel()
        viewModel.createNewSession()
        val doomed = viewModel.currentSessionId.value!!
        grantAlwaysAllow(viewModel.approvalManager)

        viewModel.deleteSession(doomed)

        assertTrue("the chat taking its place must not inherit the grant", asksAgain(viewModel.approvalManager))
    }

    @Test
    fun givenAnAlwaysAllowGrant_whenAChatIsImported_thenTheToolAsksAgain() {
        val viewModel = viewModelWithGrant()

        viewModel.importSession(ChatSession())

        assertTrue("an imported chat must not inherit the grant", asksAgain(viewModel.approvalManager))
    }

    @Test
    fun givenAnAlwaysAllowGrant_whenTheChatIsCleared_thenTheToolAsksAgain() {
        val viewModel = viewModelWithGrant()

        viewModel.clearMessages()

        assertTrue("a cleared chat starts over, grants included", asksAgain(viewModel.approvalManager))
    }

    /** A ViewModel bound to the test project, whose current chat holds an Always Allow for [tool]. */
    private fun viewModelWithGrant(): ChatViewModel =
        restoredViewModel().also { grantAlwaysAllow(it.approvalManager) }

    private fun restoredViewModel(): ChatViewModel =
        ChatViewModel { null }
            .also { it.initializeStorage(context, TEST_PROJECT_KEY) }
            .also { viewModels += it }

    /** Asks for [tool] and answers "Always Allow", then checks the grant took. */
    private fun grantAlwaysAllow(manager: ToolApprovalManager) {
        runBlocking {
            val pending = async(Dispatchers.Default) { manager.ensureApproved(tool.toolName, tool, emptyMap()) }
            withTimeout(5_000) {
                while (!manager.hasPendingApproval()) delay(5)
                manager.submitApproval(ApprovalResult.APPROVED_FOR_SESSION)
                assertTrue(pending.await().approved)
            }
        }
        assertFalse("the grant must hold before the chat changes", asksAgain(manager))
    }

    /**
     * Calls [tool] once more and reports whether a dialog appeared; one that does is declined.
     * @return true when the user was asked, false when a grant answered for them.
     */
    private fun asksAgain(manager: ToolApprovalManager): Boolean = runBlocking {
        val pending = async(Dispatchers.Default) { manager.ensureApproved(tool.toolName, tool, emptyMap()) }
        withTimeout(5_000) {
            while (!pending.isCompleted && !manager.hasPendingApproval()) delay(5)
            val asked = manager.hasPendingApproval()
            if (asked) manager.submitApproval(ApprovalResult.DENIED)
            pending.await()
            asked
        }
    }
}
