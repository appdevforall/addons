package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpCallHandle
import com.itsaky.androidide.plugins.aiagentmcp.client.McpCallResult
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.debugger.McpServers
import com.itsaky.androidide.plugins.aiagentmcp.debugger.ReadOnlyTrust
import com.itsaky.androidide.plugins.aiagentmcp.debugger.SqlTemplateRead
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpErrorFormatter
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.services.CapabilityStatus
import com.itsaky.androidide.plugins.services.SqlTemplateService
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "$LOG_PREFIX.McpToolSource"

/**
 * Contributes the tools of every configured MCP server to the IDE's AI agent.
 *
 * Only tools the user switched on are offered: one popular GitHub server advertises around ninety,
 * which would exhaust a phone-sized context window on its own, so the toggle defaults to off and
 * this class never widens it.
 *
 * @param executor where remote calls run; see [newExecutor]. A seam, so a test can wait for a
 *   call's worker to finish rather than poll for something it might still send.
 * @param beforeWork runs on the worker thread with the call's id, before the worker does anything
 *   else. A seam, so a test can land a cancel at the one point after the worker has started.
 *
 * A call is owned from the moment [invoke] accepts it, not from the moment its worker starts, so a
 * [cancel] always finds it. The ownership rule, for every entry:
 * - [calls]: [invoke] adds the entry before it schedules the worker. Exactly one party then removes
 *   it, whichever wins [Call.claimed]: the worker, in [runTool]'s `finally`, or [invoke]'s
 *   completion callback when the call finished without the worker (cancelled before it ran), or
 *   [invoke] itself when scheduling threw. The loser does nothing, and a worker that loses sends
 *   nothing.
 * - [inFlight]: [invoke] adds it once the future exists; the future's completion removes it.
 *
 * A cancel reaches a call through its [Call.handle] alone: the flag the worker reads, and the
 * connection the call's current request is using. Not through its session, which every call to
 * that server, the health probe's listing and a SQLite debugger entry's relisting share; see
 * [McpCallHandle] for the handshake that keeps a cancelled call from sending anything.
 */
class McpToolSource internal constructor(
    private val executor: ExecutorService,
    private val beforeWork: (String) -> Unit = {},
) : ToolSourceRegistry.StatusReportingToolSource,
    ToolSourceRegistry.GroupedToolSource {

    constructor() : this(newExecutor())

    /** Each accepted call's future, so a cancel completes it at once rather than when its worker ends. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<*>>()

    /**
     * Every accepted call that has not finished, so [cancel] knows a call exists whatever its worker
     * has reached. See the class KDoc for who removes an entry.
     */
    private val calls = ConcurrentHashMap<String, Call>()

    /**
     * One accepted call.
     *
     * [handle] is what a cancel reaches: its flag, which the worker reads before it looks up a
     * session (so a cancel between the two attempts of the endpoint retry, or before the worker's
     * first line, sends nothing), and the connection of the call's current request, which each
     * request publishes and then checks the flag against before it sends anything. It lives on the
     * call, so it goes with the call and a late cancel cannot leave a stray flag behind, nor reach
     * another call's or a listing's connection.
     *
     * [claimed] is the cleanup ownership: the first party to set it, the worker as it starts or the
     * completion callback as it finds the call finished without one, is the only one to clean up.
     */
    private class Call {
        val handle = McpCallHandle()
        val claimed = AtomicBoolean(false)
    }

    override fun getProviderId(): String = McpPlugin.PLUGIN_ID

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun listTools(): List<ToolSourceRegistry.ToolSpec> = exposedTools().map { exposed ->
        Spec(
            name = exposed.name,
            description = exposed.description,
            parametersSchema = exposed.tool.inputSchema,
            preApproved = exposed.server.automatic && ReadOnlyTrust.isReadOnly(exposed.tool),
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

        val callId = invocation.callId
        // Before the worker is scheduled, so a cancel landing at any point after this finds the call.
        val call = Call()
        calls[callId] = call
        val future = try {
            CompletableFuture.supplyAsync(
                {
                    beforeWork(callId)
                    runTool(callId, call, target.first, target.second, invocation.arguments)
                },
                executor,
            )
        } catch (e: Throwable) {
            // Scheduling failed (a rejected task, a shut-down executor): no worker will ever run.
            calls.remove(callId, call)
            throw e
        }
        inFlight[callId] = future
        return future.whenComplete { _, _ ->
            inFlight.remove(callId)
            // Finished without the worker (cancelled before it ran): nobody else will clean up.
            if (call.claimed.compareAndSet(false, true)) calls.remove(callId, call)
        }
    }

    /** How many accepted calls have not finished; for a test to prove nothing is left behind. */
    internal fun pendingCalls(): Int = calls.size

    override fun cancel(callId: String) {
        // Sets the flag and drops the call's connection: the worker is blocked on a socket read,
        // where an interrupt does nothing, so dropping the connection is what actually ends it.
        // Only this call's, so another call or a listing against the same server keeps its socket.
        calls[callId]?.handle?.cancel()
        inFlight.remove(callId)?.cancel(true)
    }

    /**
     * Ends the executor and every call it accepted, for the plugin shutting down: no new work is
     * taken, each running call's connection is dropped so its blocked read ends, and each future,
     * a queued call's included, completes (cancelled) so nobody waits on one that will never run.
     */
    fun close() {
        executor.shutdownNow()
        for (call in calls.values) call.handle.cancel()
        for (future in inFlight.values) future.cancel(true)
        inFlight.clear()
        calls.clear()
    }

    /**
     * Runs one remote tool.
     * @param callId the agent's id for this call, named in the failure a cancel leaves.
     * @param call the call's entry, registered by [invoke] before this was scheduled.
     * @param server the server that owns it.
     * @param tool the tool, as the server described it.
     * @param arguments the agent's arguments.
     * @return the outcome; a failure carries one sentence, never the server's raw body.
     */
    private fun runTool(
        callId: String,
        call: Call,
        server: McpServer,
        tool: McpTool,
        arguments: Map<String, Any?>,
    ): ToolSourceRegistry.ToolOutcome {
        // Lost: the call finished (cancelled) before this worker began, and the completion callback
        // has cleaned up. The result is discarded, so send nothing.
        if (!call.claimed.compareAndSet(false, true)) {
            return Outcome(false, "", string(R.string.mcp_error_cancelled, server.name))
        }
        return try {
            val result = McpToolCatalog.retry.call(server) { target ->
                // Cancelled between two attempts (or before the first): nothing is looked up or sent.
                // Signalled as an interrupted read, which is how a dropped connection already
                // reports a cancel.
                if (call.handle.isCancelled) {
                    throw InterruptedIOException("Tool call $callId was cancelled")
                }
                // A cancel landing from here on, during the session lookup or while a request reads
                // its credentials, is caught by the handle: each request publishes its connection
                // there and reads the flag again before it sends anything.
                // The session is ended after the call when it was not kept: a server removed
                // meanwhile would otherwise keep the session this call's handshake opened.
                McpConnections.withSession(target) { session ->
                    if (target.automatic && tool.name == SqlTemplateRead.TOOL_NAME) {
                        readTemplate(arguments) { name, args -> session.callTool(name, args, call.handle) }
                    } else {
                        session.callTool(tool.name, arguments, call.handle)
                    }
                }
            }
            // A reply of any kind, a tool's own failure included, means the server is answering;
            // unless it was removed while the call ran, when recording it would bring back its row,
            // or moved to another URL. Checked with the write, under the lock the removal takes, so
            // it cannot land between.
            McpServerHealth.available(server.id) { McpServers.isConfigured(server) }
            Outcome(result.success, result.text, errorWithHint(server, tool, result.errorMessage))
        } catch (e: Throwable) {
            // Throwable, unlike the other host-facing catches in this plugin: this runs inside
            // CompletableFuture.supplyAsync, which catches every Throwable itself, so narrowing this
            // would not bring an OutOfMemoryError to the IDE's handler. It would only hand AI Core an
            // exceptionally completed future in place of the failure outcome it expects.
            Log.w(TAG, "Tool '${tool.name}' on '${server.name}' failed", e)
            McpToolCatalog.recordFailure(server, e)
            val context = McpPlugin.getContext()?.androidContext
            Outcome(false, "", McpErrorFormatter.format(context, server, e))
        } finally {
            calls.remove(callId, call)
        }
    }

    /**
     * Runs [SqlTemplateRead] through the host's [SqlTemplateService], whose functions call the app's
     * tools on this call's session and handle, so a cancel reaches them too.
     */
    private fun readTemplate(
        arguments: Map<String, Any?>,
        callTool: (String, Map<String, Any?>) -> McpCallResult,
    ): McpCallResult {
        val service = McpPlugin.getContext()?.services?.get(SqlTemplateService::class.java)
            ?: return McpCallResult(false, "", string(R.string.mcp_error_templates_unavailable))
        return SqlTemplateRead(service, callTool).run(arguments)
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
     * not tell which app's database one reads. A hand-added server's text is passed through. While the
     * settings switch keeps an entry's `execute` off, its `query` also says how writes get allowed
     * ([ReadOnlyTrust.needsWriteSwitchHint]).
     */
    private fun describe(server: McpServer, tool: McpTool): String {
        if (!server.automatic) return tool.description
        val app = "${AutomaticServers.applicationIdOf(server.id)} (SQLite debugger)"
        val text = if (tool.description.isBlank()) app else "$app: ${tool.description}"
        if (!needsWriteSwitchHint(server, tool)) return text
        return "$text ${writeSwitchHint()}"
    }

    /**
     * A failed call's text, with [writeSwitchHint] after it when SQLite refused a write through an
     * app's `query` while the switch keeps `execute` off. AI-Core shows that text under the chat, so the
     * user reads how to allow writes even when the Agent's own answer is not shown.
     */
    private fun errorWithHint(server: McpServer, tool: McpTool, error: String?): String? {
        if (error == null || !needsWriteSwitchHint(server, tool) || !ReadOnlyTrust.isWriteRefusal(error)) return error
        return "${error.trimEnd().trimEnd('.')}. ${writeSwitchHint()}"
    }

    private fun needsWriteSwitchHint(server: McpServer, tool: McpTool): Boolean =
        server.automatic && ReadOnlyTrust.needsWriteSwitchHint(tool.name, server.knownTools, server.enabledTools)

    /** How the user allows writes, naming the switch by its own label. */
    private fun writeSwitchHint(): String =
        string(R.string.mcp_write_switch_hint, string(R.string.mcp_allow_database_writes))

    /**
     * One tool, as the host contract describes it.
     *
     * Every remote tool declares that it requires approval and is not read-only: this plugin cannot
     * know what a server's tool does, and guessing on the permissive side is the one guess that
     * cannot be undone. The one exception is [preApproved]: a readOnlyHint tool of the automatic
     * debugger entry, whose server Code on the Go itself installed, declares no approval needed. Its
     * `execute`, which writes, never does, whatever hint it carries (`ReadOnlyTrust.isReadOnly`).
     * AI-Core overrides `requiresApproval` to true for every contributed tool
     * (`ContributedToolHandler`), so the Agent still asks. For a read-only tool, AI-Core 3.6.0 or
     * later offers "Always Allow" for the rest of the chat (ADFA-7147); an older AI-Core asks on each
     * call. The contract is a Java interface, so its accessors are implemented as
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

        /**
         * Remote calls run here rather than on the common pool: an MCP call blocks on a socket for
         * as long as the read timeout, and the common pool is sized for CPU work.
         */
        fun newExecutor(): ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mcp-tool-call").apply { isDaemon = true }
        }
    }
}
