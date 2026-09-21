package com.itsaky.androidide.plugins.aiagentgemini.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.aiagentgemini.backend.GeminiBackend
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * Registers the Google Gemini API backend with AI Core's inference router.
 *
 * Owns the transport *and* the UI that configures it: the backend names a settings Fragment that
 * ships in this plugin, which whichever screen offers a backend selector mounts under its own
 * selector. AI Core owns routing; nothing outside this plugin handles the API key.
 */
class GeminiPlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext

    /**
     * Volatile because [activate] writes it on the loading thread while the host may deliver
     * `onPluginActivated` on another: a plain field lets [registerBackend] read null and give up
     * without scheduling a retry, leaving the selector permanently empty.
     */
    @Volatile private var backend: GeminiBackend? = null

    /** True once [backend] is registered with the router, so re-registration is idempotent. */
    @Volatile private var registered = false

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentgemini"

        /** Provider of [LlmInferenceService]; this plugin is useless without it. */
        private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"

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

    /**
     * Re-registers when AI Core activates. Plugins load in parallel with no ordering, so
     * [activate] may run before AI Core has published its service; this closes that race
     * instead of polling for it.
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

            val gemini = GeminiBackend(context)
            backend = gemini
            activeBackend = gemini

            // Decrypt the key off-thread now, so a main-thread isAvailable() can't say "no key".
            gemini.warmKeyCache()

            // Listen first, then try: a listener added after a successful attempt would still be
            // needed for a later AI Core restart, and one added before costs nothing.
            context.addPluginLifecycleListener(aiCoreLifecycle)
            if (!registerBackend()) {
                context.logger.info(
                    "GeminiPlugin: AI Core is not active yet; will register when it activates"
                )
            }

            true
        } catch (e: Exception) {
            context.logger.error("GeminiPlugin: Activation failed", e)
            false
        }
    }

    /**
     * Registers the Gemini backend with AI Core's router, if the router is reachable.
     *
     * @return true when the backend is registered (now or already), false when AI Core is absent
     */
    private fun registerBackend(): Boolean {
        if (registered) return true
        val gemini = backend ?: return false

        val service = resolveInferenceService()
        if (service == null) {
            context.logger.debug("GeminiPlugin: LlmInferenceService not available yet")
            return false
        }

        return try {
            service.registerBackend(gemini)
            registered = true
            context.logger.info("GeminiPlugin: Registered '${gemini.getId()}' backend with AI Core")
            true
        } catch (e: Exception) {
            context.logger.error("GeminiPlugin: Could not register the Gemini backend", e)
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
        context.logger.warn("GeminiPlugin: Could not resolve LlmInferenceService: ${e.message}")
        null
    }

    override fun deactivate(): Boolean {
        context.logger.info("GeminiPlugin: Deactivating plugin")

        return try {
            context.removePluginLifecycleListener(aiCoreLifecycle)

            val gemini = backend
            if (gemini != null && registered) {
                resolveInferenceService()?.unregisterBackend(gemini.getId())
                registered = false
                context.logger.info("GeminiPlugin: Unregistered '${gemini.getId()}' backend")
            }

            // A disabled plugin must not keep the decrypted key on the host heap.
            releaseBackend()

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
        backend?.close()
        backend = null
        activeBackend = null
        registered = false
    }

    override fun dispose() {
        context.logger.info("GeminiPlugin: Disposing plugin")

        // deactivate() removes this too; a dispose without one would leave the host holding this.
        runCatching { context.removePluginLifecycleListener(aiCoreLifecycle) }

        releaseBackend()
        pluginContext = null
        context.logger.info("GeminiPlugin: Released Gemini backend")
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Sends prompts to Google's Gemini API. Needs an API key and a network connection.",
            detail = """
                <p><b>AI Agent Gemini</b> adds the <code>gemini</code> backend to
                <b>AI Core</b>, calling Google's Generative Language API over
                HTTPS.</p>
                <p>Install <b>AI Core</b> as well, then select the <b>gemini</b>
                backend in <b>Agent settings</b> and enter your API key in the pane
                this plugin adds there. Prompts and any file contents a plugin
                sends are transmitted to Google.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "AI Agent Gemini guide",
                    uri = "index.html",
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_KEY,
            summary = "Your Google AI Studio API key, checked with Google before it is stored.",
            detail = """
                <p>The key is verified against Google's model list before being
                saved, so a mistyped key is caught here rather than mid-chat. It
                is then encrypted with the Android Keystore and only the
                ciphertext is written to disk.</p>
                <p>A key that cannot be checked — no network, for instance — can
                still be saved, but is marked unverified rather than claiming a
                check that never happened.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_MODEL,
            summary = "Which Gemini model to use. Refresh lists the models your key can reach.",
            detail = """
                <p><b>Refresh Models</b> asks Google which chat-capable models the
                saved key can actually use, so the list never offers a model that
                would fail with a 404. Without a key, or offline, a short list of
                current models is shown instead.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GET_KEY,
            summary = "Opens Google AI Studio in your browser, where API keys are free to create.",
            detail = """
                <p>Opens <code>aistudio.google.com/apikey</code> in your own
                browser — never an embedded WebView, so you can see Google's URL
                bar and Google's sign-in works. Sign in, create a key, copy it,
                and paste it into the field here.</p>
                <p>This plugin never sees your Google password and never reads
                your clipboard.</p>
            """.trimIndent(),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
