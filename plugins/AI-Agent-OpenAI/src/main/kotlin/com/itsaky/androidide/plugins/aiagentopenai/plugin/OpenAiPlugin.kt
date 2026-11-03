package com.itsaky.androidide.plugins.aiagentopenai.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ai.LlmBackendRegistration
import com.itsaky.androidide.plugins.ai.prompt.AssetPromptConfigSource
import com.itsaky.androidide.plugins.aiagentopenai.backend.OpenAiBackend
import com.itsaky.androidide.plugins.aiagentopenai.preferences.OpenAiPreferences
import com.itsaky.androidide.plugins.aiagentopenai.prompt.OpenAiSystemPrompt
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * Registers the OpenAI-compatible backend with AI Core's inference router.
 *
 * Owns the transport *and* the UI that configures it: the backend names a settings Fragment that
 * ships in this plugin, which whichever screen offers a backend selector mounts under its own
 * selector. AI Core owns routing; nothing outside this plugin handles the API key.
 */
class OpenAiPlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext
    private var backend: OpenAiBackend? = null

    /** Keeps [backend] registered with AI Core across its restarts, and reports setting changes. */
    private lateinit var registration: LlmBackendRegistration

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentopenai"

        private const val TOOLTIP_TAG_PLUGIN = "plugin_ai_backend_openai"

        /**
         * Category the host registers this plugin's tooltips under. Must be `"plugin_"` + the full
         * plugin id, or a long-press renders the literal string `n/a`.
         */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        // Tags for the controls on this backend's settings pane (see OpenAiSettingsFragment).
        const val TOOLTIP_TAG_SETTINGS_SERVER = "ai_openai_server"
        const val TOOLTIP_TAG_SETTINGS_PRESET = "ai_openai_preset"
        const val TOOLTIP_TAG_SETTINGS_KEY = "ai_openai_key"
        const val TOOLTIP_TAG_SETTINGS_MODEL = "ai_openai_model"
        const val TOOLTIP_TAG_SETTINGS_CONTEXT_WINDOW = "ai_openai_context_window"
        const val TOOLTIP_TAG_SETTINGS_TEST = "ai_openai_test_connection"
        const val TOOLTIP_TAG_SETTINGS_GET_KEY = "ai_openai_get_key"

        /**
         * The settings that change what [OpenAiBackend.isAvailable], its model name, its context
         * window or its embedding model answers; the last is set from Vector Search.
         */
        private val WATCHED_KEYS = setOf(
            OpenAiPreferences.KEY_BASE_URL,
            OpenAiPreferences.KEY_API_KEY,
            OpenAiPreferences.KEY_MODEL,
            OpenAiPreferences.KEY_EMBEDDING_MODEL,
            OpenAiPreferences.KEY_CONTEXT_WINDOW,
            OpenAiPreferences.KEY_CONTEXT_WINDOW_URL,
            OpenAiPreferences.KEY_LISTED_CONTEXT_WINDOWS,
        )

        /** The settings that change whether the server answers [OpenAiBackend.checkServer]. */
        private val RECHECK_KEYS = setOf(OpenAiPreferences.KEY_BASE_URL, OpenAiPreferences.KEY_API_KEY)

        @Volatile
        private var pluginContext: PluginContext? = null

        @Volatile
        private var activeBackend: OpenAiBackend? = null

        /** This plugin's context, for the settings pane the backend contributes. */
        fun getContext(): PluginContext? = pluginContext

        /**
         * The live backend, so the settings pane can test a connection and list models against the
         * same transport that serves generation. Null before activation and after disposal.
         */
        fun getBackend(): OpenAiBackend? = activeBackend
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            // Published for the settings pane, which the hosting screen constructs directly.
            pluginContext = context
            registration = LlmBackendRegistration(
                context = context,
                preferences = { OpenAiPreferences.of(context) },
                watchedKeys = WATCHED_KEYS,
                // A new server or key makes the last check describe something else.
                onSettingChanged = { key ->
                    if (key == null || key in RECHECK_KEYS) backend?.checkServer()
                },
                // After registering, so the result has someone to be announced to.
                onRegistered = { backend?.checkServer() },
            )
            context.logger.info("OpenAiPlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("OpenAiPlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("OpenAiPlugin: Activating plugin")

        return try {
            // A half-failed activation can leave a backend behind; keep at most one live.
            releaseBackend()
            preloadPromptConfig()

            val openAi = OpenAiBackend(
                context,
                sharedPromptConfig::configIfLoaded,
                onStatusChanged = registration::notifyBackendChanged,
            )
            backend = openAi
            activeBackend = openAi

            // Decrypt the key off-thread now, so a main-thread isAvailable() can't say "no key".
            openAi.warmKeyCache()
            registration.start(openAi)

            true
        } catch (e: Exception) {
            context.logger.error("OpenAiPlugin: Activation failed", e)
            false
        }
    }

    /** Reads and validates the prompt config now, so building a prompt does no disk I/O. */
    private fun preloadPromptConfig() {
        val source = AssetPromptConfigSource(context.androidContext.assets)
        sharedPromptConfig.reload(source, ::reportLoadedConfig) { error ->
            context.logger.error(
                "OpenAiPlugin: prompt config failed to load; ai-core's default prompt is sent instead",
                error,
            )
        }
    }

    /**
     * Logs that the config loaded, and any name typo its layout would hit at render time.
     *
     * @param config the config just loaded.
     */
    private fun reportLoadedConfig(config: OpenAiPromptConfig) {
        context.logger.info("OpenAiPlugin: loaded prompt config with ${config.rules.size} rule groups")
        for (problem in OpenAiSystemPrompt.problems(config)) {
            context.logger.warn("OpenAiPlugin: $problem; ai-core's default prompt is sent instead")
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("OpenAiPlugin: Deactivating plugin")

        return try {
            // A disabled plugin must not keep the decrypted key on the host heap.
            releaseBackend()
            sharedPromptConfig.clear()

            true
        } catch (e: Exception) {
            context.logger.error("OpenAiPlugin: Deactivation failed", e)
            false
        }
    }

    /**
     * Unregisters from AI Core, cancels in-flight requests, drops the decrypted key from the heap,
     * and clears the published backend. Idempotent, so [deactivate] then [dispose] is safe.
     */
    private fun releaseBackend() {
        if (::registration.isInitialized) registration.stop()
        backend?.close()
        backend = null
        activeBackend = null
    }

    override fun dispose() {
        context.logger.info("OpenAiPlugin: Disposing plugin")

        releaseBackend()
        sharedPromptConfig.clear()
        pluginContext = null
        context.logger.info("OpenAiPlugin: Released OpenAI backend")
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Sends your prompts to OpenAI, or to another server that works the same way, such as Ollama. (A network connection is required.)",
            detail = """
                OpenAI itself needs a paid API key and internet access. A server you run
                yourself, such as Ollama or LM Studio, is free. If it runs on this device or
                on a computer on the same Wi-Fi network, it works without internet
                access.<br><br>To use this agent, you must also install AI Core. Then go to
                <b>Preferences → Agent</b>, choose <b>OpenAI</b> as the backend, and choose a
                server.<br><br>Your prompts, and any project files the Agent reads, are sent
                to the server you choose.
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
            tag = TOOLTIP_TAG_SETTINGS_SERVER,
            summary = "The server to send your prompts to. The default is OpenAI.",
            detail = """
                Enter the address up to <code>/v1</code>, for example
                <code>https://api.openai.com/v1</code>. If you paste a longer address, CoGo
                removes the extra part. To keep your change, tap <b>Save Server</b>.<br><br>An
                address that starts with <code>http://</code> is allowed only for a server
                on this device or on your own network, such as Ollama on your computer. A
                server on the internet must use <code>https://</code>, so your code isn't
                sent unencrypted.
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
            tag = TOOLTIP_TAG_SETTINGS_PRESET,
            summary = "Fills in the server address for a common server. Nothing is saved until you tap <b>Save Server</b>.",
            detail = """
                The presets are OpenAI, Ollama, LM Studio, llama-server, and OpenRouter. To
                enter any other address, choose <b>Custom…</b>.<br><br>The Ollama, LM
                Studio, and llama-server presets point to this device. If the server runs on
                your computer, choose the preset, then replace <code>localhost</code> with
                your computer's network address, for example
                <code>http://192.168.1.50:11434/v1</code>.
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
            summary = "Your API key for this server. OpenAI and OpenRouter require one.",
            detail = """
                If the server is on this device or your own network, this field is hidden,
                because no key is needed.<br><br>When you tap <b>Save Key</b>, CoGo checks
                the key with the server, then stores it encrypted on this device. If the
                server can't be reached, you can still save the key without checking it.
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
            summary = "Which model to use. Type a model name, or tap the field to pick one the server listed.",
            detail = """
                To fill the list, tap <b>Test Connection &amp; List Models</b>. A name you
                type is saved when you leave the field.<br><br>For a server you run yourself,
                type the name of the model you downloaded, such as
                <code>qwen2.5-coder</code>. Some servers don't provide a list, so typing the
                name is the only way to choose a model.<br><br>The list may include a few
                models that can't chat. If a model doesn't answer, choose another one.
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
            tag = TOOLTIP_TAG_SETTINGS_CONTEXT_WINDOW,
            summary = "How much text, in tokens, the model can read at once. To detect it automatically, leave this blank.",
            detail = """
                Enter the context size your server is set to use. This may be smaller than
                the largest size the model supports. Servers you run yourself often use only
                a few thousand tokens:<ul><li>Ollama uses a small default unless you raise
                <code>num_ctx</code> or <code>OLLAMA_CONTEXT_LENGTH</code>.</li><li>LM
                Studio uses the length you chose when you loaded the model.</li><li>llama-server
                uses the value of its <code>-c</code> option.</li></ul>If you leave this
                blank, CoGo uses the size the server reports, or OpenAI's published size.
                Otherwise, it uses a default. The number you enter applies only to the
                current server.<br><br>If the number is larger than the server's real size,
                <i>This conversation is longer than the model can read</i> is displayed.
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
            summary = "Checks the server address and API key, and fills the model list.",
            detail = """
                If the server address or API key has a typo, the test fails, so you can fix
                it before you start a chat. The test doesn't save anything. If it fails, one
                of these messages is displayed:<ul><li><i>The server answered 404</i>: add
                <code>/v1</code> to the end of the server address.</li><li><i>Nothing
                answered</i>: make sure the server is running, and that this device is on the
                same network as the server.</li><li><i>The server answered but has no models
                loaded</i>: download a model on the server first.</li></ul>
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
            summary = "Opens OpenAI's API keys page in your browser, where you can create a key. OpenAI keys aren't free. (Internet access is required.)",
            detail = """
                The page opens outside CoGo, so you can check the web address and sign in
                normally.<br><br>To get a key, sign in, create a key, and copy it. Then
                return to this page, paste it into the API Key field, and tap <b>Save
                Key</b>.<br><br>An OpenAI key needs a prepaid balance, separate from a
                ChatGPT subscription. For a free option, run a model on your own computer
                and choose it in the Server field, or use AI Agent Local or AI Agent
                Gemini.<br><br>CoGo never sees your password and never reads your clipboard.
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
