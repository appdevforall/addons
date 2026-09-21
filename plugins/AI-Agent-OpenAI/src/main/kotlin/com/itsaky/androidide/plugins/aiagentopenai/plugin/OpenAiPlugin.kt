package com.itsaky.androidide.plugins.aiagentopenai.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.aiagentopenai.backend.OpenAiBackend
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

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

    /** True once [backend] is registered with the router, so re-registration is idempotent. */
    @Volatile private var registered = false

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentopenai"

        /** Provider of [LlmInferenceService]; this plugin is useless without it. */
        private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"

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
        const val TOOLTIP_TAG_SETTINGS_TEST = "ai_openai_test_connection"
        const val TOOLTIP_TAG_SETTINGS_GET_KEY = "ai_openai_get_key"

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

            val openAi = OpenAiBackend(context)
            backend = openAi
            activeBackend = openAi

            // Decrypt the key off-thread now, so a main-thread isAvailable() can't say "no key".
            openAi.warmKeyCache()

            // Listen first, then try: a listener added after a successful attempt would still be
            // needed for a later AI Core restart, and one added before costs nothing.
            context.addPluginLifecycleListener(aiCoreLifecycle)
            if (!registerBackend()) {
                context.logger.info(
                    "OpenAiPlugin: AI Core is not active yet; will register when it activates"
                )
            }

            true
        } catch (e: Exception) {
            context.logger.error("OpenAiPlugin: Activation failed", e)
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
        val openAi = backend ?: return false

        val service = resolveInferenceService()
        if (service == null) {
            context.logger.debug("OpenAiPlugin: LlmInferenceService not available yet")
            return false
        }

        return try {
            service.registerBackend(openAi)
            registered = true
            context.logger.info("OpenAiPlugin: Registered '${openAi.getId()}' backend with AI Core")
            true
        } catch (e: Exception) {
            context.logger.error("OpenAiPlugin: Could not register the OpenAI backend", e)
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
        context.logger.warn("OpenAiPlugin: Could not resolve LlmInferenceService: ${e.message}")
        null
    }

    override fun deactivate(): Boolean {
        context.logger.info("OpenAiPlugin: Deactivating plugin")

        return try {
            context.removePluginLifecycleListener(aiCoreLifecycle)

            val openAi = backend
            if (openAi != null && registered) {
                resolveInferenceService()?.unregisterBackend(openAi.getId())
                registered = false
                context.logger.info("OpenAiPlugin: Unregistered '${openAi.getId()}' backend")
            }

            // A disabled plugin must not keep the decrypted key on the host heap.
            releaseBackend()

            true
        } catch (e: Exception) {
            context.logger.error("OpenAiPlugin: Deactivation failed", e)
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
        context.logger.info("OpenAiPlugin: Disposing plugin")

        releaseBackend()
        pluginContext = null
        context.logger.info("OpenAiPlugin: Released OpenAI backend")
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Sends prompts to OpenAI, or to any server that speaks the same protocol. Needs a network connection.",
            detail = """
                <p><b>AI Agent OpenAI</b> is a headless plugin that adds the
                <code>openai</code> backend to <b>AI Core</b>, calling
                <code>chat/completions</code> over HTTP.</p>
                <p>It defaults to OpenAI's own API, but the server URL is a
                setting — point it at Ollama or LM Studio on your PC, at a
                <code>llama-server</code>, or at OpenRouter, and the same backend
                talks to all of them.</p>
                <p>Install <b>AI Core</b> as well, then configure the server in
                <b>AI Core → Agent settings</b>. Prompts and any file contents a
                plugin sends are transmitted to whichever server you configure.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "AI Agent OpenAI guide",
                    uri = "index.html",
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_SERVER,
            summary = "The server to send prompts to. Defaults to OpenAI; change it to use your own.",
            detail = """
                <p>Must end at the API root — <code>https://api.openai.com/v1</code>,
                not the <code>/chat/completions</code> path. If you paste the full
                endpoint URL, the extra path is removed for you.</p>
                <p>Plain <code>http://</code> is accepted only for your own device
                or a private network address, which is the Ollama-on-my-PC case. A
                cleartext address on the open internet is refused, because your
                project's source would travel unencrypted.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_PRESET,
            summary = "Fills the server URL for a known server. Nothing is saved until you tap Save.",
            detail = """
                <p>Each preset is only a URL: OpenAI, Ollama, LM Studio,
                <code>llama-server</code> and OpenRouter all speak the same
                protocol, so one backend reaches all of them.</p>
                <p>The local presets use <code>localhost</code>. To reach a server
                on another machine, pick the preset and then edit the host — for
                example <code>http://192.168.1.50:11434/v1</code>.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_KEY,
            summary = "Your API key. Optional — a local Ollama or LM Studio server needs none.",
            detail = """
                <p>Required for OpenAI itself and for OpenRouter; left blank for a
                local server, where no credential is sent at all. This whole
                section disappears when the server URL points at your own device or
                network, because there is no key to enter.</p>
                <p>The key is checked against the server before being saved, then
                encrypted with the Android Keystore — only the ciphertext is
                written to disk. A key that cannot be checked, because the server
                is offline, can still be saved but is marked unverified rather
                than claiming a check that never happened.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_MODEL,
            summary = "Which model to request. Type any name, or tap to pick one the server reported.",
            detail = """
                <p>One field, and it accepts both: type a name, or tap it to choose
                from the list <b>Test Connection &amp; List Models</b> fetched.
                Typing is saved as soon as you leave the field.</p>
                <p>Free text always works, which matters for a local server: the
                model is whatever you pulled, such as
                <code>qwen2.5-coder</code>. A server that does not implement a
                model list is normal — just type the name.</p>
                <p>Unlike Google's catalog, this list carries no "can chat" flag,
                so obvious non-chat models (embeddings, audio, images) are filtered
                out and anything unrecognised is kept.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_TEST,
            summary = "Checks the server and key, and fills the model list — both are the same request.",
            detail = """
                <p>Tests the URL and the key together, without saving either, so a
                typo is caught here rather than mid-chat. When the server answers
                with a catalog, that same answer fills the <b>Model</b> list.</p>
                <p><b>404</b> almost always means the URL is missing its
                <code>/v1</code> suffix. <b>Nothing answered</b> means the server
                is not running or is not reachable from this device — check that
                Ollama is started and that the phone is on the same network.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GET_KEY,
            summary = "Opens OpenAI's API keys page in your browser. OpenAI keys are not free.",
            detail = """
                <p>Opens <code>platform.openai.com/api-keys</code> in your own
                browser — never an embedded WebView, so you can see OpenAI's URL
                bar and sign-in works. Sign in, create a key, copy it, and paste
                it into the field here.</p>
                <p>OpenAI has no free tier: an API key needs a prepaid balance,
                separate from a ChatGPT subscription. For a free option, run a
                model on your own machine and point the server URL at it, or use
                the <b>AI Agent Local</b> or <b>AI Agent Gemini</b> plugin
                instead.</p>
                <p>This plugin never sees your password and never reads your
                clipboard.</p>
            """.trimIndent(),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
