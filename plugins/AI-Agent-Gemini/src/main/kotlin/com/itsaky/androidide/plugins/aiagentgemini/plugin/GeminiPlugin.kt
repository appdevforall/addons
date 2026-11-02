package com.itsaky.androidide.plugins.aiagentgemini.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ai.LlmBackendRegistration
import com.itsaky.androidide.plugins.ai.prompt.AssetPromptConfigSource
import com.itsaky.androidide.plugins.aiagentgemini.backend.GeminiBackend
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences
import com.itsaky.androidide.plugins.aiagentgemini.prompt.GeminiSystemPrompt
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * Registers the Google Gemini API backend with AI Core's inference router.
 *
 * Owns the transport *and* the UI that configures it: the backend names a settings Fragment that
 * ships in this plugin, which whichever screen offers a backend selector mounts under its own
 * selector. AI Core owns routing; nothing outside this plugin handles the API key.
 */
class GeminiPlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext

    /** The live backend, from [activate] until [deactivate] releases it. */
    @Volatile private var backend: GeminiBackend? = null

    /** Keeps [backend] registered with AI Core across its restarts, and reports setting changes. */
    private lateinit var registration: LlmBackendRegistration

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentgemini"

        /**
         * The whole-plugin entry, and the only one carrying the Tier-3 guide button. Anchored to
         * the key status line on this backend's settings pane — the one element this plugin always
         * draws, and an entry no element long-presses is an entry nobody can read.
         */
        const val TOOLTIP_TAG_PLUGIN = "plugin_ai_agent_gemini"

        /**
         * Category the host registers this plugin's tooltips under. Must be `"plugin_"` + the full
         * plugin id, or a long-press renders the literal string `n/a`.
         */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        // Tags for the controls on this backend's settings pane (see GeminiSettingsFragment).
        const val TOOLTIP_TAG_SETTINGS_GEMINI_KEY = "ai_gemini_key"
        const val TOOLTIP_TAG_SETTINGS_GEMINI_MODEL = "ai_gemini_model"
        const val TOOLTIP_TAG_SETTINGS_GET_KEY = "ai_gemini_get_free_key"

        /**
         * The settings that change what [GeminiBackend.isAvailable], its model name, context window
         * or embedding model answers; the last is set from Vector Search, which re-reads on notice.
         */
        private val WATCHED_KEYS = setOf(
            GeminiPreferences.KEY_API_KEY,
            GeminiPreferences.KEY_MODEL,
            GeminiPreferences.KEY_MODEL_CONTEXT_WINDOWS,
            GeminiPreferences.KEY_EMBEDDING_MODEL,
        )

        @Volatile
        private var pluginContext: PluginContext? = null

        @Volatile
        private var activeBackend: GeminiBackend? = null

        /** This plugin's context, for the settings pane the backend contributes. */
        fun getContext(): PluginContext? = pluginContext

        /**
         * The live backend, so the settings pane can check a key and list models against the same
         * transport that serves generation. Null before activation and after disposal.
         */
        fun getBackend(): GeminiBackend? = activeBackend
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            // Published for the settings pane, which the hosting screen constructs directly.
            pluginContext = context
            registration = LlmBackendRegistration(
                context = context,
                preferences = { GeminiPreferences.of(context) },
                watchedKeys = WATCHED_KEYS,
            )
            context.logger.info("GeminiPlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("GeminiPlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("GeminiPlugin: Activating plugin")

        return try {
            // Before the backend can read anything: takes this plugin's settings out of the agent
            // plugin's shared file, where they lived until each backend owned its own.
            GeminiPreferences.migrateIfNeeded(context)

            // A half-failed activation can leave a backend behind; keep at most one live.
            releaseBackend()
            preloadPromptConfig()

            val gemini = GeminiBackend(context, sharedPromptConfig::configIfLoaded)
            backend = gemini
            activeBackend = gemini

            // Decrypt the key off-thread now, so a main-thread isAvailable() can't say "no key".
            gemini.warmKeyCache()
            registration.start(gemini)

            true
        } catch (e: Exception) {
            context.logger.error("GeminiPlugin: Activation failed", e)
            false
        }
    }

    /** Reads and validates the prompt config now, so building a prompt does no disk I/O. */
    private fun preloadPromptConfig() {
        val source = AssetPromptConfigSource(context.androidContext.assets)
        sharedPromptConfig.reload(source, ::reportLoadedConfig) { error ->
            context.logger.error(
                "GeminiPlugin: prompt config failed to load; ai-core's default prompt is sent instead",
                error,
            )
        }
    }

    /**
     * Logs that the config loaded, and any name typo its layout would hit at render time.
     *
     * @param config the config just loaded.
     */
    private fun reportLoadedConfig(config: GeminiPromptConfig) {
        context.logger.info("GeminiPlugin: loaded prompt config with ${config.rules.size} rule groups")
        for (problem in GeminiSystemPrompt.problems(config)) {
            context.logger.warn("GeminiPlugin: $problem; ai-core's default prompt is sent instead")
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("GeminiPlugin: Deactivating plugin")

        return try {
            // A disabled plugin must not keep the decrypted key on the host heap.
            releaseBackend()
            sharedPromptConfig.clear()

            true
        } catch (e: Exception) {
            context.logger.error("GeminiPlugin: Deactivation failed", e)
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
        context.logger.info("GeminiPlugin: Disposing plugin")

        releaseBackend()
        sharedPromptConfig.clear()
        pluginContext = null
        context.logger.info("GeminiPlugin: Released Gemini backend")
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Sends your prompts to Google's Gemini. You need an API key to use it. (Internet access is required.)",
            detail = """
                API keys are free to create in Google AI Studio.<br><br>To use this agent,
                you must also install AI Core. Then go to <b>Preferences → Agent</b>,
                choose <b>Gemini API</b> as the backend, and enter your API key.<br><br>Your
                prompts, and any project files the Agent reads, are sent to Google.
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
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_KEY,
            summary = "Your Google AI Studio API key. CoGo checks it with Google before saving it.",
            detail = """
                The check happens when you tap <b>Save Key</b>, so a mistyped key is caught
                before you start a chat. CoGo then stores the key encrypted on this
                device.<br><br>If you're offline, you can still save the key without
                checking it. It's marked as unverified until it can be checked.
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
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_MODEL,
            summary = "Which Gemini model to use. To see the models your key can use, tap <b>Refresh Models</b>.",
            detail = """
                The list shows only the models Google lets your key use.<br><br>If you
                haven't saved a key, or you're offline, the list shows a short set of
                current Gemini models instead.
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
            summary = "Opens Google AI Studio in your browser, where you can create an API key for free. (Internet access is required.)",
            detail = """
                The page opens outside CoGo, so you can check the web address and sign in to
                Google normally.<br><br>To get a key, sign in, create a key, and copy it.
                Then return to this page, paste it into the API Key field, and tap <b>Save
                Key</b>.<br><br>CoGo never sees your Google password and never reads your
                clipboard.
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
