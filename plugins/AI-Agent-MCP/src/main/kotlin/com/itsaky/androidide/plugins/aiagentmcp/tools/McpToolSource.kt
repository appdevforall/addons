package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpSession
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.debugger.McpServers
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpErrorFormatter
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.services.CapabilityStatus
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import java.io.InterruptedIOException
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
class McpToolSource :
    ToolSourceRegistry.StatusReportingToolSource,
    ToolSourceRegistry.GroupedToolSource {

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

    /** Calls whose worker is running, so [cancel] knows whether a flag will ever be read. */
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Running calls that were cancelled. The session drop in [cancel] reaches only a request in
     * flight; one landing between the two attempts of the endpoint retry finds no session, so the
     * retry reads this before it sends anything.
     */
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
            description = exposed.description,
            parametersSchema = exposed.tool.inputSchema,
            preApproved = exposed.server.automatic && exposed.tool.readOnlyHint,
        )
    }

    /**
     * One group per enabled server, so the agent shows each server as its own tag. A server that
     * is enabled but unreachable offers no tools and keeps its group, marked degraded.
     */
    override fun getToolGroups(): List<ToolSourceRegistry.ToolGroup> {
        val namesByServer = exposedTools().groupBy({ it.server.id }, { it.name })
        return McpServers.all().filter { it.enabled }.map { server ->
            Group(
                id = server.id,
                displayName = server.name,
                toolNames = namesByServer[server.id].orEmpty(),
                health = McpServerHealth.of(server.id),
            )
        }
    }

    /** The worst of the enabled servers' states: one broken server is worth flagging on the whole. */
    override fun getStatus(): CapabilityStatus {
        val states = enabledHealth().map { it?.state ?: McpServerHealth.State.CONNECTING }
        return when {
            McpServerHealth.State.DEGRADED in states -> CapabilityStatus.DEGRADED
            McpServerHealth.State.CONNECTING in states -> CapabilityStatus.CONNECTING
            else -> CapabilityStatus.AVAILABLE
        }
    }

    /** The first broken server's reason; each group carries its own. */
    override fun getStatusMessage(): String? =
        enabledHealth().firstOrNull { it?.state == McpServerHealth.State.DEGRADED }?.message

    private fun enabledHealth(): List<McpServerHealth.Health?> =
        McpServers.all().filter { it.enabled }.map { McpServerHealth.of(it.id) }

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
        if (callId in running) cancelled += callId
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
        running += callId
        val result = McpToolCatalog.retry.call(server) { target ->
            // Cancelled between two attempts (or before the first): nothing is sent. Signalled as
            // an interrupted read, which is how a dropped connection already reports a cancel.
            if (callId in cancelled) throw InterruptedIOException("Tool call $callId was cancelled")
            val session = McpConnections.session(target)
            liveSessions[callId] = session
            try {
                session.callTool(tool.name, arguments)
            } finally {
                liveSessions.remove(callId)
            }
        }
        // A reply of any kind, a tool's own failure included, means the server is answering.
        McpServerHealth.available(server.id)
        Outcome(result.success, result.text, result.errorMessage)
    } catch (e: Throwable) {
        // Throwable, unlike the other host-facing catches in this plugin: this runs inside
        // CompletableFuture.supplyAsync, which catches every Throwable itself, so narrowing this
        // would not bring an OutOfMemoryError to the IDE's handler. It would only hand AI Core an
        // exceptionally completed future in place of the failure outcome it expects.
        Log.w(TAG, "Tool '${tool.name}' on '${server.name}' failed", e)
        McpToolCatalog.recordFailure(server, e)
        val context = McpPlugin.getContext()?.androidContext
        Outcome(false, "", McpErrorFormatter.format(context, server.name, e))
    } finally {
        running -= callId
        cancelled -= callId
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
     * @param servers one snapshot of the server list, so the caller's other reads agree with it.
     * @return the pairs, in configured order.
     */
    private fun enabledTools(servers: List<McpServer>): List<Pair<McpServer, McpTool>> =
        servers
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

        // Read once: an app listed between two reads would have tools here but no prefix below.
        val servers = McpServers.all()

        // From every automatic entry, not only those offering tools, so a prefix does not change
        // when another app's tools are switched off or its tool list is still empty.
        val appPrefixes = McpToolText.appPrefixes(
            servers.filter { it.automatic }.map { AutomaticServers.applicationIdOf(it.id) },
        )
        for ((server, tool) in enabledTools(servers)) {
            val base = if (server.automatic) {
                McpToolText.exposedNameWithPrefix(
                    appPrefixes.getValue(AutomaticServers.applicationIdOf(server.id)),
                    tool.name,
                )
            } else {
                McpToolText.exposedName(server.name, tool.name)
            }
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
            exposed += Exposed(server, tool, name, describe(server, tool))
        }
        return exposed
    }

    /**
     * One tool and the name it is published under.
     * @property server the server that owns it.
     * @property tool the tool, as the server described it.
     * @property name the name the agent and the model see.
     */
    private class Exposed(
        val server: McpServer,
        val tool: McpTool,
        val name: String,
        val description: String,
    )

    /**
     * The description the model and the approval dialog see. A SQLite debugger tool starts with
     * its application ID: every entry's tools say the same thing otherwise, and the model could
     * not tell which app's database one reads. A hand-added server's text is passed through.
     */
    private fun describe(server: McpServer, tool: McpTool): String {
        if (!server.automatic) return tool.description
        val app = "${AutomaticServers.applicationIdOf(server.id)} (SQLite debugger)"
        return if (tool.description.isBlank()) app else "$app: ${tool.description}"
    }

    /**
     * One tool, as the host contract describes it.
     *
     * Every remote tool declares that it requires approval and is not read-only: this plugin cannot
     * know what a server's tool does, and guessing on the permissive side is the one guess that
     * cannot be undone. The one exception is [preApproved]: a readOnlyHint tool of the automatic
     * debugger entry, whose server Code on the Go itself installed, declares no approval needed.
     * That is a declaration only: AI-Core currently overrides `requiresApproval` to true for every
     * contributed tool (`ContributedToolHandler`), so the Agent still asks on each call. Honouring
     * the declaration is tracked in ADFA-7147. The contract is a Java interface, so its accessors are implemented as
     * functions: Kotlin synthesises properties for *reading* a Java getter, never for overriding one.
     */
    private class Spec(
        private val name: String,
        private val description: String,
        private val parametersSchema: Map<String, Any>,
        private val preApproved: Boolean = false,
    ) : ToolSourceRegistry.ToolSpec {
        override fun getName(): String = name
        override fun getDescription(): String = description
        override fun getParametersSchema(): Map<String, Any> = parametersSchema
        override fun requiresApproval(): Boolean = !preApproved
        override fun isReadOnly(): Boolean = preApproved
    }

    /**
     * One server's tools, as the host contract describes a group. A server never asked yet reads
     * as connecting rather than working: the tag should not promise tools that may not answer.
     */
    private class Group(
        private val id: String,
        private val displayName: String,
        private val toolNames: List<String>,
        private val health: McpServerHealth.Health?,
    ) : ToolSourceRegistry.ToolGroup {
        override fun getId(): String = id
        override fun getDisplayName(): String = displayName
        override fun getToolNames(): List<String> = toolNames
        override fun getStatus(): CapabilityStatus = when (health?.state) {
            McpServerHealth.State.AVAILABLE -> CapabilityStatus.AVAILABLE
            McpServerHealth.State.DEGRADED -> CapabilityStatus.DEGRADED
            McpServerHealth.State.CONNECTING, null -> CapabilityStatus.CONNECTING
        }
        override fun getStatusMessage(): String? = health?.message
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
