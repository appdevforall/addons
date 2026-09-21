package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant behind the approval gate: whether the user is asked is decided by the handler's own
 * `requiresApproval`, so every built-in that acts on the project has to declare it. A name list
 * beside the gate used to exempt two of them, and nothing failed when it drifted out of step.
 */
class BuiltInHandlerApprovalTest {

    private companion object {
        /** Built-ins that change the project, install an app, or start a build. */
        val MUTATING_TOOLS = setOf(
            "create_file",
            "update_file",
            "edit_file",
            "add_dependency",
            "run_app",
            "gradle_sync",
            "generate_from_template",
        )

        /** The subset that rewrites a project file, which is what the progress guard counts. */
        val WRITING_TOOLS = setOf("create_file", "update_file", "edit_file", "add_dependency")

        /**
         * Built-ins that only read. `open_file` belongs here despite opening an editor tab: it
         * shows the user a file rather than changing one, and asking to read is noise.
         */
        val READ_ONLY_TOOLS = setOf(
            "read_file",
            "list_files",
            "search_project",
            "open_file",
            "read_build_output",
        )
    }

    private val context = mockk<PluginContext>(relaxed = true)

    /** The catalogue the chat registers, not a copy of it, so a new built-in lands here too. */
    private val builtIns: List<ToolHandler> = BuiltInToolHandlers.create(context)

    @Test
    fun givenEveryBuiltIn_whenItIsClassified_thenTheTwoSetsAccountForAllOfThem() {
        // So a new handler cannot be added without someone deciding which side it is on.
        assertEquals(
            "classify the new built-in below before shipping it",
            MUTATING_TOOLS + READ_ONLY_TOOLS,
            builtIns.mapTo(mutableSetOf()) { it.toolName },
        )
    }

    @Test
    fun givenABuiltInThatActsOnTheProject_whenItIsRegistered_thenItRequiresApproval() {
        val unguarded = builtIns.filter { it.toolName in MUTATING_TOOLS && !it.requiresApproval }

        assertTrue(
            "these run without asking the user: ${unguarded.map { it.toolName }}",
            unguarded.isEmpty(),
        )
    }

    @Test
    fun givenABuiltInThatOnlyReads_whenItIsRegistered_thenItNeedsNoApproval() {
        // The other half of the rule: a dialog on every read is what trains the user to tap through.
        val overGuarded = builtIns.filter { it.toolName in READ_ONLY_TOOLS && it.requiresApproval }

        assertTrue(
            "these ask the user for a read: ${overGuarded.map { it.toolName }}",
            overGuarded.isEmpty(),
        )
    }

    @Test
    fun givenEveryBuiltIn_whenItIsRegistered_thenOnlyTheWritersCountAsChangingTheProject() {
        // A build tool asks first without writing, so approval is not the signal the guard reads.
        assertEquals(
            WRITING_TOOLS,
            builtIns.filter { it.mutatesProject }.mapTo(mutableSetOf()) { it.toolName },
        )
    }
}
