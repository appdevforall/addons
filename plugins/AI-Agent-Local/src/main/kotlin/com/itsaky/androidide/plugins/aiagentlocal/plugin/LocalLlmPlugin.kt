package com.itsaky.androidide.plugins.aiagentlocal.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ai.LlmBackendRegistration
import com.itsaky.androidide.plugins.ai.prompt.AssetPromptConfigSource
import com.itsaky.androidide.plugins.aiagentlocal.backend.LocalLlmBackend
import com.itsaky.androidide.plugins.aiagentlocal.preferences.LocalLlmPreferences
import com.itsaky.androidide.plugins.aiagentlocal.prompt.LocalSystemPrompt
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * Registers the on-device llama.cpp backend with AI Core's inference router.
 *
 * Owns the engine *and* the UI that configures it: the backend names a settings Fragment that
 * ships in this plugin, which whichever screen offers a backend selector mounts under its own
 * selector. AI Core owns routing; nothing outside this plugin knows what a `.gguf` file is.
 */
class LocalLlmPlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext

    /** The live backend, from [activate] until [deactivate] releases it. */
    @Volatile private var backend: LocalLlmBackend? = null

    /** Keeps [backend] registered with AI Core across its restarts, and reports setting changes. */
    private lateinit var registration: LlmBackendRegistration

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentlocal"

        /**
         * The whole-plugin entry, and the only one carrying the Tier-3 guide button. Anchored to
         * the engine status line on this backend's settings pane — the one element this plugin
         * always draws, and an entry no element long-presses is an entry nobody can read.
         */
        const val TOOLTIP_TAG_PLUGIN = "plugin_ai_agent_local"

        /**
         * Category the host registers this plugin's tooltips under. Must be `"plugin_"` + the full
         * plugin id, or a long-press renders the literal string `n/a`.
         */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        // Tags for the controls on this backend's settings pane (see LocalLlmSettingsFragment).
        const val TOOLTIP_TAG_SETTINGS_LOCAL_MODEL = "ai_local_model"
        const val TOOLTIP_TAG_SETTINGS_LOCAL_SHA = "ai_local_model_sha"
        const val TOOLTIP_TAG_SETTINGS_SIMPLE_PROMPT = "ai_local_simple_prompt"

        // Tags for the memory pre-flight warning (see MemoryWarningDialogFragment).
        const val TOOLTIP_TAG_MEMORY_PROCEED = "ai_local_memory_warning_proceed"
        const val TOOLTIP_TAG_MEMORY_CANCEL = "ai_local_memory_warning_cancel"

        /** The settings that change what [LocalLlmBackend.isAvailable] or its model name answers. */
        private val WATCHED_KEYS = setOf(
            LocalLlmPreferences.KEY_MODEL_PATH,
            LocalLlmPreferences.KEY_MODEL_NAME,
        )

        @Volatile
        private var pluginContext: PluginContext? = null

        /** This plugin's context, for the settings pane the backend contributes. */
        fun getContext(): PluginContext? = pluginContext
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            // Published for the settings pane, which the hosting screen constructs directly.
            pluginContext = context
            registration = LlmBackendRegistration(
                context = context,
                preferences = { LocalLlmPreferences.of(context) },
                watchedKeys = WATCHED_KEYS,
            )
            context.logger.info("LocalLlmPlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("LocalLlmPlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("LocalLlmPlugin: Activating plugin")

        return try {
            // Before the backend can read anything: takes this plugin's settings out of the agent
            // plugin's shared file, where they lived until each backend owned its own.
            LocalLlmPreferences.migrateIfNeeded(context)

            // A half-failed activation can leave a backend behind; keep at most one live.
            releaseBackend()
            preloadPromptConfig()

            val local = LocalLlmBackend(context, sharedPromptConfig::configIfLoaded)
            backend = local
            registration.start(local)

            true
        } catch (e: Exception) {
            context.logger.error("LocalLlmPlugin: Activation failed", e)
            false
        }
    }

    /** Reads and validates the prompt config now, so building a prompt does no disk I/O. */
    private fun preloadPromptConfig() {
        val source = AssetPromptConfigSource(context.androidContext.assets)
        sharedPromptConfig.reload(source, ::reportLoadedConfig) { error ->
            context.logger.error(
                "LocalLlmPlugin: prompt config failed to load; ai-core's default prompt is sent instead",
                error,
            )
        }
    }

    /**
     * Logs that the config loaded, and any name typo its layout would hit at render time.
     *
     * @param config the config just loaded.
     */
    private fun reportLoadedConfig(config: LocalPromptConfig) {
        context.logger.info("LocalLlmPlugin: loaded prompt config with ${config.rules.size} rule groups")
        for (problem in LocalSystemPrompt.problems(config)) {
            context.logger.warn("LocalLlmPlugin: $problem; ai-core's default prompt is sent instead")
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("LocalLlmPlugin: Deactivating plugin")

        return try {
            // A disabled plugin must not keep the loaded model resident in host RAM.
            releaseBackend()
            sharedPromptConfig.clear()

            true
        } catch (e: Exception) {
            context.logger.error("LocalLlmPlugin: Deactivation failed", e)
            false
        }
    }

    /**
     * Frees the native model and stops the run loop. Idempotent, so a [deactivate] followed by
     * [dispose] closes nothing twice; `LLamaAndroid` recreates the run loop on the next use, so a
     * re-enable still infers.
     */
    private fun releaseBackend() {
        if (::registration.isInitialized) registration.stop()
        backend?.close()
        backend = null
    }

    override fun dispose() {
        context.logger.info("LocalLlmPlugin: Disposing plugin")

        releaseBackend()
        sharedPromptConfig.clear()
        pluginContext = null
        context.logger.info("LocalLlmPlugin: Released local LLM backend")
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Runs an AI model on this device, so it works offline and your prompts and code never leave the device.",
            detail = """
                You need a model file in <code>.gguf</code> format. To download one, use the
                Get AI Models add-on. Model files are large, so download them over Wi-Fi if
                mobile data is expensive.<br><br>To use this agent, you must also install AI
                Core. Then go to <b>Preferences → Agent</b>, choose <b>Local LLM</b> as the
                backend, and choose your model file.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-local.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_LOCAL_MODEL,
            summary = "Choose the <code>.gguf</code> model file to run.",
            detail = """
                After you select a <code>.gguf</code> model, CoGo uses the file from where
                it's saved. If you move the file, tap <b>Browse for Model File</b> and choose
                it from its new location. You'll also need to choose it again if you delete
                it, remove its storage card, or clear CoGo's app data.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-local.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_LOCAL_SHA,
            summary = "A place to note the model file's SHA-256 checksum, if you want a record of it. This field is optional.",
            detail = """
                CoGo doesn't check the file against this value. It only saves what you type,
                and never sends it anywhere. If the model's download page lists a checksum,
                you can paste it here to remember exactly which file you chose.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-local.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_SIMPLE_PROMPT,
            summary = "Gives small models shorter instructions, which they follow more reliably. This option is on by default.",
            detail = """
                With every message, CoGo sends the model a set of instructions about how to
                work with your project. Small models running on a device often get confused
                by the full set. If you're running a larger model that can handle the full
                instructions, clear this checkbox.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-local.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MEMORY_PROCEED,
            summary = "Loads the model even though this device may not have enough free memory for it.",
            detail = """
                Loading may work, fail quickly, or fail after several minutes. While it's
                loading, CoGo may stop responding. To free up memory, close other apps
                before you tap this button.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-local.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MEMORY_CANCEL,
            summary = "Closes this dialog without loading the model. The model you selected before stays selected.",
            detail = """
                To try again, close other apps to free up memory, or choose a smaller model.
                A good starting point is a model with 1 to 3 billion parameters (1B–3B) in
                the <code>Q4_K_M</code> format, which uses less memory.
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more",
                    uri = "i/ai-local.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
