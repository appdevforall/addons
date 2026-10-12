package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolOutputBudget
import com.itsaky.androidide.plugins.services.LogSource

/**
 * The agent's own tool catalogue, in one place rather than inline in the chat: what the agent can
 * do is not a view model's business, and the test that checks every built-in declares its own
 * approval has to read the same list the chat registers, not a copy of it.
 */
object BuiltInToolHandlers {

    /**
     * Builds one handler per built-in tool, the web tools included.
     * @param context the plugin context each handler works through.
     * @param webSearch runs one web search through the active backend.
     * @param budget the per-result cap the agent loop applies, which paged and windowed reads fit.
     * @return the handlers, read-only tools first.
     */
    fun create(
        context: PluginContext,
        webSearch: suspend (String) -> ToolResult = { ToolResult.failure("Web search is unavailable") },
        budget: ToolOutputBudget = ToolOutputBudget.DEFAULT,
    ): List<ToolHandler> = listOf(
        // Read-only tools
        ReadFileHandler(context, budget),
        ListFilesHandler(context),
        SearchProjectHandler(context),
        OpenFileHandler(context),
        ReadBuildOutputHandler(context, budget),
    ) + (if (hostHasLogApi()) LogToolHandlers.create(context, budget) else emptyList()) + listOf(
        // Write tools
        CreateFileHandler(context),
        UpdateFileHandler(context),
        EditFileHandler(context),
        AddDependencyHandler(context),
        // Build tools
        RunAppHandler(context, hasLogTools = hostHasLogApi()),
        GradleSyncHandler(context),
    ) + (if (hostHasGradleTaskApi()) listOf(RunGradleTaskHandler(context, budget)) else emptyList()) +
        (if (hostHasTaskListApi()) listOf(ListGradleTasksHandler(context, budget)) else emptyList()) + listOf(
        // Template tool
        GenerateFromTemplateHandler(context),
        // Web tools
        WebSearchHandler(webSearch),
        FetchUrlHandler(),
    )

    // A string, not a class literal: hosts before ADFA-6267 lack the class, and the literal would throw.
    private fun hostHasLogApi(): Boolean =
        runCatching { Class.forName("com.itsaky.androidide.plugins.services.IdeLogService") }.isSuccess

    // Same reason: a 26.41 host from before ADFA-6373 lacks executeTasks(tasks, arguments).
    private fun hostHasGradleTaskApi(): Boolean =
        runCatching { Class.forName("com.itsaky.androidide.plugins.services.GradleTaskResult") }.isSuccess

    // Same reason: a host from before IdeBuildService.getTasks lacks the class, and the call would throw.
    private fun hostHasTaskListApi(): Boolean =
        runCatching { Class.forName("com.itsaky.androidide.plugins.services.GradleTaskInfo") }.isSuccess
}

/** The log tools, kept apart so [LogSource] is only touched on a host that has it. */
private object LogToolHandlers {
    fun create(context: PluginContext, budget: ToolOutputBudget): List<ToolHandler> = listOf(
        ReadLogsHandler(context, LogSource.APP, budget),
        ReadLogsHandler(context, LogSource.IDE, budget),
    )
}
