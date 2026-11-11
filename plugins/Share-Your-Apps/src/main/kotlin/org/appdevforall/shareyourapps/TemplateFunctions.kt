package org.appdevforall.shareyourapps

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.pebbletemplates.pebble.extension.AbstractExtension
import io.pebbletemplates.pebble.extension.Filter
import io.pebbletemplates.pebble.extension.Function
import io.pebbletemplates.pebble.extension.escaper.SafeString
import io.pebbletemplates.pebble.template.EvaluationContext
import io.pebbletemplates.pebble.template.PebbleTemplate

/**
 * Built-in filters and functions for store page templates (design, Appendix D.2), written and
 * tested with the plugin, so owners can use them without writing code. Text they return is escaped
 * like any other; only `markdown` and `qr` return HTML, which they build themselves, safely.
 *
 * - `t("key")`, or `t("key", "fallback")`: text from the Extra JSON `strings` table, by the store's
 *   language, then English, then the fallback, then the key itself:
 *   `{"strings": {"sw": {"download": "Pakua"}, "en": {"download": "Download"}}}`.
 * - `plural(n, "# app", "# apps")`: the first form for 1, the second otherwise; `#` becomes n.
 * - `text|markdown`: a safe subset of Markdown — paragraphs, "- " lists, **bold**, *italic*,
 *   `code`, and [links](https://…) to http, https or mailto only. Everything else is escaped.
 * - `qr("text")`: a QR code, as an inline SVG image, labelled for screen readers.
 * - `n|bytes`: a byte count as a size, like the store's own labels: 532.0 KB, 5.0 MB.
 * - `text|slugify`: text as a URL-safe name, by the store's slug rule.
 */
class TemplateFunctions : AbstractExtension() {
    override fun getFunctions(): Map<String, Function> = mapOf("t" to T, "plural" to Plural, "qr" to Qr)
    override fun getFilters(): Map<String, Filter> = mapOf("markdown" to Markdown, "bytes" to Bytes, "slugify" to Slugify)

    private object T : Function {
        override fun getArgumentNames() = listOf("key", "fallback")
        override fun execute(args: Map<String, Any?>, self: PebbleTemplate?, context: EvaluationContext, line: Int): Any? {
            val key = args["key"]?.toString() ?: return ""
            val strings = (context.getVariable("extra") as? Map<*, *>)?.get("strings") as? Map<*, *>
            val lang = context.getVariable("lang")?.toString()?.lowercase()
            fun from(l: String?) = l?.let { (strings?.get(it) as? Map<*, *>)?.get(key)?.toString() }
            return from(lang) ?: from(lang?.substringBefore('-')) ?: from("en") ?: args["fallback"]?.toString() ?: key
        }
    }

    private object Plural : Function {
        override fun getArgumentNames() = listOf("n", "one", "other")
        override fun execute(args: Map<String, Any?>, self: PebbleTemplate?, context: EvaluationContext, line: Int): Any? {
            val n = (args["n"] as? Number) ?: args["n"]?.toString()?.toBigDecimalOrNull() ?: return ""
            val whole = if (n.toDouble() == Math.floor(n.toDouble())) n.toLong().toString() else n.toString()
            val form = if (n.toDouble() == 1.0) args["one"] else args["other"]
            return form?.toString()?.replace("#", whole) ?: whole
        }
    }

    private object Qr : Function {
        override fun getArgumentNames() = listOf("text", "label")
        override fun execute(args: Map<String, Any?>, self: PebbleTemplate?, context: EvaluationContext, line: Int): Any? {
            val text = args["text"]?.toString()?.takeIf { it.isNotEmpty() } ?: return SafeString("")
            val label = escape(args["label"]?.toString() ?: "QR code")
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
            val w = m.width; val h = m.height
            val path = StringBuilder()
            for (y in 0 until h) for (x in 0 until w) if (m.get(x, y)) path.append("M$x ${y}h1v1h-1z")
            return SafeString("<svg class=qr xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $w $h\" role=\"img\" aria-label=\"$label\" " +
                "shape-rendering=\"crispEdges\"><rect width=\"$w\" height=\"$h\" fill=\"#fff\"/><path d=\"$path\" fill=\"#000\"/></svg>")
        }
    }

    private object Markdown : Filter {
        override fun getArgumentNames() = emptyList<String>()
        override fun apply(input: Any?, args: Map<String, Any?>, self: PebbleTemplate?, context: EvaluationContext, line: Int): Any? =
            SafeString(markdown(input?.toString() ?: ""))
    }

    private object Bytes : Filter {
        override fun getArgumentNames() = emptyList<String>()
        override fun apply(input: Any?, args: Map<String, Any?>, self: PebbleTemplate?, context: EvaluationContext, line: Int): Any? {
            val n = (input as? Number)?.toLong() ?: input?.toString()?.trim()?.toLongOrNull() ?: return input
            return sizeLabel(n)
        }
    }

    private object Slugify : Filter {
        override fun getArgumentNames() = emptyList<String>()
        override fun apply(input: Any?, args: Map<String, Any?>, self: PebbleTemplate?, context: EvaluationContext, line: Int): Any? = slug(input?.toString() ?: "")
    }

    companion object {
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        /** The safe subset of Markdown. Everything is escaped first, so no HTML in the source survives. */
        fun markdown(src: String): String {
            val blocks = src.replace("\r\n", "\n").trim().split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
            return blocks.joinToString("") { block ->
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.all { it.startsWith("- ") || it.startsWith("* ") })
                    "<ul>" + lines.joinToString("") { "<li>" + inline(escape(it.substring(2))) + "</li>" } + "</ul>"
                else "<p>" + lines.joinToString("<br>") { inline(escape(it)) } + "</p>"
            }
        }

        private fun inline(s: String): String {
            var r = s
            // Code first, so nothing inside it is treated as formatting.
            val codes = mutableListOf<String>()
            r = Regex("`([^`]+)`").replace(r) { codes += it.groupValues[1]; "\u0000${codes.size - 1}\u0000" }
            // A link's address ends at an escaped quote or angle bracket, so it can never carry one.
            r = Regex("\\[([^\\]]+)]\\(((?:https?://|mailto:)(?:(?!&quot;|&#39;|&lt;|&gt;)[^\\s)])+)\\)").replace(r) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
            r = Regex("\\*\\*([^*]+)\\*\\*").replace(r) { "<strong>${it.groupValues[1]}</strong>" }
            r = Regex("(?<![*\\w])\\*([^*]+)\\*(?![*\\w])").replace(r) { "<em>${it.groupValues[1]}</em>" }
            r = Regex("\u0000(\\d+)\u0000").replace(r) { "<code>${codes[it.groupValues[1].toInt()]}</code>" }
            return r
        }
    }
}
