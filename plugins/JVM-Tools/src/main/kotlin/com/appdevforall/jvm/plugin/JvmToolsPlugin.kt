package com.appdevforall.jvm.plugin

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.BuildActionCategory
import com.itsaky.androidide.plugins.extensions.BuildActionExtension
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginBuildAction
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.ToolbarActionIds
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class JvmToolsPlugin : IPlugin, BuildActionExtension, UIExtension, DocumentationExtension {

    private var pluginContext: PluginContext? = null
    private var projectService: IdeProjectService? = null
    private var editorService: IdeEditorService? = null
    private var environmentService: IdeEnvironmentService? = null
    private var templateService: IdeTemplateService? = null
    private var uiService: IdeUIService? = null
    private var tooltipService: IdeTooltipService? = null
    private var configurations: RunConfigurationStore? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val registeredTemplates = mutableListOf<String>()
    private val tooltipBindingScheduled = AtomicBoolean(false)

    override fun initialize(context: PluginContext): Boolean {
        pluginContext = context
        projectService = context.services.get(IdeProjectService::class.java)
        editorService = context.services.get(IdeEditorService::class.java)
        environmentService = context.services.get(IdeEnvironmentService::class.java)
        templateService = context.services.get(IdeTemplateService::class.java)
        uiService = context.services.get(IdeUIService::class.java)
        tooltipService = context.services.get(IdeTooltipService::class.java)
        configurations = RunConfigurationStore(File(context.resources.getPluginDirectory(), CONFIGURATIONS_FILE))
        return true
    }

    override fun activate(): Boolean {
        registerTemplates()
        return true
    }

    override fun deactivate(): Boolean {
        templateService?.let { service -> registeredTemplates.forEach { service.unregisterTemplate(it) } }
        registeredTemplates.clear()
        return true
    }

    override fun dispose() {
        scope.cancel()
        pluginContext = null
        projectService = null
        editorService = null
        environmentService = null
        templateService = null
        uiService = null
        tooltipService = null
        configurations = null
    }

    override fun toolbarActionsToHide(): Set<String> {
        if (!isJvmProjectOpen()) return emptySet()
        scheduleTooltipBinding()
        return ToolbarActionIds.BUILD_HIDEABLE
    }

    override fun getHiddenToolbarActionIds(): Set<String> =
        if (isJvmProjectOpen()) setOf(QUICK_BUILD_ACTION_ID) else emptySet()

    override fun getToolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction(
            id = ACTION_RUN_CONFIGURATION,
            title = RUN_CONFIGURATION_LABEL,
            icon = R.drawable.ic_run_configuration,
            order = RUN_CONFIGURATION_ORDER,
            action = ::showRunConfiguration,
        ).apply { isVisibleProvider = ::isJvmProjectOpen }
    )

    override fun getBuildActions(): List<PluginBuildAction> {
        if (!isJvmProjectOpen()) return emptyList()
        val root = projectService?.getCurrentProject()?.rootDir ?: return emptyList()
        val toolchain = resolveToolchain() ?: return emptyList()
        scheduleTooltipBinding()
        val configuration = effectiveConfiguration(root, MainClassFinder.find(root))
        return listOf(
            PluginBuildAction(
                id = ACTION_RUN,
                name = RUN_LABEL,
                description = configuration?.let { "Compile the project and run ${it.mainClass}" }
                    ?: "Compile the project and run its main class",
                icon = R.drawable.ic_run_jvm,
                category = BuildActionCategory.BUILD,
                command = CommandSpec.ShellCommand("sh", listOf("-c", runScript(root, toolchain, configuration))),
                timeoutMs = RUN_TIMEOUT_MS,
            )
        )
    }

    override fun onActionStarted(actionId: String) {
        if (actionId != ACTION_RUN) return
        val editor = editorService ?: return
        val root = projectService?.getCurrentProject()?.rootDir?.canonicalFile ?: return
        val unsaved = editor.getModifiedFiles().filter { MainClassFinder.isSource(it) && it.canonicalFile.startsWith(root) }
        unsaved.forEach { file -> editor.getFileContent(file)?.let(file::writeText) }
        scope.launch { unsaved.forEach { editor.saveFile(it) } }
    }

    override fun getTooltipCategory(): String = JvmToolsDocumentation.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = JvmToolsDocumentation.entries()

    override fun getTier3DocsAssetPath(): String = JvmToolsDocumentation.DOCS_ASSET_PATH

    private fun isJvmProjectOpen(): Boolean = JvmDomain.isJvmProject(projectService?.getCurrentProject()?.rootDir)

    private fun effectiveConfiguration(root: File, mainClasses: List<MainClass>): RunConfiguration? {
        val saved = configurations?.get(root)
        if (saved != null && mainClasses.any { it.name == saved.mainClass }) return saved
        val current = editorService?.getCurrentFile()?.canonicalFile
        val main = mainClasses.firstOrNull { it.source.canonicalFile == current } ?: mainClasses.firstOrNull() ?: return null
        return RunConfiguration(main.name, saved?.arguments ?: "")
    }

    private fun showRunConfiguration() {
        val root = projectService?.getCurrentProject()?.rootDir ?: return
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        scope.launch {
            val mainClasses = withContext(Dispatchers.IO) { MainClassFinder.find(root) }
            val selected = effectiveConfiguration(root, mainClasses) ?: RunConfiguration("", "")
            RunConfigurationDialog.show(
                activity,
                mainClasses,
                selected,
                onHelp = { view ->
                    tooltipService?.showTooltip(
                        view,
                        JvmToolsDocumentation.CATEGORY,
                        JvmToolsDocumentation.tagFor(ACTION_RUN_CONFIGURATION),
                    )
                },
            ) { configuration ->
                scope.launch(Dispatchers.IO) { configurations?.put(root, configuration) }
            }
        }
    }

    private fun registerTemplates() {
        val service = templateService ?: return
        val context = pluginContext ?: return
        val template = service.createTemplateBuilder(TEMPLATE_NAME)
            .description("A command-line program in Java or Kotlin. Choose the language when you create it, then tap Run.")
            .showLanguageOption()
            .thumbnailFromAssets("templates/console/thumb.png", context)
            .addTemplateFromAssets("src/Main.java", "templates/console/Main.java.peb", context)
            .addTemplateFromAssets("src/Greeter.java", "templates/console/Greeter.java.peb", context)
            .addTemplateFromAssets("src/Main.kt", "templates/console/Main.kt.peb", context)
            .addTemplateFromAssets("src/Greeter.kt", "templates/console/Greeter.kt.peb", context)
            .addStaticFromAssets("README.md", "templates/console/README.md", context)
            .build(context.resources.getPluginDirectory())
        if (service.registerTemplate(template)) registeredTemplates += template.name
    }

    private fun resolveToolchain(): Toolchain? {
        val tmp = environmentService?.getTmpDirectory() ?: return null
        val prefix = tmp.parentFile ?: return null
        val jdk = File(prefix, "lib/jvm/java-21-openjdk/bin")
        return Toolchain(
            java = File(jdk, "java"),
            javac = File(jdk, "javac"),
            mavenRepository = File(prefix.parentFile, "home/maven/localMvnRepository"),
            workDirectory = File(tmp, "jvm-tools"),
        )
    }

    private fun runScript(root: File, toolchain: Toolchain, configuration: RunConfiguration?): String = buildString {
        append("JAVA=").append(shellQuote(toolchain.java.absolutePath)).append('\n')
        append("JAVAC=").append(shellQuote(toolchain.javac.absolutePath)).append('\n')
        append("MAVEN=").append(shellQuote(toolchain.mavenRepository.absolutePath)).append('\n')
        append("PROJECT=").append(shellQuote(root.absolutePath)).append('\n')
        append("WORK=").append(shellQuote(File(toolchain.workDirectory, root.absolutePath.hashCode().toUInt().toString(16)).absolutePath)).append('\n')
        append("MAIN=").append(shellQuote(configuration?.mainClass ?: "")).append('\n')
        append("set --")
        ProgramArguments.parse(configuration?.arguments ?: "").forEach { append(' ').append(shellQuote(it)) }
        append('\n')
        append(RUN_SCRIPT)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
                (decor as? ViewGroup)?.let(::bindTooltipsIn)
            }
        }
    }

    private fun bindTooltipsIn(group: ViewGroup) {
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is ImageButton -> bindTooltip(child)
                is ViewGroup -> bindTooltipsIn(child)
            }
        }
    }

    private fun bindTooltip(button: ImageButton) {
        val tooltips = tooltipService ?: return
        if (button.contentDescription?.toString() !in RUN_BUTTON_LABELS) return
        button.setOnLongClickListener { view: View ->
            tooltips.showTooltip(view, JvmToolsDocumentation.CATEGORY, JvmToolsDocumentation.tagFor(ACTION_RUN))
            true
        }
    }

    private data class Toolchain(val java: File, val javac: File, val mavenRepository: File, val workDirectory: File)

    companion object {
        internal const val PLUGIN_ID = "com.appdevforall.jvm.plugin"
        internal const val ACTION_RUN = "jvm.run"
        internal const val ACTION_RUN_CONFIGURATION = "jvm.runConfiguration"

        private const val RUN_LABEL = "Run"
        private const val RUN_CONFIGURATION_LABEL = "Run configuration"
        private val RUN_BUTTON_LABELS = setOf(RUN_LABEL, "Cancel $RUN_LABEL")
        private const val RUN_CONFIGURATION_ORDER = 14
        private const val RUN_TIMEOUT_MS = 30 * 60_000L
        private const val QUICK_BUILD_ACTION_ID = "ide.editor.build.quickBuild"
        private const val CONFIGURATIONS_FILE = "run-configurations.json"
        private const val TEMPLATE_NAME = "Java/Kotlin Program"

        private val RUN_SCRIPT: String = """
            set -u
            if [ -z "§MAIN" ]; then
              echo "No main class found. Add a Java class with public static void main(String[] args), or a Kotlin file with a top-level fun main()." >&2
              exit 2
            fi
            if [ ! -x "§JAVA" ] || [ ! -x "§JAVAC" ]; then
              echo "The device JDK is missing from §(dirname "§JAVA")." >&2
              exit 3
            fi
            cd "§PROJECT" || exit 2
            rm -rf "§WORK"
            mkdir -p "§WORK/classes"
            find . \( -name build -o -name '.?*' \) -prune -o -type f \( -name '*.java' -o -name '*.kt' \) -print | sort > "§WORK/sources"
            quote() { sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/.*/"&"/'; }
            grep '\.java§' "§WORK/sources" | quote > "§WORK/java-sources"
            CP="§WORK/classes"
            if grep -q '\.kt§' "§WORK/sources"; then
              find_jar() {
                for version in §(ls -1 "§MAVEN/§1" 2>/dev/null | sort -r); do
                  jar=§(ls -1 "§MAVEN/§1/§version"/*.jar 2>/dev/null | grep -vE '(-sources|-javadoc)\.jar§' | head -n1)
                  if [ -n "§jar" ]; then echo "§jar"; return 0; fi
                done
                return 1
              }
              compiler=§(find_jar org/jetbrains/kotlin/kotlin-compiler-embeddable) || { echo "The Kotlin compiler is missing from §MAVEN." >&2; exit 4; }
              stdlib=§(find_jar org/jetbrains/kotlin/kotlin-stdlib) || { echo "The Kotlin standard library is missing from §MAVEN." >&2; exit 4; }
              boot="§compiler:§stdlib"
              for artifact in org/jetbrains/kotlin/kotlin-script-runtime org/jetbrains/kotlin/kotlin-reflect \
                  org/jetbrains/kotlin/kotlin-daemon-embeddable org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm \
                  org/jetbrains/annotations; do
                jar=§(find_jar "§artifact") && boot="§boot:§jar"
              done
              quote < "§WORK/sources" > "§WORK/kotlin-sources"
              echo ">> Compiling Kotlin"
              "§JAVA" -Dkotlin.colors.enabled=false -cp "§boot" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
                -no-stdlib -no-reflect -classpath "§stdlib" -d "§WORK/classes" @"§WORK/kotlin-sources" || exit 1
              CP="§CP:§stdlib"
            fi
            if [ -s "§WORK/java-sources" ]; then
              echo ">> Compiling Java"
              "§JAVAC" -cp "§CP" -d "§WORK/classes" @"§WORK/java-sources" || exit 1
            fi
            echo ">> Running §MAIN"
            exec "§JAVA" -cp "§CP" "§MAIN" "§@"
        """.trimIndent().replace('§', '$')
    }
}
