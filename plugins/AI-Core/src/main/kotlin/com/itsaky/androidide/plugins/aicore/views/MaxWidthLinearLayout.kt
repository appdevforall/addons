package com.itsaky.androidide.plugins.aicore.views

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.itsaky.androidide.plugins.aicore.R

/**
 * A LinearLayout that wraps its content but never grows past R.fraction.chat_user_bubble_max_width
 * of the width its parent offers. Done in code because library `app:` attributes (ConstraintLayout's
 * included) are read by the host's copy of the library against the host's ids, so they are ignored.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val maxWidthFraction = resources.getFraction(R.fraction.chat_user_bubble_max_width, 1, 1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val cap = (MeasureSpec.getSize(widthMeasureSpec) * maxWidthFraction).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST), heightMeasureSpec)
    }
}
