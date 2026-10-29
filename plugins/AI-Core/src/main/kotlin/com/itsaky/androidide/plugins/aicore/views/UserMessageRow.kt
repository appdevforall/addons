package com.itsaky.androidide.plugins.aicore.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * A user message: its bubble at the end edge and its actions beside the bubble, on the start side
 * and level with its last line, so a short prompt costs one row of the transcript instead of two.
 * When the actions do not fit beside the bubble they go under it, end-aligned.
 *
 * Holds exactly two children, the bubble and then the actions. Written as a ViewGroup because the
 * choice depends on both measured widths, and library `app:` attributes such as Flexbox's are
 * read against the host's ids in a plugin layout, so they are ignored.
 */
class UserMessageRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Whether the last measure put the actions beside the bubble rather than under it. */
    private var actionsBeside = false

    private val bubble: View get() = getChildAt(0)
    private val actions: View get() = getChildAt(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(childCount == 2) { "UserMessageRow holds the bubble and its actions, found $childCount views" }
        val width = MeasureSpec.getSize(widthMeasureSpec)

        measureChildWithMargins(bubble, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val bubbleHeight = bubble.measuredHeight + bubble.verticalMargins

        val contentHeight = if (actions.visibility == GONE) {
            actionsBeside = false
            bubbleHeight
        } else {
            measureChildWithMargins(actions, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val besideWidth = bubble.measuredWidth + bubble.horizontalMargins +
                actions.measuredWidth + actions.horizontalMargins
            actionsBeside = besideWidth <= width - paddingLeft - paddingRight
            if (actionsBeside) {
                maxOf(bubbleHeight, actions.measuredHeight)
            } else {
                bubbleHeight + actions.verticalMargins + actions.measuredHeight
            }
        }
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val bubbleParams = bubble.layoutParams as MarginLayoutParams
        // Beside, the bubble and the actions share one bottom line, whichever of them is taller.
        val bubbleBottom = if (actionsBeside) {
            height - paddingBottom - bubbleParams.bottomMargin
        } else {
            paddingTop + bubbleParams.topMargin + bubble.measuredHeight
        }
        val bubbleLeft = if (rtl) {
            paddingLeft + bubbleParams.leftMargin
        } else {
            width - paddingRight - bubbleParams.rightMargin - bubble.measuredWidth
        }
        bubble.layout(bubbleLeft, bubbleBottom - bubble.measuredHeight, bubbleLeft + bubble.measuredWidth, bubbleBottom)

        if (actions.visibility == GONE) return
        val actionsParams = actions.layoutParams as MarginLayoutParams
        val actionsTop: Int
        val actionsLeft: Int
        if (actionsBeside) {
            actionsTop = bubbleBottom - actions.measuredHeight
            actionsLeft = if (rtl) {
                bubble.right + bubbleParams.rightMargin + actionsParams.leftMargin
            } else {
                bubble.left - bubbleParams.leftMargin - actionsParams.rightMargin - actions.measuredWidth
            }
        } else {
            actionsTop = bubbleBottom + bubbleParams.bottomMargin + actionsParams.topMargin
            actionsLeft = if (rtl) {
                paddingLeft + actionsParams.leftMargin
            } else {
                width - paddingRight - actionsParams.rightMargin - actions.measuredWidth
            }
        }
        actions.layout(actionsLeft, actionsTop, actionsLeft + actions.measuredWidth, actionsTop + actions.measuredHeight)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    private val View.horizontalMargins: Int
        get() = (layoutParams as MarginLayoutParams).let { it.leftMargin + it.rightMargin }

    private val View.verticalMargins: Int
        get() = (layoutParams as MarginLayoutParams).let { it.topMargin + it.bottomMargin }
}
