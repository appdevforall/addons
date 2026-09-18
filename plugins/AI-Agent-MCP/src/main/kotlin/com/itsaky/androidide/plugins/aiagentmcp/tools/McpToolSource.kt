package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpSession
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpErrorFormatter
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "$LOG_PREFIX.McpToolSource"

/**
 * Contributes the tools of every configured MCP server to the IDE's AI agent.
 *
 * Only tools the user switched on are offered: one popular GitHub server advertises around ninety,
 * which would exhaust a phone-sized context window on its own, so the toggle defaults to off and
 * this class never widens it.
 */
class McpToolSource : ToolSourceRegistry.ToolSource {

    /** Calls in flight, so a stopped agent run can drop the socket instead of waiting it out. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<*>>()

    /**
     * The session each in-flight call is using.
     *
     * Per call rather than "every session there is": two tools can be in flight against different
     * servers at once, and cancelling both would report the survivor to the model as a transport
     * failure for a call nobody cancelled.
     */
    private val liveSessions = ConcurrentHashMap<String, McpSession>()

    /**
     * Remote calls run here rather than on the common pool: an MCP call blocks on a socket for as
     * long as the read timeout, and the common pool is sized for CPU work.
     */
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mcp-tool-call").apply { isDaemon = true }
    }

    override fun getProviderId(): String = McpPlugin.PLUGIN_ID

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun listTools(): List<ToolSourceRegistry.ToolSpec> = exposedTools().map { exposed ->
        Spec(
            name = exposed.name,
            description = exposed.tool.description,
            parametersSchema = exposed.tool.inputSchema,
        )
    }

    override fun invoke(
        invocation: ToolSourceRegistry.ToolInvocation,
    ): CompletableFuture<ToolSourceRegistry.ToolOutcome> {
        val target = resolve(invocation.toolName)
            ?: return CompletableFuture.completedFuture(
                Outcome(false, "", string(R.string.mcp_error_tool_gone, invocation.toolName))
            )

        val future = CompletableFuture.supplyAsync(
            { runTool(invocation.callId, target.first, target.second, invocation.arguments) },
            executor,
        )
        inFlight[invocation.callId] = future
        return future.whenComplete { _, _ -> inFlight.remove(invocation.callId) }
    }

    override fun cancel(callId: String) {
        inFlight.remove(callId)?.cancel(true)
        // The worker is blocked on a socket read, where an interrupt does nothing; dropping the
        // connection is what actually ends it. Only this call's, so a concurrent call against
        // another server keeps its socket.
        liveSessions.remove(callId)?.let { runCatching { it.cancel() } }
    }

    /** Ends the executor, for the plugin shutting down. */
    fun close() {
        executor.shutdownNow()
        inFlight.clear()
        liveSessions.clear()
    }

    /**
     * Runs one remote tool.
     * @param callId the agent's id for this call, so [cancel] can reach the session it is using.
     * @param server the server that owns it.
     * @param tool the tool, as the server described it.
     * @param arguments the agent's arguments.
     * @return the outcome; a failure carries one sentence, never the server's raw body.
     */
    private fun runTool(
        callId: String,
        server: McpServer,
        tool: McpTool,
        arguments: Map<String, Any?>,
    ): ToolSourceRegistry.ToolOutcome = try {
        val session = McpConnections.session(server)
        liveSessions[callId] = session
        val result = try {
            session.callTool(tool.name, arguments)
        } finally {
            liveSessions.remove(callId)
        }
        Outcome(result.success, result.text, result.errorMessage)
    } catch (e: Throwable) {
        Log.w(TAG, "Tool '${tool.name}' on '${server.name}' failed", e)
        val context = McpPlugin.getContext()?.androidContext
        Outcome(false, "", McpErrorFormatter.format(context, server.name, e))
    }

    /**
     * Resolves a string against this plugin's own resources.
     * @param resId the string resource.
     * @param args format arguments.
     * @return the resolved string, or the raw arguments when the plugin context is already gone.
     */
    private fun string(resId: Int, vararg args: Any?): String =
        McpPlugin.getContext()?.androidContext?.getString(resId, *args)
            ?: args.joinToString(" ")

    /**
     * Every enabled tool of every enabled server, paired with its server.
     * @return the pairs, in configured order.
     */
    private fun enabledTools(): List<Pair<McpServer, McpTool>> =
        McpServerStore.servers()
            .filter { it.enabled }
            .flatMap { server ->
                McpToolCatalog.tools(server.id)
                    .filter { it.name in server.enabledTools }
                    .map { server to it }
            }

    /**
     * Finds the server and tool behind an exposed name.
     * @param exposedName the name this source published.
     * @return the pair, or null when the tool has since been switched off or removed.
     */
    private fun resolve(exposedName: String): Pair<McpServer, McpTool>? =
        exposedTools().firstOrNull { it.name == exposedName }?.let { it.server to it.tool }

    /**
     * Every enabled tool paired with the name this source publishes it under.
     *
     * The one place a name is decided, so listing and resolving cannot disagree: both walk the same
     * servers in the same order and hand a truncation collision to the same numbering rule.
     *
     * @return the tools this source offers, in configured order.
     */
    private fun exposedTools(): List<Exposed> {
        val exposed = mutableListOf<Exposed>()
        val taken = mutableSetOf<String>()

        for ((server, tool) in enabledTools()) {
            val base = McpToolText.exposedName(server.name, tool.name)
            if (base == null) {
                Log.w(TAG, "Dropping a tool from '${server.name}': its name has nothing usable")
                continue
            }
            val name = McpToolText.disambiguate(base, taken)
            if (name == null) {
                Log.w(TAG, "Dropping '$base' from '${server.name}': that name is already taken")
                continue
            }
            if (name != base) {
                Log.i(TAG, "'${tool.name}' on '${server.name}' shares a name; offering it as '$name'")
            }
            taken += name
            exposed += Exposed(server, tool, name)
        }
        return exposed
    }

    /**
     * One tool and the name it is published under.
     * @property server the server that owns it.
     * @property tool the tool, as the server described it.
     * @property name the name the agent and the model see.
     */
    private class Exposed(val server: McpServer, val tool: McpTool, val name: String)

    /**
     * One tool, as the host contract describes it.
     *
     * Every remote tool asks for approval and declares itself non-read-only: this plugin cannot
     * know what a server's tool does, and guessing on the permissive side is the one guess that
     * cannot be undone. The contract is a Java interface, so its accessors are implemented as
     * functions: Kotlin synthesises properties for *reading* a Java getter, never for overriding one.
     */
    private class Spec(
        private val name: String,
        private val description: String,
        private val parametersSchema: Map<String, Any>,
    ) : ToolSourceRegistry.ToolSpec {
        override fun getName(): String = name
        override fun getDescription(): String = description
        override fun getParametersSchema(): Map<String, Any> = parametersSchema
        override fun requiresApproval(): Boolean = true
        override fun isReadOnly(): Boolean = false
    }

    /** One outcome, as the host contract describes it. */
    private class Outcome(
        private val success: Boolean,
        private val output: String,
        private val errorMessage: String? = null,
    ) : ToolSourceRegistry.ToolOutcome {
        override fun isSuccess(): Boolean = success
        override fun getOutput(): String = output
        override fun getErrorMessage(): String? = errorMessage
    }

    private companion object {
        /** Shown wherever tool provenance is surfaced. */
        const val DISPLAY_NAME = "MCP servers"
    }
}
