package org.appdevforall.shellhooks

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.FileOpenExtension
import com.itsaky.androidide.plugins.extensions.FileTabMenuItem
import com.itsaky.androidide.plugins.extensions.PluginSettingsEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.SettingsExtension
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.FileChangeListener
import com.itsaky.androidide.plugins.services.FileSaveListener
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeCommandService
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.ProjectLifecycleListener
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.appdevforall.shellhooks.engine.Coalescer
import org.appdevforall.shellhooks.engine.HookDiscovery
import org.appdevforall.shellhooks.engine.HookEvent
import org.appdevforall.shellhooks.engine.HookLog
import org.appdevforall.shellhooks.engine.HookPaths
import org.appdevforall.shellhooks.engine.HookResolver
import org.appdevforall.shellhooks.engine.HookRunner
import org.appdevforall.shellhooks.engine.IdeCommandRunner
import org.appdevforall.shellhooks.engine.SampleHooks
import org.appdevforall.shellhooks.engine.ShellQuote
import org.appdevforall.shellhooks.engine.TrustStore
import org.appdevforall.shellhooks.ui.HooksPanelFragment
import org.appdevforall.shellhooks.ui.ShellHooksSettingsFragment
import java.io.File
import java.io.IOException

/**
 * Runs the user's shell scripts at the IDE's integration points. The plugin only listens and
 * queues; [HookRunner] finds the scripts for each event and runs them one at a time through the
 * IDE's command service. Nothing runs until the user turns hooks on in Preferences.
 */
class ShellHooksPlugin :
    IPlugin,
    UIExtension,
    FileOpenExtension,
    SettingsExtension,
    DocumentationExtension {
    private lateinit var context: PluginContext
    lateinit var paths: HookPaths
        private set
    lateinit var settings: ShellHooksSettings
        private set
    lateinit var trust: TrustStore
        private set
    val log = HookLog()

    private var scope: CoroutineScope? = null
    private var runner: HookRunner? = null
    private var coalescer: Coalescer? = null

    @Volatile
    private var wrapper: File? = null

    private val projectListener =
        object : ProjectLifecycleListener {
            override fun onProjectOpened(projectDir: File) = submit(HookEvent.projectOpened(projectDir))

            override fun onProjectClosed(projectDir: File) = submit(HookEvent.projectClosed(projectDir))
        }

    private val fileChangeListener =
        FileChangeListener { file ->
            if (isOn()) coalescer?.offer(HookEvent.fileActivated(file, currentProjectDir()))
        }

    private val fileSaveListener = FileSaveListener { file -> submit(HookEvent.fileSaved(file, currentProjectDir())) }

    private val buildListener =
        object : BuildStatusListener {
            override fun onBuildStarted() = submit(HookEvent.buildStarted(currentProjectDir()))

            override fun onBuildFinished() = submit(HookEvent.buildSucceeded(currentProjectDir()))

            override fun onBuildFailed(error: String?) = submit(HookEvent.buildFailed(error, currentProjectDir()))
        }

    override fun initialize(context: PluginContext): Boolean =
        try {
            this.context = context
            paths = HookPaths(context.getAppFilesDir())
            settings = ShellHooksSettings(context.getPluginSharedPreferences(ShellHooksSettings.PREFS))
            trust = TrustStore(PrefsKeyValueStore(context.getPluginSharedPreferences(ShellHooksSettings.TRUST_PREFS)))
            instance = this
            true
        } catch (t: Throwable) {
            context.logger.error("Shell Hooks failed to initialize", t)
            false
        }

    override fun activate(): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        wrapper = installWrapper()
        val warn: (String) -> Unit = { context.logger.warn(it) }
        runner =
            HookRunner(
                scope = scope,
                resolver = HookResolver(paths, settings, trust, log, File(context.getPluginDataDir(), VERIFIED_DIR), warn),
                commands = IdeCommandRunner { service(IdeCommandService::class.java) },
                log = log,
                wrapper = { wrapper },
                timeoutMs = { settings.timeoutSeconds * 1000L },
                ideVersion = ideVersion(),
                warn = warn,
            )
        coalescer = Coalescer(scope, FILE_ACTIVATED_DEBOUNCE_MS) { submit(it) }

        // Each in its own try: a host without one listener, or a refused permission, must not
        // cost the others.
        listen("project") { service(IdeProjectService::class.java)?.addProjectLifecycleListener(projectListener) }
        listen("file change") { service(IdeEditorService::class.java)?.addFileChangeListener(fileChangeListener) }
        listen("file save") { service(IdeEditorService::class.java)?.addFileSaveListener(fileSaveListener) }
        listen("build") { service(IdeBuildService::class.java)?.addBuildStatusListener(buildListener) }

        submit(HookEvent.ideStarted(currentProjectDir()))
        return true
    }

    override fun deactivate(): Boolean {
        listen("project") { service(IdeProjectService::class.java)?.removeProjectLifecycleListener(projectListener) }
        listen("file change") { service(IdeEditorService::class.java)?.removeFileChangeListener(fileChangeListener) }
        listen("file save") { service(IdeEditorService::class.java)?.removeFileSaveListener(fileSaveListener) }
        listen("build") { service(IdeBuildService::class.java)?.removeBuildStatusListener(buildListener) }
        coalescer?.cancel()
        coalescer = null
        runner?.close()
        runner = null
        scope?.cancel()
        scope = null
        return true
    }

    override fun dispose() {
        if (instance === this) instance = null
    }

    // FileOpenExtension

    override fun onFileOpened(file: File) = submit(HookEvent.fileOpened(file, currentProjectDir()))

    override fun onFileClosed(file: File) = submit(HookEvent.fileClosed(file, currentProjectDir()))

    /** One "Run <name>" per script in the actions folder; the host shows these in the file tree too. */
    override fun getFileTabMenuItems(file: File): List<FileTabMenuItem> {
        if (!isOn()) return emptyList()
        return HookDiscovery.actions(paths.actions).mapIndexed { index, action ->
            FileTabMenuItem(
                id = "action:${action.name}",
                title = "Run ${action.name}",
                order = ACTION_MENU_ORDER + index,
                tooltipTag = ShellHooksHelp.TAG_ACTION,
                action = { runAction(action, file) },
            )
        }
    }

    // UIExtension

    override fun getEditorTabs(): List<TabItem> =
        listOf(
            TabItem(
                id = TAB_ID,
                title = "Hooks",
                fragmentFactory = { HooksPanelFragment() },
                order = 210,
                tooltipTag = ShellHooksHelp.TAG_TAB,
            ),
        )

    // SettingsExtension

    override fun getSettingsEntries(): List<PluginSettingsEntry> =
        listOf(
            PluginSettingsEntry(
                id = "shell_hooks",
                title = "Shell Hooks",
                summary = "Run your scripts when a project opens, a file is saved, or a build ends",
                fragmentClassName = ShellHooksSettingsFragment::class.java.name,
            ),
        )

    // DocumentationExtension

    override fun getTooltipCategory(): String = TOOLTIP_CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = ShellHooksHelp.entries()

    override fun getTier3DocsAssetPath(): String = "docs"

    /** Writes the sample hooks; returns how many files, or throws when the folder is not writable. */
    @Throws(IOException::class)
    fun writeSamples(): Int = SampleHooks.write(paths)

    fun isOn(): Boolean = ::settings.isInitialized && settings.enabled

    fun <T> service(type: Class<T>): T? = runCatching { context.services.get(type) }.getOrNull()

    fun logger() = context.logger

    private fun submit(event: HookEvent) {
        if (!isOn() || !settings.isHookEnabled(event.hook)) return
        runner?.submit(event)
    }

    private fun currentProjectDir(): File? =
        runCatching { service(IdeProjectService::class.java)?.getCurrentProject()?.rootDir }.getOrNull()

    /** Runs [action] on [file] in the visible Terminal, so the user sees what it prints. */
    private fun runAction(
        action: File,
        file: File,
    ) {
        val scope = scope ?: return
        val runnerScript = wrapper ?: return
        val terminal = service(IdeTerminalService::class.java) ?: return
        val projectDir = currentProjectDir()
        // The Terminal takes a command line, so every value is quoted as one word.
        val command =
            ShellQuote.command(
                linkedMapOf(
                    "COGO_HOOK" to "action",
                    "COGO_PROJECT_DIR" to (projectDir?.path ?: ""),
                    "COGO_HOOKS_DIR" to paths.actions.path,
                    "COGO_HOOK_SCRIPT" to action.path,
                    "COGO_HOOK_SOURCE" to "global",
                ),
                listOf("sh", runnerScript.path, action.path, file.path),
            )
        scope.launch {
            try {
                val result = terminal.runInTerminal(command)
                if (result is TerminalCommandResult.NotStarted) {
                    context.logger.warn("Shell Hooks: action ${action.name} did not start: ${result.reason}")
                }
            } catch (e: Exception) {
                context.logger.warn("Shell Hooks: action ${action.name} failed to start", e)
            }
        }
    }

    // The runner script ships as an asset and is copied out on every activation, so an update to
    // the plugin replaces it.
    private fun installWrapper(): File? =
        try {
            val target = File(context.getPluginDataDir(), WRAPPER_NAME)
            val stream = context.resources.openPluginAsset(WRAPPER_NAME) ?: throw IOException("asset missing")
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            target
        } catch (e: Exception) {
            context.logger.error("Shell Hooks: cannot install $WRAPPER_NAME; no hook will run", e)
            null
        }

    private fun ideVersion(): String? =
        runCatching {
            val app = context.androidContext.applicationContext
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull()

    private inline fun listen(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            context.logger.warn("Shell Hooks: no $what events: ${e.message}")
        } catch (e: LinkageError) {
            // A host older than 26.47 has no such method; that hook simply never fires.
            context.logger.warn("Shell Hooks: this IDE has no $what events")
        }
    }

    companion object {
        const val PLUGIN_ID = "org.appdevforall.shellhooks"
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"
        const val TAB_ID = "shell_hooks_tab"
        const val WRAPPER_NAME = "cogo-hook-run.sh"
        private const val VERIFIED_DIR = "verified"
        private const val FILE_ACTIVATED_DEBOUNCE_MS = 500L
        private const val ACTION_MENU_ORDER = 500

        @Volatile
        private var instance: ShellHooksPlugin? = null

        fun getInstance(): ShellHooksPlugin? = instance
    }
}
