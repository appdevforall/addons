package com.appdevforall.go.plugin

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class GoToolsPlugin : IPlugin, BuildActionExtension, DocumentationExtension, LanguageExtension, UIExtension {

    private var pluginContext: PluginContext? = null
    private var templateService: IdeTemplateService? = null
    private var projectService: IdeProjectService? = null
    private var editorService: IdeEditorService? = null
    private var commandService: IdeCommandService? = null
    private var buildService: IdeBuildService? = null
    private var environmentService: IdeEnvironmentService? = null
    private var uiService: IdeUIService? = null
    private var tooltipService: IdeTooltipService? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var installJob: Job? = null
    private var toolbarContainer: WeakReference<ViewGroup>? = null
    private val tooltipBindingScheduled = AtomicBoolean(false)
    private val domainRefreshRunning = AtomicBoolean(false)

    @Volatile
    private var goProjectOpen = false

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
            Log.e(TAG, "IdeEnvironmentService unavailable; Go Tools cannot place built binaries")
            return false
        }
        environmentService = environment

        Log.i(TAG, "Go Tools initialized")
        return true
    }

    override fun activate(): Boolean {
        buildService?.addBuildStatusListener(buildStatusListener)
        refreshDomain()
        registerTemplates()
        installJob = scope.launch { if (ensureTool(goTool())) ensureTool(goplsTool()) }
        Log.i(TAG, "Go Tools activated")
        return true
    }

    override fun deactivate(): Boolean {
        buildService?.removeBuildStatusListener(buildStatusListener)
        installJob?.cancel()
        templateService?.let { service ->
            service.unregisterTemplate(STARTER_CGT)
            service.unregisterTemplate(HTTP_CGT)
        }
        Log.i(TAG, "Go Tools deactivated")
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
    }

    override fun toolbarActionsToHide(): Set<String> {
        if (!isGoProjectOpen()) return emptySet()
        scheduleTooltipBinding()
        return ToolbarActionIds.BUILD_HIDEABLE
    }

    override fun getHiddenToolbarActionIds(): Set<String> =
        if (isGoProjectOpen()) setOf(QUICK_BUILD_ACTION_ID) else emptySet()

    override fun getBuildActions(): List<PluginBuildAction> {
        if (!isGoProjectOpen()) return emptyList()
        scheduleTooltipBinding()

        val actions = mutableListOf(
            PluginBuildAction(
                id = ACTION_RUN_APP,
                name = ACTION_LABELS.getValue(ACTION_RUN_APP),
                description = "Build the main package in the project root and run it",
                icon = R.drawable.ic_run_app,
                category = BuildActionCategory.BUILD,
                command = shell(buildAndExec(".", BINARY_APP)),
                timeoutMs = RUN_TIMEOUT_MS,
            ),
        )

        val current = editorService?.getCurrentFile()
        if (current != null && current.name.endsWith(".go") && !current.name.endsWith("_test.go")) {
            actions.add(
                PluginBuildAction(
                    id = ACTION_RUN_CURRENT_FILE,
                    name = ACTION_LABELS.getValue(ACTION_RUN_CURRENT_FILE),
                    description = "Build and run ${current.name}",
                    icon = R.drawable.ic_run_file,
                    category = BuildActionCategory.BUILD,
                    command = shell(buildAndExec(quote(current.absolutePath), BINARY_FILE)),
                    timeoutMs = RUN_TIMEOUT_MS,
                ),
            )
        }

        actions.add(
            PluginBuildAction(
                id = ACTION_TIDY,
                name = ACTION_LABELS.getValue(ACTION_TIDY),
                description = "Resolve imports and sync go.mod and go.sum",
                icon = R.drawable.ic_mod_tidy,
                category = BuildActionCategory.BUILD,
                command = shell("${goBinary()} mod tidy"),
                timeoutMs = TIDY_TIMEOUT_MS,
            ),
        )
        actions.add(
            PluginBuildAction(
                id = ACTION_TEST,
                name = ACTION_LABELS.getValue(ACTION_TEST),
                description = "Run every test in the module",
                icon = R.drawable.ic_run_tests,
                category = BuildActionCategory.TEST,
                command = shell("${goBinary()} test ./..."),
                timeoutMs = TEST_TIMEOUT_MS,
            ),
        )
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
                if (isRunAction(actionId)) notify("${actionLabel(actionId)} stopped.")
            }
            is CommandResult.Failure -> {
                Log.e(
                    TAG,
                    "Action $actionId failed (exit ${result.exitCode})" +
                        "\nstderr: ${result.stderr.take(2000)}" +
                        "\nstdout: ${result.stdout.take(2000)}" +
                        "\nerror: ${result.error}",
                )
                when {
                    isMissingModuleFailure(result) -> tidyModules()
                    isMissingGoModFailure(result) ->
                        notify("No go.mod here. Run \"go mod init <module>\" in the terminal first.")
                    else -> notify("${actionLabel(actionId)} failed (exit ${result.exitCode})")
                }
            }
        }
    }

    private fun actionLabel(actionId: String): String = ACTION_LABELS[actionId] ?: actionId

    private fun isRunAction(actionId: String): Boolean =
        actionId == ACTION_RUN_APP || actionId == ACTION_RUN_CURRENT_FILE

    private fun isMissingModuleFailure(result: CommandResult.Failure): Boolean {
        val output = result.stderr + "\n" + result.stdout
        return output.contains("no required module provides package") ||
            output.contains("cannot find module providing package") ||
            output.contains("missing go.sum entry")
    }

    private fun isMissingGoModFailure(result: CommandResult.Failure): Boolean =
        (result.stderr + "\n" + result.stdout).contains("go.mod file not found")

    private fun tidyModules() {
        val cmd = commandService ?: return
        notify("Missing modules - running go mod tidy...")
        scope.launch {
            val result = try {
                cmd.executeCommand(shell("${goBinary()} mod tidy"), timeoutMs = TIDY_TIMEOUT_MS).await()
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start go mod tidy", t)
                notify("Could not start go mod tidy.")
                return@launch
            }
            when (result) {
                is CommandResult.Success -> notify("Modules resolved. Tap Run again.")
                is CommandResult.Failure -> notify("go mod tidy failed (exit ${result.exitCode}). Downloading modules needs a network connection.")
                is CommandResult.Cancelled -> Unit
            }
        }
    }

    private val buildStatusListener = object : BuildStatusListener {
        override fun onBuildStarted() = Unit

        override fun onBuildFinished() = invalidateDomain()

        override fun onBuildFailed(error: String?) = invalidateDomain()
    }

    private fun invalidateDomain() {
        domainRootPath = DOMAIN_UNSET
        refreshDomain()
    }

    private fun isGoProjectOpen(): Boolean {
        refreshDomain()
        return goProjectOpen
    }

    private fun refreshDomain() {
        if (!domainRefreshRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val root = projectService?.getCurrentProject()?.rootDir
                val path = root?.absolutePath
                if (path == domainRootPath) return@launch
                goProjectOpen = GoDomain.isGoProject(root)
                val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return@launch
                domainRootPath = path
                activity.runOnUiThread { uiService?.refreshToolbarActions() }
            } finally {
                domainRefreshRunning.set(false)
            }
        }
    }

    private fun buildAndExec(target: String, binaryName: String): String {
        val out = quote(File(environmentService!!.getTmpDirectory(), binaryName).absolutePath)
        return "${goBinary()} build -o $out $target && exec $out"
    }

    private fun quote(path: String): String = "\"" + path + "\""

    private fun pluginDir(): File = pluginContext!!.resources.getPluginDirectory()

    private fun goRoot(): File = File(pluginDir(), "go")

    private fun goBinary(): String = quote(File(goRoot(), "bin/go").absolutePath)

    private fun goplsFile(): File = File(pluginDir(), "bin/gopls")

    private fun goEnvironment(): Map<String, String> {
        val tmp = environmentService!!.getTmpDirectory().absolutePath
        return mapOf(
            "CGO_ENABLED" to "0",
            "GOFLAGS" to "-buildvcs=false",
            "GOROOT" to goRoot().absolutePath,
            "GOPATH" to File(pluginDir(), "gopath").absolutePath,
            "GOCACHE" to File(pluginDir(), "gocache").absolutePath,
            "TMPDIR" to tmp,
            "GOTMPDIR" to tmp,
        )
    }

    private fun shell(script: String): CommandSpec.ShellCommand =
        CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", script),
            environment = goEnvironment(),
        )

    override fun getLanguages(): List<LanguageDefinition> = listOf(
        LanguageDefinition(
            languageId = LANGUAGE_ID,
            fileExtensions = setOf("go"),
            grammar = TreeSitterGrammar(name = LANGUAGE_ID, queriesAssetPath = "treesitter/go"),
            server = LanguageServerDefinition(
                command = listOf(goplsFile().absolutePath),
                environment = goEnvironment() + ("PATH" to "${File(goRoot(), "bin").absolutePath}:$SYSTEM_BIN"),
            ),
        ),
    )

    private fun registerTemplates() {
        val service = templateService ?: return
        val ctx = pluginContext ?: return

        runCatching {
            val starter = service.createTemplateBuilder("Go Starter")
                .description("A minimal Go module with a main package and a table-driven test")
                .thumbnailFromAssets("templates/starter/thumb.png", ctx)
                .addTextParameter("Module path", "MODULE_PATH", "example.com/myapp")
                .addTemplateFromAssets("go.mod", "templates/starter/go.mod.peb", ctx)
                .addTemplateFromAssets("main.go", "templates/starter/main.go.peb", ctx)
                .addTemplateFromAssets("main_test.go", "templates/starter/main_test.go.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/starter/README.md.peb", ctx)
                .addStaticFromAssets(".gitignore", "templates/starter/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(starter)

            val http = service.createTemplateBuilder("Go HTTP Server")
                .description("A net/http server with routes, JSON, and graceful shutdown")
                .thumbnailFromAssets("templates/httpserver/thumb.png", ctx)
                .addTextParameter("Module path", "MODULE_PATH", "example.com/server")
                .addTextParameter("Port", "PORT", "8080")
                .addTemplateFromAssets("go.mod", "templates/httpserver/go.mod.peb", ctx)
                .addTemplateFromAssets("main.go", "templates/httpserver/main.go.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/httpserver/README.md.peb", ctx)
                .addStaticFromAssets(".gitignore", "templates/httpserver/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(http)

            Log.i(TAG, "Registered Go templates: Starter + HTTP Server")
        }.onFailure {
            Log.e(TAG, "Failed to register Go templates", it)
        }
    }

    override fun getTooltipCategory(): String = GoToolsDocumentation.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = GoToolsDocumentation.entries()

    override fun getTier3DocsAssetPath(): String = GoToolsDocumentation.DOCS_ASSET_PATH

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
        val tag = GoToolsDocumentation.tagFor(actionId)
        button.setOnLongClickListener { view ->
            tooltips.showTooltip(view, GoToolsDocumentation.CATEGORY, tag)
            true
        }
        (button.parent as? ViewGroup)?.let { toolbarContainer = WeakReference(it) }
        return true
    }

    private suspend fun ensureTool(tool: TermuxTool?): Boolean {
        val cmd = commandService ?: run {
            Log.e(TAG, "IdeCommandService unavailable; cannot install bundled tools")
            return false
        }
        if (tool == null) {
            Log.e(TAG, "No bundled tools for ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
            notify("Go is not available for this device's CPU.")
            return false
        }

        if (toolAvailable(cmd, tool)) {
            Log.i(TAG, "${tool.label} is already installed")
            return true
        }

        val script = if (tool.bundledAsset != null && isAssetPresent(tool.bundledAsset)) {
            notify("Installing bundled ${tool.label}. This runs once.")
            if (!stageBundledArchive(tool)) {
                notify("Could not unpack the bundled ${tool.label}.")
                return false
            }
            extractCommands(tool).joinToString(" && ")
        } else {
            notify("Downloading ${tool.label} (about ${tool.downloadSize}). This runs once.")
            (listOf(downloadCommand(tool)) + extractCommands(tool)).joinToString(" && ")
        }

        val result = try {
            cmd.executeCommand(shell(script), timeoutMs = INSTALL_TIMEOUT_MS).await()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start the ${tool.label} install", t)
            notify("Could not start the ${tool.label} install.")
            return false
        }

        return when (result) {
            is CommandResult.Success ->
                if (toolAvailable(cmd, tool)) {
                    notify("${tool.label} installed.")
                    true
                } else {
                    notify("Install finished but ${tool.label} does not run.")
                    false
                }
            is CommandResult.Failure -> {
                val detail = result.error ?: result.stderr.takeIf { it.isNotBlank() } ?: "exit ${result.exitCode}"
                Log.e(TAG, "${tool.label} install failed: $detail")
                notify("Failed to install ${tool.label}: ${detail.lineSequence().last { it.isNotBlank() }}")
                false
            }
            is CommandResult.Cancelled -> {
                Log.i(TAG, "${tool.label} install cancelled")
                false
            }
        }
    }

    private fun goTool(): TermuxTool? = when (deviceAbi()) {
        ABI_ARM64 -> TermuxTool(
            label = "Go $GO_VERSION",
            bundledAsset = "$TOOLCHAIN_ASSET_DIR/$BUNDLED_GO_ARM64",
            download = GoRelease("$GO_DOWNLOAD_BASE/$GO_ARM64_ARCHIVE", GO_ARM64_SHA256),
            downloadSize = "38 MB",
            pathInDeb = TERMUX_GOROOT_IN_DEB,
            destination = goRoot(),
            probe = "${goBinary()} version",
        )
        ABI_ARM -> TermuxTool(
            label = "Go $GO_VERSION",
            bundledAsset = null,
            download = GoRelease("$GO_DOWNLOAD_BASE/$GO_ARMV6_ARCHIVE", GO_ARMV6_SHA256),
            downloadSize = "38 MB",
            pathInDeb = TERMUX_GOROOT_IN_DEB,
            destination = goRoot(),
            probe = "${goBinary()} version",
        )
        else -> null
    }

    private fun goplsTool(): TermuxTool? = when (deviceAbi()) {
        ABI_ARM64 -> TermuxTool(
            label = "gopls $GOPLS_VERSION",
            bundledAsset = "$TOOLCHAIN_ASSET_DIR/$BUNDLED_GOPLS_ARM64",
            download = GoRelease("$GOPLS_DOWNLOAD_BASE/$GOPLS_ARM64_ARCHIVE", GOPLS_ARM64_SHA256),
            downloadSize = "8 MB",
            pathInDeb = TERMUX_GOPLS_IN_DEB,
            destination = goplsFile(),
            probe = "${quote(goplsFile().absolutePath)} version",
        )
        ABI_ARM -> TermuxTool(
            label = "gopls $GOPLS_VERSION",
            bundledAsset = null,
            download = GoRelease("$GOPLS_DOWNLOAD_BASE/$GOPLS_ARM_ARCHIVE", GOPLS_ARM_SHA256),
            downloadSize = "8 MB",
            pathInDeb = TERMUX_GOPLS_IN_DEB,
            destination = goplsFile(),
            probe = "${quote(goplsFile().absolutePath)} version",
        )
        else -> null
    }

    private fun deviceAbi(): String? = when {
        Build.SUPPORTED_64_BIT_ABIS.contains(ABI_ARM64) -> ABI_ARM64
        Build.SUPPORTED_32_BIT_ABIS.contains(ABI_ARM) -> ABI_ARM
        else -> null
    }

    private fun isAssetPresent(assetPath: String): Boolean {
        val assets = pluginContext?.androidContext?.assets ?: return false
        val dir = assetPath.substringBeforeLast('/')
        return runCatching { assets.list(dir)?.contains(assetPath.substringAfterLast('/')) }.getOrNull() ?: false
    }

    private fun stageBundledArchive(tool: TermuxTool): Boolean {
        val assets = pluginContext?.androidContext?.assets ?: return false
        val assetPath = tool.bundledAsset ?: return false
        return runCatching {
            assets.open(assetPath).use { input ->
                archiveFile(tool).outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrElse {
            Log.e(TAG, "Failed to stage bundled ${tool.label}", it)
            false
        }
    }

    private fun archiveFile(tool: TermuxTool): File = File(pluginDir(), "${tool.destination.name}.deb")

    private fun downloadCommand(tool: TermuxTool): String {
        val archive = archiveFile(tool).absolutePath
        return "curl -fL --retry 3 -o ${quote(archive)} ${quote(tool.download.url)}" +
            " && echo ${quote("${tool.download.sha256}  $archive")} | sha256sum -c -"
    }

    private fun extractCommands(tool: TermuxTool): List<String> {
        val staging = File(pluginDir(), "deb-staging").absolutePath
        val destination = tool.destination.absolutePath
        val archive = archiveFile(tool).absolutePath
        return listOf(
            "rm -rf ${quote(staging)} ${quote(destination)}",
            "mkdir -p ${quote(staging)} ${quote(tool.destination.parentFile!!.absolutePath)}",
            "dpkg-deb -x ${quote(archive)} ${quote(staging)}",
            "mv ${quote("$staging${tool.pathInDeb}")} ${quote(destination)}",
            "rm -rf ${quote(staging)} ${quote(archive)}",
            tool.probe,
        )
    }

    private suspend fun toolAvailable(cmd: IdeCommandService, tool: TermuxTool): Boolean = try {
        val result = cmd.executeCommand(shell(tool.probe), timeoutMs = PROBE_TIMEOUT_MS).await()
        result is CommandResult.Success && result.exitCode == 0
    } catch (t: Throwable) {
        false
    }

    private data class GoRelease(val url: String, val sha256: String)

    private data class TermuxTool(
        val label: String,
        val bundledAsset: String?,
        val download: GoRelease,
        val downloadSize: String,
        val pathInDeb: String,
        val destination: File,
        val probe: String,
    )

    private fun notify(message: String) {
        Log.i(TAG, message)
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, "Go Tools: $message", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "GoToolsPlugin"
        private const val QUICK_BUILD_ACTION_ID = "ide.editor.build.quickBuild"

        internal const val PLUGIN_ID = "com.appdevforall.go.plugin"
        internal const val ACTION_RUN_APP = "go.run.app"
        internal const val ACTION_RUN_CURRENT_FILE = "go.run.currentFile"
        internal const val ACTION_TIDY = "go.mod.tidy"
        internal const val ACTION_TEST = "go.test"

        internal val ACTION_LABELS: Map<String, String> = mapOf(
            ACTION_RUN_APP to "Run app",
            ACTION_RUN_CURRENT_FILE to "Run current file",
            ACTION_TIDY to "Tidy modules",
            ACTION_TEST to "Run tests",
        )

        private val TOOLTIP_TARGETS: Map<String, String> =
            ACTION_LABELS.entries.flatMap { (id, label) -> listOf(label to id, "Cancel " + label to id) }.toMap()

        private const val STARTER_CGT = "GoStarter.cgt"
        private const val HTTP_CGT = "GoHTTPServer.cgt"

        private const val BINARY_APP = "cogo-go-app"
        private const val BINARY_FILE = "cogo-go-file"

        private const val RUN_TIMEOUT_MS = 1_800_000L
        private const val TIDY_TIMEOUT_MS = 600_000L
        private const val TEST_TIMEOUT_MS = 900_000L
        private const val INSTALL_TIMEOUT_MS = 20 * 60_000L
        private const val PROBE_TIMEOUT_MS = 20_000L

        private const val LANGUAGE_ID = "go"
        private const val SYSTEM_BIN = "/system/bin"
        private const val ABI_ARM64 = "arm64-v8a"
        private const val ABI_ARM = "armeabi-v7a"
        private const val DOMAIN_UNSET = "\u0000unset"
        private const val TOOLCHAIN_ASSET_DIR = "toolchain"

        private const val GO_VERSION = "1.27.1"
        private const val GO_DOWNLOAD_BASE = "https://packages.termux.dev/apt/termux-main/pool/main/g/golang"
        private const val TERMUX_GOROOT_IN_DEB = "/data/data/com.termux/files/usr/lib/go"
        private const val BUNDLED_GO_ARM64 = "golang-aarch64.deb"
        private const val GO_ARM64_ARCHIVE = "golang_3%3a1.27.1_aarch64.deb"
        private const val GO_ARM64_SHA256 = "95d7e100ed75278c74d01a1db4794b755526b70938d8ca4aca62b441658fe2f9"
        private const val GO_ARMV6_ARCHIVE = "golang_3%3a1.27.1_arm.deb"
        private const val GO_ARMV6_SHA256 = "7c3209562851ffe28e5b0f0259fee68ef8f59dff3ae07ac89b0756ac8ef512a9"

        private const val GOPLS_VERSION = "0.23.0"
        private const val GOPLS_DOWNLOAD_BASE = "https://packages.termux.dev/apt/termux-main/pool/main/g/gopls"
        private const val TERMUX_GOPLS_IN_DEB = "/data/data/com.termux/files/usr/bin/gopls"
        private const val BUNDLED_GOPLS_ARM64 = "gopls-aarch64.deb"
        private const val GOPLS_ARM64_ARCHIVE = "gopls_0.23.0_aarch64.deb"
        private const val GOPLS_ARM64_SHA256 = "6ae1811cd09168bb2edfcef4dcfe2cf868af9b94f8b97ae06579b0101274d73c"
        private const val GOPLS_ARM_ARCHIVE = "gopls_0.23.0_arm.deb"
        private const val GOPLS_ARM_SHA256 = "33c823efbc32fab4eba00398ce7c22beb63e8a1dfb0dda736a26fc5eed2e42e0"
    }
}
