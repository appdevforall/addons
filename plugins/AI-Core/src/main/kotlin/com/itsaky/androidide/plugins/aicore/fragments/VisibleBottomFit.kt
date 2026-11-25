package com.itsaky.androidide.plugins.aicore.fragments

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.updatePadding

internal fun View.fitContentToVisibleBottom() {
    val visible = Rect()
    val fit = ViewTreeObserver.OnPreDrawListener {
        if (getLocalVisibleRect(visible)) {
            val hidden = (height - visible.bottom).coerceAtLeast(0)
            if (hidden != paddingBottom) updatePadding(bottom = hidden)
        }
        true
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(fit)
        override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(fit)
    })
    if (isAttachedToWindow) viewTreeObserver.addOnPreDrawListener(fit)
}
