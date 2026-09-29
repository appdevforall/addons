package com.itsaky.androidide.plugins.aicore.fragments

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.itsaky.androidide.plugins.aicore.R

/**
 * A Material 3 menu for the chat sidebar: a rounded, elevated surface of 48dp rows, each a leading
 * icon and a label.
 *
 * Drawn from the plugin's own resources rather than a PopupMenu styled through the theme: theme
 * attributes in a plugin can resolve to the host's styling instead of the plugin's.
 *
 * Long-pressing an entry closes the menu and shows that entry's help on the button that opened it.
 * Not on the entry itself: the host's tooltip is a PopupWindow too, and a PopupWindow anchored
 * inside another one throws BadTokenException, which takes the IDE down.
 *
 * @param showTooltip shows this plugin's help for a tag on a view right away.
 * @param showMessage tells the user something on the screen, such as why an entry is unavailable.
 */
internal class SidebarPopupMenu(
    private val showTooltip: (View, String) -> Boolean,
    private val showMessage: (String) -> Unit,
) {

    /**
     * One entry.
     *
     * @property icon the leading icon.
     * @property label what the entry reads.
     * @property tooltipTag the long-press help it carries.
     * @property tint the icon's color; the error color marks an entry that destroys something.
     * @property unavailableReason when set, the entry is dimmed and tapping it shows this message
     *   instead of running [onClick]. Still tappable, unlike a disabled view, so it can say why.
     * @property onClick what picking it does, called after the menu has closed.
     */
    data class Item(
        @param:DrawableRes val icon: Int,
        @param:StringRes val label: Int,
        val tooltipTag: String,
        @param:ColorRes val tint: Int = R.color.plugin_on_surface_variant,
        @param:StringRes val unavailableReason: Int? = null,
        val onClick: () -> Unit,
    )

    private var popup: PopupWindow? = null

    /**
     * Shows [items] under [anchor], end-aligned to it, replacing any menu this one already had up.
     *
     * @param anchor the button that opened the menu; its themed Context inflates the rows, so they
     *   follow the IDE's day/night setting.
     * @param items the entries, in the order they are listed.
     */
    fun show(anchor: View, items: List<Item>) {
        dismiss()
        val context = anchor.context
        val resources = context.resources
        val inflater = LayoutInflater.from(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val vertical = resources.getDimensionPixelSize(R.dimen.sidebar_menu_padding_vertical)
            setPadding(0, vertical, 0, vertical)
        }

        val window = PopupWindow(
            list,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        for (item in items) {
            val row = inflater.inflate(R.layout.list_item_sidebar_menu, list, false)
            row.findViewById<ImageView>(R.id.sidebar_menu_icon).apply {
                setImageResource(item.icon)
                imageTintList = context.getColorStateList(item.tint)
            }
            row.findViewById<TextView>(R.id.sidebar_menu_label).setText(item.label)
            row.accessibilityDelegate = MenuItemRole
            val reason = item.unavailableReason
            if (reason != null) {
                row.alpha = UNAVAILABLE_ALPHA
                ViewCompat.setStateDescription(row, context.getString(R.string.menu_item_unavailable))
            }
            row.setOnClickListener {
                window.dismiss()
                if (reason != null) showMessage(context.getString(reason)) else item.onClick()
            }
            row.setOnLongClickListener {
                window.dismiss()
                showTooltip(anchor, item.tooltipTag)
            }
            list.addView(row)
        }

        // Material's menu width rule: as wide as the widest entry, within 112dp and 280dp. Every
        // row is then stretched to it, so the ripple spans the whole menu.
        val unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        list.measure(unbounded, unbounded)
        window.width = list.measuredWidth.coerceIn(
            resources.getDimensionPixelSize(R.dimen.sidebar_menu_min_width),
            resources.getDimensionPixelSize(R.dimen.sidebar_menu_max_width),
        )
        for (index in 0 until list.childCount) {
            list.getChildAt(index).layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        }

        // The shape is the window's background, not the list's, so the elevation shadow follows
        // its rounded outline and a tap outside the menu dismisses it.
        window.setBackgroundDrawable(context.getDrawable(R.drawable.bg_sidebar_menu))
        window.elevation = resources.getDimension(R.dimen.sidebar_menu_elevation)
        window.setOnDismissListener { if (popup === window) popup = null }
        popup = window
        window.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    /** Closes the menu if it is up; call when the views it hangs off are going away. */
    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private companion object {
        /** Material's opacity for disabled content. */
        const val UNAVAILABLE_ALPHA = 0.38f
    }

    /** Announces a row as a button, so TalkBack says the entry can be activated. */
    private object MenuItemRole : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }
}
