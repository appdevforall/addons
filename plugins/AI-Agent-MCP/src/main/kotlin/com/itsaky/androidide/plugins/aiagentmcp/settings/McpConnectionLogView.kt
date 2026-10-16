package com.itsaky.androidide.plugins.aiagentmcp.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpLogFormat
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpTranscript
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchange

/**
 * Lays a [McpTranscript.Log] out as cards, one per exchange: badges, URL, then request and response
 * with headers as rows and bodies as code blocks. Colours are `@color/plugin_*`, set in code.
 *
 * @param inflater the plugin's inflater, so layouts and colours resolve against plugin resources.
 * @param labels the translated pieces of a redacted header or capped body.
 */
class McpConnectionLogView(
    private val inflater: LayoutInflater,
    private val labels: McpTranscript.Labels,
) {

    /** The text roles of a card, each a step of Material 3's type scale with its colour and gap. */
    private enum class Role(val sizeSp: Float, val family: String, @ColorRes val color: Int, val gapDp: Int) {
        SECTION(14f, "sans-serif-medium", R.color.plugin_on_surface, 16),
        LABEL(12f, "sans-serif-medium", R.color.plugin_on_surface_variant, 8),
        CODE(12f, "monospace", R.color.plugin_on_surface, 4),
        NOTE(12f, "sans-serif", R.color.plugin_text_muted, 4),
    }

    private companion object {
        const val CODE_PADDING_DP = 12
        const val HEADER_LINE_SPACING = 1.2f
    }

    /**
     * Fills [entries] with [log], replacing whatever it held.
     * @param entries the vertical container the cards go in.
     */
    fun bind(entries: LinearLayout, log: McpTranscript.Log) {
        entries.removeAllViews()
        if (log.exchanges.isEmpty()) entries.addView(code(entries.context, labels.nothingSent))
        log.exchanges.forEach { entries.addView(card(entries, it)) }
        log.unexplained?.let { entries.addView(failure(entries.context, it)) }
    }

    private fun card(parent: LinearLayout, exchange: McpExchange): View {
        val card = inflater.inflate(R.layout.item_mcp_log_exchange, parent, false)
        val context = card.context
        card.findViewById<TextView>(R.id.mcpLogMethod).text = exchange.method
        card.findViewById<TextView>(R.id.mcpLogUrl).text = exchange.url
        status(card.findViewById(R.id.mcpLogStatus), exchange.status)

        val sections = card.findViewById<LinearLayout>(R.id.mcpLogSections)
        sections.addView(text(context, context.getString(R.string.mcp_log_request), Role.SECTION))
        addHeaders(sections, exchange.requestHeaders)
        addBody(sections, exchange.requestBody)
        if (exchange.status != null) {
            sections.addView(text(context, context.getString(R.string.mcp_log_response), Role.SECTION))
            addHeaders(sections, exchange.responseHeaders)
            exchange.responseBody?.let { addBody(sections, it) }
        }
        exchange.failure?.let { sections.addView(failure(context, it)) }
        return card
    }

    private fun status(badge: TextView, status: String?) {
        val context = badge.context
        val (container, content) = when (McpLogFormat.tone(status)) {
            McpLogFormat.Tone.SUCCESS -> R.color.plugin_success_container to R.color.plugin_success
            McpLogFormat.Tone.REDIRECT ->
                R.color.plugin_secondary_container to R.color.plugin_on_secondary_container
            McpLogFormat.Tone.ERROR -> R.color.plugin_error_container to R.color.plugin_on_error_container
        }
        badge.text = status?.let(McpLogFormat::badge) ?: context.getString(R.string.mcp_log_no_response)
        badge.backgroundTintList = ColorStateList.valueOf(color(context, container))
        badge.setTextColor(color(context, content))
    }

    private fun addHeaders(sections: LinearLayout, headers: List<McpExchange.Header>) {
        if (headers.isEmpty()) return
        val context = sections.context
        val name = color(context, R.color.plugin_on_surface_variant)
        val shown = color(context, R.color.plugin_on_surface)
        val hidden = color(context, R.color.plugin_text_muted)
        val rows = SpannableStringBuilder()
        headers.forEachIndexed { index, header ->
            if (index > 0) rows.append('\n')
            rows.span(header.name, ForegroundColorSpan(name), StyleSpan(Typeface.BOLD))
            rows.append(": ")
            val value = McpTranscript.value(header.value, labels)
            when (header.value) {
                is McpExchange.Value.Shown -> rows.span(value, ForegroundColorSpan(shown))
                is McpExchange.Value.Hidden ->
                    rows.span(value, ForegroundColorSpan(hidden), StyleSpan(Typeface.ITALIC))
            }
        }
        sections.addView(text(context, context.getString(R.string.mcp_log_headers), Role.LABEL))
        sections.addView(text(context, rows, Role.CODE).apply { setLineSpacing(0f, HEADER_LINE_SPACING) })
    }

    private fun addBody(sections: LinearLayout, body: McpExchange.Body) {
        if (body.text.isEmpty() && body.omitted == 0) return
        val context = sections.context
        sections.addView(text(context, context.getString(R.string.mcp_log_body), Role.LABEL))
        sections.addView(code(context, McpLogFormat.pretty(body.text)))
        if (body.omitted > 0) sections.addView(text(context, labels.omitted(body.omitted), Role.NOTE))
    }

    /** A code block on the surface-variant container. */
    private fun code(context: Context, content: CharSequence): TextView =
        text(context, content, Role.CODE).apply {
            setBackgroundResource(R.drawable.bg_mcp_log_code)
            padded(this)
        }

    /** What interrupted an attempt, on the error container. */
    private fun failure(context: Context, message: String): TextView =
        text(context, message, Role.CODE).apply {
            setBackgroundResource(R.drawable.bg_mcp_log_badge)
            backgroundTintList = ColorStateList.valueOf(color(context, R.color.plugin_error_container))
            setTextColor(color(context, R.color.plugin_on_error_container))
            padded(this)
        }

    private fun text(context: Context, content: CharSequence, role: Role): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, role.gapDp) }
            text = content
            textSize = role.sizeSp
            typeface = Typeface.create(role.family, Typeface.NORMAL)
            setTextColor(color(context, role.color))
            setTextIsSelectable(true)
        }

    private fun padded(view: TextView) {
        val padding = dp(view.context, CODE_PADDING_DP)
        view.setPadding(padding, padding, padding, padding)
    }

    private fun SpannableStringBuilder.span(content: String, vararg spans: Any) {
        val start = length
        append(content)
        spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun color(context: Context, @ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    private fun dp(context: Context, dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
