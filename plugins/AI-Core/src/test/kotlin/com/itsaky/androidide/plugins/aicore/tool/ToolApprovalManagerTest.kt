package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolApprovalManager] — in particular the "Correct" decision, which is not a
 * plain denial (the instruction has to reach the model), and the rule that a destructive tool
 * is never blanket-approved for the session.
 */
class ToolApprovalManagerTest {

    private val approvableHandler = object : ToolHandler {
        override val toolName = "edit_file"
        override val description = "fake edit"
        override val requiresApproval = true
        override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
    }

    /**
     * Runs [ensureApproved] concurrently with the user's decision, which can only be
     * submitted once the request is actually pending.
     */
    private fun decideWith(
        manager: ToolApprovalManager,
        result: ApprovalResult,
        correction: String? = null,
        toolName: String = "edit_file",
    ): ApprovalResponse = runBlocking {
        // Dispatchers.Default: the wait below must not sit on the thread it needs to progress on.
        val pending = async(Dispatchers.Default) {
            manager.ensureApproved(toolName, approvableHandler, mapOf("file_path" to "Main.kt"))
        }
        withTimeout(5_000) {
            while (!manager.hasPendingApproval()) delay(5)
            manager.submitApproval(result, correction)
            pending.await()
        }
    }

    @Test
    fun givenAToolDeclaringNoApproval_whenApprovalIsRequested_thenItRunsWithNoDialog() = runBlocking {
        // The handler's own declaration is the whole gate now; no name list is consulted.
        val manager = ToolApprovalManager()
        val readOnly = object : ToolHandler {
            override val toolName = "read_file"
            override val description = "fake read"
            override val requiresApproval = false
            override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
        }

        val response = manager.ensureApproved(readOnly.toolName, readOnly, mapOf("file_path" to "A.kt"))

        assertTrue(response.approved)
        assertFalse("no dialog may be raised for a read", manager.hasPendingApproval())
    }

    @Test
    fun givenAToolDeclaringApproval_whenItIsNamedLikeAFormerlyExemptTool_thenTheUserIsStillAsked() {
        // `gradle_sync` and `generate_from_template` were exempted by name ahead of what their
        // handlers asked for, which is how the two tools that act on the project ran unprompted.
        val manager = ToolApprovalManager()
        val syncing = object : ToolHandler {
            override val toolName = "gradle_sync"
            override val description = "fake sync"
            override val requiresApproval = true
            override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
        }

        val response = runBlocking {
            val pending = async(Dispatchers.Default) {
                manager.ensureApproved(syncing.toolName, syncing, emptyMap())
            }
            withTimeout(5_000) {
                while (!manager.hasPendingApproval()) delay(5)
                manager.submitApproval(ApprovalResult.DENIED)
                pending.await()
            }
        }

        assertFalse("the name may no longer skip the dialog", response.approved)
    }

    @Test
    fun givenACorrection_whenApprovalIsRequested_thenItIsNotApprovedAndTheInstructionIsRelayed() {
        val manager = ToolApprovalManager()

        val response = decideWith(manager, ApprovalResult.CORRECTED, "keep the original method name")

        assertFalse("a correction must not run the tool", response.approved)
        assertTrue(
            "the user's words must reach the model: ${response.denialMessage}",
            response.denialMessage?.contains("keep the original method name") == true
        )
    }

    @Test
    fun givenACorrectionWithNoText_whenApprovalIsRequested_thenItStillReadsAsARevisionRequest() {
        val manager = ToolApprovalManager()

        val response = decideWith(manager, ApprovalResult.CORRECTED, "   ")

        assertFalse(response.approved)
        assertTrue(response.denialMessage?.contains("revise") == true)
    }

    @Test
    fun givenSessionApprovalOfAnEdit_whenAskedAgain_thenTheUserIsAskedAgain() {
        // Keyed by tool name alone, so honouring it would grant unreviewed writes to every file.
        val manager = ToolApprovalManager()

        val first = decideWith(manager, ApprovalResult.APPROVED_FOR_SESSION)
        assertTrue(first.approved)

        val second = decideWith(manager, ApprovalResult.DENIED)
        assertFalse("edit_file must be re-confirmed every time", second.approved)
    }

    @Test
    fun givenSessionApprovalOfANonDestructiveTool_whenAskedAgain_thenItIsRemembered() = runBlocking {
        val manager = ToolApprovalManager()
        val handler = object : ToolHandler {
            override val toolName = "add_dependency"
            override val description = "fake"
            override val requiresApproval = true
            override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
        }

        val first = decideWith(manager, ApprovalResult.APPROVED_FOR_SESSION, toolName = "add_dependency")
        assertTrue(first.approved)

        // No dialog this time: the session grant answers immediately.
        val second = manager.ensureApproved("add_dependency", handler, emptyMap())
        assertTrue(second.approved)
    }

    @Test
    fun givenTwoConcurrentRequests_whenBothAreAnswered_thenNeitherCallerIsStranded() = runBlocking {
        // One slot and one dialog: a second request used to overwrite it and strand the first.
        val manager = ToolApprovalManager()

        val first = async(Dispatchers.Default) {
            manager.ensureApproved("edit_file", approvableHandler, mapOf("file_path" to "A.kt"))
        }
        withTimeout(5_000) { while (!manager.hasPendingApproval()) delay(5) }

        val second = async(Dispatchers.Default) {
            manager.ensureApproved("edit_file", approvableHandler, mapOf("file_path" to "B.kt"))
        }

        withTimeout(5_000) {
            manager.submitApproval(ApprovalResult.APPROVED_ONCE)
            val firstResponse = first.await()

            // The second dialog can only appear now that the first has been answered.
            while (!manager.hasPendingApproval()) delay(5)
            manager.submitApproval(ApprovalResult.DENIED)
            val secondResponse = second.await()

            assertTrue("the first caller must get its own approval", firstResponse.approved)
            assertFalse("the second caller must get its own denial", secondResponse.approved)
        }
    }

    @Test
    fun givenACancelledRun_whenApprovalWasPending_thenNoStaleRequestKeepsTheDialogUp() = runBlocking {
        // Cancelling at the await must still clear the request, or the dialog stays on screen.
        val manager = ToolApprovalManager()

        val pending = async(Dispatchers.Default) {
            manager.ensureApproved("edit_file", approvableHandler, mapOf("file_path" to "A.kt"))
        }
        withTimeout(5_000) { while (!manager.hasPendingApproval()) delay(5) }

        pending.cancel()
        withTimeout(5_000) { while (manager.hasPendingApproval()) delay(5) }

        assertFalse("no request may outlive the cancelled call", manager.hasPendingApproval())
    }

    @Test
    fun givenAContributedTool_whenApprovedForTheSession_thenTheUserIsStillAskedAgain() {
        // A contributed tool runs outside PathGuard and cannot be enumerated in a name list, so
        // "Always Allow" has to be refused by the handler's own declaration.
        val manager = ToolApprovalManager()
        val contributed = object : ToolHandler {
            override val toolName = "aiagentmcp_create_issue"
            override val description = "creates an issue on a remote server"
            override val requiresApproval = true
            override val allowsSessionApproval = false
            override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
        }

        val first = runBlocking {
            val pending = async(Dispatchers.Default) {
                manager.ensureApproved(contributed.toolName, contributed, emptyMap())
            }
            withTimeout(5_000) {
                while (!manager.hasPendingApproval()) delay(5)
                manager.submitApproval(ApprovalResult.APPROVED_FOR_SESSION)
                pending.await()
            }
        }
        assertTrue(first.approved)

        val second = runBlocking {
            val pending = async(Dispatchers.Default) {
                manager.ensureApproved(contributed.toolName, contributed, emptyMap())
            }
            withTimeout(5_000) {
                while (!manager.hasPendingApproval()) delay(5)
                manager.submitApproval(ApprovalResult.DENIED)
                pending.await()
            }
        }
        assertFalse("a contributed tool must be prompt-every-time", second.approved)

        // The other downgrade, on the same manager: both are consulted only after the gate has
        // returned, so simplifying the gate to the handler's declaration must not have moved them.
        assertTrue(decideWith(manager, ApprovalResult.APPROVED_FOR_SESSION).approved)
        assertFalse(
            "edit_file must still be re-confirmed every time",
            decideWith(manager, ApprovalResult.DENIED).approved,
        )
    }

    @Test
    fun givenADenial_whenApprovalIsRequested_thenItReportsTheDenial() {
        val manager = ToolApprovalManager()

        val response = decideWith(manager, ApprovalResult.DENIED)

        assertFalse(response.approved)
        assertTrue(response.denialMessage?.contains("denied") == true)
    }
}
