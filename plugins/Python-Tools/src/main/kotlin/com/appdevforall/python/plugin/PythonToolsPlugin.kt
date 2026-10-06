package com.appdevforall.python.plugin

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.BuildActionCategory
import com.itsaky.androidide.plugins.extensions.BuildActionExtension
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.LanguageDefinition
import com.itsaky.androidide.plugins.extensions.LanguageExtension
import com.itsaky.androidide.plugins.extensions.LanguageServerDefinition
import com.itsaky.androidide.plugins.extensions.PluginBuildAction
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ToolbarActionIds
import com.itsaky.androidide.plugins.extensions.TreeSitterGrammar
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeCommandService
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Python Tools plugin.
 *
 * Contributes two project templates — a Flask web app and a starter Python project — built from
 * `.peb` Pebble assets. When the open project is in the Python domain it hides the built-in Gradle
 * toolbar actions and contributes Python build actions instead; non-Python projects are untouched.
 * On activate it ensures a Python interpreter is present, installing it via Termux `pkg` if not.
 *
 * Structure follows the flask-plugin standard (asset-based templates, vector icons, `sh -c`
 * commands). The Python-domain gating and the interpreter bootstrap are intentional additions: the
 * plugin must not steal the toolbar from Java/Kotlin/Android projects, and it must be usable on a
 * fresh device without Python pre-installed.
 */
class PythonToolsPlugin : IPlugin, BuildActionExtension, DocumentationExtension, LanguageExtension, UIExtension {

    private var pluginContext: PluginContext? = null
    private var templateService: IdeTemplateService? = null
    private var projectService: IdeProjectService? = null
    private var editorService: IdeEditorService? = null
    private var commandService: IdeCommandService? = null
    private var uiService: IdeUIService? = null
    private var tooltipService: IdeTooltipService? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var installJob: Job? = null
    private var toolbarContainer: WeakReference<ViewGroup>? = null
    private val tooltipBindingScheduled = AtomicBoolean(false)
    private val requirementsSyncedRoot = AtomicReference<String?>(null)

    override fun initialize(context: PluginContext): Boolean {
        pluginContext = context
        templateService = context.services.get(IdeTemplateService::class.java)
        projectService = context.services.get(IdeProjectService::class.java)
        editorService = context.services.get(IdeEditorService::class.java)
        commandService = context.services.get(IdeCommandService::class.java)
        uiService = context.services.get(IdeUIService::class.java)
        tooltipService = context.services.get(IdeTooltipService::class.java)
        Log.i(TAG, "Python Tools initialized")
        return true
    }

    override fun activate(): Boolean {
        installPyHooks()
        registerTemplates()
        installJob = scope.launch { if (ensurePython()) ensureBundledPackages() }
        Log.i(TAG, "Python Tools activated")
        return true
    }

    override fun deactivate(): Boolean {
        installJob?.cancel()
        templateService?.let { service ->
            service.unregisterTemplate(FLASK_CGT)
            service.unregisterTemplate(STARTER_CGT)
        }
        Log.i(TAG, "Python Tools deactivated")
        return true
    }

    override fun getLanguages(): List<LanguageDefinition> = listOf(
        LanguageDefinition(
            languageId = LANGUAGE_ID,
            fileExtensions = setOf("py"),
            grammar = TreeSitterGrammar(name = LANGUAGE_ID, queriesAssetPath = "treesitter/python"),
            server = LanguageServerDefinition(
                command = listOf(LANGUAGE_SERVER_COMMAND),
                environment = mapOf("PYTHONUNBUFFERED" to "1"),
            ),
        ),
    )

    override fun dispose() {
        scope.cancel()
        pluginContext = null
        templateService = null
        projectService = null
        editorService = null
        commandService = null
        uiService = null
        tooltipService = null
    }

    // region Build toolbar (Python domain only)

    override fun toolbarActionsToHide(): Set<String> {
        if (!isPythonProjectOpen()) return emptySet()
        syncRequirementsOnce()
        scheduleTooltipBinding()
        return ToolbarActionIds.BUILD_HIDEABLE
    }

    override fun getHiddenToolbarActionIds(): Set<String> =
        if (isPythonProjectOpen()) setOf(QUICK_BUILD_ACTION_ID) else emptySet()

    override fun getBuildActions(): List<PluginBuildAction> {
        if (!isPythonProjectOpen()) return emptyList()
        scheduleTooltipBinding()

        val actions = mutableListOf(
            PluginBuildAction(
                id = ACTION_RUN_APP,
                name = ACTION_LABELS.getValue(ACTION_RUN_APP),
                description = "Run the Python app (app.py, main.py, manage.py, __main__.py, or wsgi.py)",
                icon = R.drawable.ic_run_server,
                category = BuildActionCategory.BUILD,
                command = shell(
                    "if [ -f app.py ]; then exec python app.py; " +
                        "elif [ -f main.py ]; then exec python main.py; " +
                        "elif [ -f manage.py ]; then exec python manage.py runserver; " +
                        "elif [ -f __main__.py ]; then exec python __main__.py; " +
                        "elif [ -f wsgi.py ]; then exec python wsgi.py; " +
                        "else echo 'No Python entry point found " +
                        "(looked for app.py, main.py, manage.py, __main__.py, wsgi.py)'; exit 1; fi"
                ),
                timeoutMs = 1_800_000,
            ),
        )

        val current = editorService?.getCurrentFile()
        if (current != null && current.name.endsWith(".py")) {
            actions.add(
                PluginBuildAction(
                    id = ACTION_RUN_CURRENT_FILE,
                    name = ACTION_LABELS.getValue(ACTION_RUN_CURRENT_FILE),
                    description = "Run ${current.name}",
                    icon = R.drawable.ic_run_python,
                    category = BuildActionCategory.BUILD,
                    command = shell("exec python \"${current.absolutePath}\""),
                    timeoutMs = 1_800_000,
                ),
            )
        }

        actions.add(
            PluginBuildAction(
                id = ACTION_SYNC_DEPS,
                name = ACTION_LABELS.getValue(ACTION_SYNC_DEPS),
                description = "Install dependencies from requirements.txt",
                icon = R.drawable.ic_sync_deps,
                category = BuildActionCategory.BUILD,
                command = shell(requirementsInstallScript()),
                timeoutMs = 300_000,
            ),
        )
        actions.add(
            PluginBuildAction(
                id = ACTION_TEST,
                name = ACTION_LABELS.getValue(ACTION_TEST),
                description = "Run the test suite with pytest",
                icon = R.drawable.ic_run_tests,
                category = BuildActionCategory.TEST,
                command = shell("pip show pytest > /dev/null 2>&1 || pip install pytest -q; python -m pytest -q"),
                timeoutMs = 600_000,
            ),
        )
        return actions
    }

    override fun onActionStarted(actionId: String) {
        Log.i(TAG, "Action started: $actionId")
    }

    override fun onActionCompleted(actionId: String, result: CommandResult) {
        val status = when (result) {
            is CommandResult.Success -> "completed (${result.durationMs}ms)"
            is CommandResult.Failure -> "failed (exit ${result.exitCode})"
            is CommandResult.Cancelled -> "stopped"
        }
        Log.i(TAG, "Action $actionId $status")
        when (result) {
            is CommandResult.Failure ->
                if (isRunAction(actionId) && isMissingDependencyFailure(result)) {
                    installRequirements()
                } else {
                    notify("${actionLabel(actionId)} failed (exit ${result.exitCode})")
                }
            is CommandResult.Cancelled ->
                if (isRunAction(actionId)) notify("${actionLabel(actionId)} stopped.")
            is CommandResult.Success -> Unit
        }
    }

    private fun actionLabel(actionId: String): String = ACTION_LABELS[actionId] ?: actionId

    private fun isRunAction(actionId: String): Boolean =
        actionId == ACTION_RUN_APP || actionId == ACTION_RUN_CURRENT_FILE

    private fun isMissingDependencyFailure(result: CommandResult.Failure): Boolean {
        val output = result.stderr + "\n" + result.stdout
        return output.contains("ModuleNotFoundError") ||
            output.contains("No module named") ||
            output.contains("ImportError")
    }

    private fun installRequirements() {
        val cmd = commandService ?: return
        val root = projectService?.getCurrentProject()?.rootDir ?: return
        if (!File(root, "requirements.txt").exists()) {
            notify("Run failed: a dependency is missing and no requirements.txt was found.")
            return
        }
        notify("Missing dependencies — installing from requirements.txt…")
        scope.launch {
            val result = pipInstallRequirements(cmd) ?: return@launch
            when (result) {
                is CommandResult.Success -> notify("Dependencies installed. Tap Run again.")
                is CommandResult.Failure -> notify("Dependency install failed (exit ${result.exitCode}).")
                is CommandResult.Cancelled -> Unit
            }
        }
    }

    private fun syncRequirementsOnce() {
        val cmd = commandService ?: return
        val root = projectService?.getCurrentProject()?.rootDir ?: return
        val path = root.absolutePath
        if (requirementsSyncedRoot.getAndSet(path) == path) return
        scope.launch {
            installJob?.join()
            if (!File(root, "requirements.txt").exists()) return@launch
            val result = pipInstallRequirements(cmd) ?: return@launch
            when (result) {
                is CommandResult.Success ->
                    if (result.stdout.contains("Successfully installed")) notify("Project dependencies installed.")
                is CommandResult.Failure -> {
                    Log.e(TAG, "pip install -r requirements.txt failed: ${result.stderr.take(2000)}")
                    notify(
                        "Could not install project dependencies (exit ${result.exitCode}). It needs a network " +
                            "connection; tap ${actionLabel(ACTION_SYNC_DEPS)} to try again.",
                    )
                }
                is CommandResult.Cancelled -> Log.i(TAG, "pip install -r requirements.txt cancelled")
            }
        }
    }

    private suspend fun pipInstallRequirements(cmd: IdeCommandService): CommandResult? = try {
        cmd.executeCommand(
            shell(requirementsInstallScript()),
            timeoutMs = 300_000L,
        ).await()
    } catch (t: Throwable) {
        Log.e(TAG, "Could not start pip", t)
        notify("Could not start dependency install.")
        null
    }

    private fun isPythonProjectOpen(): Boolean =
        PythonDomain.isPythonProject(projectService?.getCurrentProject()?.rootDir)

    private fun shell(script: String): CommandSpec.ShellCommand {
        val environment = mutableMapOf("PYTHONUNBUFFERED" to "1")
        pyHooksDir()?.absolutePath?.let { environment["PYTHONPATH"] = it }
        return CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", script),
            environment = environment,
        )
    }

    private fun pyHooksDir(): File? =
        pluginContext?.resources?.getPluginDirectory()?.let { File(it, "pyhooks") }

    private fun installPyHooks() {
        val dir = pyHooksDir() ?: return
        runCatching {
            dir.mkdirs()
            File(dir, "sitecustomize.py").writeText(SITE_CUSTOMIZE)
        }.onFailure { Log.e(TAG, "Failed to install Python process hooks", it) }
    }

    // endregion

    // region Templates

    private fun registerTemplates() {
        val service = templateService ?: return
        val ctx = pluginContext ?: return

        runCatching {
            val flask = service.createTemplateBuilder("Python Flask App")
                .description("A Flask web app with routes, templates, static files, and error handling")
                .showPackageNameOption()
                .thumbnailFromAssets("templates/flask/thumb.png", ctx)
                .addTextParameter("Port", "PORT", "5000")
                .addTemplateFromAssets("app.py", "templates/flask/app.py.peb", ctx)
                .addTemplateFromAssets("config.py", "templates/flask/config.py.peb", ctx)
                .addTemplateFromAssets("templates/base.html", "templates/flask/base.html.peb", ctx)
                .addTemplateFromAssets("templates/index.html", "templates/flask/index.html.peb", ctx)
                .addTemplateFromAssets("templates/about.html", "templates/flask/about.html.peb", ctx)
                .addTemplateFromAssets("templates/404.html", "templates/flask/404.html.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/flask/README.md.peb", ctx)
                .addStaticFromAssets("static/css/style.css", "templates/flask/static/css/style.css", ctx)
                .addStaticFromAssets("requirements.txt", "templates/flask/requirements.txt", ctx)
                .addStaticFromAssets(".gitignore", "templates/flask/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(flask)

            val starter = service.createTemplateBuilder("Python Starter")
                .description("A minimal Python project with a main entry point")
                .showPackageNameOption()
                .thumbnailFromAssets("templates/starter/thumb.png", ctx)
                .addTemplateFromAssets("main.py", "templates/starter/main.py.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/starter/README.md.peb", ctx)
                .addStaticFromAssets("requirements.txt", "templates/starter/requirements.txt", ctx)
                .addStaticFromAssets(".gitignore", "templates/starter/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(starter)

            Log.i(TAG, "Registered Python templates: Flask + Starter")
        }.onFailure {
            Log.e(TAG, "Failed to register Python templates", it)
        }
    }

    // endregion

    override fun getTooltipCategory(): String = PythonToolsDocumentation.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = PythonToolsDocumentation.entries()

    override fun getTier3DocsAssetPath(): String = PythonToolsDocumentation.DOCS_ASSET_PATH

    private fun scheduleTooltipBinding() {
        if (tooltipService == null) return
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        if (!tooltipBindingScheduled.compareAndSet(false, true)) return
        activity.runOnUiThread {
            val decor = activity.window?.decorView
            if (decor == null) {
                tooltipBindingScheduled.set(false)
                return@runOnUiThread
            }
            decor.post {
                tooltipBindingScheduled.set(false)
                bindActionTooltips(decor)
            }
        }
    }

    private fun bindActionTooltips(decor: View) {
        val tooltips = tooltipService ?: return
        val container = toolbarContainer?.get()
        if (container != null && container.isAttachedToWindow && bindTooltipsIn(container, tooltips)) return
        (decor as? ViewGroup)?.let { bindTooltipsIn(it, tooltips) }
    }

    private fun bindTooltipsIn(group: ViewGroup, tooltips: IdeTooltipService): Boolean {
        var bound = false
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is ImageButton -> if (bindTooltip(child, tooltips)) bound = true
                is ViewGroup -> if (bindTooltipsIn(child, tooltips)) bound = true
            }
        }
        return bound
    }

    private fun bindTooltip(button: ImageButton, tooltips: IdeTooltipService): Boolean {
        val actionId = TOOLTIP_TARGETS[button.contentDescription?.toString()] ?: return false
        val tag = PythonToolsDocumentation.tagFor(actionId)
        button.setOnLongClickListener { view ->
            tooltips.showTooltip(view, PythonToolsDocumentation.CATEGORY, tag)
            true
        }
        (button.parent as? ViewGroup)?.let { toolbarContainer = WeakReference(it) }
        return true
    }

    // region Python interpreter bootstrap

    private suspend fun ensurePython(): Boolean {
        val cmd = commandService ?: run {
            Log.e(TAG, "IdeCommandService unavailable; cannot manage Python")
            return false
        }

        if (pythonAvailable(cmd)) {
            Log.i(TAG, "Python is already installed")
            return true
        }

        notify("Python not found. Installing via Termux…")
        return runInstall(cmd, "Python", "pkg install python -y", INSTALL_TIMEOUT_MS) && pythonAvailable(cmd)
    }

    private suspend fun ensureBundledPackages() {
        val cmd = commandService ?: return
        if (!extractWheelhouse()) {
            notify("Could not unpack the bundled Python packages.")
            return
        }
        if (probe(cmd, "command -v $LANGUAGE_SERVER_COMMAND >/dev/null && python -c 'import flask, gunicorn'")) {
            Log.i(TAG, "Bundled Python packages are already installed")
            return
        }

        notify("Installing the Python language server, Flask, and gunicorn. This runs once.")
        val wheelhouse = quote(wheelhouseDir().absolutePath)
        runInstall(
            cmd,
            "the Python language server, Flask, and gunicorn",
            "python -m pip install --disable-pip-version-check --no-index --find-links $wheelhouse " +
                "\"$LANGUAGE_SERVER_PACKAGE\" $BUNDLED_PACKAGES",
            INSTALL_TIMEOUT_MS,
        )
    }

    private suspend fun runInstall(
        cmd: IdeCommandService,
        label: String,
        script: String,
        timeoutMs: Long,
    ): Boolean {
        val result = try {
            cmd.executeCommand(shell(script), timeoutMs = timeoutMs).await()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start the $label install", t)
            notify("Could not start the $label install.")
            return false
        }
        return when (result) {
            is CommandResult.Success -> {
                notify("Installed $label.")
                true
            }
            is CommandResult.Failure -> {
                val detail = result.error ?: result.stderr.takeIf { it.isNotBlank() } ?: "exit ${result.exitCode}"
                Log.e(TAG, "$label install failed: $detail")
                notify("Failed to install $label: ${detail.lineSequence().last { it.isNotBlank() }}")
                false
            }
            is CommandResult.Cancelled -> {
                Log.i(TAG, "$label install cancelled")
                false
            }
        }
    }

    private suspend fun probe(cmd: IdeCommandService, script: String): Boolean = try {
        val result = cmd.executeCommand(shell(script), timeoutMs = PROBE_TIMEOUT_MS).await()
        result is CommandResult.Success && result.exitCode == 0
    } catch (t: Throwable) {
        false
    }

    private fun extractWheelhouse(): Boolean {
        val assets = pluginContext?.androidContext?.assets ?: return false
        val target = wheelhouseDir()
        return runCatching {
            target.mkdirs()
            ZipInputStream(assets.open(WHEELHOUSE_ASSET)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val out = File(target, entry.name)
                    if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                        throw SecurityException("Wheelhouse entry escapes its directory: ${entry.name}")
                    }
                    if (!out.exists()) {
                        val partial = File(target, "${entry.name}.partial")
                        partial.outputStream().use { zip.copyTo(it) }
                        if (!partial.renameTo(out)) throw IOException("Could not move ${partial.name} into place")
                    }
                }
            }
            true
        }.getOrElse {
            Log.e(TAG, "Failed to extract the bundled wheelhouse", it)
            false
        }
    }

    private fun pluginDir(): File = pluginContext!!.resources.getPluginDirectory()

    private fun wheelhouseDir(): File = File(pluginDir(), "wheelhouse")

    private fun quote(path: String): String = "\"" + path + "\""

    private fun requirementsInstallScript(): String =
        "python -m pip install --disable-pip-version-check --find-links ${quote(wheelhouseDir().absolutePath)} -r requirements.txt"

    private suspend fun pythonAvailable(cmd: IdeCommandService): Boolean = try {
        val result = cmd.executeCommand(shell("python --version"), timeoutMs = 20_000L).await()
        result is CommandResult.Success && result.exitCode == 0
    } catch (t: Throwable) {
        false
    }

    private fun notify(message: String) {
        Log.i(TAG, message)
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, "Python Tools: $message", Toast.LENGTH_SHORT).show()
        }
    }

    // endregion

    companion object {
        private const val TAG = "PythonToolsPlugin"
        private const val LANGUAGE_ID = "python"
        private const val LANGUAGE_SERVER_COMMAND = "pylsp"
        private const val LANGUAGE_SERVER_PACKAGE = "python-lsp-server[pyflakes,yapf]"
        private const val PROBE_TIMEOUT_MS = 20_000L
        private const val INSTALL_TIMEOUT_MS = 20 * 60_000L
        private const val WHEELHOUSE_ASSET = "python/wheelhouse.zip"
        private const val BUNDLED_PACKAGES = "flask gunicorn"
        private const val QUICK_BUILD_ACTION_ID = "ide.editor.build.quickBuild"

        internal const val PLUGIN_ID = "com.appdevforall.python.plugin"
        internal const val ACTION_RUN_APP = "python.run.app"
        internal const val ACTION_RUN_CURRENT_FILE = "python.run.currentFile"
        internal const val ACTION_SYNC_DEPS = "python.sync.deps"
        internal const val ACTION_TEST = "python.test"

        internal val ACTION_LABELS: Map<String, String> = mapOf(
            ACTION_RUN_APP to "Run app",
            ACTION_RUN_CURRENT_FILE to "Run current file",
            ACTION_SYNC_DEPS to "Install requirements",
            ACTION_TEST to "Run tests",
        )

        private val TOOLTIP_TARGETS: Map<String, String> =
            ACTION_LABELS.entries.flatMap { (id, label) -> listOf(label to id, "Cancel " + label to id) }.toMap()

        private const val FLASK_CGT = "PythonFlaskApp.cgt"
        private const val STARTER_CGT = "PythonStarter.cgt"

        private val SITE_CUSTOMIZE = """
            import ctypes
            import signal

            _libc = None
            for _name in ("libc.so", "libc.so.6", None):
                try:
                    _libc = ctypes.CDLL(_name, use_errno=True)
                    break
                except OSError:
                    _libc = None
            if _libc is not None:
                try:
                    _libc.prctl(1, signal.SIGKILL)
                except Exception:
                    pass
        """.trimIndent()
    }
}
