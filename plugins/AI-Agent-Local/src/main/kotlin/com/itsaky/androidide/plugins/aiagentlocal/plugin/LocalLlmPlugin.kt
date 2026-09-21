package com.itsaky.androidide.plugins.aiagentlocal.plugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.aiagentlocal.backend.LocalLlmBackend
import com.itsaky.androidide.plugins.aiagentlocal.preferences.LocalLlmPreferences
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * Registers the on-device llama.cpp backend with AI Core's inference router.
 *
 * Owns the engine *and* the UI that configures it: the backend names a settings Fragment that
 * ships in this plugin, which whichever screen offers a backend selector mounts under its own
 * selector. AI Core owns routing; nothing outside this plugin knows what a `.gguf` file is.
 */
class LocalLlmPlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext

    /**
     * Volatile because [activate] writes it on the loading thread while the host may deliver
     * `onPluginActivated` on another: a plain field lets [registerBackend] read null and give up
     * without scheduling a retry, leaving the selector permanently empty.
     */
    @Volatile private var backend: LocalLlmBackend? = null

    /** True once [backend] is registered with the router, so re-registration is idempotent. */
    @Volatile private var registered = false

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentlocal"

        /** Provider of [LlmInferenceService]; this plugin is useless without it. */
        private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"

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

        @Volatile
        private var pluginContext: PluginContext? = null

        /** This plugin's context, for the settings pane the backend contributes. */
        fun getContext(): PluginContext? = pluginContext
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

            backend = LocalLlmBackend(context)

            // Listen first, then try: a listener added after a successful attempt would still be
            // needed for a later AI Core restart, and one added before costs nothing.
            context.addPluginLifecycleListener(aiCoreLifecycle)
            if (!registerBackend()) {
                context.logger.info(
                    "LocalLlmPlugin: AI Core is not active yet; will register when it activates"
                )
            }

            true
        } catch (e: Exception) {
            context.logger.error("LocalLlmPlugin: Activation failed", e)
            false
        }
    }

    /**
     * Registers the local backend with AI Core's router, if the router is reachable.
     *
     * @return true when the backend is registered (now or already), false when AI Core is absent
     */
    private fun registerBackend(): Boolean {
        if (registered) return true
        val local = backend ?: return false

        val service = resolveInferenceService()
        if (service == null) {
            context.logger.debug("LocalLlmPlugin: LlmInferenceService not available yet")
            return false
        }

        return try {
            service.registerBackend(local)
            registered = true
            context.logger.info("LocalLlmPlugin: Registered '${local.getId()}' backend with AI Core")
            true
        } catch (e: Exception) {
            context.logger.error("LocalLlmPlugin: Could not register the local backend", e)
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
        context.logger.warn("LocalLlmPlugin: Could not resolve LlmInferenceService: ${e.message}")
        null
    }

    override fun deactivate(): Boolean {
        context.logger.info("LocalLlmPlugin: Deactivating plugin")

        return try {
            context.removePluginLifecycleListener(aiCoreLifecycle)

            val local = backend
            if (local != null && registered) {
                resolveInferenceService()?.unregisterBackend(local.getId())
                registered = false
                context.logger.info("LocalLlmPlugin: Unregistered '${local.getId()}' backend")
            }

            // A disabled plugin must not keep the loaded model resident in host RAM.
            releaseBackend()

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
        backend?.close()
        backend = null
        registered = false
    }

    override fun dispose() {
        context.logger.info("LocalLlmPlugin: Disposing plugin")

        // deactivate() removes this too; a dispose without one would leave the host holding this.
        runCatching { context.removePluginLifecycleListener(aiCoreLifecycle) }

        releaseBackend()
        pluginContext = null
        context.logger.info("LocalLlmPlugin: Released local LLM backend")
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "Runs .gguf models entirely on this device, with no network access.",
            detail = """
                <p><b>AI Agent Local</b> adds the on-device <code>local</code>
                backend to <b>AI Core</b>. It runs a <code>.gguf</code> model
                through a bundled llama.cpp build, so prompts and code never leave
                the device.</p>
                <p>Install <b>AI Core</b> as well, then select the
                <b>local</b> backend in <b>Agent settings</b> and pick the model
                file in the pane this plugin adds there.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "AI Agent Local guide",
                    uri = "index.html",
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_LOCAL_MODEL,
            summary = "Choose the .gguf model file this backend runs.",
            detail = """
                <p>Pick a <code>.gguf</code> chat model from device storage. The
                file is checked before it is stored: a file that isn't a valid
                <code>.gguf</code> is rejected, and one that looks too large for
                this device's free memory raises a warning first.</p>
                <p>The model is read where you saved it and never copied, so leave
                the file in place. If it is moved or deleted, if its storage is
                disconnected, or if the IDE's app data is cleared, pick it again
                with <b>Browse</b>.</p>
                <p><b>Load from saved</b> reloads the model already configured
                without opening the picker — useful after restarting the IDE.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_LOCAL_SHA,
            summary = "Optional SHA-256 of the model file, recorded for your own verification.",
            detail = """
                <p>Paste the checksum published alongside the model download if you
                want a record of which exact file is configured. It is stored as
                typed and never sent anywhere.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_SIMPLE_PROMPT,
            summary = "Send small local models a shorter, simpler system prompt.",
            detail = """
                <p>On by default. Small on-device models follow a short prompt far
                more reliably than the full tool-calling one; turn it off only if
                you are running a larger model that handles the longer prompt.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MEMORY_PROCEED,
            summary = "Load the model anyway, accepting that it may fail.",
            detail = """
                <p>The estimate says this model may not fit in the memory free
                right now. Loading it may work, fail quickly, or fail after
                several minutes and leave the IDE unresponsive in the meantime.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MEMORY_CANCEL,
            summary = "Abandon this model; the previously selected one is unchanged.",
            detail = """
                <p>Nothing is stored, so the model is never loaded. Close other
                apps to free memory, or choose a smaller or more heavily quantized
                model — a Q4_K_M build of a 1–3B model is the safest starting
                point.</p>
            """.trimIndent(),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
