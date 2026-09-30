package com.itsaky.androidide.plugins.aiagentgemini.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ai.LlmBackendRegistration
import com.itsaky.androidide.plugins.aiagentgemini.backend.GeminiBackend
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences
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
        const val TOOLTIP_TAG_SETTINGS_GEMINI_EMBEDDING_MODEL = "ai_gemini_embedding_model"
        const val TOOLTIP_TAG_SETTINGS_GET_KEY = "ai_gemini_get_free_key"

        /** The settings that change what [GeminiBackend.isAvailable] or its model name answers. */
        private val WATCHED_KEYS = setOf(GeminiPreferences.KEY_API_KEY, GeminiPreferences.KEY_MODEL)

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

            val gemini = GeminiBackend(context)
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

    override fun deactivate(): Boolean {
        context.logger.info("GeminiPlugin: Deactivating plugin")

        return try {
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
        if (::registration.isInitialized) registration.stop()
        backend?.close()
        backend = null
        activeBackend = null
    }

    override fun dispose() {
        context.logger.info("GeminiPlugin: Disposing plugin")

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
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_EMBEDDING_MODEL,
            summary = "Which model turns your code into vectors for semantic search. Never used for chat.",
            detail = """
                <p>Semantic search compares meaning rather than words, which it
                does by embedding every chunk of the project with this model. It
                is a separate setting because no Gemini model does both: this list
                holds the models that advertise <code>embedContent</code>, and the
                <b>Model</b> list above holds those that advertise
                <code>generateContent</code>.</p>
                <p>Changing it changes the vector space, so the project is indexed
                again from scratch. Vectors from two different models are not
                comparable, and mixing them would quietly return worse results
                rather than fail.</p>
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
