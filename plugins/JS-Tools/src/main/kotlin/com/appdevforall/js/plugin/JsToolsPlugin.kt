package com.appdevforall.js.plugin

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

class JsToolsPlugin : IPlugin, BuildActionExtension, DocumentationExtension, LanguageExtension, UIExtension {

    private var pluginContext: PluginContext? = null
    private var templateService: IdeTemplateService? = null
    private var projectService: IdeProjectService? = null
    private var editorService: IdeEditorService? = null
    private var commandService: IdeCommandService? = null
    private var buildService: IdeBuildService? = null
    private var environmentService: IdeEnvironmentService? = null
    private var uiService: IdeUIService? = null
    private var tooltipService: IdeTooltipService? = null
    private var runtime: NodeRuntime? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var setupJob: Job? = null
    private val registeredTemplates = mutableListOf<String>()
    private var toolbarContainer: WeakReference<ViewGroup>? = null
    private val tooltipBindingScheduled = AtomicBoolean(false)
    private val domainRefreshRunning = AtomicBoolean(false)

    @Volatile
    private var jsProjectOpen = false

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
            Log.e(TAG, "IdeEnvironmentService unavailable; JS Tools cannot locate the terminal environment")
            return false
        }
        environmentService = environment
        runtime = NodeRuntime(context.resources.getPluginDirectory(), environment.getTmpDirectory().parentFile!!)
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
        environmentService = null
        uiService = null
        tooltipService = null
        runtime = null
    }

    override fun toolbarActionsToHide(): Set<String> {
        if (!isJsProjectOpen()) return emptySet()
        scheduleTooltipBinding()
        return ToolbarActionIds.BUILD_HIDEABLE
    }

    override fun getHiddenToolbarActionIds(): Set<String> =
        if (isJsProjectOpen()) setOf(QUICK_BUILD_ACTION_ID) else emptySet()

    override fun getBuildActions(): List<PluginBuildAction> {
        if (!isJsProjectOpen()) return emptyList()
        val root = projectService?.getCurrentProject()?.rootDir ?: return emptyList()
        scheduleTooltipBinding()

        val actions = mutableListOf(
            action(ACTION_RUN_APP, "Run the project's entry point with Node.js", R.drawable.ic_run_app, BuildActionCategory.BUILD, runAppScript(root), RUN_TIMEOUT_MS),
        )
        val current = editorService?.getCurrentFile()
        if (current != null && JsDomain.isRunnable(current)) {
            actions += action(ACTION_RUN_CURRENT_FILE, "Run ${current.name} with Node.js", R.drawable.ic_run_file, BuildActionCategory.BUILD, "exec node ${NodeRuntime.q(current)}", RUN_TIMEOUT_MS)
        }
        val tests = if (JsDomain.testScript(root) != null) "exec npm test" else "exec node --test"
        actions += action(ACTION_TEST, "Run the project's tests", R.drawable.ic_run_tests, BuildActionCategory.TEST, tests, TEST_TIMEOUT_MS)
        JsDomain.typeCheckConfig(root)?.let { config ->
            actions += action(ACTION_TYPE_CHECK, "Type-check the project with ${config.name}", R.drawable.ic_type_check, BuildActionCategory.TEST, "exec tsc -p ${NodeRuntime.q(config)} --noEmit", TYPE_CHECK_TIMEOUT_MS)
        }
        if (JsDomain.hasPackageJson(root)) {
            actions += action(ACTION_INSTALL, "Install the packages listed in package.json", R.drawable.ic_install_packages, BuildActionCategory.BUILD, "exec npm install", INSTALL_PACKAGES_TIMEOUT_MS)
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
        val tmp = environmentService?.getTmpDirectory() ?: return emptyList()
        val server = LanguageServerDefinition(
            command = runtime.languageServerCommand(),
            environment = runtime.environment(tmp),
            initializationOptions = mapOf("tsserver" to mapOf("fallbackPath" to runtime.tsserver.absolutePath)),
        )
        return LANGUAGES.map { (languageId, grammar, extensions) ->
            LanguageDefinition(
                languageId = languageId,
                fileExtensions = extensions,
                grammar = TreeSitterGrammar(name = grammar, queriesAssetPath = "treesitter/$grammar"),
                server = server,
            )
        }
    }

    override fun getTooltipCategory(): String = JsToolsDocumentation.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = JsToolsDocumentation.entries()

    override fun getTier3DocsAssetPath(): String = JsToolsDocumentation.DOCS_ASSET_PATH

    private fun action(
        id: String,
        description: String,
        icon: Int,
        category: BuildActionCategory,
        script: String,
        timeoutMs: Long,
    ): PluginBuildAction {
        val runtime = runtime!!
        val guard = "[ -x ${NodeRuntime.q(runtime.node)} ] || { echo ${NodeRuntime.q(RUNTIME_NOT_READY)} >&2; exit 3; }"
        return PluginBuildAction(
            id = id,
            name = actionLabel(id),
            description = description,
            icon = icon,
            category = category,
            command = CommandSpec.ShellCommand(
                executable = "sh",
                arguments = listOf("-c", "$guard\n$script"),
                environment = runtime.environment(environmentService!!.getTmpDirectory()),
            ),
            timeoutMs = timeoutMs,
        )
    }

    private fun runAppScript(root: File): String =
        when (val target = JsDomain.runTarget(root)) {
            is RunTarget.Source -> "exec node ${NodeRuntime.q(target.file)}"
            is RunTarget.Script -> "exec npm run ${NodeRuntime.q(target.name)}"
            is RunTarget.Invalid -> "echo ${NodeRuntime.q(target.reason)} >&2; exit 2"
            null -> "echo ${NodeRuntime.q(NO_ENTRY_POINT)} >&2; exit 2"
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

    private fun isJsProjectOpen(): Boolean {
        refreshDomain()
        return jsProjectOpen
    }

    private fun refreshDomain() {
        if (!domainRefreshRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val root = projectService?.getCurrentProject()?.rootDir
                val path = root?.absolutePath
                if (path == domainRootPath) return@launch
                jsProjectOpen = JsDomain.isJsProject(root)
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
            Log.e(TAG, "Could not build the JS Tools templates", e)
            notify("Could not add the JavaScript and TypeScript templates: ${e.message}")
        }
    }

    private fun registerTemplatesFromAssets() {
        val service = templateService ?: return
        val context = pluginContext ?: return
        val typesVersion = bundleEntries().first { it.name == TYPES_NODE }.version
        TEMPLATES.forEach { template ->
            val builder = service.createTemplateBuilder(template.name)
                .description(template.description)
                .thumbnailFromAssets("$TEMPLATE_ASSET_DIR/${template.directory}/thumb.png", context)
                .addTextParameter("Package name", "NPM_NAME", template.packageName)
            template.parameters.forEach { (label, identifier, default) -> builder.addTextParameter(label, identifier, default) }
            addTemplateFiles(builder, context, "$TEMPLATE_ASSET_DIR/${template.directory}", "", typesVersion)
            addAssetTree(builder, context, TYPES_ASSET_DIR, "node_modules")
            val file = builder.build(context.resources.getPluginDirectory())
            if (service.registerTemplate(file)) registeredTemplates += file.name
        }
    }

    private fun addTemplateFiles(
        builder: CgtTemplateBuilder,
        context: PluginContext,
        assetDir: String,
        projectDir: String,
        typesVersion: String,
    ) {
        val assets = context.androidContext.assets
        assets.list(assetDir).orEmpty().forEach { name ->
            val asset = "$assetDir/$name"
            val children = assets.list(asset).orEmpty()
            val target = projectPath(projectDir, if (name == "gitignore") ".gitignore" else name.removeSuffix(".peb"))
            when {
                children.isNotEmpty() -> addTemplateFiles(builder, context, asset, target, typesVersion)
                projectDir.isEmpty() && name == "thumb.png" -> Unit
                name.endsWith(".peb") -> {
                    val content = assets.open(asset).bufferedReader().use { it.readText() }
                    if (TYPES_VERSION_TOKEN in content) {
                        builder.addTemplateFile(target, content.replace(TYPES_VERSION_TOKEN, typesVersion))
                    } else {
                        builder.addTemplateFromAssets(target, asset, context)
                    }
                }
                else -> builder.addStaticFromAssets(target, asset, context)
            }
        }
    }

    private fun addAssetTree(
        builder: CgtTemplateBuilder,
        context: PluginContext,
        assetDir: String,
        projectDir: String,
    ) {
        val assets = context.androidContext.assets
        assets.list(assetDir).orEmpty().forEach { name ->
            val asset = "$assetDir/$name"
            val target = projectPath(projectDir, name)
            if (assets.list(asset).orEmpty().isNotEmpty()) {
                addAssetTree(builder, context, asset, target)
            } else {
                builder.addStaticFromAssets(target, asset, context)
            }
        }
    }

    private fun projectPath(directory: String, name: String): String = if (directory.isEmpty()) name else "$directory/$name"

    private suspend fun ensureRuntime() {
        val runtime = runtime ?: return
        val cmd = commandService ?: run {
            Log.e(TAG, "IdeCommandService unavailable; cannot install Node.js")
            return
        }
        val abi = deviceAbi() ?: run {
            notify("Node.js is not available for this device's CPU.")
            return
        }
        val entries = BundleEntry.forDevice(bundleEntries(), abi)
        if (runtime.isInstalled(entries)) {
            Log.i(TAG, "Node.js runtime is already installed")
            return
        }

        val missing = try {
            runtime.staging.deleteRecursively()
            runtime.archives.mkdirs()
            entries.filterNot { stageBundledArchive(it, runtime) }
        } catch (e: IOException) {
            Log.e(TAG, "Could not unpack the bundled Node.js archives", e)
            notify("Could not unpack the bundled Node.js: ${e.message}")
            return
        }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "The plugin does not carry ${missing.map { "${it.abi}/${it.fileName}" }}")
            notify("This copy of JS Tools is missing Node.js for $abi. Reinstall the plugin.")
            return
        }
        notify("Installing the bundled Node.js and TypeScript. This runs once.")

        val install = CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", runtime.installScript(entries)),
            environment = mapOf("TMPDIR" to environmentService!!.getTmpDirectory().absolutePath),
        )
        val result = try {
            cmd.executeCommand(install, timeoutMs = RUNTIME_INSTALL_TIMEOUT_MS).await()
        } catch (e: SecurityException) {
            Log.e(TAG, "Not allowed to install Node.js", e)
            notify("Could not install Node.js: ${e.message}")
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Could not start the Node.js install", e)
            notify("Could not start the Node.js install: ${e.message}")
            return
        }
        when (result) {
            is CommandResult.Success -> {
                try {
                    runtime.writeLaunchers(entries)
                } catch (e: IOException) {
                    Log.e(TAG, "Could not write the Node.js launchers", e)
                    notify("Could not finish installing Node.js: ${e.message}")
                    return
                }
                val node = entries.first { it.name.startsWith("nodejs") }.version.substringBefore('-')
                val typescript = entries.first { it.name == "typescript" }.version
                if (probe(cmd, runtime)) {
                    notify("Node.js $node and TypeScript $typescript are ready.")
                } else {
                    notify("Node.js was installed but does not start. See the IDE log for details.")
                }
            }
            is CommandResult.Failure -> {
                val detail = result.error ?: result.stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"
                Log.e(TAG, "Node.js install failed: ${result.stderr.take(4000)}")
                notify("Could not install Node.js: $detail")
            }
            is CommandResult.Cancelled -> Log.i(TAG, "Node.js install cancelled")
        }
    }

    private suspend fun probe(cmd: IdeCommandService, runtime: NodeRuntime): Boolean {
        val spec = CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", "node --version && tsc --version && typescript-language-server --version"),
            environment = runtime.environment(environmentService!!.getTmpDirectory()),
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

    private fun stageBundledArchive(entry: BundleEntry, runtime: NodeRuntime): Boolean {
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
            tooltips.showTooltip(view, JsToolsDocumentation.CATEGORY, JsToolsDocumentation.tagFor(actionId))
            true
        }
        (button.parent as? ViewGroup)?.let { toolbarContainer = WeakReference(it) }
        return true
    }

    private fun notify(message: String) {
        Log.i(TAG, message)
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, "JS Tools: $message", Toast.LENGTH_LONG).show()
        }
    }

    private data class Language(val id: String, val grammar: String, val extensions: Set<String>)

    private data class TemplateParameter(val label: String, val identifier: String, val default: String)

    private data class Template(
        val name: String,
        val directory: String,
        val description: String,
        val packageName: String,
        val parameters: List<TemplateParameter> = emptyList(),
    )

    companion object {
        private const val TAG = "JsToolsPlugin"
        private const val QUICK_BUILD_ACTION_ID = "ide.editor.build.quickBuild"

        internal const val PLUGIN_ID = "com.appdevforall.js.plugin"
        internal const val ACTION_RUN_APP = "js.run.app"
        internal const val ACTION_RUN_CURRENT_FILE = "js.run.currentFile"
        internal const val ACTION_TEST = "js.test"
        internal const val ACTION_TYPE_CHECK = "js.typeCheck"
        internal const val ACTION_INSTALL = "js.npm.install"

        internal val ACTION_LABELS: Map<String, String> = mapOf(
            ACTION_RUN_APP to "Run app",
            ACTION_RUN_CURRENT_FILE to "Run current file",
            ACTION_TEST to "Run tests",
            ACTION_TYPE_CHECK to "Type check",
            ACTION_INSTALL to "Install packages",
        )

        private val TOOLTIP_TARGETS: Map<String, String> =
            ACTION_LABELS.entries.flatMap { (id, label) -> listOf(label to id, "Cancel $label" to id) }.toMap()

        private val LANGUAGES = listOf(
            Language("javascript", "javascript", setOf("js", "mjs", "cjs")),
            Language("javascriptreact", "javascript", setOf("jsx")),
            Language("typescript", "typescript", setOf("ts", "mts", "cts")),
            Language("typescriptreact", "tsx", setOf("tsx")),
        )

        private val TEMPLATES = listOf(
            Template(
                name = "JavaScript App",
                directory = "javascript",
                description = "A Node.js program in JavaScript with a module, a test, and type checking",
                packageName = "my-app",
            ),
            Template(
                name = "TypeScript App",
                directory = "typescript",
                description = "A Node.js program in TypeScript that runs without a build step, with a test and strict type checking",
                packageName = "my-app",
            ),
            Template(
                name = "Node.js Web Server",
                directory = "webserver",
                description = "A TypeScript HTTP server on node:http with JSON routes, tests, and graceful shutdown",
                packageName = "my-server",
                parameters = listOf(TemplateParameter("Port", "PORT", "3000")),
            ),
        )

        private const val LOCK_ASSET = "toolchain/node-bundle.lock"
        private const val TOOLCHAIN_ASSET_DIR = "toolchain"
        private const val TEMPLATE_ASSET_DIR = "templates"
        private const val TYPES_ASSET_DIR = "types/node_modules"
        private const val TYPES_NODE = "@types/node"
        private const val TYPES_VERSION_TOKEN = "@TYPES_NODE_VERSION@"

        private const val RUNTIME_NOT_READY = "JS Tools is still installing Node.js. Try again once it reports that Node.js is ready."
        private const val NO_ENTRY_POINT =
            "No entry point found. Add src/index.js or src/index.ts, a \"main\" field, or a \"start\" script to package.json."

        private const val RUN_TIMEOUT_MS = 1_800_000L
        private const val TEST_TIMEOUT_MS = 900_000L
        private const val TYPE_CHECK_TIMEOUT_MS = 600_000L
        private const val INSTALL_PACKAGES_TIMEOUT_MS = 900_000L
        private const val RUNTIME_INSTALL_TIMEOUT_MS = 20 * 60_000L
        private const val PROBE_TIMEOUT_MS = 30_000L

        private const val ABI_ARM64 = "arm64-v8a"
        private const val ABI_ARM = "armeabi-v7a"
        private const val DOMAIN_UNSET = "\u0000unset"
    }
}
