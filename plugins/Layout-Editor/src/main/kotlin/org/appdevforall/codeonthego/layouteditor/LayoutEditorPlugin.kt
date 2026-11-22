package org.appdevforall.codeonthego.layouteditor

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeUIService
import org.appdevforall.codeonthego.layouteditor.editor.convert.ConvertImportedXml
import org.appdevforall.codeonthego.layouteditor.tools.ValidationResult
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutParser
import java.io.File

/**
 * Entry point for the extracted Layout Editor feature.
 *
 * Surfaces a content-aware menu item (visible only when an Android layout `.xml` file is open)
 * that opens the editor full-screen via [IdeUIService.openPluginScreen], handing the target
 * layout over through [LayoutEditorState]. Mirrors Sketch-to-UI's proven pattern; the
 * host's built-in PreviewLayout action is removed separately when the editor leaves the host build.
 */
class LayoutEditorPlugin : IPlugin, UIExtension, DocumentationExtension, BuildStatusListener {

    private lateinit var context: PluginContext
    private var buildService: IdeBuildService? = null

    private var syncFailed = false

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        context.logger.info("LayoutEditorPlugin initialized")
        return true
    }

    override fun activate(): Boolean {
        buildService = context.services.get(IdeBuildService::class.java)?.also {
            it.addBuildStatusListener(this)
        }
        return true
    }

    override fun deactivate(): Boolean {
        buildService?.removeBuildStatusListener(this)
        return true
    }

    override fun dispose() {
        buildService?.removeBuildStatusListener(this)
        buildService = null
    }

    override fun onBuildStarted() {
        syncFailed = false
    }

    override fun onBuildFinished() {
        syncFailed = false
    }

    override fun onBuildFailed(error: String?) {
        syncFailed = true
    }

    override fun getToolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction(
            id = "layout_editor",
            title = "Layout Editor",
            icon = R.drawable.ic_preview_layout,
            showAsAction = ShowAsAction.IF_ROOM,
            order = PREVIEW_LAYOUT_ORDER,
            action = { openEditorIfValid() },
        ).apply {
            isEnabledProvider = { isLayoutXmlOpen() && !syncFailed }
        }
    )

    private fun openEditorIfValid() {
        val editorService = context.services.get(IdeEditorService::class.java)
        val file = editorService?.getCurrentFile()
        if (file == null || !isLayoutXml(file)) {
            context.logger.warn("Layout Editor requires an Android layout XML file")
            return
        }

        val uiService = context.services.get(IdeUIService::class.java)
        val activity = uiService?.getCurrentActivity()
        if (activity == null) {
            context.logger.warn("Layout Editor needs a foreground activity to open")
            return
        }

        val pluginContext = PluginFragmentHelper.getPluginContext(PLUGIN_ID)
        if (pluginContext == null) {
            context.logger.error("No plugin resource context; cannot validate ${file.name}")
            return
        }

        val errors = validationErrorsFor(editorService.getCurrentFileContent(), file, pluginContext)
        if (errors != null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(pluginContext.getString(R.string.xml_error_title))
                .setMessage(errors)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        editorService.saveCurrentFile()

        LayoutEditorState.set(
            filePath = file.absolutePath.substringBefore("layout"),
            layoutFileName = file.name.substringBefore("."),
        )
        uiService.openPluginScreen(PLUGIN_ID, LayoutEditorFragment::class.java.name, "Layout Editor")
    }

    private fun validationErrorsFor(
        xml: String?,
        file: File,
        pluginContext: android.content.Context,
    ): String? {
        if (xml == null) {
            context.logger.warn("No editor buffer available for ${file.name}")
            return pluginContext.getString(R.string.xml_error_read_failed, file.name)
        }

        if (xml.isBlank()) {
            return pluginContext.getString(R.string.xml_error_empty_file, file.name)
        }

        val converted = ConvertImportedXml(xml).getXmlConverted(pluginContext)
            ?: return pluginContext.getString(R.string.xml_error_not_well_formed)

        return when (val result = XmlLayoutParser(pluginContext).validateXml(converted, pluginContext)) {
            is ValidationResult.Success -> null
            is ValidationResult.Error -> result.formattedMessage
        }
    }

    private fun isLayoutXmlOpen(): Boolean {
        val file = context.services.get(IdeEditorService::class.java)?.getCurrentFile() ?: return false
        return isLayoutXml(file)
    }

    private fun isLayoutXml(file: File): Boolean =
        file.name.endsWith(".xml") && file.parentFile?.name?.startsWith("layout") == true


    override fun getTooltipCategory(): String = LayoutEditorDocs.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = "$PLUGIN_ID.layout_editor",
            summary = "If an XML layout file is open, tap this button to preview how the layout will look in the app.",
            detail = "",
            buttons = listOf(
                PluginTooltipButton(
                    description = "Learn more about layout",
                    uri = "i/layout-top.html",
                    order = 0,
                    directPath = true,
                ),
            ),
        ),
        PluginTooltipEntry(
            tag = LayoutEditorDocs.TAG_HELP,
            summary = "<b>Help</b><br>Open the Layout Editor documentation.",
            detail = "Everything in the editor is documented — long-press any control, palette item, or " +
                "attribute for its tooltip. Tap <i>Learn more</i> for the Layout Editor guide.",
            buttons = listOf(
                PluginTooltipButton(description = "Learn more", uri = "i/layout-editor.html", order = 0, directPath = true),
            ),
        ),
        PluginTooltipEntry(
            tag = LayoutEditorDocs.TAG_VIEW_TYPE,
            summary = "<b>View type</b><br>Switch between Design and Blueprint.",
            detail = "<b>Design</b> renders your layout the way it will look at runtime. <b>Blueprint</b> " +
                "shows a wireframe outline of each view's bounds, which makes spacing and nesting easier to see.",
            buttons = docButtons(),
        ),
        PluginTooltipEntry(
            tag = LayoutEditorDocs.TAG_DEVICE_SIZE,
            summary = "<b>Device size</b><br>Change the preview dimensions.",
            detail = "Preview the layout at different device sizes and orientations to check how it responds " +
                "without deploying to a device or emulator.",
            buttons = docButtons(),
        ),
        PluginTooltipEntry(
            tag = LayoutEditorDocs.TAG_PALETTE,
            summary = "<b>Palette</b><br>Drag widgets onto the canvas.",
            detail = "The palette groups the available views (Common, Text, Buttons, Widgets, Layouts, " +
                "Containers). Drag an item onto the canvas to add it; long-press an item for its description.",
            buttons = docButtons(),
        ),
        PluginTooltipEntry(
            tag = LayoutEditorDocs.TAG_STRUCTURE,
            summary = "<b>Component tree</b><br>The view hierarchy of your layout.",
            detail = "Shows every view in the layout as a tree. Tap a node to select and edit that view; " +
                "long-press a node for information about it.",
            buttons = docButtons(),
        ),
        PluginTooltipEntry(
            tag = LayoutEditorDocs.TAG_ADD_ATTRIBUTE,
            summary = "<b>Add attribute</b><br>Expose another attribute on the view.",
            detail = "Opens the list of attributes available for the selected view so you can add one that " +
                "isn't shown yet, then set its value.",
            buttons = docButtons(),
        ),
        PluginTooltipEntry(
            tag = LayoutEditorDocs.TAG_REMOVE_ATTRIBUTE,
            summary = "<b>Remove attribute</b><br>Clear this attribute from the view.",
            detail = "Removes the attribute's value from the selected view. Required attributes such as " +
                "<code>layout_width</code> and <code>layout_height</code> can't be removed.",
            buttons = docButtons(),
        ),
    ) + widgetAndAttributeEntries()

    private fun docButtons(): List<PluginTooltipButton> = listOf(
        PluginTooltipButton(description = "Learn more", uri = "i/plugins-adfa.html", order = 0, directPath = true),
    )

    private data class TooltipDef(
        val tag: String,
        val summary: String,
        val detail: String = "",
        val uri: String = "",
        val label: String = "",
    )

    private fun widgetAndAttributeEntries(): List<PluginTooltipEntry> {
        val assets = PluginFragmentHelper.getPluginContext(PLUGIN_ID)?.assets ?: return emptyList()
        return try {
            val json = assets.open("tooltips.json").bufferedReader().use { it.readText() }
            val defs: List<TooltipDef> =
                Gson().fromJson(json, object : TypeToken<List<TooltipDef>>() {}.type)
            defs.map { def ->
                PluginTooltipEntry(
                    tag = def.tag,
                    summary = def.summary,
                    detail = def.detail,
                    buttons = if (def.uri.isNotBlank()) {
                        listOf(
                            PluginTooltipButton(
                                description = def.label.ifBlank { "Learn more" },
                                uri = def.uri,
                                order = 0,
                                directPath = true,
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            }
        } catch (e: Exception) {
            context.logger.warn("Failed to load tooltips.json: ${e.message}")
            emptyList()
        }
    }


    companion object {
        const val PLUGIN_ID = "org.appdevforall.codeonthego.layouteditor"
        private const val PREVIEW_LAYOUT_ORDER = 7
    }
}
