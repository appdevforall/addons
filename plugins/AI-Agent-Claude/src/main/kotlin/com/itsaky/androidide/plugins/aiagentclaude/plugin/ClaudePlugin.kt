package com.itsaky.androidide.plugins.aiagentclaude.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.aiagentclaude.backend.ClaudeBackend
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * Registers the Claude backend with AI Core's inference router.
 *
 * Owns the transport *and* the UI that configures it: the backend names a settings Fragment that
 * ships in this plugin, which whichever screen offers a backend selector mounts under its own
 * selector. AI Core owns routing; nothing outside this plugin handles the API key.
 */
class ClaudePlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext
    private var backend: ClaudeBackend? = null

    /** True once [backend] is registered with the router, so re-registration is idempotent. */
    @Volatile private var registered = false

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentclaude"

        /** Provider of [LlmInferenceService]; this plugin is useless without it. */
        private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"

        private const val TOOLTIP_TAG_PLUGIN = "plugin_ai_backend_claude"

        /**
         * Category the host registers this plugin's tooltips under. Must be `"plugin_"` + the full
         * plugin id, or a long-press renders the literal string `n/a`.
         */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        // Tags for the controls on this backend's settings pane (see ClaudeSettingsFragment).
        const val TOOLTIP_TAG_SETTINGS_KEY = "ai_claude_key"
        const val TOOLTIP_TAG_SETTINGS_WORKSPACE = "ai_claude_workspace"
        const val TOOLTIP_TAG_SETTINGS_MODEL = "ai_claude_model"
        const val TOOLTIP_TAG_SETTINGS_TEST = "ai_claude_test_connection"
        const val TOOLTIP_TAG_SETTINGS_GET_KEY = "ai_claude_get_key"

        @Volatile
        private var pluginContext: PluginContext? = null

        @Volatile
        private var activeBackend: ClaudeBackend? = null

        /** This plugin's context, for the settings pane the backend contributes. */
        fun getContext(): PluginContext? = pluginContext

        /**
         * The live backend, so the settings pane can test a connection and list models against the
         * same transport that serves generation. Null before activation and after disposal.
         */
        fun getBackend(): ClaudeBackend? = activeBackend
    }

    /**
     * Re-registers when AI Core activates. Plugins load in parallel with no ordering, so
     * [activate] may run before AI Core has published its service; this closes that race instead
     * of polling for it.
     */
    private val aiCoreLifecycle = object : PluginLifecycleListener {
        override fun onPluginActivated(pluginId: String) {
            if (pluginId == AI_CORE_PLUGIN_ID) registerBackend()
        }

        override fun onPluginDeactivated(pluginId: String) {
            // The router went away and took the registration with it; allow a fresh one.
            if (pluginId == AI_CORE_PLUGIN_ID) registered = false
        }

        override fun onPluginUninstalled(pluginId: String) {
            if (pluginId == AI_CORE_PLUGIN_ID) registered = false
        }
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            // Published for the settings pane, which the hosting screen constructs directly.
            pluginContext = context
            context.logger.info("ClaudePlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("ClaudePlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("ClaudePlugin: Activating plugin")

        return try {
            // A half-failed activation can leave a backend behind; keep at most one live.
            releaseBackend()

            val claude = ClaudeBackend(context)
            backend = claude
            activeBackend = claude

            // Decrypt the key off-thread now, so a main-thread isAvailable() can't say "no key".
            claude.warmKeyCache()

            // Listen first, then try: a listener added after a successful attempt would still be
            // needed for a later AI Core restart, and one added before costs nothing.
            context.addPluginLifecycleListener(aiCoreLifecycle)
            if (!registerBackend()) {
                context.logger.info(
                    "ClaudePlugin: AI Core is not active yet; will register when it activates"
                )
            }

            true
        } catch (e: Exception) {
            context.logger.error("ClaudePlugin: Activation failed", e)
            false
        }
    }

    /**
     * Registers the backend with AI Core's router, if the router is reachable.
     *
     * @return true when the backend is registered (now or already), false when AI Core is absent
     */
    private fun registerBackend(): Boolean {
        if (registered) return true
        val claude = backend ?: return false

        val service = resolveInferenceService()
        if (service == null) {
            context.logger.debug("ClaudePlugin: LlmInferenceService not available yet")
            return false
        }

        return try {
            service.registerBackend(claude)
            registered = true
            context.logger.info("ClaudePlugin: Registered '${claude.getId()}' backend with AI Core")
            true
        } catch (e: Exception) {
            context.logger.error("ClaudePlugin: Could not register the Claude backend", e)
            false
        }
    }

    /**
     * Resolves AI Core's router, preferring the process-global registry and falling back to the
     * provider-scoped lookup so a registry cleared by another plugin is not fatal.
     */
    private fun resolveInferenceService(): LlmInferenceService? = try {
        SharedServices.get(LlmInferenceService::class.java)
            ?: context.getPluginService(AI_CORE_PLUGIN_ID, LlmInferenceService::class.java)
    } catch (e: Exception) {
        context.logger.warn("ClaudePlugin: Could not resolve LlmInferenceService: ${e.message}")
        null
    }

    override fun deactivate(): Boolean {
        context.logger.info("ClaudePlugin: Deactivating plugin")

        return try {
            context.removePluginLifecycleListener(aiCoreLifecycle)

            val claude = backend
            if (claude != null && registered) {
                resolveInferenceService()?.unregisterBackend(claude.getId())
                registered = false
                context.logger.info("ClaudePlugin: Unregistered '${claude.getId()}' backend")
            }

            // A disabled plugin must not keep the decrypted key on the host heap.
            releaseBackend()

            true
        } catch (e: Exception) {
            context.logger.error("ClaudePlugin: Deactivation failed", e)
            false
        }
    }

    /**
     * Cancels in-flight requests, drops the decrypted key from the heap, and clears the published
     * backend. Idempotent, so a [deactivate] followed by [dispose] closes nothing twice.
     */
    private fun releaseBackend() {
        backend?.close()
        backend = null
        activeBackend = null
        registered = false
    }

    override fun dispose() {
        context.logger.info("ClaudePlugin: Disposing plugin")

        // deactivate() removes this too; a dispose without one would leave the host holding this.
        runCatching { context.removePluginLifecycleListener(aiCoreLifecycle) }

        releaseBackend()
        pluginContext = null
        context.logger.info("ClaudePlugin: Released Claude backend")
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Sends prompts to Anthropic's Claude. Needs an API key and a network connection.",
            detail = """
                <p><b>AI Agent Claude</b> is a headless plugin that adds the
                <code>claude</code> backend to <b>AI Core</b>, calling Anthropic's
                Messages API over HTTPS.</p>
                <p>The agent's tools are declared to Claude directly, so it reads
                and edits your project through structured calls rather than
                text it has to get exactly right.</p>
                <p>Install <b>AI Core</b> as well, then add your key in
                <b>AI Core &rarr; Agent settings</b>. Prompts and any file
                contents a plugin sends are transmitted to Anthropic.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "AI Agent Claude guide",
                    uri = "index.html",
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_KEY,
            summary = "Your Claude API key. Required: Claude has no anonymous access.",
            detail = """
                <p>Keys start with <code>sk-ant-</code> and come from the Claude
                Console, not from a Claude.ai subscription.</p>
                <p>The key is checked against Claude before being saved, then
                encrypted with the Android Keystore. Only the ciphertext is
                written to disk. A key that cannot be checked, because the device
                is offline, can still be saved but is marked unverified rather
                than claiming a check that never happened.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_WORKSPACE,
            summary = "Only for a key that is not in a workspace: the workspace Claude should bill it to.",
            detail = """
                <p>A key that is not in a workspace is refused on every request
                until it names one, so this field appears only after Claude has
                said so, and a key in a workspace never needs it.</p>
                <p>Copy the ID, which starts with <code>wrkspc_</code>, from the
                workspace's page in the Claude Console. It is saved with the key
                and removed when the key is cleared.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_MODEL,
            summary = "Which Claude model to use. Type any id, or tap to pick one your key can use.",
            detail = """
                <p>One field, and it accepts both: type a model id, or tap it to
                choose from the list <b>Test Connection &amp; List Models</b>
                fetched. Typing is saved as soon as you leave the field.</p>
                <p>The default, <code>claude-opus-5-5</code>, is the strongest
                model for building apps. <code>claude-sonnet-5-5</code> is faster
                and cheaper; <code>claude-haiku-4-5</code> is fastest and
                cheapest.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_TEST,
            summary = "Checks your key and fills the model list. Both are the same request.",
            detail = """
                <p>Tests the key without saving it, so a typo is caught here rather
                than mid-chat. When Claude answers, that same answer fills the
                <b>Model</b> list with the models your key can use.</p>
                <p><b>Couldn't reach Claude</b> means this device has no route to
                the internet, or Claude is briefly overloaded. Try again in a
                moment.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GET_KEY,
            summary = "Opens the Claude Console's API keys page in your browser. API use is not free.",
            detail = """
                <p>Opens <code>platform.claude.com</code> in your own browser,
                never an embedded WebView, so you can see the Console's URL bar
                and sign-in works. Sign in, create a key, copy it, and paste it
                into the field here.</p>
                <p>The Claude API is billed to prepaid credit, separate from a
                Claude.ai subscription. For a free option, use the
                <b>AI Agent Local</b> or <b>AI Agent Gemini</b> plugin
                instead.</p>
                <p>This plugin never sees your password and never reads your
                clipboard.</p>
            """.trimIndent(),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
