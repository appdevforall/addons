package org.appdevforall.composepreview

import android.content.Context
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.ToolbarActionIds
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeUIService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.appdevforall.composepreview.compiler.SharedCompilerDaemon
import org.appdevforall.composepreview.domain.blankComments

/**
 * Entry point for the extracted Jetpack Compose preview feature.
 *
 * Surfaces a content-aware editor toolbar action / menu item (enabled only for a `.kt`
 * file containing `@Preview`) that opens the renderer full-screen via
 * [IdeUIService.openPluginScreen]. Depends only on the plugin-api contract.
 */
class ComposePreviewPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        pluginAndroidContext = context.androidContext
        Environment.init(context.androidContext, context.services.get(IdeEnvironmentService::class.java))
        context.logger.info("ComposePreviewPlugin initialized")
        return true
    }

    override fun activate(): Boolean = true

    override fun deactivate(): Boolean {
        SharedCompilerDaemon.shutdown()
        return true
    }

    override fun dispose() = SharedCompilerDaemon.shutdown()

    override fun getToolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction(
            id = "compose_preview",
            title = "Compose Preview",
            icon = R.drawable.ic_compose_preview,
            showAsAction = ShowAsAction.IF_ROOM,
            // Matches PreviewLayoutAction's toolbar slot so the Compose icon takes the
            // preview position; it is only shown for Compose files (see provider below),
            // and on those files the built-in preview is hidden via getHiddenToolbarActionIds.
            order = COMPOSE_PREVIEW_ORDER,
            action = { openPreviewIfValid() },
        ).apply {
            isVisibleProvider = { hasComposePreview() }
        }
    )

    override fun getHiddenToolbarActionIds(): Set<String> =
        if (hasComposePreview()) setOf(ToolbarActionIds.PREVIEW_LAYOUT) else emptySet()

    // Documentation shown when the user long-presses the toolbar action. The tag matches the one
    // the host derives for this action (pluginTooltipTag = "<pluginId>.<toolbarAction.id>"), and
    // entries are stored under the plugin's category ("plugin_<pluginId>"), so the host's existing
    // long-press tooltip lookup resolves to these. Content mirrors the built-in
    // ide/editor.compose.preview tooltip: summary = tier one, detail = tier two ("See More").
    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = "$PLUGIN_ID.compose_preview",
            summary = "Preview the Compose layout.",
            detail = "See how your Compose UI looks while you build it, without running the app on a device or emulator.",
            buttons = listOf(
                PluginTooltipButton(
                    description = "How it works",
                    uri = "index.html",
                    order = 0,
                ),
                PluginTooltipButton(
                    description = "Learn More",
                    uri = "i/plugins-adfa.html",
                    order = 1,
                    directPath = true,
                )
            )
        )
    )

    // Tier 3 documentation bundle: files under assets/docs/ are indexed at install time and served
    // from http://localhost:6174/plugin/<pluginId>/<file>. The "How it works" button links index.html.
    override fun getTier3DocsAssetPath(): String = "docs"

    private fun openPreviewIfValid() {
        val editor = context.services.get(IdeEditorService::class.java)
        val file = editor?.getCurrentFile()
        val source = editor?.getCurrentFileContent()
        if (file == null || source == null || !isComposePreviewSource(file.name, source)) {
            context.logger.warn("Compose Preview requires a .kt file containing @Preview")
            return
        }

        ComposePreviewState.set(file.absolutePath, source)
        scope.launch {
            try {
                if (editor?.saveFile(file) != true) {
                    context.logger.warn("Compose Preview could not save ${file.name} before opening")
                }
            } catch (e: SecurityException) {
                context.logger.warn("Compose Preview is not allowed to save ${file.name}: ${e.message}")
            }
            context.services.get(IdeUIService::class.java)
                ?.openPluginScreen(PLUGIN_ID, ComposePreviewFragment::class.java.name, "Compose Preview")
        }
    }

    private fun hasComposePreview(): Boolean {
        val editor = context.services.get(IdeEditorService::class.java) ?: return false
        val file = editor.getCurrentFile() ?: return false
        val source = editor.getCurrentFileContent() ?: return false
        if (!isComposePreviewSource(file.name, source)) return false
        SharedCompilerDaemon.warmUpOnce(context.androidContext)
        return true
    }

    private fun isComposePreviewSource(fileName: String, source: String): Boolean =
        fileName.endsWith(".kt") && PREVIEW_REGEX.containsMatchIn(blankComments(source))

    companion object {
        const val PLUGIN_ID = "org.appdevforall.composepreview"

        // Built-in PreviewLayoutAction is registered at toolbar order index 7; matching it
        // places the Compose preview icon in the same slot it replaces on Compose files.
        private const val COMPOSE_PREVIEW_ORDER = 7

        private val PREVIEW_REGEX = Regex("""@Preview\b""")

        @Volatile
        var pluginAndroidContext: Context? = null
            private set
    }
}
