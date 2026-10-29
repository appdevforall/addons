package org.appdevforall.maps.ui

import android.view.View
import com.itsaky.androidide.plugins.services.IdeTooltipService
import org.appdevforall.maps.MapsPlugin

/** Long-pressing this view shows the Maps tooltip entry for [tag]. */
internal fun View.showMapsTooltipOnLongPress(tag: String) {
    setOnLongClickListener { anchor -> showMapsTooltip(anchor, tag) }
}

/**
 * Shows the Maps tooltip entry for [tag] on [anchor], using the 3-arg lookup because the 2-arg
 * form searches the IDE's own category and renders `n/a`.
 *
 * @return false when the host has no tooltip service, so the long press is not consumed.
 */
internal fun showMapsTooltip(anchor: View, tag: String): Boolean {
    val context = MapsPlugin.pluginContext ?: return false
    val service = context.services.get(IdeTooltipService::class.java) ?: return false
    service.showTooltip(anchor, MapsPlugin.TOOLTIP_CATEGORY, tag)
    return true
}
