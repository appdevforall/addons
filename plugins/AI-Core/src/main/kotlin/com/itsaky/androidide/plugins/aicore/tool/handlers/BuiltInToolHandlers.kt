package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.LogSource

/**
 * The agent's own tool catalogue, in one place rather than inline in the chat: what the agent can
 * do is not a view model's business, and the test that checks every built-in declares its own
 * approval has to read the same list the chat registers, not a copy of it.
 */
object BuiltInToolHandlers {

    /**
     * Builds one handler per built-in tool.
     * @param context the plugin context each handler works through.
     * @return the handlers, read-only tools first.
     */
    fun create(context: PluginContext): List<ToolHandler> = listOf(
        // Read-only tools
        ReadFileHandler(context),
        ListFilesHandler(context),
        SearchProjectHandler(context),
        OpenFileHandler(context),
        ReadBuildOutputHandler(context),
    ) + (if (hostHasLogApi()) LogToolHandlers.create(context) else emptyList()) + listOf(
        // Write tools
        CreateFileHandler(context),
        UpdateFileHandler(context),
        EditFileHandler(context),
        AddDependencyHandler(context),
        // Build tools
        RunAppHandler(context, hasLogTools = hostHasLogApi()),
        GradleSyncHandler(context),
        // Template tool
        GenerateFromTemplateHandler(context),
    )

    // A string, not a class literal: hosts before ADFA-6267 lack the class, and the literal would throw.
    private fun hostHasLogApi(): Boolean =
        runCatching { Class.forName("com.itsaky.androidide.plugins.services.IdeLogService") }.isSuccess
}

/** The log tools, kept apart so [LogSource] is only touched on a host that has it. */
private object LogToolHandlers {
    fun create(context: PluginContext): List<ToolHandler> = listOf(
        ReadLogsHandler(context, LogSource.APP),
        ReadLogsHandler(context, LogSource.IDE),
    )
}
