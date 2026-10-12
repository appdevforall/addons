package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.services.GradleTaskInfo
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.CancellationException

/**
 * Handler for listing the project's Gradle tasks with their group and description, from the IDE's
 * last sync, so the agent picks a task that exists before it calls run_gradle_task.
 */
class ListGradleTasksHandler(
    private val pluginContext: PluginContext,
) : ToolHandler {
    override val toolName = "list_gradle_tasks"
    override val requiresApproval = false

    override val parametersSchema = ToolSchema.objectOf(
        "filter" to ToolSchema.string(),
    )

    override val argAliases = mapOf(
        "query" to "filter",
        "search" to "filter",
        "module" to "filter",
        "group" to "filter",
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filter = (args["filter"] as? String)?.trim().orEmpty()
        return try {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                AgentTrace.refusal("BUILD", "$toolName rejected", "IdeBuildService not available")
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available."
                )
            }
            val tasks = buildService.getTasks()
            AgentTrace.detail("BUILD", "$toolName filter='$filter' hostTasks=${tasks.size}")
            resultFor(tasks, filter)
        } catch (ce: CancellationException) {
            // An Exception on the JVM, so the catch below would report Stop as a listing failure.
            throw ce
        } catch (e: Exception) {
            AgentTrace.refusal("BUILD", "$toolName failed", e.toString())
            pluginContext.logger.error("$toolName failed", e)
            ToolResult.failure(
                "Error listing Gradle tasks",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }

    companion object {
        /** Maximum characters of the whole result, message included: the prompt cuts a longer one. */
        internal const val MAX_LIST_CHARS = LogWindowCalculator.MAX_OUTPUT_CHARS

        private const val NOT_SYNCED =
            "The IDE has no task list: the project is not open or has not synced. Call gradle_sync " +
                "and then list the tasks again."

        private const val NO_DESCRIPTIONS =
            "\n\n[Descriptions left out to fit every task. Pass a filter to see them.]"

        private const val CUT_SHORT =
            "\n[List cut short. Pass a filter such as a module (\":app\") or a group " +
                "(\"verification\") to see the rest.]"

        /**
         * The listing for [tasks]. Without [filter] it shows grouped tasks and described ones, so a
         * user's ungrouped task shows; a filter searches every task by path, group and description.
         */
        internal fun resultFor(tasks: List<GradleTaskInfo>, filter: String): ToolResult {
            if (tasks.isEmpty()) return ToolResult.failure("No Gradle tasks", NOT_SYNCED)

            val shown = if (filter.isEmpty()) {
                // Plugin-internal tasks such as compileDebugKotlin carry neither; a user's task has one.
                tasks.filter { it.group != null || it.description != null }
            } else {
                tasks.filter { it.matches(filter) }
            }
            if (shown.isEmpty()) {
                return ToolResult.success(
                    message = "No Gradle task matches \"$filter\"",
                    data = "None of the ${tasks.size} tasks matches. Try a shorter filter, or omit " +
                        "it to see the main tasks. A task added since the last sync needs " +
                        "gradle_sync first."
                )
            }

            val hidden = tasks.size - shown.size
            val note = if (filter.isEmpty() && hidden > 0) {
                "\n\n$hidden tasks with no group or description are not shown; pass a filter to " +
                    "search them too."
            } else {
                ""
            }
            val message = if (filter.isEmpty()) {
                "${shown.size} Gradle tasks"
            } else {
                "${shown.size} Gradle tasks matching \"$filter\""
            }
            // The prompt sends the message, a newline and the data, so the listing gets what is left.
            val room = MAX_LIST_CHARS - message.length - 1 - note.length

            // Descriptions go before tasks do: a cut drops the last groups, the user's own among them.
            val full = render(shown, withDescriptions = true)
            val listing = if (full.length <= room) {
                full
            } else {
                render(shown, withDescriptions = false) + NO_DESCRIPTIONS
            }
            val text = if (listing.length > room) {
                listing.take(room - CUT_SHORT.length).substringBeforeLast('\n') + CUT_SHORT
            } else {
                listing
            }
            return ToolResult.success(message = message, data = "$text$note")
        }

        private fun GradleTaskInfo.matches(filter: String): Boolean =
            listOfNotNull(path, group, description).any { it.contains(filter, ignoreCase = true) }

        /** One block per group, "path — description" per task; ungrouped tasks come last. */
        private fun render(tasks: List<GradleTaskInfo>, withDescriptions: Boolean): String =
            tasks.groupBy { it.group ?: "other" }
                .entries
                .sortedWith(compareBy({ it.key == "other" }, { it.key }))
                .joinToString("\n\n") { (group, inGroup) ->
                    "$group:\n" + inGroup.joinToString("\n") { task ->
                        task.description?.takeIf { withDescriptions }?.let { "${task.path} — $it" } ?: task.path
                    }
                }
    }
}
