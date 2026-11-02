package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpSettingsFragment
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpServerHealth
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolSource
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginSettingsEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.SettingsExtension
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Connects the Agent to Model Context Protocol servers.
 *
 * Deliberately a plugin of its own rather than part of AI Core: everything here is network work,
 * so it declares `network.access` and nothing else, while AI Core declares the filesystem, shell
 * and project permissions its own tools need and no network at all.
 */
class McpPlugin : IPlugin, SettingsExtension, DocumentationExtension {

    private lateinit var context: PluginContext
    private var toolSource: McpToolSource? = null

    /** True once [toolSource] is registered with AI Core, so re-registration is idempotent. */
    @Volatile private var registered = false

    /**
     * Serialises every swap of [scope].
     *
     * Cancelling the old scope and installing a new one is one transition, not two: without this,
     * two lifecycle calls landing together can each cancel the scope the other has already replaced,
     * leaving a live refresh behind after [deactivate] or orphaning an activation's scope uncancelled.
     * `@Volatile` alone would publish each write but still let the pair interleave.
     */
    private val lifecycleLock = Any()

    /**
     * Background work: listing tools is network work and never belongs on the main thread.
     *
     * Replaced on every [activate] and cancelled by [deactivate], so a refresh left running cannot
     * register sessions in [McpConnections] after `closeAll()` emptied the map. Volatile like
     * [registered]: the host may drive the lifecycle from one thread and the next from another,
     * and [scopeJob] reads it outside [lifecycleLock].
     */
    @Volatile private var scope = newScope()

    /**
     * The scope [stopScope] leaves behind: cancelled from birth, so a `launch` arriving after the
     * lifecycle edge is the no-op it has always been. One per plugin, since cancellation is
     * terminal and a cancelled scope carries no state a later stop could disturb.
     */
    private val stoppedScope = newScope().apply { cancel() }

    companion object {
        /** Must match `plugin.id` in AndroidManifest.xml; also this source's provider id. */
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentmcp"

        /**
         * How often the health probe wakes. A server known to be broken is retried every time, so
         * its tag clears within this long of it coming back.
         */
        internal const val PROBE_INTERVAL_MS = 20_000L

        /**
         * Probe cycles between re-checks of a server that is working: often enough that one going
         * down is flagged within a minute, rarely enough not to spend a phone's radio on it.
         */
        internal const val HEALTHY_PROBE_EVERY = 3

        /** Provider of [ToolSourceRegistry]; this plugin contributes nothing without it. */
        private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"

        /**
         * Category the host registers this plugin's tooltips under. Must be `"plugin_"` + the full
         * plugin id, or a long-press renders the literal string `n/a`.
         */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        const val TOOLTIP_TAG_PLUGIN = "plugin_ai_agent_mcp"

        // Tags for the controls on the MCP settings screen (see McpSettingsFragment).
        const val TOOLTIP_TAG_ADD_SERVER = "mcp_add_server"
        const val TOOLTIP_TAG_SERVER_ROW = "mcp_server_row"
        const val TOOLTIP_TAG_SERVER_ENABLED = "mcp_server_enabled"
        const val TOOLTIP_TAG_CONNECT = "mcp_connect"
        const val TOOLTIP_TAG_SHOW_DETAILS = "mcp_show_details"
        const val TOOLTIP_TAG_TOOL_TOGGLE = "mcp_tool_toggle"
        const val TOOLTIP_TAG_SERVER_NAME = "mcp_server_name"
        const val TOOLTIP_TAG_SERVER_URL = "mcp_server_url"
        const val TOOLTIP_TAG_SERVER_TOKEN = "mcp_server_token"
        const val TOOLTIP_TAG_CLEAR_CREDENTIAL = "mcp_clear_credential"
        const val TOOLTIP_TAG_ADD_HEADER = "mcp_add_header"
        const val TOOLTIP_TAG_HEADER_NAME = "mcp_header_name"
        const val TOOLTIP_TAG_HEADER_VALUE = "mcp_header_value"
        const val TOOLTIP_TAG_HEADER_REMOVE = "mcp_header_remove"
        const val TOOLTIP_TAG_BACK = "mcp_back"

        @Volatile
        private var pluginContext: PluginContext? = null

        /** This plugin's context, for the settings pane the host constructs by name. */
        fun getContext(): PluginContext? = pluginContext
    }

    /**
     * Re-registers when AI Core activates. Plugins load in parallel with no ordering, so
     * [activate] may run before AI Core has published its registry.
     */
    private val aiCoreLifecycle = object : PluginLifecycleListener {
        override fun onPluginActivated(pluginId: String) {
            if (pluginId == AI_CORE_PLUGIN_ID) registerToolSource()
        }

        override fun onPluginDeactivated(pluginId: String) {
            // The registry went away and took the registration with it; allow a fresh one.
            if (pluginId == AI_CORE_PLUGIN_ID) registered = false
        }

        override fun onPluginUninstalled(pluginId: String) {
            if (pluginId == AI_CORE_PLUGIN_ID) registered = false
        }
    }

    /** Tells the agent to re-read the tool list whenever a server or a toggle changes. */
    private val settingsChanged: () -> Unit = {
        resolveToolSourceRegistry()?.notifyToolsChanged(PLUGIN_ID)
    }

    /**
     * Tells the agent a server went down or came back. Status only: the tool list is unchanged,
     * so the agent redraws its tags without rebuilding its tool set.
     */
    private val healthChanged: () -> Unit = {
        try {
            if (registered) resolveToolSourceRegistry()?.notifyToolSourceStatusChanged(PLUGIN_ID)
        } catch (e: Throwable) {
            // An AI Core built before contract 2 has no such method; its tags cannot show health.
            context.logger.debug("McpPlugin: could not report a health change: ${e.message}")
        }
    }

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        pluginContext = context
        context.logger.info("McpPlugin: initialized")
        return true
    }

    override fun activate(): Boolean = try {
        // Cancelled and replaced as one step: a host that activates twice would otherwise orphan
        // the running scope, and the launch below has to use this activation's scope, not whatever
        // a concurrent lifecycle call has since installed.
        val active = swapScope(newScope())
        activationJob = active.coroutineContext[Job]
        toolSource = McpToolSource()
        McpServerStore.addChangeListener(settingsChanged)
        McpServerHealth.addChangeListener(healthChanged)
        context.addPluginLifecycleListener(aiCoreLifecycle)

        if (!registerToolSource()) {
            context.logger.info("McpPlugin: AI Core is not active yet; will register when it is")
        }

        // Tool lists are answered from cache, so the cache has to be filled before the user opens
        // the Agent — otherwise the first cold-start session sees no MCP tools at all.
        active.launch {
            val refreshed = McpToolCatalog.refreshAll { isActive }
            if (refreshed > 0 && isActive) settingsChanged()
            probeHealth { isActive }
        }
        true
    } catch (e: Exception) {
        context.logger.error("McpPlugin: activation failed", e)
        false
    }

    override fun deactivate(): Boolean = try {
        context.removePluginLifecycleListener(aiCoreLifecycle)
        McpServerStore.removeChangeListener(settingsChanged)
        McpServerHealth.removeChangeListener(healthChanged)
        unregisterToolSource()
        // Before the connections are closed: an in-flight refresh would otherwise repopulate the
        // catalogue and the session map straight after they were cleared.
        stopScope()
        releaseConnections()
        true
    } catch (e: Exception) {
        context.logger.error("McpPlugin: deactivation failed", e)
        false
    }

    override fun dispose() {
        runCatching { context.removePluginLifecycleListener(aiCoreLifecycle) }
        McpServerStore.removeChangeListener(settingsChanged)
        McpServerHealth.removeChangeListener(healthChanged)
        unregisterToolSource()
        stopScope()
        releaseConnections()
        pluginContext = null
        context.logger.info("McpPlugin: disposed")
    }

    /**
     * Re-asks each enabled server, so its tag in the agent tracks it going down and coming back
     * without the user pressing Connect. A broken or never-reached server is asked every cycle, a
     * working one every [HEALTHY_PROBE_EVERY]th, and one that refused its credential not until the
     * user changes it. Runs until [keepGoing] turns false. A server whose cached tools changed, one
     * coming back with the same names included, makes the agent re-read.
     *
     * Servers are asked in parallel, so one that hangs until its timeout delays no other's tag.
     *
     * @param keepGoing false once this activation is over; checked before each probe, since a probe
     *   blocks on a socket with no suspension point, like [McpToolCatalog.refreshAll].
     */
    private suspend fun probeHealth(keepGoing: () -> Boolean) {
        var cycle = 0
        while (keepGoing()) {
            delay(PROBE_INTERVAL_MS)
            cycle++
            val due = McpServerStore.servers().filter { server ->
                val health = McpServerHealth.of(server.id)
                server.enabled && health?.refused != true && (cycle % HEALTHY_PROBE_EVERY == 0 ||
                    health?.state != McpServerHealth.State.AVAILABLE)
            }
            val toolsChanged = coroutineScope {
                due.map { server ->
                    async {
                        if (!keepGoing()) return@async false
                        val before = McpToolCatalog.tools(server.id)
                        // Recorded by connect itself; a failure here is what the tag already shows.
                        runCatching { McpToolCatalog.connect(server) }
                        McpToolCatalog.tools(server.id) != before
                    }
                }.awaitAll().any { it }
            }
            // The stored names may be unchanged, so the store's own notification cannot be relied on.
            if (toolsChanged && keepGoing()) settingsChanged()
        }
    }

    /** A fresh scope for this activation; the previous one is cancelled, never reused. */
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Installs [next] as the current scope and cancels whichever scope it displaced.
     *
     * @param next the scope to install.
     * @return [next], so a caller can launch on the scope it installed rather than re-reading the
     *   field and handing its work to a later activation.
     */
    private fun swapScope(next: CoroutineScope): CoroutineScope {
        val previous = synchronized(lifecycleLock) { scope.also { scope = next } }
        previous.cancel()
        return next
    }

    /**
     * Ends the current activation's scope, leaving an already-cancelled one in the field.
     *
     * Installing [stoppedScope] rather than cancelling the field in place: an [activate] running
     * alongside this has by then installed a scope of its own, and cancelling whatever the field
     * happens to hold would either miss it or kill it. Swapping ends exactly the scope this call
     * displaced.
     */
    private fun stopScope() {
        swapScope(stoppedScope)
    }

    /**
     * The current activation scope's job.
     *
     * A seam: the rule that a second [activate] orphans nothing and that [deactivate] leaves no
     * refresh running is otherwise only observable on a device, where the symptom is a background
     * `tools/list` repopulating a catalogue that was just cleared.
     */
    internal val scopeJob: Job?
        get() = scope.coroutineContext[Job]

    /**
     * The job of the scope the last [activate] launched its refresh on.
     *
     * The second half of the seam: [scopeJob] says what the field holds, and this says what an
     * activation actually handed work to. A stop that displaces some other scope leaves the two
     * disagreeing — a live refresh nothing can reach — which is the orphan [lifecycleLock] exists
     * to prevent and the only way a test can see it.
     */
    @Volatile internal var activationJob: Job? = null
        private set

    /**
     * Registers this plugin's tools with AI Core, if the registry is reachable.
     *
     * Guarded against [Throwable] rather than [Exception]: on an IDE older than the release that
     * ships the contract the class is simply absent, and a [NoClassDefFoundError] here would fail
     * the whole plugin rather than the one thing it cannot do.
     *
     * @return true when registered (now or already), false when AI Core is absent.
     */
    private fun registerToolSource(): Boolean {
        if (registered) return true
        val source = toolSource ?: return false

        return try {
            val registry = resolveToolSourceRegistry() ?: return false
            registry.registerToolSource(source)
            registered = true
            context.logger.info("McpPlugin: registered the MCP tool source with AI Core")
            true
        } catch (e: Throwable) {
            context.logger.error("McpPlugin: could not register the MCP tool source", e)
            false
        }
    }

    /** Withdraws the tools, so a disabled plugin stops appearing in the agent's tool list. */
    private fun unregisterToolSource() {
        if (!registered) return
        val source = toolSource
        try {
            if (source != null) {
                resolveToolSourceRegistry()?.unregisterToolSource(source)
                context.logger.info("McpPlugin: unregistered the MCP tool source")
            }
        } catch (e: Throwable) {
            context.logger.warn("McpPlugin: could not unregister the MCP tool source", e)
        }
        registered = false
    }

    /** Closes every session and forgets the cached tool lists. */
    private fun releaseConnections() {
        toolSource?.close()
        toolSource = null
        McpConnections.closeAll()
        McpToolCatalog.clear()
    }

    /**
     * Resolves AI Core's tool registry, preferring the process-global registry and falling back to
     * the provider-scoped lookup so a registry cleared by another plugin is not fatal.
     * @return the registry, or null when AI Core is absent or too old to carry the contract.
     */
    private fun resolveToolSourceRegistry(): ToolSourceRegistry? = try {
        SharedServices.get(ToolSourceRegistry::class.java)
            ?: context.getPluginService(AI_CORE_PLUGIN_ID, ToolSourceRegistry::class.java)
    } catch (e: Throwable) {
        context.logger.warn("McpPlugin: could not resolve the tool registry: ${e.message}")
        null
    }

    // --- SettingsExtension: the MCP servers row in Preferences -> Configuration ---

    override fun getSettingsEntries(): List<PluginSettingsEntry> = listOf(
        PluginSettingsEntry(
            id = "mcp_servers",
            title = string(R.string.pref_mcp_title, "MCP servers"),
            summary = string(R.string.pref_mcp_summary, "Remote tools for the Agent"),
            fragmentClassName = McpSettingsFragment::class.java.name
        )
    )

    /**
     * Resolves [resId] against this plugin's own resources.
     * @param resId the string resource.
     * @param fallback returned when the context is missing or the lookup fails; the host may build
     *   Preferences either side of a lifecycle edge and must never see an exception from here.
     * @return the resolved string.
     */
    private fun string(resId: Int, fallback: String): String = try {
        pluginContext?.androidContext?.getString(resId) ?: fallback
    } catch (e: Exception) {
        fallback
    }

    // --- DocumentationExtension: three-tier in-IDE help ---

    override fun getTooltipCategory(): String = TOOLTIP_CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Lets the Agent use tools from MCP servers that you add. (Internet access is required.)",
            detail = """
                MCP (Model Context Protocol) servers give the Agent extra tools, such as
                access to an issue tracker or a documentation site.<br><br>To use this
                add-on, you must also install AI Core. Then go to <b>Preferences → MCP
                servers</b> and add a server. Each tool stays off until you turn it on, and
                the Agent asks for your approval every time it uses one.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_ADD_SERVER,
            summary = "Adds an MCP server so the Agent can use its tools.",
            detail = """
                In the dialog, enter a name and the server's endpoint URL. If the server
                requires a token, enter that too.<br><br>The Agent puts the name in front of
                each of this server's tool names so it can tell servers apart. CoGo stores
                the token encrypted on this device.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_ROW,
            summary = "Tap to edit this server, its token, and which of its tools the Agent can use.",
            detail = """
                Each tool has its own switch. The tool list comes from the last time CoGo
                connected to the server. If the list is empty, tap <b>Connect</b>. The
                server may not have been reachable when CoGo started.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_ENABLED,
            summary = "Turns this server on or off without deleting it.",
            detail = """
                While the server is off, CoGo doesn't connect to it and the Agent can't use
                its tools. When you turn it back on, its URL, token, and tool switches are as
                you left them.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_NAME,
            summary = "A name that tells you which server this is. The Agent also uses it to tell this server's tools apart from other servers' tools.",
            detail = """
                Use any name that helps you recognize the server, such as Company GitHub,
                Staging, or Docs.<br><br>If you rename the server, its tool names change
                too. To see the new names in a chat that's already open, tap <b>Connect</b>.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_URL,
            summary = "The address of the server's MCP endpoint. It usually starts with <code>https://</code> and ends with <code>/mcp</code>.",
            detail = """
                CoGo works with servers that support the Streamable HTTP connection type. If
                the server's documentation lists more than one address, use the one for
                Streamable HTTP.<br><br>Servers that provide only a command to run, with no
                web address (stdio), or that support only the older HTTP+SSE type, won't
                work.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_TOKEN,
            summary = "The access token for this server. If the server doesn't require one, leave this empty.",
            detail = """
                CoGo stores the token encrypted on this device.<br><br>A token works only
                with an endpoint URL that starts with <code>https://</code>. If the URL
                starts with <code>http://</code>, CoGo won't save the server, because the
                token would be sent unencrypted.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CLEAR_CREDENTIAL,
            summary = "Deletes the token and any extra headers saved for this server. (Emptying the token field doesn't delete a saved token.)",
            detail = """
                Use this if you saved the wrong token, if the server no longer requires a
                token, or if CoGo can no longer read the saved token. CoGo also closes the
                server's current connection.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_ADD_HEADER,
            summary = "Adds an extra header that CoGo sends with every request to this server. Use it if the server requires more than a token.",
            detail = """
                Some servers require extra information that isn't part of the URL or the
                token, such as an API key, an environment name, or a client ID. The server's
                documentation tells you the header name and value to use.<br><br>CoGo stores
                header values encrypted, like the token. You can't use the names CoGo sets
                itself: <code>Accept</code>, <code>Content-Type</code>, and the two names
                that start with <code>Mcp-</code>.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_HEADER_NAME,
            summary = "The header's name, such as <code>X-Api-Key</code>. Use only letters, digits, and the symbols <code>-</code>, <code>_</code>, and <code>.</code>.",
            detail = """
                When you tap <b>Save</b>, CoGo checks the name. If there's a problem, the
                reason is displayed under the header.<br><br>You can't use
                <code>Accept</code>, <code>Content-Type</code>, or the two
                <code>Mcp-</code> headers, because CoGo sets those itself.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_HEADER_VALUE,
            summary = "The value to send for this header. CoGo stores it encrypted on this device.",
            detail = """
                CoGo sends the value exactly as you type it, so include everything the
                server's documentation shows. For example, some values start with a word
                such as <code>Bearer</code> or <code>Basic</code>. The value can be empty,
                but it can't contain a line break.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_HEADER_REMOVE,
            summary = "Removes this header. CoGo deletes it when you tap <b>Save</b>.",
            detail = """
                If a header's name and value are both empty, you don't need to remove it.
                CoGo ignores it when you save.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_BACK,
            summary = "Returns to Preferences. Your changes on this screen are automatically saved.",
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CONNECT,
            summary = "Saves this server, connects to it, and lists its tools below.",
            detail = """
                New tools are off until you turn them on. Tools the server no longer offers
                are removed.<br><br>If CoGo connects but no tools are listed, the server may
                offer only features CoGo doesn't use, such as prompts.<br><br>To use a newly
                added tool in a chat that's already open, tap <b>Connect</b> again.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SHOW_DETAILS,
            summary = "Shows exactly what CoGo sent to the server and how the server answered.",
            detail = """
                This button is displayed only after a connection attempt fails. The log
                lists each request and the server's response, in order.<br><br>Your token
                and header values are hidden in the log. To paste the log into a bug report,
                tap <b>Copy</b>.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_TOOL_TOGGLE,
            summary = "Lets the Agent use this tool. Each tool is off until you turn it on.",
            detail = """
                Turn on only the tools you need. Each tool you turn on takes up space in
                every message the Agent sends, which leaves less room for your
                conversation.<br><br>Turning a tool on doesn't let it run on its own. Every
                time the Agent wants to use the tool, it asks for your approval and shows
                what it will send.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-addons-other.html#mcp",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
