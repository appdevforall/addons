package com.appdevforall.php.plugin

import android.os.Build
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
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeCommandService
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
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

class PhpToolsPlugin : IPlugin, BuildActionExtension, DocumentationExtension, LanguageExtension, UIExtension {

    private var pluginContext: PluginContext? = null
    private var templateService: IdeTemplateService? = null
    private var projectService: IdeProjectService? = null
    private var editorService: IdeEditorService? = null
    private var commandService: IdeCommandService? = null
    private var buildService: IdeBuildService? = null
    private var uiService: IdeUIService? = null
    private var tooltipService: IdeTooltipService? = null
    private var runtime: PhpRuntime? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var setupJob: Job? = null
    private val registeredTemplates = mutableListOf<String>()
    private var toolbarContainer: WeakReference<ViewGroup>? = null
    private val tooltipBindingScheduled = AtomicBoolean(false)
    private val domainRefreshRunning = AtomicBoolean(false)

    @Volatile
    private var phpProjectOpen = false

    @Volatile
    private var domainRootPath: String? = DOMAIN_UNSET

    override fun initialize(context: PluginContext): Boolean {
        pluginContext = context
        templateService = context.services.get(IdeTemplateService::class.java)
        projectService = context.services.get(IdeProjectService::class.java)
        editorService = context.services.get(IdeEditorService::class.java)
        commandService = context.services.get(IdeCommandService::class.java)
        buildService = context.services.get(IdeBuildService::class.java)
        uiService = context.services.get(IdeUIService::class.java)
        tooltipService = context.services.get(IdeTooltipService::class.java)

        val environment = context.services.get(IdeEnvironmentService::class.java)
        if (environment == null) {
            Log.e(TAG, "IdeEnvironmentService unavailable; PHP Tools cannot locate the terminal environment")
            return false
        }
        runtime = PhpRuntime(context.resources.getPluginDirectory(), environment.getTmpDirectory())
        return true
    }

    override fun activate(): Boolean {
        buildService?.addBuildStatusListener(buildStatusListener)
        refreshDomain()
        setupJob = scope.launch {
            registerTemplates()
            ensureRuntime()
        }
        return true
    }

    override fun deactivate(): Boolean {
        buildService?.removeBuildStatusListener(buildStatusListener)
        setupJob?.cancel()
        templateService?.let { service -> registeredTemplates.forEach { service.unregisterTemplate(it) } }
        registeredTemplates.clear()
        return true
    }

    override fun dispose() {
        scope.cancel()
        pluginContext = null
        templateService = null
        projectService = null
        editorService = null
        commandService = null
        buildService = null
        uiService = null
        tooltipService = null
        runtime = null
    }

    override fun toolbarActionsToHide(): Set<String> {
        if (!isPhpProjectOpen()) return emptySet()
        scheduleTooltipBinding()
        return ToolbarActionIds.BUILD_HIDEABLE
    }

    override fun getHiddenToolbarActionIds(): Set<String> =
        if (isPhpProjectOpen()) setOf(QUICK_BUILD_ACTION_ID) else emptySet()

    override fun getBuildActions(): List<PluginBuildAction> {
        if (!isPhpProjectOpen()) return emptyList()
        val root = projectService?.getCurrentProject()?.rootDir ?: return emptyList()
        scheduleTooltipBinding()

        val actions = mutableListOf(
            action(ACTION_RUN_APP, "Run the project with PHP", R.drawable.ic_run_app, BuildActionCategory.BUILD, runAppScript(root), RUN_TIMEOUT_MS),
        )
        val current = editorService?.getCurrentFile()
        if (current != null && PhpDomain.isRunnable(current)) {
            actions += action(ACTION_RUN_CURRENT_FILE, "Run ${current.name} with PHP", R.drawable.ic_run_file, BuildActionCategory.BUILD, "exec php ${PhpRuntime.q(current)}", RUN_TIMEOUT_MS)
        }
        actions += action(ACTION_TEST, "Run the project's tests", R.drawable.ic_run_tests, BuildActionCategory.TEST, testScript(root), TEST_TIMEOUT_MS)
        actions += action(ACTION_CHECK_SYNTAX, "Check every PHP file for syntax errors", R.drawable.ic_check_syntax, BuildActionCategory.TEST, CHECK_SYNTAX_SCRIPT, CHECK_TIMEOUT_MS)
        if (PhpDomain.hasComposerJson(root)) {
            actions += action(ACTION_INSTALL, "Install the packages listed in composer.json", R.drawable.ic_install_packages, BuildActionCategory.BUILD, "exec composer install", INSTALL_PACKAGES_TIMEOUT_MS)
        }
        return actions
    }

    override fun onActionStarted(actionId: String) {
        Log.i(TAG, "Action started: $actionId")
    }

    override fun onActionCompleted(actionId: String, result: CommandResult) {
        when (result) {
            is CommandResult.Success -> Log.i(TAG, "Action $actionId completed (${result.durationMs}ms)")
            is CommandResult.Cancelled -> {
                Log.i(TAG, "Action $actionId stopped")
                if (actionId == ACTION_RUN_APP || actionId == ACTION_RUN_CURRENT_FILE) notify("${actionLabel(actionId)} stopped.")
            }
            is CommandResult.Failure -> {
                Log.e(TAG, "Action $actionId failed (exit ${result.exitCode})\nstderr: ${result.stderr.take(2000)}\nerror: ${result.error}")
                notify("${actionLabel(actionId)} failed (exit ${result.exitCode})")
            }
        }
    }

    override fun getLanguages(): List<LanguageDefinition> {
        val runtime = runtime ?: return emptyList()
        return listOf(
            LanguageDefinition(
                languageId = LANGUAGE_ID,
                fileExtensions = PhpDomain.SOURCE_EXTENSIONS,
                grammar = TreeSitterGrammar(name = LANGUAGE_ID, queriesAssetPath = "treesitter/$LANGUAGE_ID"),
                server = LanguageServerDefinition(
                    command = runtime.languageServerCommand(),
                    environment = runtime.environment(),
                ),
            ),
        )
    }

    override fun getTooltipCategory(): String = PhpToolsDocumentation.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = PhpToolsDocumentation.entries()

    override fun getTier3DocsAssetPath(): String = PhpToolsDocumentation.DOCS_ASSET_PATH

    private fun action(
        id: String,
        description: String,
        icon: Int,
        category: BuildActionCategory,
        script: String,
        timeoutMs: Long,
    ): PluginBuildAction {
        val runtime = runtime!!
        val guard = "[ -x ${PhpRuntime.q(runtime.php)} ] || { echo ${PhpRuntime.q(RUNTIME_NOT_READY)} >&2; exit 3; }"
        return PluginBuildAction(
            id = id,
            name = actionLabel(id),
            description = description,
            icon = icon,
            category = category,
            command = CommandSpec.ShellCommand(
                executable = "sh",
                arguments = listOf("-c", "$guard\n$script"),
                environment = runtime.environment(),
            ),
            timeoutMs = timeoutMs,
        )
    }

    private fun runAppScript(root: File): String =
        when (val target = PhpDomain.runTarget(root)) {
            is RunTarget.Php -> "exec php " + target.arguments.joinToString(" ") { PhpRuntime.q(it) }
            is RunTarget.Script -> "exec composer run-script ${PhpRuntime.q(target.name)}"
            is RunTarget.Invalid -> "echo ${PhpRuntime.q(target.reason)} >&2; exit 2"
            null -> "echo ${PhpRuntime.q(NO_ENTRY_POINT)} >&2; exit 2"
        }

    private fun testScript(root: File): String =
        when {
            PhpDomain.hasPhpUnit(root) -> "exec php vendor/bin/phpunit"
            PhpDomain.hasTestScript(root) -> "exec composer run-script test"
            else -> BUILT_IN_TEST_SCRIPT
        }

    private fun actionLabel(actionId: String): String = ACTION_LABELS.getValue(actionId)

    private val buildStatusListener = object : BuildStatusListener {
        override fun onBuildStarted() = Unit

        override fun onBuildFinished() = invalidateDomain()

        override fun onBuildFailed(error: String?) = invalidateDomain()
    }

    private fun invalidateDomain() {
        domainRootPath = DOMAIN_UNSET
        refreshDomain()
    }

    private fun isPhpProjectOpen(): Boolean {
        refreshDomain()
        return phpProjectOpen
    }

    private fun refreshDomain() {
        if (!domainRefreshRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val root = projectService?.getCurrentProject()?.rootDir
                val path = root?.absolutePath
                if (path == domainRootPath) return@launch
                phpProjectOpen = PhpDomain.isPhpProject(root)
                val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return@launch
                domainRootPath = path
                activity.runOnUiThread { uiService?.refreshToolbarActions() }
            } finally {
                domainRefreshRunning.set(false)
            }
        }
    }

    private fun bundleEntries(): List<BundleEntry> {
        val assets = pluginContext!!.androidContext.assets
        return assets.open(LOCK_ASSET).bufferedReader().use { BundleEntry.parseLock(it.readText()) }
    }

    private fun registerTemplates() {
        try {
            registerTemplatesFromAssets()
        } catch (e: IOException) {
            Log.e(TAG, "Could not build the PHP Tools templates", e)
            notify("Could not add the PHP templates: ${e.message}")
        }
    }

    private fun registerTemplatesFromAssets() {
        val service = templateService ?: return
        val context = pluginContext ?: return
        TEMPLATES.forEach { template ->
            val builder = service.createTemplateBuilder(template.name)
                .description(template.description)
                .thumbnailFromAssets("$TEMPLATE_ASSET_DIR/${template.directory}/thumb.png", context)
                .addTextParameter("Composer package name", "COMPOSER_NAME", template.composerName)
            template.parameters.forEach { (label, identifier, default) -> builder.addTextParameter(label, identifier, default) }
            addTemplateFiles(builder, context, "$TEMPLATE_ASSET_DIR/${template.directory}", "")
            val file = builder.build(context.resources.getPluginDirectory())
            if (service.registerTemplate(file)) registeredTemplates += file.name
        }
    }

    private fun addTemplateFiles(
        builder: CgtTemplateBuilder,
        context: PluginContext,
        assetDir: String,
        projectDir: String,
    ) {
        val assets = context.androidContext.assets
        assets.list(assetDir).orEmpty().forEach { name ->
            val asset = "$assetDir/$name"
            val children = assets.list(asset).orEmpty()
            val target = projectPath(projectDir, if (name == "gitignore") ".gitignore" else name.removeSuffix(".peb"))
            when {
                children.isNotEmpty() -> addTemplateFiles(builder, context, asset, target)
                projectDir.isEmpty() && name == "thumb.png" -> Unit
                name.endsWith(".peb") -> builder.addTemplateFromAssets(target, asset, context)
                else -> builder.addStaticFromAssets(target, asset, context)
            }
        }
    }

    private fun projectPath(directory: String, name: String): String = if (directory.isEmpty()) name else "$directory/$name"

    private suspend fun ensureRuntime() {
        val runtime = runtime ?: return
        val cmd = commandService ?: run {
            Log.e(TAG, "IdeCommandService unavailable; cannot install PHP")
            return
        }
        val abi = deviceAbi() ?: run {
            notify("PHP is not available for this device's CPU.")
            return
        }
        val entries = BundleEntry.forDevice(bundleEntries(), abi)
        if (runtime.isInstalled(entries)) {
            Log.i(TAG, "PHP runtime is already installed")
            return
        }

        val missing = try {
            runtime.staging.deleteRecursively()
            runtime.archives.mkdirs()
            entries.filterNot { stageBundledArchive(it, runtime) }
        } catch (e: IOException) {
            Log.e(TAG, "Could not unpack the bundled PHP archives", e)
            notify("Could not unpack the bundled PHP: ${e.message}")
            return
        }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "The plugin does not carry ${missing.map { "${it.abi}/${it.fileName}" }}")
            notify("This copy of PHP Tools is missing PHP for $abi. Reinstall the plugin.")
            return
        }
        notify("Installing the bundled PHP, Composer, and phpactor. This runs once.")

        val install = CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", runtime.installScript(entries)),
            environment = runtime.environment(),
        )
        val result = try {
            cmd.executeCommand(install, timeoutMs = RUNTIME_INSTALL_TIMEOUT_MS).await()
        } catch (e: SecurityException) {
            Log.e(TAG, "Not allowed to install PHP", e)
            notify("Could not install PHP: ${e.message}")
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Could not start the PHP install", e)
            notify("Could not start the PHP install: ${e.message}")
            return
        }
        when (result) {
            is CommandResult.Success -> {
                try {
                    runtime.relocateShell()
                    runtime.writeLaunchers(entries)
                } catch (e: IOException) {
                    Log.e(TAG, "Could not write the PHP launchers", e)
                    notify("Could not finish installing PHP: ${e.message}")
                    return
                }
                val php = entries.first { it.name == "php" }.version
                val phpactor = entries.first { it.name == "phpactor" }.version
                if (probe(cmd, runtime)) {
                    notify("PHP $php and phpactor $phpactor are ready.")
                } else {
                    notify("PHP was installed but does not start. See the IDE log for details.")
                }
            }
            is CommandResult.Failure -> {
                val detail = result.error ?: result.stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"
                Log.e(TAG, "PHP install failed: ${result.stderr.take(4000)}")
                notify("Could not install PHP: $detail")
            }
            is CommandResult.Cancelled -> Log.i(TAG, "PHP install cancelled")
        }
    }

    private suspend fun probe(cmd: IdeCommandService, runtime: PhpRuntime): Boolean {
        val spec = CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", "php --version && composer --version && phpactor --version"),
            environment = runtime.environment(),
        )
        val result = try {
            cmd.executeCommand(spec, timeoutMs = PROBE_TIMEOUT_MS).await()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Could not start the runtime probe", e)
            return false
        }
        return when (result) {
            is CommandResult.Success -> {
                Log.i(TAG, "Runtime probe: ${result.stdout.trim()}")
                true
            }
            is CommandResult.Failure -> {
                Log.e(TAG, "Runtime probe failed (exit ${result.exitCode}): ${result.stderr.take(2000)}")
                false
            }
            is CommandResult.Cancelled -> false
        }
    }

    private fun stageBundledArchive(entry: BundleEntry, runtime: PhpRuntime): Boolean {
        val assets = pluginContext!!.androidContext.assets
        val directory = "$TOOLCHAIN_ASSET_DIR/${entry.abi}"
        if (entry.fileName !in assets.list(directory).orEmpty()) return false
        assets.open("$directory/${entry.fileName}").use { input ->
            File(runtime.archives, entry.fileName).outputStream().use { output -> input.copyTo(output) }
        }
        return true
    }

    private fun deviceAbi(): String? = when {
        Build.SUPPORTED_64_BIT_ABIS.contains(ABI_ARM64) -> ABI_ARM64
        Build.SUPPORTED_32_BIT_ABIS.contains(ABI_ARM) -> ABI_ARM
        else -> null
    }

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
        button.setOnLongClickListener { view ->
            tooltips.showTooltip(view, PhpToolsDocumentation.CATEGORY, PhpToolsDocumentation.tagFor(actionId))
            true
        }
        (button.parent as? ViewGroup)?.let { toolbarContainer = WeakReference(it) }
        return true
    }

    private fun notify(message: String) {
        Log.i(TAG, message)
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, "PHP Tools: $message", Toast.LENGTH_LONG).show()
        }
    }

    private data class TemplateParameter(val label: String, val identifier: String, val default: String)

    private data class Template(
        val name: String,
        val directory: String,
        val description: String,
        val composerName: String,
        val parameters: List<TemplateParameter> = emptyList(),
    )

    companion object {
        private const val TAG = "PhpToolsPlugin"
        private const val QUICK_BUILD_ACTION_ID = "ide.editor.build.quickBuild"
        private const val LANGUAGE_ID = "php"

        internal const val PLUGIN_ID = "com.appdevforall.php.plugin"
        internal const val ACTION_RUN_APP = "php.run.app"
        internal const val ACTION_RUN_CURRENT_FILE = "php.run.currentFile"
        internal const val ACTION_TEST = "php.test"
        internal const val ACTION_CHECK_SYNTAX = "php.lint"
        internal const val ACTION_INSTALL = "php.composer.install"

        internal val ACTION_LABELS: Map<String, String> = mapOf(
            ACTION_RUN_APP to "Run app",
            ACTION_RUN_CURRENT_FILE to "Run current file",
            ACTION_TEST to "Run tests",
            ACTION_CHECK_SYNTAX to "Check syntax",
            ACTION_INSTALL to "Install dependencies",
        )

        private val TOOLTIP_TARGETS: Map<String, String> =
            ACTION_LABELS.entries.flatMap { (id, label) -> listOf(label to id, "Cancel $label" to id) }.toMap()

        private val TEMPLATES = listOf(
            Template(
                name = "PHP Script",
                directory = "script",
                description = "A command-line PHP program with a class and tests that run with no packages to install",
                composerName = "example/my-script",
            ),
            Template(
                name = "PHP Web App",
                directory = "webapp",
                description = "A website and JSON API on PHP's built-in web server, with routes and tests",
                composerName = "example/my-web-app",
                parameters = listOf(TemplateParameter("Port", "PORT", PhpDomain.WEB_PORT.toString())),
            ),
        )

        private const val LOCK_ASSET = "toolchain/php-bundle.lock"
        private const val TOOLCHAIN_ASSET_DIR = "toolchain"
        private const val TEMPLATE_ASSET_DIR = "templates"

        private const val RUNTIME_NOT_READY = "PHP Tools is still installing PHP. Try again once it reports that PHP is ready."
        private val NO_ENTRY_POINT =
            "No entry point found. Add index.php or main.php, public/index.php for a website, " +
                "or a \"start\" script to composer.json."

        private val BUILT_IN_TEST_SCRIPT = """
            set -- tests/*Test.php
            [ -e "${'$'}1" ] || { echo "No tests found. Add files named tests/*Test.php, or a \"test\" script to composer.json." >&2; exit 2; }
            failed=0
            for test in "${'$'}@"; do
              echo ">> ${'$'}test"
              php -d zend.assertions=1 -d assert.exception=1 "${'$'}test" || failed=${'$'}((failed + 1))
            done
            echo ">> Ran ${'$'}# test file(s), ${'$'}failed failed"
            [ "${'$'}failed" -eq 0 ]
        """.trimIndent()

        private const val CHECK_SYNTAX_SCRIPT =
            "find . -path ./vendor -prune -o -name '*.php' -print0 | xargs -0 -r -n 1 php -l"

        private const val RUN_TIMEOUT_MS = 1_800_000L
        private const val TEST_TIMEOUT_MS = 900_000L
        private const val CHECK_TIMEOUT_MS = 600_000L
        private const val INSTALL_PACKAGES_TIMEOUT_MS = 900_000L
        private const val RUNTIME_INSTALL_TIMEOUT_MS = 20 * 60_000L
        private const val PROBE_TIMEOUT_MS = 60_000L

        private const val ABI_ARM64 = "arm64-v8a"
        private const val ABI_ARM = "armeabi-v7a"
        private const val DOMAIN_UNSET = "\u0000unset"
    }
}
