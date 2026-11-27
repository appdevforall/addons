package org.appdevforall.codeonthego.layouteditor

import android.view.View
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService

object LayoutEditorDocs {

    const val CATEGORY = "plugin_org.appdevforall.codeonthego.layouteditor"
    private const val IDE_CATEGORY_JAVA = "java"
    private const val IDE_CATEGORY_XML = "xml"

    const val TAG_OVERVIEW = "layouteditor.overview"
    const val TAG_HELP = "layouteditor.help"
    const val TAG_VIEW_TYPE = "layouteditor.view_type"
    const val TAG_DEVICE_SIZE = "layouteditor.device_size"
    const val TAG_PALETTE = "layouteditor.palette"
    const val TAG_STRUCTURE = "layouteditor.structure"
    const val TAG_WIDGET = "layouteditor.widget"
    const val TAG_ATTRIBUTE = "layouteditor.attribute"
    const val TAG_ADD_ATTRIBUTE = "layouteditor.add_attribute"
    const val TAG_REMOVE_ATTRIBUTE = "layouteditor.remove_attribute"

    private fun service(): IdeTooltipService? =
        PluginFragmentHelper.getServiceRegistry(LayoutEditorPlugin.PLUGIN_ID)
            ?.get(IdeTooltipService::class.java)

    fun show(anchor: View, tag: String) {
        service()?.showTooltip(anchor, tag)
    }

    fun showWidget(anchor: View, className: String) {
        service()?.showTooltip(anchor, IDE_CATEGORY_JAVA, className)
    }

    fun showAttribute(anchor: View, attributeName: String) {
        service()?.showTooltip(anchor, IDE_CATEGORY_XML, attributeName.substringAfterLast(":"))
    }

    fun bindLongPress(view: View, tag: String) {
        view.setOnLongClickListener {
            show(it, tag)
            true
        }
    }
}
