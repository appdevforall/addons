package com.itsaky.androidide.plugins.aiagentclaude.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ai.LlmBackendRegistration
import com.itsaky.androidide.plugins.ai.prompt.AssetPromptConfigSource
import com.itsaky.androidide.plugins.aiagentclaude.backend.ClaudeBackend
import com.itsaky.androidide.plugins.aiagentclaude.preferences.ClaudePreferences
import com.itsaky.androidide.plugins.aiagentclaude.prompt.ClaudeSystemPrompt
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.ClaudePromptConfig
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * Registers the Claude backend with AI Core's inference router.
 *
 * Owns the transport *and* the UI that configures it: the backend names a settings Fragment that
 * ships in this plugin, which whichever screen offers a backend selector mounts under its own
 * selector. AI Core owns routing; nothing outside this plugin handles the API key.
 */
class ClaudePlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext

    /** The live backend, from [activate] until [deactivate] releases it. */
    @Volatile private var backend: ClaudeBackend? = null

    /** Keeps [backend] registered with AI Core across its restarts, and reports setting changes. */
    private lateinit var registration: LlmBackendRegistration

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentclaude"

        /**
         * The whole-plugin entry, and the only one carrying the Tier-3 guide button. Anchored to
         * the key status line on this backend's settings pane — the one element this plugin always
         * draws, and an entry no element long-presses is an entry nobody can read.
         */
        const val TOOLTIP_TAG_PLUGIN = "plugin_ai_agent_claude"

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

        /** The settings that change what [ClaudeBackend.isAvailable] or its model name answers. */
        private val WATCHED_KEYS = setOf(ClaudePreferences.KEY_API_KEY, ClaudePreferences.KEY_MODEL)

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

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            // Published for the settings pane, which the hosting screen constructs directly.
            pluginContext = context
            registration = LlmBackendRegistration(
                context = context,
                preferences = { ClaudePreferences.of(context) },
                watchedKeys = WATCHED_KEYS,
            )
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
            preloadPromptConfig()

            val claude = ClaudeBackend(context, sharedPromptConfig::configIfLoaded)
            backend = claude
            activeBackend = claude

            // Decrypt the key off-thread now, so a main-thread isAvailable() can't say "no key".
            claude.warmKeyCache()
            registration.start(claude)

            true
        } catch (e: Exception) {
            context.logger.error("ClaudePlugin: Activation failed", e)
            false
        }
    }

    /** Reads and validates the prompt config now, so building a prompt does no disk I/O. */
    private fun preloadPromptConfig() {
        val source = AssetPromptConfigSource(context.androidContext.assets)
        sharedPromptConfig.reload(source, ::reportLoadedConfig) { error ->
            context.logger.error(
                "ClaudePlugin: prompt config failed to load; ai-core's default prompt is sent instead",
                error,
            )
        }
    }

    /**
     * Logs that the config loaded, and any name typo its layout would hit at render time.
     *
     * @param config the config just loaded.
     */
    private fun reportLoadedConfig(config: ClaudePromptConfig) {
        context.logger.info("ClaudePlugin: loaded prompt config with ${config.rules.size} rule groups")
        for (problem in ClaudeSystemPrompt.problems(config)) {
            context.logger.warn("ClaudePlugin: $problem; ai-core's default prompt is sent instead")
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("ClaudePlugin: Deactivating plugin")

        return try {
            // A disabled plugin must not keep the decrypted key on the host heap.
            releaseBackend()
            sharedPromptConfig.clear()

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
        if (::registration.isInitialized) registration.stop()
        backend?.close()
        backend = null
        activeBackend = null
    }

    override fun dispose() {
        context.logger.info("ClaudePlugin: Disposing plugin")

        releaseBackend()
        sharedPromptConfig.clear()
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
                <p>Install <b>AI Core</b> as well, then open <b>Preferences →
                Configuration → Agent</b>, select the <b>claude</b> backend and
                enter your API key in the pane this plugin adds there. Prompts
                and any file contents a plugin sends are transmitted to
                Anthropic.</p>
                <p>The system prompt is set in YAML files under the plugin's
                <code>assets/prompts/</code>, so its wording changes without code.
                If they cannot load or render, AI Core's default prompt is sent
                instead.</p>
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
                <p>Workspace IDs start with <code>wrkspc_</code>. Find yours in
                the Claude Console under <b>Workspaces</b>, then copy it into this
                field. It is saved with the key and removed when the key is
                cleared.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_MODEL,
            summary = "Which Claude model to use. Type any id, or tap to pick one your key can use.",
            detail = """
                <p>One field, and it accepts both: type a model id, or tap it to
                choose from the list <b>Test Connection &amp; List Models</b>
                fetched. Typing is saved as soon as you leave the field.</p>
                <p>The default, <code>claude-opus-5-5</code>, suits building
                apps. <code>claude-fable-5-1</code> is more capable and costs
                more; <code>claude-sonnet-5-5</code> is faster and cheaper;
                <code>claude-haiku-4-5</code> is fastest and cheapest.</p>
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
