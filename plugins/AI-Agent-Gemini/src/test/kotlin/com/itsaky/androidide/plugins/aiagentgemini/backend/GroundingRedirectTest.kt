package com.itsaky.androidide.plugins.aiagentgemini.backend

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unit tests for [GroundingRedirect], which cites a grounding source by the page it points to. */
class GroundingRedirectTest {

    /** Answers with [code] and [location], recording how it was asked. */
    private class FakeConnection(
        url: URL,
        private val code: Int,
        private val location: String?,
        private val failure: Exception? = null,
    ) : HttpURLConnection(url) {
        var disconnected = false

        override fun getResponseCode(): Int = failure?.let { throw it } ?: code
        override fun getHeaderField(name: String): String? =
            if (name.equals("Location", ignoreCase = true)) location else null

        override fun connect() {}
        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false
    }

    private val source = "https://redirect.example/grounding/abc"

    @Test
    fun givenARedirect_whenResolving_thenItsTargetIsReturnedWithoutFollowingIt() {
        var opened: FakeConnection? = null

        val target = GroundingRedirect.target(source) { url ->
            FakeConnection(url, 302, "https://firebase.google.com/docs/ai-logic").also { opened = it }
        }

        assertEquals("https://firebase.google.com/docs/ai-logic", target)
        assertEquals("HEAD", opened!!.requestMethod)
        assertFalse(opened!!.instanceFollowRedirects)
        assertEquals(true, opened!!.disconnected)
    }

    @Test
    fun givenARelativeLocation_whenResolving_thenItIsMadeAbsolute() {
        val target = GroundingRedirect.target(source) { url -> FakeConnection(url, 301, "/docs/page") }

        assertEquals("https://redirect.example/docs/page", target)
    }

    @Test
    fun givenNoRedirect_whenResolving_thenTheLinkIsKept() {
        val target = GroundingRedirect.target(source) { url -> FakeConnection(url, 200, null) }

        assertEquals(source, target)
    }

    @Test
    fun givenAFailedRequest_whenResolving_thenTheLinkIsKept() {
        val target = GroundingRedirect.target(source) { url ->
            FakeConnection(url, 0, null, IOException("timeout"))
        }

        assertEquals(source, target)
    }

    @Test
    fun givenAMalformedLink_whenResolving_thenItIsKeptWithoutARequest() {
        var opened = false

        val target = GroundingRedirect.target("not a url") { url ->
            opened = true
            FakeConnection(url, 302, "https://x.example")
        }

        assertEquals("not a url", target)
        assertFalse(opened)
    }

    @Test
    fun givenAnUncheckedFailure_whenResolving_thenTheLinkIsKept() {
        val target = GroundingRedirect.target(source) { url ->
            FakeConnection(url, 0, null, IllegalStateException("already connected"))
        }

        assertEquals(source, target)
    }

    @Test
    fun givenANonHttpLink_whenResolving_thenItIsKeptRatherThanThrowing() {
        // The default opener's cast used to throw ClassCastException here and fail the whole search.
        assertEquals("file:///tmp/source", GroundingRedirect.target("file:///tmp/source"))
    }
}
