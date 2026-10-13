package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.HostServiceCall.Companion.BUILD_STAGE
import com.itsaky.androidide.plugins.services.IdeBuildService

/**
 * Handler for reading the current build output.
 */
class ReadBuildOutputHandler(
    pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "read_build_output"
    override val requiresApproval = false

    private val buildCall = HostServiceCall(
        pluginContext, toolName, IdeBuildService::class.java, BUILD_STAGE, "Build",
        failureMessage = { "Error reading build output" },
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return buildCall.run { buildService ->
            val output = buildService.getBuildOutput()
            if (output.isNullOrBlank()) {
                AgentTrace.detail(BUILD_STAGE, "$toolName chars=0 (host returned nothing)")
                ToolResult.success(
                    message = "No build output available",
                    data = "(No recent build output)"
                )
            } else {
                val window = windowFor(output)
                AgentTrace.detail(
                    BUILD_STAGE,
                    "$toolName chars=${window.text.length} " +
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
        }
    }

    companion object {
        /** Maximum characters of build log handed to the model. */
        internal const val MAX_OUTPUT_CHARS = 8000

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
                text = if (dropped) "$TRUNCATION_MARKER\n$text" else text,
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
