package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingGateTest {
    private val different = IconVerdict.Different(40)

    @Test
    fun withNeitherNameNorIconNothingIsRequired() {
        assertEquals(emptyList<String>(), BrandingGate.problems("", false, null, false))
        assertEquals(emptyList<String>(), BrandingGate.problems("   ", false, null, false))
        assertTrue(!BrandingGate.isCustom(" ", false))
    }

    @Test
    fun aGoodNameNeedsTheConfirmation() {
        val p = BrandingGate.problems("My IDE", false, null, false)
        assertEquals(1, p.size)
        assertTrue(p[0], p[0].contains(BrandingGate.CONFIRMATION))
        assertEquals(emptyList<String>(), BrandingGate.problems("My IDE", false, null, true))
    }

    @Test
    fun aRefusedNameBlocksEvenWhenConfirmed() {
        val p = BrandingGate.problems("CoGo", false, null, true)
        assertEquals(1, p.size)
        assertTrue(p[0], p[0].startsWith("Name:"))
    }

    @Test
    fun aPictureTooCloseToTheOriginalBlocksAndOneStillBeingCheckedWaits() {
        assertTrue(BrandingGate.problems("", true, IconVerdict.TooSimilar(3), true).single().startsWith("Icon:"))
        assertTrue(BrandingGate.problems("", true, null, true).single().contains("wait"))
        assertEquals(emptyList<String>(), BrandingGate.problems("", true, different, true))
        assertEquals(emptyList<String>(), BrandingGate.problems("", true, IconVerdict.Unchecked("no detail"), true))
    }

    @Test
    fun everyProblemIsListedTogether() {
        val p = BrandingGate.problems("Code on the Go", true, IconVerdict.TooSimilar(0), false)
        assertEquals(3, p.size)
    }

    @Test
    fun theNameToWriteIsTheTrimmedOneOrNullForBlank() {
        assertEquals("My IDE", BrandingGate.nameToWrite("  My IDE "))
        assertNull(BrandingGate.nameToWrite("   "))
    }

    @Test
    fun theConfirmationReadsAsAgreed() {
        assertEquals("This name and icon are my own, and not Code on the Go's", BrandingGate.CONFIRMATION)
    }
}
