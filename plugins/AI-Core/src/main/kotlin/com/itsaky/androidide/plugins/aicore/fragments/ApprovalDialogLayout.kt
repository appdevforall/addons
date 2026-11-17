package com.itsaky.androidide.plugins.aicore.fragments

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.min

/** The sizing decisions of the approval dialog, kept free of views so they can be unit-tested. */
internal object ApprovalDialogLayoutMath {

    /** The dialog never asks for more than this share of the screen height. */
    const val MAX_SCREEN_FRACTION = 0.9f

    /** Whether buttons of these natural widths fit in one row of [available] pixels. */
    fun fitsSideBySide(naturalWidths: List<Int>, available: Int): Boolean =
        naturalWidths.sum() <= available

    /**
     * The order top to bottom when the buttons stack, given them in side-by-side order (Always Allow
     * or Correct, Decline, Run now or Accept): the reverse, matching the stock Material dialog, so the
     * primary action sits on top and the one that approves every later call is never where Run now
     * is expected.
     */
    fun <T> stackedOrder(sideBySide: List<T>): List<T> = sideBySide.asReversed()

    /**
     * Height the scrolling text area may use: whatever the dialog may occupy ([maxTotal]) after
     * the button area ([buttonsHeight]), but never less than [minScroll], so the buttons are
     * always on screen and some text stays visible.
     */
    fun scrollBudget(maxTotal: Int, buttonsHeight: Int, minScroll: Int): Int =
        max(minScroll, maxTotal - buttonsHeight)

    /** The most the dialog may occupy: the parent's offer, capped by [MAX_SCREEN_FRACTION] of the screen. */
    fun maxTotalHeight(offered: Int?, screenHeight: Int): Int {
        val cap = (screenHeight * MAX_SCREEN_FRACTION).toInt()
        return if (offered == null) cap else min(offered, cap)
    }
}

/** A [ScrollView] that is never taller than [maxHeightPx]; shorter content keeps its own height. */
internal class BoundedScrollView(context: Context) : ScrollView(context) {
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val capped = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeightPx
        else min(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST))
    }
}

/**
 * The dialog's buttons. Side by side (the leading buttons at the start, the rest at the end) when
 * their natural widths fit: Always Allow (or Correct), Decline, Run now (or Accept). Otherwise one
 * per line, each at its natural width against the end edge, as the stock Material dialog stacks them,
 * and in its order: the primary action on top, then Decline, then Always Allow (or Correct) at the
 * bottom (see [ApprovalDialogLayoutMath.stackedOrder]).
 * The children are re-ordered with the layout, so focus and accessibility traversal follow the visual
 * order. Re-decided on every measure, so a font-scale or window change cannot leave one cut off.
 */
internal class ApprovalButtonBar(context: Context) : LinearLayout(context) {
    private val gap = View(context)
    private var buttons: List<View> = emptyList()
    private var leading = 0
    private var arrangedStacked: Boolean? = null

    /** Adds [buttons], given in side-by-side order; the first [leading] sit at the start, the rest at the end. */
    fun setButtons(buttons: List<View>, leading: Int) {
        this.buttons = buttons
        this.leading = leading
        arrangedStacked = null
        arrange(stacked = false)
    }

    private fun arrange(stacked: Boolean) {
        if (arrangedStacked == stacked) return
        arrangedStacked = stacked
        removeAllViewsInLayout()
        orientation = if (stacked) VERTICAL else HORIZONTAL
        if (stacked) {
            ApprovalDialogLayoutMath.stackedOrder(buttons).forEach { addButton(it, Gravity.END) }
        } else {
            buttons.forEachIndexed { i, button ->
                if (i == leading) addViewInLayout(gap, -1, LayoutParams(0, 0, 1f), true)
                addButton(button, Gravity.NO_GRAVITY)
            }
        }
        requestLayout()
    }

    private fun addButton(button: View, gravity: Int) {
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also { it.gravity = gravity }
        addViewInLayout(button, -1, params, true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val widths = buttons.map {
            it.layoutParams?.width = ViewGroup.LayoutParams.WRAP_CONTENT
            it.measure(unbounded, unbounded)
            it.measuredWidth
        }
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val sideBySide = ApprovalDialogLayoutMath.fitsSideBySide(widths, available)
        arrange(stacked = !sideBySide)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * The approval dialog's body: [scroll] above [buttons], the scroll area shrunk (and scrolling)
 * so that the buttons always fit on screen, however large the text or long the arguments.
 */
internal class ApprovalDialogLayout(
    context: Context,
    private val scroll: BoundedScrollView,
    private val buttons: ApprovalButtonBar,
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        buttons.measure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.AT_MOST),
            unbounded,
        )
        val offered = MeasureSpec.getSize(heightMeasureSpec)
            .takeIf { MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
        val maxTotal = ApprovalDialogLayoutMath.maxTotalHeight(offered, resources.displayMetrics.heightPixels)
        val minScroll = (96 * resources.displayMetrics.density).toInt()
        scroll.maxHeightPx = ApprovalDialogLayoutMath.scrollBudget(maxTotal, buttons.measuredHeight, minScroll)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
