package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.GradleSyncCallback
import com.itsaky.androidide.plugins.services.IdeBuildService
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.delay

private const val TAG = "$LOG_PREFIX.GradleSyncHandler"

/**
 * Handler for triggering Gradle project sync.
 */
class GradleSyncHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "gradle_sync"
    override val description = "Sync the Gradle project (reload dependencies and rebuild cache)"

    /**
     * Approved like the build tool it is, not like a read: a sync starts a real Gradle build, can
     * pull dependencies over a metered connection, and writes `.gradle/` and the Gradle cache.
     * [RunAppHandler], the same action one step further, already asks.
     */
    override val requiresApproval = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        Log.d(TAG, "Gradle sync requested")

        return try {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                Log.w(TAG, "IdeBuildService not available")
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available."
                )
            }

            // Track both success flag and output message
            val syncComplete = CompletableFuture<Pair<Boolean, String>>()

            Log.d(TAG, "Triggering gradle sync...")
            buildService.triggerGradleSync(object : GradleSyncCallback {
                override fun onComplete(success: Boolean, output: String) {
                    Log.d(TAG, "Sync completed: success=$success, output length=${output.length}")
                    val message = if (success) {
                        output.ifEmpty { "Gradle sync completed successfully" }
                    } else {
                        "Gradle sync failed:\n$output"
                    }
                    syncComplete.complete(Pair(success, message))
                }
            })

            // Wait for sync to complete (with timeout of 2 minutes)
            val (syncSuccess, result) = try {
                syncComplete.get(120_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Gradle sync timed out or failed: ${e.message}")
                Pair(false, "Gradle sync in progress or timed out (may take 1-2 minutes)")
            }

            // Return success or failure based on actual sync result
            if (syncSuccess) {
                ToolResult.success(
                    message = "Gradle sync completed",
                    data = result
                )
            } else {
                ToolResult.failure(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering gradle sync", e)
            ToolResult.failure(
                "Error triggering sync",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
