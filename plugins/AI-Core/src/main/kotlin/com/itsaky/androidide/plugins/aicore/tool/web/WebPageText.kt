package com.itsaky.androidide.plugins.aicore.tool.web

/**
 * Turns what a URL returned into text a model can read, and a URL into the one worth fetching.
 * Pure, so every rule is unit-testable without a network.
 */
object WebPageText {

    /** Elements whose content is never prose: dropped whole, content and all. */
    private val DROPPED_ELEMENTS = Regex(
        """<(script|style|noscript|svg|template|iframe)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEAD = Regex("""<head\b[^>]*>.*?</head\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // An item opens its own line, so its closing tag adds none; that would leave a blank line between items.
    private val LIST_ITEM = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val LINE_BREAK = Regex(
        """<br\s*/?>|</?(p|div|section|article|header|footer|nav|main|aside|h[1-6]|tr|ul|ol|pre|blockquote|table|dd|dt)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val TAG = Regex("""<[^>]+>""")
    private val NUMERIC_ENTITY = Regex("""&#(x[0-9a-fA-F]+|\d+);""")
    private val NAMED_ENTITIES = mapOf(
        "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'",
        "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…", "&copy;" to "©",
    )
    private val SPACES = Regex("""[ \t\u000B\f\r]+""")
    private val BLANK_LINES = Regex("""\n\s*\n\s*\n+""")

    /** `github.com/{owner}/{repo}/blob/{ref}/{path}`, whose page wraps the file in site chrome. */
    private val GITHUB_BLOB = Regex("""^https://github\.com/([^/]+)/([^/]+)/blob/(.+)$""")

    /**
     * The URL to fetch in place of [url]: a GitHub file page becomes its raw file, since the page
     * buries the contents under navigation the result cap would spend itself on.
     *
     * @param url the URL the model asked for.
     * @return the URL to request.
     */
    fun preferredUrl(url: String): String {
        val match = GITHUB_BLOB.matchEntire(url) ?: return url
        val (owner, repo, rest) = match.destructured
        return "https://raw.githubusercontent.com/$owner/$repo/$rest"
    }

    /**
     * Whether a response of [contentType] is text this tool can hand back.
     *
     * @param contentType the `Content-Type` header, or null when the server sent none.
     * @return true for text, HTML, JSON and XML; false for images, archives and the like.
     */
    fun isReadable(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return type.isEmpty() || type.startsWith("text/") || type.endsWith("/json") ||
            type.endsWith("+json") || type.endsWith("/xml") || type.endsWith("+xml") ||
            type == "application/javascript"
    }

    /** @return whether [contentType] or, lacking one, the body itself says HTML. */
    fun isHtml(contentType: String?, body: String): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (!type.isNullOrEmpty()) return type == "text/html" || type == "application/xhtml+xml"
        val start = body.trimStart().take(64).lowercase()
        return start.startsWith("<!doctype html") || start.startsWith("<html")
    }

    /**
     * Reduces an HTML page to its readable text: the title, then the body with scripts, styles
     * and markup removed and block elements kept as line breaks.
     *
     * @param html the page.
     * @return the text; never markup.
     */
    fun htmlToText(html: String): String {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.trim().orEmpty()
        val body = html
            .replace(COMMENT, "")
            .replace(DROPPED_ELEMENTS, "")
            .replace(HEAD, "")
            .replace(LIST_ITEM, "\n- ")
            .replace(LINE_BREAK, "\n")
            .replace(TAG, "")
            .let(::decodeEntities)
            .replace(SPACES, " ")
            .lines().joinToString("\n") { it.trim() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
        return if (title.isEmpty()) body else "$title\n\n$body"
    }

    private fun decodeEntities(text: String): String {
        var decoded = NUMERIC_ENTITY.replace(text) { match ->
            val code = match.groupValues[1]
            val value = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            value?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
        }
        for ((entity, char) in NAMED_ENTITIES) decoded = decoded.replace(entity, char)
        // Last, so "&amp;lt;" decodes to the literal "&lt;" it spelled rather than to "<".
        return decoded.replace("&amp;", "&")
    }
}
