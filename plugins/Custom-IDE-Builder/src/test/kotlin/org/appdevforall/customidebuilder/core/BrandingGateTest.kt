package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingGateTest {
    private val different = IconVerdict.Different(40)

    @Test
    fun aBuildWithNeitherNameNorIconIsRefusedWithAMessageForEach() {
        for (blank in listOf("", "   ")) {
            val p = BrandingGate.problems(blank, false, null, true)
            assertEquals(p.toString(), 2, p.size)
            assertTrue(p[0], p[0].startsWith("Name:") && "enter a name" in p[0])
            assertTrue(p[1], p[1].startsWith("Icon:") && "choose a picture" in p[1])
        }
    }

    @Test
    fun aNameWithoutAnIconIsRefusedForTheMissingIcon() {
        val p = BrandingGate.problems("My IDE", false, null, true)
        assertEquals(p.toString(), 1, p.size)
        assertTrue(p[0], p[0].startsWith("Icon:") && "choose a picture" in p[0])
    }

    @Test
    fun anIconWithoutANameIsRefusedForTheMissingName() {
        val p = BrandingGate.problems("", true, different, true)
        assertEquals(p.toString(), 1, p.size)
        assertTrue(p[0], p[0].startsWith("Name:") && "enter a name" in p[0])
    }

    @Test
    fun nameIconAndConfirmationTogetherPass() {
        assertEquals(emptyList<String>(), BrandingGate.problems("My IDE", true, different, true))
        assertEquals(emptyList<String>(), BrandingGate.problems("My IDE", true, IconVerdict.Unchecked("no detail"), true))
    }

    @Test
    fun theConfirmationIsAlwaysNeeded() {
        val p = BrandingGate.problems("My IDE", true, different, false)
        assertEquals(1, p.size)
        assertTrue(p[0], p[0].contains(BrandingGate.CONFIRMATION))
        // Also with nothing else chosen, where the old gate asked for nothing.
        assertTrue(BrandingGate.problems("", false, null, false).any { BrandingGate.CONFIRMATION in it })
    }

    @Test
    fun aRefusedNameBlocksEvenWhenConfirmed() {
        val p = BrandingGate.problems("CoGo", true, different, true)
        assertEquals(1, p.size)
        assertTrue(p[0], p[0].startsWith("Name:"))
    }

    @Test
    fun aPictureTooCloseToTheOriginalBlocksAndOneStillBeingCheckedWaits() {
        assertTrue(BrandingGate.problems("My IDE", true, IconVerdict.TooSimilar(3), true).single().startsWith("Icon:"))
        assertTrue(BrandingGate.problems("My IDE", true, null, true).single().contains("wait"))
    }

    @Test
    fun everyProblemIsListedTogether() {
        val p = BrandingGate.problems("Code on the Go", true, IconVerdict.TooSimilar(0), false)
        assertEquals(3, p.size)
        assertEquals(3, BrandingGate.problems("", false, null, false).size)
    }

    @Test
    fun blankContactDetailsPassAndBadOnesBlockWithTheirFieldNamed() {
        assertEquals(emptyList<String>(), BrandingGate.problems("My IDE", true, different, true, Contacts()))
        assertEquals(emptyList<String>(), BrandingGate.problems("My IDE", true, different, true, Contacts(website = "https://my.example/", feedbackEmail = "a@my.example")))
        val p = BrandingGate.problems("My IDE", true, different, true, Contacts(website = "http://my.example/", contactEmail = "nobody"))
        assertEquals(p.toString(), 2, p.size)
        assertTrue(p[0], p[0].startsWith("Website: ") && "https://" in p[0])
        assertTrue(p[1], p[1].startsWith("Contact email: "))
    }

    @Test
    fun contactProblemsAreListedWithTheOthers() {
        val p = BrandingGate.problems("", true, different, false, Contacts(youtubeUrl = "youtube"))
        assertEquals(p.toString(), 3, p.size)
        assertTrue(p.any { it.startsWith("Name:") })
        assertTrue(p.any { it.startsWith("YouTube channel: ") })
        assertTrue(p.any { BrandingGate.CONFIRMATION in it })
    }

    @Test
    fun theNameToWriteIsTheTrimmedOne() {
        assertEquals("My IDE", BrandingGate.nameToWrite("  My IDE "))
    }

    @Test
    fun theConfirmationReadsAsAgreed() {
        assertEquals("This name and icon are my own, and not Code on the Go's", BrandingGate.CONFIRMATION)
    }
}
