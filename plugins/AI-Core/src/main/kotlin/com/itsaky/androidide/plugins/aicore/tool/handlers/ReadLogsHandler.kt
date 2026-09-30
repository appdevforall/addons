package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.services.IdeLogService
import com.itsaky.androidide.plugins.services.LogLevel
import com.itsaky.androidide.plugins.services.LogQuery
import com.itsaky.androidide.plugins.services.LogSource
import kotlinx.coroutines.CancellationException

/**
 * Handler for reading one of the IDE's log tabs: App Logs (`read_app_logs`, the user's running
 * app) or IDE Logs (`read_ide_logs`, CoGo and its plugins). Read-only, so it never asks.
 */
class ReadLogsHandler(
    private val pluginContext: PluginContext,
    private val source: LogSource,
) : ToolHandler {
    private val tab = tabFor(source)

    override val toolName = tab.toolName
    override val description = tab.description
    override val requiresApproval = false
    override val parametersSchema = ToolSchema.objectOf(
        "min_level" to ToolSchema.string(
            "Lowest level to include: verbose, debug, info, warning or error. Defaults to all levels."
        ),
        "filter" to ToolSchema.string(
            "Case-insensitive text a line must contain, matched against its tag and message."
        ),
    )

    private val label = tab.label

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val minLevel = args["min_level"]?.toString()?.trim().orEmpty()
        val levels = if (minLevel.isEmpty()) {
            emptySet()
        } else {
            levelsFrom(minLevel) ?: return ToolResult.failure(
                "Unknown min_level '$minLevel'",
                "Use one of: verbose, debug, info, warning, error."
            )
        }
        val filter = args["filter"]?.toString()?.trim().orEmpty()

        return try {
            val logService = pluginContext.services.get(IdeLogService::class.java)
            if (logService == null) {
                // A clear answer rather than a failure: the agent can say so and carry on.
                AgentTrace.refusal("LOGS", "$toolName rejected", "IdeLogService not available")
                return ToolResult.success(
                    message = "$label not available",
                    data = "(This version of Code on the Go does not let plugins read $label.)"
                )
            }

            val result = logService.readLogs(
                source,
                LogQuery(levels = levels, text = filter, maxLines = LogQuery.MAX_LINES),
            )
            if (result.entries.isEmpty()) {
                AgentTrace.detail("LOGS", "$toolName lines=0 levels=$levels filter=${filter.isNotEmpty()}")
                ToolResult.success(message = "No $label", data = emptyMessage(filter, levels))
            } else {
                val window = LogWindowCalculator.windowFor(result.entries, result.truncated)
                AgentTrace.detail(
                    "LOGS",
                    "$toolName lines=${result.entries.size} chars=${window.text.length} " +
                        "anchoredOnError=${window.anchoredOnError} hostTruncated=${result.truncated}"
                )
                ToolResult.success(
                    message = if (window.anchoredOnError) {
                        "$label from the newest error (${window.text.length} characters)"
                    } else {
                        "$label (last ${window.text.length} characters)"
                    },
                    data = window.text
                )
            }
        } catch (ce: CancellationException) {
            // An Exception on the JVM, so the catch below would report Stop as a read failure.
            throw ce
        } catch (e: Exception) {
            AgentTrace.refusal("LOGS", "$toolName failed", e.toString())
            pluginContext.logger.error("$toolName failed", e)
            ToolResult.failure(
                "Error reading $label",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }

    private fun emptyMessage(filter: String, levels: Set<LogLevel>): String =
        if (filter.isEmpty() && levels.isEmpty()) {
            tab.emptyLog
        } else {
            "(No $label lines match that level or filter. Try again without them.)"
        }

    /** How the tool for one log tab is named and described, to the model and in messages. */
    private class LogTab(
        val toolName: String,
        val description: String,
        val label: String,
        val emptyLog: String,
    )

    companion object {
        private val ORDERED_LEVELS = listOf(
            LogLevel.VERBOSE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARNING, LogLevel.ERROR,
        )

        /**
         * The levels at or above [name], which the host takes as an exact set.
         * @param name a level name, case-insensitive; `warn` and `e`-style initials are accepted.
         * @return the levels, or null when [name] is not a level.
         */
        internal fun levelsFrom(name: String): Set<LogLevel>? {
            val level = when (name.lowercase()) {
                "verbose", "v" -> LogLevel.VERBOSE
                "debug", "d" -> LogLevel.DEBUG
                "info", "i" -> LogLevel.INFO
                "warning", "warn", "w" -> LogLevel.WARNING
                "error", "e" -> LogLevel.ERROR
                else -> return null
            }
            return ORDERED_LEVELS.subList(ORDERED_LEVELS.indexOf(level), ORDERED_LEVELS.size).toSet()
        }

        private fun tabFor(source: LogSource): LogTab = when (source) {
            LogSource.APP -> LogTab(
                toolName = "read_app_logs",
                description = "Read the App Logs of the user's running app, from the newest crash or error",
                label = "App Logs",
                emptyLog = "(App Logs are empty. Run the app with run_app first, then read them again.)",
            )
            LogSource.IDE -> LogTab(
                toolName = "read_ide_logs",
                description = "Read the IDE Logs of Code on the Go and its plugins, from the newest error",
                label = "IDE Logs",
                emptyLog = "(IDE Logs are empty.)",
            )
        }
    }
}
