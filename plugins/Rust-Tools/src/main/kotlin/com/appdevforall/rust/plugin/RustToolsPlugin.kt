package com.appdevforall.rust.plugin

import android.os.StatFs
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
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class RustToolsPlugin : IPlugin, BuildActionExtension, DocumentationExtension, LanguageExtension, UIExtension {

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
    private var rustProjectOpen = false

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
            Log.e(TAG, "IdeEnvironmentService unavailable; Rust Tools cannot place build output")
            return false
        }
        environmentService = environment

        Log.i(TAG, "Rust Tools initialized")
        return true
    }

    override fun activate(): Boolean {
        buildService?.addBuildStatusListener(buildStatusListener)
        refreshDomain()
        registerTemplates()
        installJob = scope.launch { ensureToolchain() }
        Log.i(TAG, "Rust Tools activated")
        return true
    }

    override fun deactivate(): Boolean {
        buildService?.removeBuildStatusListener(buildStatusListener)
        installJob?.cancel()
        templateService?.let { service ->
            service.unregisterTemplate(STARTER_CGT)
            service.unregisterTemplate(HTTP_CGT)
        }
        Log.i(TAG, "Rust Tools deactivated")
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
        if (!isRustProjectOpen()) return emptySet()
        scheduleTooltipBinding()
        return ToolbarActionIds.BUILD_HIDEABLE
    }

    override fun getHiddenToolbarActionIds(): Set<String> =
        if (isRustProjectOpen()) setOf(QUICK_BUILD_ACTION_ID) else emptySet()

    override fun getBuildActions(): List<PluginBuildAction> {
        if (!android.os.Process.is64Bit() || !isRustProjectOpen()) return emptyList()
        scheduleTooltipBinding()

        val actions = mutableListOf(
            PluginBuildAction(
                id = ACTION_RUN_APP,
                name = ACTION_LABELS.getValue(ACTION_RUN_APP),
                description = "Build the crate with cargo and run it",
                icon = R.drawable.ic_run_app,
                category = BuildActionCategory.BUILD,
                command = shell("exec ${quote(tool("cargo"))} run"),
                timeoutMs = RUN_TIMEOUT_MS,
            ),
        )

        actions.add(
            PluginBuildAction(
                id = ACTION_RUN_CURRENT_FILE,
                name = ACTION_LABELS.getValue(ACTION_RUN_CURRENT_FILE),
                description = "Compile and run the open .rs file",
                icon = R.drawable.ic_run_file,
                category = BuildActionCategory.BUILD,
                command = shell(runCurrentFileScript()),
                timeoutMs = RUN_TIMEOUT_MS,
            ),
        )

        actions.add(
            PluginBuildAction(
                id = ACTION_FORMAT,
                name = ACTION_LABELS.getValue(ACTION_FORMAT),
                description = "Format every file in the crate with rustfmt",
                icon = R.drawable.ic_format,
                category = BuildActionCategory.BUILD,
                command = shell("exec ${quote(tool("cargo"))} fmt"),
                timeoutMs = FORMAT_TIMEOUT_MS,
            ),
        )
        actions.add(
            PluginBuildAction(
                id = ACTION_TEST,
                name = ACTION_LABELS.getValue(ACTION_TEST),
                description = "Run every test in the crate",
                icon = R.drawable.ic_run_tests,
                category = BuildActionCategory.TEST,
                command = shell("exec ${quote(tool("cargo"))} test"),
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
                val output = result.stderr + "\n" + result.stdout
                when {
                    output.contains(NO_RUST_FILE_MESSAGE) -> notify(NO_RUST_FILE_MESSAGE)
                    installJob?.isActive == true ->
                        notify("The Rust toolchain is still installing. Try again when it finishes.")
                    output.contains("could not find `Cargo.toml`") ->
                        notify("This project has no Cargo.toml in its root folder.")
                    NETWORK_FAILURES.any { output.contains(it) } ->
                        notify("Downloading crates needs a network connection.")
                    else -> notify("${actionLabel(actionId)} failed (exit ${result.exitCode})")
                }
            }
        }
    }

    private fun actionLabel(actionId: String): String = ACTION_LABELS[actionId] ?: actionId

    private fun isRunAction(actionId: String): Boolean =
        actionId == ACTION_RUN_APP || actionId == ACTION_RUN_CURRENT_FILE

    private val buildStatusListener = object : BuildStatusListener {
        override fun onBuildStarted() = Unit

        override fun onBuildFinished() = invalidateDomain()

        override fun onBuildFailed(error: String?) = invalidateDomain()
    }

    private fun invalidateDomain() {
        domainRootPath = DOMAIN_UNSET
        refreshDomain()
    }

    private fun isRustProjectOpen(): Boolean {
        refreshDomain()
        return rustProjectOpen
    }

    private fun refreshDomain() {
        if (!domainRefreshRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val root = projectService?.getCurrentProject()?.rootDir
                val path = root?.absolutePath
                if (path == domainRootPath) return@launch
                rustProjectOpen = RustDomain.isRustProject(root)
                val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return@launch
                domainRootPath = path
                activity.runOnUiThread { uiService?.refreshToolbarActions() }
            } finally {
                domainRefreshRunning.set(false)
            }
        }
    }

    private fun runCurrentFileScript(): String {
        val current = editorService?.getCurrentFile()
        if (current == null || !current.name.endsWith(".rs")) {
            return "echo ${quote(NO_RUST_FILE_MESSAGE)} >&2; exit 1"
        }
        val binary = quote(File(environmentService!!.getTmpDirectory(), BINARY_FILE).absolutePath)
        return "${quote(tool("rustc"))} --edition $RUST_EDITION -C linker=${quote(linkerWrapper().absolutePath)} " +
            "${quote(current.absolutePath)} -o $binary && exec $binary"
    }

    private fun quote(path: String): String = "\"" + path + "\""

    private fun pluginDir(): File = pluginContext!!.resources.getPluginDirectory()

    private fun toolchainDir(): File = File(pluginDir(), TOOLCHAIN_DIR)

    private fun tool(name: String): String = File(toolchainDir(), "bin/$name").absolutePath

    private fun linkerWrapper(): File = File(toolchainDir(), "bin/$LINKER_WRAPPER")

    private fun toolchainMarker(): File = File(toolchainDir(), MARKER_FILE)

    private fun rustEnvironment(targetDir: File): Map<String, String> = mapOf(
        "CARGO_HOME" to File(pluginDir(), "cargo-home").absolutePath,
        "CARGO_TARGET_DIR" to targetDir.absolutePath,
        "CARGO" to tool("cargo"),
        "RUSTC" to tool("rustc"),
        "RUSTDOC" to tool("rustdoc"),
        "RUSTFMT" to tool("rustfmt"),
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" to linkerWrapper().absolutePath,
        "TMPDIR" to environmentService!!.getTmpDirectory().absolutePath,
    )

    private fun withToolchainPath(script: String): String {
        val root = toolchainDir().absolutePath
        return "export PATH=\"$root/bin:\$PATH\" LD_LIBRARY_PATH=\"$root/lib:\$LD_LIBRARY_PATH\"; $script"
    }

    private fun shell(script: String): CommandSpec.ShellCommand =
        CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", withToolchainPath(script)),
            environment = rustEnvironment(File(pluginDir(), BUILD_TARGET_DIR)),
        )

    override fun getLanguages(): List<LanguageDefinition> = listOf(
        LanguageDefinition(
            languageId = LANGUAGE_ID,
            fileExtensions = setOf("rs"),
            grammar = TreeSitterGrammar(name = LANGUAGE_ID, queriesAssetPath = "treesitter/rust"),
            server = LanguageServerDefinition(
                command = listOf(SYSTEM_SH, "-c", withToolchainPath("exec ${quote(tool("rust-analyzer"))}")),
                environment = rustEnvironment(File(pluginDir(), ANALYZER_TARGET_DIR)),
            ),
        ),
    )

    private fun registerTemplates() {
        val service = templateService ?: return
        val ctx = pluginContext ?: return

        runCatching {
            val starter = service.createTemplateBuilder("Rust Starter")
                .description("A minimal Rust crate with a main function and unit tests")
                .thumbnailFromAssets("templates/starter/thumb.png", ctx)
                .addTextParameter("Crate name", "CRATE_NAME", "hello_rust")
                .addTemplateFromAssets("Cargo.toml", "templates/starter/Cargo.toml.peb", ctx)
                .addTemplateFromAssets("src/main.rs", "templates/starter/main.rs.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/starter/README.md.peb", ctx)
                .addStaticFromAssets(".gitignore", "templates/starter/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(starter)

            val http = service.createTemplateBuilder("Rust HTTP Server")
                .description("A std::net HTTP server with an HTML page and a JSON route")
                .thumbnailFromAssets("templates/httpserver/thumb.png", ctx)
                .addTextParameter("Crate name", "CRATE_NAME", "rust_server")
                .addTextParameter("Port", "PORT", "8080")
                .addTemplateFromAssets("Cargo.toml", "templates/httpserver/Cargo.toml.peb", ctx)
                .addTemplateFromAssets("src/main.rs", "templates/httpserver/main.rs.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/httpserver/README.md.peb", ctx)
                .addStaticFromAssets(".gitignore", "templates/httpserver/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(http)

            Log.i(TAG, "Registered Rust templates: Starter + HTTP Server")
        }.onFailure {
            Log.e(TAG, "Failed to register Rust templates", it)
        }
    }

    override fun getTooltipCategory(): String = RustToolsDocumentation.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = RustToolsDocumentation.entries()

    override fun getTier3DocsAssetPath(): String = RustToolsDocumentation.DOCS_ASSET_PATH

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
        val tag = RustToolsDocumentation.tagFor(actionId)
        button.setOnLongClickListener { view ->
            tooltips.showTooltip(view, RustToolsDocumentation.CATEGORY, tag)
            true
        }
        (button.parent as? ViewGroup)?.let { toolbarContainer = WeakReference(it) }
        return true
    }

    private suspend fun ensureToolchain(): Boolean {
        if (!android.os.Process.is64Bit()) {
            Log.e(TAG, "Rust Tools needs a 64-bit IDE process; this one is 32-bit")
            notify("Rust needs the 64-bit (arm64-v8a) build of Code on the Go.")
            return false
        }
        val cmd = commandService ?: run {
            Log.e(TAG, "IdeCommandService unavailable; cannot install the Rust toolchain")
            return false
        }
        val expected = bundledToolchainId() ?: run {
            Log.e(TAG, "This plugin build carries no Rust toolchain asset")
            notify("This build of Rust Tools has no bundled toolchain.")
            return false
        }
        if (installedToolchainId() == expected) {
            Log.i(TAG, "Rust toolchain is already installed")
            return true
        }

        val free = StatFs(pluginDir().absolutePath).availableBytes
        if (free < REQUIRED_FREE_BYTES) {
            Log.e(TAG, "Not enough space to install Rust: $free bytes free")
            notify("Installing Rust needs ${REQUIRED_FREE_BYTES / MEGABYTE} MB free; this device has ${free / MEGABYTE} MB.")
            return false
        }

        notify("Installing the bundled Rust toolchain (about 600 MB). This runs once.")
        if (!stageBundledArchive()) {
            notify("Could not copy the bundled Rust toolchain.")
            return false
        }

        val result = try {
            cmd.executeCommand(
                CommandSpec.ShellCommand(executable = "sh", arguments = listOf("-c", extractScript())),
                timeoutMs = INSTALL_TIMEOUT_MS,
            ).await()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start the Rust toolchain install", t)
            notify("Could not start the Rust toolchain install.")
            return false
        }

        when (result) {
            is CommandResult.Success -> Unit
            is CommandResult.Failure -> {
                val detail = listOfNotNull(result.error, result.stderr).joinToString("\n")
                val lastLine = detail.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"
                Log.e(TAG, "Rust toolchain install failed (exit ${result.exitCode}): $detail")
                notify("Failed to install Rust: $lastLine")
                return false
            }
            is CommandResult.Cancelled -> {
                Log.i(TAG, "Rust toolchain install cancelled")
                return false
            }
        }

        try {
            linkerWrapper().writeText(
                "#!/system/bin/sh\nexec ${quote(tool("clang"))} --sysroot=${quote(toolchainDir().absolutePath)} \"\$@\"\n",
            )
        } catch (e: IOException) {
            Log.e(TAG, "Could not write the linker wrapper", e)
            notify("Could not finish the Rust install: ${e.message}")
            return false
        }
        if (!linkerWrapper().setExecutable(true, true)) {
            Log.e(TAG, "Could not make the linker wrapper executable")
            notify("Could not finish the Rust install.")
            return false
        }

        if (!toolchainRuns(cmd)) {
            notify("Rust was unpacked but does not run.")
            return false
        }

        try {
            toolchainMarker().writeText(expected)
        } catch (e: IOException) {
            Log.e(TAG, "Could not record the Rust install", e)
            notify("Could not finish the Rust install: ${e.message}")
            return false
        }
        notify("Rust toolchain installed.")
        return true
    }

    private fun bundledToolchainId(): String? {
        val assets = pluginContext?.androidContext?.assets ?: return null
        val listed = assets.list(TOOLCHAIN_ASSET_DIR) ?: return null
        if (TOOLCHAIN_ARCHIVE !in listed || TOOLCHAIN_ID !in listed) return null
        return assets.open("$TOOLCHAIN_ASSET_DIR/$TOOLCHAIN_ID").bufferedReader().use { it.readText().trim() }
    }

    private fun installedToolchainId(): String? =
        toolchainMarker().takeIf { it.isFile }?.readText()?.trim()

    private fun stageBundledArchive(): Boolean {
        val assets = pluginContext?.androidContext?.assets ?: return false
        return try {
            assets.open("$TOOLCHAIN_ASSET_DIR/$TOOLCHAIN_ARCHIVE").use { input ->
                File(pluginDir(), STAGED_ARCHIVE).outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to stage the bundled Rust toolchain", e)
            false
        }
    }

    private fun extractScript(): String = listOf(
        "set -e",
        "cd ${quote(pluginDir().absolutePath)}",
        "rm -rf $TOOLCHAIN_DIR $TOOLCHAIN_DIR.partial",
        "mkdir $TOOLCHAIN_DIR.partial",
        "tar -xJf $STAGED_ARCHIVE -C $TOOLCHAIN_DIR.partial",
        "mv $TOOLCHAIN_DIR.partial/$TOOLCHAIN_DIR $TOOLCHAIN_DIR",
        "rmdir $TOOLCHAIN_DIR.partial",
        "rm -f $STAGED_ARCHIVE",
    ).joinToString("\n")

    private suspend fun toolchainRuns(cmd: IdeCommandService): Boolean = try {
        val probe = shell("${quote(tool("rustc"))} --version && ${quote(tool("cargo"))} --version")
        val result = cmd.executeCommand(probe, timeoutMs = PROBE_TIMEOUT_MS).await()
        if (result is CommandResult.Failure) {
            Log.e(TAG, "Rust toolchain probe failed: ${result.error ?: result.stderr}")
        }
        result is CommandResult.Success && result.exitCode == 0
    } catch (t: Throwable) {
        Log.e(TAG, "Could not start the Rust toolchain probe", t)
        false
    }

    private fun notify(message: String) {
        Log.i(TAG, message)
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, "Rust Tools: $message", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "RustToolsPlugin"
        private const val QUICK_BUILD_ACTION_ID = "ide.editor.build.quickBuild"

        internal const val PLUGIN_ID = "com.appdevforall.rust.plugin"
        internal const val ACTION_RUN_APP = "rust.run.app"
        internal const val ACTION_RUN_CURRENT_FILE = "rust.run.currentFile"
        internal const val ACTION_FORMAT = "rust.fmt"
        internal const val ACTION_TEST = "rust.test"

        internal val ACTION_LABELS: Map<String, String> = mapOf(
            ACTION_RUN_APP to "Run app",
            ACTION_RUN_CURRENT_FILE to "Run current file",
            ACTION_FORMAT to "Format code",
            ACTION_TEST to "Run tests",
        )

        private val TOOLTIP_TARGETS: Map<String, String> =
            ACTION_LABELS.entries.flatMap { (id, label) -> listOf(label to id, "Cancel " + label to id) }.toMap()

        private val NETWORK_FAILURES = listOf(
            "failed to download",
            "Couldn't resolve host",
            "failed to query replaced source registry",
            "failed to update registry",
        )

        private const val STARTER_CGT = "RustStarter.cgt"
        private const val HTTP_CGT = "RustHTTPServer.cgt"

        private const val BINARY_FILE = "cogo-rust-file"
        private const val NO_RUST_FILE_MESSAGE = "Open a .rs file to run it."
        private const val RUST_EDITION = "2024"

        private const val RUN_TIMEOUT_MS = 1_800_000L
        private const val FORMAT_TIMEOUT_MS = 300_000L
        private const val TEST_TIMEOUT_MS = 900_000L
        private const val INSTALL_TIMEOUT_MS = 20 * 60_000L
        private const val PROBE_TIMEOUT_MS = 60_000L

        private const val MEGABYTE = 1024L * 1024L
        private const val REQUIRED_FREE_BYTES = 900L * MEGABYTE

        private const val LANGUAGE_ID = "rust"
        private const val SYSTEM_SH = "/system/bin/sh"
        private const val DOMAIN_UNSET = "\u0000unset"

        private const val TOOLCHAIN_ASSET_DIR = "toolchain"
        private const val TOOLCHAIN_ARCHIVE = "rust-aarch64.tar.xz"
        private const val TOOLCHAIN_ID = "rust-aarch64.id"
        private const val TOOLCHAIN_DIR = "rust"
        private const val STAGED_ARCHIVE = "rust-toolchain.tar.xz"
        private const val MARKER_FILE = ".bundle-id"
        private const val LINKER_WRAPPER = "rust-cc"
        private const val BUILD_TARGET_DIR = "target/build"
        private const val ANALYZER_TARGET_DIR = "target/analyzer"
    }
}
