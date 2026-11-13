package org.appdevforall.shareyourapps

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

/**
 * The wireframes' look (Appendix B, wireframe.css): pale background, white bordered cards and
 * fields, dark text, muted hints, and a blue accent for primary actions. CodeOnTheGo is often
 * used in dark mode, so there's a matching dark palette, chosen from the system setting.
 */
class Style(private val ctx: Context) {

    private val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val bg = c(if (dark) "#16181c" else "#eef0f3")
    val surface = c(if (dark) "#22252b" else "#ffffff")
    val border = c(if (dark) "#3a3f47" else "#c7ccd3")
    val borderStrong = c(if (dark) "#5a616c" else "#9aa1ad")
    val ink = c(if (dark) "#e6e8eb" else "#20242b")
    val muted = c(if (dark) "#9aa1ad" else "#6b7280")
    val accent = c(if (dark) "#9fb2e6" else "#485d92")
    private val onAccent = c(if (dark) "#16181c" else "#ffffff")
    val ok = c(if (dark) "#66bb6a" else "#4caf50")
    val danger = c(if (dark) "#ef5350" else "#f44336")

    private fun c(hex: String) = Color.parseColor(hex)
    fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, stroke: Int?, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    fun eyebrow(t: String) = TextView(ctx).apply {
        text = t.uppercase(); textSize = 11f; letterSpacing = 0.08f; setTextColor(muted); setTypeface(typeface, Typeface.BOLD)
    }
    fun title(t: String) = TextView(ctx).apply { text = t; textSize = 20f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD) }

    /** A group heading, like the wireframe's section labels. */
    fun section(t: String) = TextView(ctx).apply {
        text = t.uppercase(); textSize = 11f; letterSpacing = 0.06f; setTextColor(muted); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(4))
    }
    fun label(t: String) = TextView(ctx).apply {
        text = t; textSize = 13f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(6))
    }
    fun hint(t: String) = TextView(ctx).apply { text = t; textSize = 11f; setTextColor(muted); setPadding(0, dp(4), 0, 0) }
    fun body(t: String) = TextView(ctx).apply { text = t; textSize = 14f; setTextColor(ink) }

    fun input(e: EditText) = e.apply {
        background = rounded(surface, borderStrong, 6)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(ink); setHintTextColor(muted); textSize = if (typeface == Typeface.MONOSPACE) 13f else 14f
    }

    fun primary(b: Button) = b.apply {
        background = rounded(accent, null, 8); setTextColor(onAccent); isAllCaps = false; textSize = 15f
        setTypeface(typeface, Typeface.BOLD); stateListAnimator = null; setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    fun secondary(b: Button) = b.apply {
        background = rounded(surface, border, 8); setTextColor(accent); isAllCaps = false; textSize = 13f
        // Android's own button style has a minimum of 88 × 48 dp; these are as wide as their label, and shorter.
        stateListAnimator = null; minHeight = dp(34); minimumHeight = dp(34); minWidth = 0; minimumWidth = 0
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }
    fun check(cb: CheckBox) = cb.apply { setTextColor(ink); buttonTintList = ColorStateList.valueOf(accent) }

    /** A raised card, like the wireframe's product cards. */
    fun card(v: View) = v.apply { background = rounded(surface, border, 10); elevation = dp(2).toFloat() }

    /** Disabled controls dim, since custom backgrounds don't do it themselves. */
    fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }
}
