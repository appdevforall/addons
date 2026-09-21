package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpSettingsFragment
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
import kotlinx.coroutines.cancel
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
        context.addPluginLifecycleListener(aiCoreLifecycle)

        if (!registerToolSource()) {
            context.logger.info("McpPlugin: AI Core is not active yet; will register when it is")
        }

        // Tool lists are answered from cache, so the cache has to be filled before the user opens
        // the Agent — otherwise the first cold-start session sees no MCP tools at all.
        active.launch {
            val refreshed = McpToolCatalog.refreshAll { isActive }
            if (refreshed > 0 && isActive) settingsChanged()
        }
        true
    } catch (e: Exception) {
        context.logger.error("McpPlugin: activation failed", e)
        false
    }

    override fun deactivate(): Boolean = try {
        context.removePluginLifecycleListener(aiCoreLifecycle)
        McpServerStore.removeChangeListener(settingsChanged)
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
        unregisterToolSource()
        stopScope()
        releaseConnections()
        pluginContext = null
        context.logger.info("McpPlugin: disposed")
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
            summary = "Lets the Agent use tools from remote MCP servers you configure. Needs a network connection.",
            detail = """
                <p><b>AI Agent MCP</b> connects the Agent to
                <b>Model Context Protocol</b> servers — issue trackers,
                documentation indexes, internal APIs — so their tools appear
                alongside the Agent's own.</p>
                <p>Install <b>AI Core</b> as well, then add a server under
                <b>Preferences &rarr; Configuration &rarr; MCP servers</b>. Tools
                are off until you switch them on, and every remote tool asks for
                your approval before it runs.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_ADD_SERVER,
            summary = "Add an MCP server by URL, with an optional access token.",
            detail = """
                <p>Asks for a name, the server's MCP endpoint URL and, when the
                server needs one, a token.</p>
                <p>The name is only a label, but it also prefixes that server's
                tool names so the Agent can tell two servers apart. The token is
                encrypted with the Android Keystore; only the ciphertext is
                written to disk.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_ROW,
            summary = "Tap to edit this server, its token and which of its tools the Agent may use.",
            detail = """
                <p>Opens the server's details: name, URL, token, and the list of
                tools it advertises with a switch for each.</p>
                <p>The tool list comes from the last successful connection. If it is
                empty, tap <b>Connect</b> — the server may have been
                unreachable when the IDE started.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_ENABLED,
            summary = "Switch the whole server off without deleting it or losing your tool choices.",
            detail = """
                <p>A disabled server contributes no tools to the Agent and is not
                contacted. Its URL, token and per-tool switches are kept, so
                turning it back on restores exactly what you had.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_NAME,
            summary = "Your label for this server. It also prefixes the tool names the Agent sees.",
            detail = """
                <p>Only a label, so name it whatever tells you which server this
                is — <b>Company GitHub</b>, <b>Staging</b>, <b>Docs</b>.</p>
                <p>It is also how the Agent tells two servers' tools apart: the
                first few characters, lowercased and reduced to letters, digits
                and <code>_</code>, are prefixed to every tool this server
                offers. Renaming the server therefore renames its tools, so a
                chat already open needs a <b>Connect</b> to see them.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_URL,
            summary = "The server's MCP endpoint, usually an https:// URL ending in /mcp.",
            detail = """
                <p>This plugin speaks MCP's <b>Streamable HTTP</b> transport: one
                POST per call, answered with JSON or with a stream of it.</p>
                <p>A server that offers only the older stdio transport cannot be
                used from a phone at all, and one that offers the deprecated
                HTTP+SSE transport is not supported here.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SERVER_TOKEN,
            summary = "Optional bearer token, encrypted on this device and sent only to this server.",
            detail = """
                <p>Sent as an <code>Authorization: Bearer</code> header, never in
                the URL — query strings leak into logs and proxies.</p>
                <p>It is encrypted with a key held in the Android Keystore, so a
                copy of the settings file is useless on another device. Leave it
                empty for a server that needs no token.</p>
                <p>A token needs an <code>https://</code> endpoint: encryption at
                rest buys nothing for a credential sent in the clear over shared
                Wi-Fi, so this plugin refuses that combination on Save.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CLEAR_CREDENTIAL,
            summary = "Forget the token and headers stored for this server.",
            detail = """
                <p>The token field never shows what is stored, and leaving it
                empty means "keep it" — so this is the way back to a server
                that needs no credential at all.</p>
                <p>It removes the stored token <em>and</em> every extra header,
                and drops the open connection that was using them. Use it after
                pasting the wrong token, when a server stops requiring auth, or
                when the stored value can no longer be decrypted on this
                device.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_ADD_HEADER,
            summary = "Send an extra header with every request, for a server that needs more than a token.",
            detail = """
                <p>Some servers route on something the URL and the token do
                not carry — an API key, an environment, a client id. Add the
                header's name and value here and every request to this server
                carries it.</p>
                <p>Values are encrypted with the token, since a header is as
                often a credential. Names this plugin sets itself
                (<code>Accept</code>, <code>Content-Type</code> and the two
                <code>Mcp-</code> headers) are refused — overriding them would
                break the protocol.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_HEADER_NAME,
            summary = "The header's name, such as X-Api-Key. Letters, digits and - _ . only.",
            detail = """
                <p>Must be a real header name: letters, digits and the
                punctuation HTTP allows in one. A space or a colon is refused as
                you save, with the reason shown under the row.</p>
                <p>The four names this plugin sets itself — <code>Accept</code>,
                <code>Content-Type</code> and the two <code>Mcp-</code> headers —
                are refused too, because overriding them would break the protocol
                and the failure would look like a broken server.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_HEADER_VALUE,
            summary = "What to send for this header. Encrypted on this device, like the token.",
            detail = """
                <p>Sent verbatim with every request to this server. An empty
                value is allowed, since an empty header is occasionally
                meaningful, but a line break is not — one would forge a second
                header at the socket.</p>
                <p>Stored encrypted with the token rather than beside the URL: a
                header is as often a credential as the token is, and nothing here
                can tell which is which.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_HEADER_REMOVE,
            summary = "Drop this header. It is forgotten once you save.",
            detail = """
                <p>Takes the row off the screen; the stored header goes when you
                save, so leaving by <b>Cancel</b> keeps it.</p>
                <p>A row left completely blank needs no removing — it is ignored
                on save rather than stored empty.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_BACK,
            summary = "Return to Preferences. Your servers and switches are already saved.",
            detail = """
                <p>Nothing on this screen is held until you leave it: adding a
                server, switching one on and switching a tool on each save as
                you do them.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CONNECT,
            summary = "Save this server, connect to it, and list the tools it offers below.",
            detail = """
                <p>Saves what you typed, performs the MCP handshake and reads the
                server's tool catalogue, then reports what happened in one
                sentence. Tools that disappeared are removed along with their
                switches; new ones arrive switched <b>off</b>.</p>
                <p>A server that completes the handshake but offers no tool
                catalogue is still reported as reachable — some servers expose
                only prompts or resources, which this plugin does not use.</p>
                <p>The Agent reads this list from memory, so connecting again is
                also what makes a newly added tool visible to a chat that is
                already open.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_TOOL_TOGGLE,
            summary = "Let the Agent use this one tool. Off by default — pick only what you need.",
            detail = """
                <p>Every switched-on tool costs prompt space on every message, and
                a large server can advertise ninety of them, so they start off and
                the Agent only ever sees the ones you chose.</p>
                <p>Switching one on does not let it run unattended: a remote tool
                asks for approval on every call, showing the arguments it would
                send.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Agent MCP guide", uri = "index.html", order = 0)
            )
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
