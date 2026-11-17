package org.appdevforall.shareyourapps.server

import android.graphics.Insets
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * Edge-to-edge (Android 15 and later): apps targeting Android 15+ are drawn behind the status and
 * navigation bars, and must keep their content clear themselves. This app targets Android 16, which
 * ignores the old opt-out. So each screen's content pads itself by how much the bars — and any
 * camera cutout — actually overlap it, measured where it really sits. On Android 16 the title bar
 * (the action bar) overlaps the content too, so its bottom edge counts as well, found and measured
 * in the window: where the title bar already pushes the content down, the content is below it, and
 * nothing more is added. On Android 14 and older, nothing changes.
 */
object SystemBars {
    fun fit(root: View) {
        if (Build.VERSION.SDK_INT < 35) return
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        (root as? ViewGroup)?.clipToPadding = false   // scrolling content can pass under the bars, but always scroll clear of them
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.post { pad(v, bars, base) }   // after layout, so its position in the window is known
            insets
        }
        root.requestApplyInsets()
    }

    /** Where the title bar ends in the window, if one is showing; 0 if not. */
    private fun titleBarBottom(v: View, statusBarBottom: Int): Int {
        val activity = v.context as? android.app.Activity ?: return 0
        val bar = activity.actionBar?.takeIf { it.isShowing } ?: return 0
        val id = v.resources.getIdentifier("action_bar_container", "id", "android")
        val view = if (id != 0) activity.window.decorView.findViewById<View>(id) else null
        if (view != null && view.height > 0) {
            val at = IntArray(2); view.getLocationInWindow(at)
            return at[1] + view.height
        }
        return statusBarBottom + bar.height   // below the status bar, if its view can't be found
    }

    private fun pad(v: View, bars: Insets, base: IntArray) {
        val at = IntArray(2); v.getLocationInWindow(at)
        val window = v.rootView
        val left = (bars.left - at[0]).coerceAtLeast(0)
        val top = (maxOf(bars.top, titleBarBottom(v, bars.top)) - at[1]).coerceAtLeast(0)
        val right = (at[0] + v.width - (window.width - bars.right)).coerceAtLeast(0)
        val bottom = (at[1] + v.height - (window.height - bars.bottom)).coerceAtLeast(0)
        val want = intArrayOf(base[0] + left, base[1] + top, base[2] + right, base[3] + bottom)
        if (want[0] != v.paddingLeft || want[1] != v.paddingTop || want[2] != v.paddingRight || want[3] != v.paddingBottom)
            v.setPadding(want[0], want[1], want[2], want[3])
    }
}
