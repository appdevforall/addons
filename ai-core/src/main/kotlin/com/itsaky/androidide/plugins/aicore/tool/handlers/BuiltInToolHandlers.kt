package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler

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
    )
}
