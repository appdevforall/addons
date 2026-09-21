package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.services.IdeProjectManipulationService

private const val TAG = "$LOG_PREFIX.AddDependencyHandler"

/** Where a call that names no build file adds the dependency. */
private const val DEFAULT_BUILD_FILE = "app/build.gradle.kts"

/**
 * Handler for adding dependencies to the project build file.
 */
class AddDependencyHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "add_dependency"
    override val parametersSchema = ToolSchema.objectOf(
        "dependency" to ToolSchema.string(
            "Maven coordinate to add, as group:artifact:version."
        ),
        "build_file" to ToolSchema.string(
            "Project-relative build file to add it to. Defaults to the app module's."
        ),
        required = listOf("dependency"),
    )
    override val description = "Add a Maven dependency to the project build file"
    override val requiresApproval = true
    override val mutatesProject = true

    override val pathArgs = listOf("build_file")
    override val pathDefaults = mapOf("build_file" to DEFAULT_BUILD_FILE)

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val dependency = args["dependency"]?.toString()?.trim()
        if (dependency.isNullOrBlank()) {
            return ToolResult.failure("dependency is required (e.g., 'com.squareup.retrofit2:retrofit:2.9.0')")
        }

        val buildFile = args["build_file"]?.toString()?.trim() ?: DEFAULT_BUILD_FILE

        Log.d(TAG, "Adding dependency: $dependency to $buildFile")

        return try {
            val service = pluginContext.services.get(IdeProjectManipulationService::class.java)
            if (service == null) {
                Log.w(TAG, "IdeProjectManipulationService not available")
                return ToolResult.failure("Project manipulation service not available")
            }

            val success = service.addDependency(dependency, buildFile)
            if (success) {
                Log.d(TAG, "Dependency added successfully: $dependency")
                ToolResult.success(
                    message = "Added dependency: $dependency",
                    data = "Dependency added to $buildFile. Run gradle_sync to reload."
                )
            } else {
                Log.w(TAG, "Failed to add dependency: $dependency")
                ToolResult.failure(
                    "Failed to add dependency",
                    "Could not add $dependency to $buildFile. Check the build file format."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding dependency", e)
            ToolResult.failure(
                "Error adding dependency",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
