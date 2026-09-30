package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler

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
     * @return the handlers, read-only tools first.
     */
    fun create(
        context: PluginContext,
        webSearch: suspend (String) -> ToolResult = { ToolResult.failure("Web search is unavailable") },
    ): List<ToolHandler> = listOf(
        // Read-only tools
        ReadFileHandler(context),
        ListFilesHandler(context),
        SearchProjectHandler(context),
        OpenFileHandler(context),
        ReadBuildOutputHandler(context),
        // Write tools
        CreateFileHandler(context),
        UpdateFileHandler(context),
        EditFileHandler(context),
        AddDependencyHandler(context),
        // Build tools
        RunAppHandler(context),
        GradleSyncHandler(context),
        // Template tool
        GenerateFromTemplateHandler(context),
        // Web tools
        WebSearchHandler(webSearch),
        FetchUrlHandler(),
    )
}
