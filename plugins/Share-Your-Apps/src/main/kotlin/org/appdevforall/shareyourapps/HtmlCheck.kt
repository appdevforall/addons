package org.appdevforall.shareyourapps

/**
 * Checks owner-written HTML — the store and product descriptions, which go into the page as-is —
 * for the mistakes an owner can make and fix: a tag opened but never closed, a tag closed that was
 * never opened, and tags closed in the wrong order. It knows HTML's own rules, so it doesn't
 * complain about what's legal: tags that never close (`<br>`, `<img>`), end tags that are optional
 * (`<li>`, `<p>`, `<td>`), and the raw text inside `<script>` and `<style>`. It only warns;
 * browsers tolerate a great deal. It doesn't check tag or attribute names, or CSS.
 */
object HtmlCheck {

    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")
    /** End tags HTML lets you leave out. */
    private val OPTIONAL_END = setOf("li", "p", "dt", "dd", "option", "optgroup", "tr", "td", "th", "thead", "tbody", "tfoot",
        "colgroup", "rt", "rp", "html", "head", "body")
    /** Tags whose start closes an open one of the same group, as HTML does: a new <li> ends the last. */
    private val CLOSES_SIBLING = mapOf(
        "li" to setOf("li"), "dt" to setOf("dt", "dd"), "dd" to setOf("dt", "dd"), "option" to setOf("option"),
        "tr" to setOf("tr", "td", "th"), "td" to setOf("td", "th"), "th" to setOf("td", "th"),
        "thead" to setOf("tbody", "tfoot"), "tbody" to setOf("thead", "tbody"), "tfoot" to setOf("thead", "tbody"),
    )
    /** Block tags that end an open <p>. */
    private val ENDS_P = setOf("address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset", "figcaption",
        "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "nav", "ol", "p", "pre",
        "section", "table", "ul")

    private const val MAX = 5

    /** Problems found in [html], in plain words, at most a handful; empty when it's fine. */
    fun problems(html: String): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayList<String>()
        var i = 0
        val n = html.length
        while (i < n && out.size < MAX) {
            if (html[i] != '<') { i++; continue }
            // A comment: skip to its end.
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                if (end < 0) { out += "A comment <!-- is never closed with -->"; break }
                i = end + 3; continue
            }
            // A doctype or processing instruction: skip it.
            if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
                i = html.indexOf('>', i).let { if (it < 0) n else it + 1 }; continue
            }
            val closing = i + 1 < n && html[i + 1] == '/'
            val nameStart = if (closing) i + 2 else i + 1
            var j = nameStart
            while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':')) j++
            // "<" not followed by a tag name is just text, as in "a < b".
            if (j == nameStart || !html[nameStart].isLetter()) { i++; continue }
            val name = html.substring(nameStart, j).lowercase()
            // Find the tag's end, skipping quoted attribute values, which may contain ">".
            var k = j
            var quote = 0.toChar()
            while (k < n) {
                val c = html[k]
                if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '>') break
                k++
            }
            if (k >= n) { out += "<$name is never finished with >"; break }
            val selfClosing = !closing && k > j && html[k - 1] == '/'
            i = k + 1

            if (closing) {
                endTag(name, stack, out)
                continue
            }
            // Opening a tag: HTML's implicit closes first.
            CLOSES_SIBLING[name]?.let { group -> if (stack.isNotEmpty() && stack.last() in group) stack.removeAt(stack.size - 1) }
            if (name in ENDS_P && stack.isNotEmpty() && stack.last() == "p") stack.removeAt(stack.size - 1)
            if (name in VOID || selfClosing) continue
            if (name in RAW_TEXT) {
                // Everything up to its end tag is text, not tags.
                val end = html.indexOf("</$name", i, ignoreCase = true)
                if (end < 0) { out += "<$name> is opened but never closed"; break }
                stack += name   // so its end tag, next, closes it
                i = end; continue
            }
            stack += name
        }
        // Whatever is still open, and isn't allowed to be left open, was never closed.
        val unclosed = stack.filter { it !in OPTIONAL_END }
        for (name in unclosed.distinct()) {
            if (out.size >= MAX) break
            val count = unclosed.count { it == name }
            out += if (count == 1) "<$name> is opened but never closed" else "<$name> is opened $count times but never closed"
        }
        return out
    }

    private fun endTag(name: String, stack: ArrayList<String>, out: MutableList<String>) {
        if (name in VOID) { out += "</$name> isn't needed: <$name> never takes a closing tag"; return }
        val at = stack.lastIndexOf(name)
        if (at < 0) {
            // An optional end tag with nothing open, such as a stray </p>, is harmless to browsers but still a mistake.
            out += "</$name> closes a tag that was never opened"
            return
        }
        // Anything opened inside it and still open must have been closed first — unless HTML lets it close itself.
        val inside = stack.subList(at + 1, stack.size).filter { it !in OPTIONAL_END }
        inside.lastOrNull()?.let { out += "</$name> closes before the <$it> inside it" }
        while (stack.size > at) stack.removeAt(stack.size - 1)
    }
}
