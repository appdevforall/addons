package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.CancellationException

/** The slice of a build log handed to the model, and whether it starts at the first error. */
internal data class OutputWindow(
    val text: String,
    val anchoredOnError: Boolean,
)

/**
 * Handler for reading the current build output.
 */
class ReadBuildOutputHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "read_build_output"
    override val description = "Read the current build output and status"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return try {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                AgentTrace.refusal(
                    "BUILD",
                    "read_build_output rejected",
                    "IdeBuildService not available"
                )
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available."
                )
            }

            val output = buildService.getBuildOutput()
            if (output.isNullOrBlank()) {
                AgentTrace.detail("BUILD", "read_build_output chars=0 (host returned nothing)")
                ToolResult.success(
                    message = "No build output available",
                    data = "(No recent build output)"
                )
            } else {
                val window = windowFor(output)
                AgentTrace.detail(
                    "BUILD",
                    "read_build_output chars=${window.text.length} " +
                        "anchoredOnError=${window.anchoredOnError} hostChars=${output.length}"
                )
                ToolResult.success(
                    message = if (window.anchoredOnError) {
                        "Build output from the first error (${window.text.length} characters)"
                    } else {
                        "Build output (last ${window.text.length} characters)"
                    },
                    data = window.text
                )
            }
        } catch (ce: CancellationException) {
            // An Exception on the JVM, so the catch below would report Stop as a read failure.
            throw ce
        } catch (e: Exception) {
            // The trace stream keeps the run readable; the host log keeps the stack trace, which
            // AgentTrace previews only in a debug build.
            AgentTrace.refusal("BUILD", "read_build_output failed", e.toString())
            pluginContext.logger.error("read_build_output failed", e)
            ToolResult.failure(
                "Error reading build output",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }

    companion object {
        /** Maximum characters of build log handed to the model. */
        internal const val MAX_OUTPUT_CHARS = 8000

        private const val TRUNCATION_MARKER = "...[truncated]...\n"

        // The host strips line timing prefixes; tolerated here so the window is right either way.
        private val LINE_PREFIX = Regex("""^(?:\[\d{2}:\d{2}:\d{2}\.\d{3}] )?(?:Δ\d+ms\s+)?""")

        /**
         * Markers that begin the part of a build log worth reading. Warnings are deliberately
         * absent: a build with 200 warnings and one error must anchor on the error.
         */
        private val ERROR_MARKERS = listOf(
            Regex("""^e: """),
            Regex("""(?:^|\s)error:"""),
            Regex("""^FAILURE: Build failed"""),
            Regex("""^\* What went wrong:"""),
            Regex("""^Execution failed for task"""),
            Regex("""^BUILD FAILED"""),
            Regex("""^Caused by:"""),
        )

        /**
         * Selects the slice of [output] the model needs. A plain tail is the wrong window for a
         * failed build — the tail is the summary and boilerplate, while the compiler errors sit
         * hundreds of lines earlier — so the window starts at the first error line when there is one.
         */
        internal fun windowFor(output: String): OutputWindow {
            val errorOffset = firstErrorOffset(output)
            val body = if (errorOffset == null) output else output.substring(errorOffset)
            val overflows = body.length > MAX_OUTPUT_CHARS
            // A cascade of hundreds of errors still ends at the summary, so overflow re-tails.
            val text = if (overflows) body.takeLast(MAX_OUTPUT_CHARS) else body
            val dropped = overflows || (errorOffset ?: 0) > 0
            return OutputWindow(
                text = if (dropped) TRUNCATION_MARKER + text else text,
                anchoredOnError = errorOffset != null && !overflows,
            )
        }

        /** Character offset of the first line that looks like a compiler or Gradle failure. */
        private fun firstErrorOffset(output: String): Int? {
            var lineStart = 0
            while (true) {
                val newline = output.indexOf('\n', lineStart)
                val lineEnd = if (newline == -1) output.length else newline
                if (isErrorLine(output.substring(lineStart, lineEnd))) return lineStart
                if (newline == -1) return null
                lineStart = newline + 1
            }
        }

        private fun isErrorLine(line: String): Boolean {
            val body = LINE_PREFIX.replaceFirst(line, "")
            return ERROR_MARKERS.any { it.containsMatchIn(body) }
        }
    }
}
