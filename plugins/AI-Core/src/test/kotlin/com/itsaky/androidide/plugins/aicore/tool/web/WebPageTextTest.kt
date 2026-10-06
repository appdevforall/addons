package com.itsaky.androidide.plugins.aicore.tool.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [WebPageText], which decides what fetch_url requests and what it hands back. */
class WebPageTextTest {

    @Test
    fun givenAGithubFilePage_whenPreferringAUrl_thenTheRawFileIsFetchedInstead() {
        val url = "https://github.com/appdevforall/CodeOnTheGo/blob/stage/README.md"

        assertEquals(
            "https://raw.githubusercontent.com/appdevforall/CodeOnTheGo/stage/README.md",
            WebPageText.preferredUrl(url),
        )
    }

    @Test
    fun givenARepositoryRoot_whenPreferringAUrl_thenItIsLeftAlone() {
        val url = "https://github.com/appdevforall/CodeOnTheGo"

        assertEquals(url, WebPageText.preferredUrl(url))
    }

    @Test
    fun givenAPage_whenReducingToText_thenScriptsStylesAndMarkupAreGone() {
        val html = """
            <html><head><title>Release notes</title><style>p{color:red}</style></head>
            <body><script>track()</script><h1>Version 2.0</h1><p>Adds <b>web</b> search.</p></body></html>
        """.trimIndent()

        val text = WebPageText.htmlToText(html)

        assertTrue(text.startsWith("Release notes\n\n"))
        assertTrue(text.contains("Version 2.0\n"))
        assertTrue(text.contains("Adds web search."))
        assertFalse(text.contains("track()"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("<"))
    }

    @Test
    fun givenAList_whenReducingToText_thenEachItemIsItsOwnDashedLine() {
        val text = WebPageText.htmlToText("<ul><li>one</li><li>two</li></ul>")

        assertEquals("- one\n- two", text)
    }

    @Test
    fun givenEntities_whenReducingToText_thenTheyAreDecodedOnce() {
        // "&amp;lt;" spells the literal text "&lt;", not a bracket.
        val text = WebPageText.htmlToText("<p>a &lt; b &amp;&amp; c &#8212; &amp;lt;</p>")

        assertEquals("a < b && c — &lt;", text)
    }

    @Test
    fun givenContentTypes_whenCheckingReadability_thenTextJsonAndXmlPassAndBinariesDoNot() {
        assertTrue(WebPageText.isReadable("text/html; charset=utf-8"))
        assertTrue(WebPageText.isReadable("application/json"))
        assertTrue(WebPageText.isReadable("application/vnd.github+json"))
        assertTrue(WebPageText.isReadable(null))
        assertFalse(WebPageText.isReadable("image/png"))
        assertFalse(WebPageText.isReadable("application/zip"))
    }

    @Test
    fun givenNoContentType_whenCheckingForHtml_thenTheBodyDecides() {
        assertTrue(WebPageText.isHtml(null, "  <!DOCTYPE html><html></html>"))
        assertFalse(WebPageText.isHtml(null, "# README"))
        assertFalse(WebPageText.isHtml("text/plain", "<html></html>"))
    }
}
