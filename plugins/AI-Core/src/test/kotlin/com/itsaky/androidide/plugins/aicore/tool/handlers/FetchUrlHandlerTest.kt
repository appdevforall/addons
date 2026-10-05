package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.aicore.tool.Validation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FetchUrlHandler.validate], which runs before the approval dialog: a URL the tool
 * cannot fetch must be refused without spending the user's attention on it.
 */
class FetchUrlHandlerTest {

    private val handler = FetchUrlHandler()

    @Test
    fun givenAnHttpsUrl_whenValidating_thenItIsAccepted() {
        val result = validate("https://developer.android.com/jetpack/compose")

        assertTrue(result is Validation.Accepted)
    }

    @Test
    fun givenAGithubFilePage_whenValidating_thenTheDialogShowsTheRawUrlThatWillBeFetched() {
        val result = validate("https://github.com/o/r/blob/main/build.gradle.kts") as Validation.Accepted

        assertEquals("https://raw.githubusercontent.com/o/r/main/build.gradle.kts", result.args["url"])
    }

    @Test
    fun givenALocalFileUrl_whenValidating_thenItIsRefused() {
        // Otherwise the tool would be a way around PathGuard's project containment.
        val result = validate("file:///data/data/com.itsaky.androidide/databases/documentation.db")

        assertTrue(result is Validation.Rejected)
        assertFalse((result as Validation.Rejected).result.success)
    }

    @Test
    fun givenNoUrl_whenValidating_thenItIsRefused() {
        assertTrue(validate("") is Validation.Rejected)
    }

    @Test
    fun givenAUrlWithNoHost_whenValidating_thenItIsRefused() {
        assertTrue(validate("https:///path") is Validation.Rejected)
    }

    private fun validate(url: String): Validation = runBlocking { handler.validate(mapOf("url" to url)) }
}
