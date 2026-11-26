package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameCheckTest {
    private fun rejected(name: String) = assertTrue("\"$name\" should be rejected", AppNameCheck.check(name) is NameVerdict.Rejected)

    private fun accepted(name: String) {
        val v = AppNameCheck.check(name)
        assertTrue("\"$name\" should pass but: ${(v as? NameVerdict.Rejected)?.reason}", v is NameVerdict.Ok)
    }

    @Test
    fun theRealNameInEveryDisguiseIsRejected() {
        listOf(
            "Code on the Go", "Code on the Road", "CODE ON THE GO", "code on the go", "Code On The Go", "Code On the Go", "CodeOnTheGo", "Code-on-the-Go",
            "Code_on_the_Go", "Code.on.the.Go", "C o d e o n t h e G o", "  Code on the Go  ", "Code on the Go IDE", "My Code on the Go",
            "Code on the Go 2", "Code on the Go!", "(Code on the Go)",
        ).forEach(::rejected)
    }

    @Test
    fun cogoInAnyFormIsRejected() {
        listOf("CoGo", "COGO", "cogo", "Co Go", "Co-Go", "Co.Go", "CoGo IDE", "My CoGo", "CoGo2", "cogo-editor", "Cogo Pro Max").forEach(::rejected)
    }

    @Test
    fun lookAlikeDigitsAndSymbolsAreSeenThrough() {
        listOf("C0de 0n the G0", "C0G0", "C0Go", "Cog0", "Cod3 on th3 Go", "C0d3 0n Th3 G0", "C@de on the Go", "Code on the 9o", "C0de-0n-the-G0").forEach(::rejected)
    }

    @Test
    fun lookAlikeCyrillicAndGreekLettersAreSeenThrough() {
        listOf(
            "\u0421ode on the Go", // Cyrillic Es for C
            "C\u043Ede on the Go", // Cyrillic o
            "Code \u043En the Go",
            "C\u043Eg\u043E", // both o
            "\u0421\u043E\u0261\u043E", // Es, o, Latin script g, o
            "C\u03BFd\u03B5 on the Go", // Greek omicron, epsilon
            "\u03F2\u03BF\u03B3\u03BF".replace('\u03B3', 'g'), // Greek lunate sigma, omicron
            "Cod\u0435 \u043En th\u0435 G\u043E",
        ).forEach(::rejected)
    }

    @Test
    fun widthAndAccentVariantsAreSeenThrough() {
        listOf("\uFF23\uFF4F\uFF27\uFF4F", "C\u00F3g\u00F4", "C\u00F6de \u00F6n the G\u00F6", "Code \u00F3n the Go").forEach(::rejected)
    }

    @Test
    fun withinTwoEditsOfTheLongNameIsRejected() {
        listOf(
            "Cod on the go", "Code on teh Go", "Code on the", // 2 deletions ("go")
            "Codeonthe go", "Kode on the Go", "Code in the Go", "Cade an the Go", "Code on the Gooo", "Cdoe on the Go", "Code at the Go",
            "Codee on the Goo", "Code on the Go Pro", "The Code on the Go App", "Cod on th go",
        ).forEach(::rejected)
    }

    @Test
    fun threeEditsFromTheLongNameIsNotEnough() {
        accepted("Cade an the Gu") // 3 substitutions away
        accepted("Code and go")
        accepted("Cd n th Go") // 4 deletions
    }

    @Test
    fun ordinaryNamesPass() {
        listOf(
            "My IDE", "Code Editor", "Pocket Studio", "Tinker", "Coding Go", "Go Coder", "The Go IDE", "Bingo IDE", "Mongo IDE", "Cogitation",
            "Logo Maker", "Cargo", "Congo Coder", "Ego Soft", "Chicago", "Tokyo", "Code at Home", "Code and Go", "Anywhere IDE",
            "Dev Studio 2", "\u9ED1\u5BA2\u5DE5\u574A", "Caf\u00E9 IDE", "A", "12345",
        ).forEach(::accepted)
    }

    @Test
    fun cogoIsMatchedAsASubstringOnlySoNearbyWordsPass() {
        // Two edits from a four-letter word reach too many real words, so only an exact "cogo" is refused (see AppNameCheck).
        listOf("Go", "Logo", "Congo", "Cargo", "Gogo", "Coco", "Ego", "Cog").forEach(::accepted)
    }

    @Test
    fun knownFalsePositivesAreAcceptedAsTheCostOfIgnoringSpaces() {
        // Spaces and punctuation are ignored, so words that run into "cogo" across a space are refused. A user can pick another name.
        rejected("Taco Go")
        rejected("Disco Gold")
        rejected("Eco Gold")
    }

    @Test
    fun blankLongAndControlNamesAreRejectedWithTheirOwnReasons() {
        fun reason(s: String) = (AppNameCheck.check(s) as NameVerdict.Rejected).reason
        assertTrue(reason("").contains("Enter a name"))
        assertTrue(reason("   \t ").contains("Enter a name"))
        assertTrue(reason("a".repeat(51)).contains("longer than 50"))
        accepted("a".repeat(50))
        assertTrue(reason("two\nlines").contains("control"))
        assertTrue(reason("100% IDE").contains("percent"))
        assertTrue(reason("!!! ???").contains("letter or digit"))
        assertTrue(reason("Code on the Go").contains("too close"))
    }

    @Test
    fun theLengthCountsCharactersNotUtf16Units() {
        accepted("\uD835\uDC9C".repeat(50)) // mathematical script A, a letter outside the basic plane
        assertTrue(AppNameCheck.check("\uD835\uDC9C".repeat(51)) is NameVerdict.Rejected)
    }

    @Test
    fun theAcceptedNameIsTheTrimmedInput() {
        assertEquals("My IDE", (AppNameCheck.check("  My IDE \t") as NameVerdict.Ok).name)
    }

    @Test
    fun normaliseFoldsAsDescribed() {
        assertEquals("codeonthegoi", AppNameCheck.normalise("C0de-0n the G0!"))
        assertEquals("cogo", AppNameCheck.normalise("\u0421\u043E\u0261\u043E"))
        assertEquals("myide", AppNameCheck.normalise("My IDE"))
        assertEquals("cafe", AppNameCheck.normalise("Caf\u00E9"))
    }

    @Test
    fun theApproximateMatcherAgreesWithPlainEditDistance() {
        assertEquals(3, AppNameCheck.editDistance("kitten", "sitting"))
        assertTrue(AppNameCheck.approximatelyContains("xxcodonthegoxx", "codeonthego", 1))
        assertTrue(!AppNameCheck.approximatelyContains("xxcodnthegoxx", "codeonthego", 1))
        assertTrue(AppNameCheck.approximatelyContains("codeonthego", "codeonthego", 0))
        assertTrue(AppNameCheck.approximatelyContains("", "ab", 2))
        assertTrue(!AppNameCheck.approximatelyContains("", "abc", 2))
    }
}
