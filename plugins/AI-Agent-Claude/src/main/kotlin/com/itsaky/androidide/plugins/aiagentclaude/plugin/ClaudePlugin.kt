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

        /**
         * The settings that change what [ClaudeBackend.isAvailable], its model name or its context
         * window answers; a refreshed catalog can change the window of the model already chosen.
         */
        private val WATCHED_KEYS = setOf(
            ClaudePreferences.KEY_API_KEY,
            ClaudePreferences.KEY_MODEL,
            ClaudePreferences.KEY_MODEL_CAPABILITIES,
        )

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
            summary = "Sends your prompts to Anthropic's Claude. You need an API key to use it. (Internet access is required.)",
            detail = """
                API use is billed to prepaid credit, separate from a Claude.ai subscription.
                For a free option, use AI Agent Local or AI Agent Gemini.<br><br>To use this
                agent, you must also install AI Core. Then go to <b>Preferences → Agent</b>,
                choose <b>Claude</b> as the backend, and enter your API key.<br><br>Your
                prompts, and any project files the Agent reads, are sent to Anthropic.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-online.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_KEY,
            summary = "Your Claude API key. Claude won't respond without one.",
            detail = """
                Keys start with <code>sk-ant-</code> and come from the Claude Console, not
                from a Claude.ai subscription. When you tap <b>Save Key</b>, CoGo checks the
                key with Claude, then stores it encrypted on this device. If Claude refuses
                the key, it isn't saved, and any key you saved before is kept.<br><br>If
                you're offline, you can still save the key without checking it. It will be
                used once Claude can be reached.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-online.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_WORKSPACE,
            summary = "The Claude workspace to bill this key to.",
            detail = """
                This field is displayed only if Claude refuses your key because the key isn't
                in a workspace. If your key is in a workspace, you never need it.<br><br>To
                find your workspace ID, open the Claude Console and go to <b>Workspaces</b>.
                IDs start with <code>wrkspc_</code>. Paste the ID here, then tap <b>Save Key</b>
                again.<br><br>The ID is saved with your key. To remove it, tap <b>Clear</b>.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-online.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_MODEL,
            summary = "Which Claude model to use. Type a model ID, or tap the field to pick one your key can use.",
            detail = """
                To fill the list, tap <b>Test Connection &amp; List Models</b>. A model ID you
                type is saved when you leave the field.<ul><li><code>claude-opus-5-5</code>
                (the default) is a good choice for building apps.</li><li><code>claude-fable-5-1</code>
                is more capable and costs more.</li><li><code>claude-sonnet-5-5</code> is faster
                and cheaper.</li><li><code>claude-haiku-4-5</code> is the fastest and cheapest.</li></ul>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-online.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_TEST,
            summary = "Checks your key and fills the model list.",
            detail = """
                If the key has a typo, the test fails, so you can fix it before you start a
                chat. The test doesn't save anything.<br><br>If <i>Couldn't reach Claude</i> is
                displayed, this device isn't connected to the internet, or Claude is briefly
                overloaded. Try again in a moment.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-online.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GET_KEY,
            summary = "Opens the Claude Console in your browser, where you can create an API key. API use isn't free. (Internet access is required.)",
            detail = """
                The page opens outside CoGo, so you can check the web address and sign in
                normally.<br><br>To get a key, sign in, create a key, and copy it. Then return
                to this page, paste it into the Claude API Key field, and tap <b>Save Key</b>.<br><br>The
                Claude API is billed to prepaid credit, separate from a Claude.ai subscription.
                For a free option, use AI Agent Local or AI Agent Gemini.<br><br>CoGo never sees
                your password and never reads your clipboard.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-online.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
