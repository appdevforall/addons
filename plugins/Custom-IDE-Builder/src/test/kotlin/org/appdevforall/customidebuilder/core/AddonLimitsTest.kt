package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonLimitsTest {
    private val mb = 1024L * 1024

    @Test
    fun aSizeAtTheCeilingPassesAndOneByteOverIsTurnedAway() {
        assertNull(AddonLimits.sizeProblem("a.cgp", AddonLimits.MAX_BYTES))
        val problem = AddonLimits.sizeProblem("a.cgp", AddonLimits.MAX_BYTES + 1)
        assertNotNull(problem)
        assertTrue(problem, problem!!.startsWith("a.cgp: ") && problem.contains("512 MB"))
    }

    @Test
    fun theFileAndTheMarginMustFitInTheFreeSpace() {
        val need = 100 * mb + AddonLimits.SPACE_MARGIN
        assertNull(AddonLimits.spaceProblem("a.cgp", 100 * mb, need))
        val problem = AddonLimits.spaceProblem("a.cgp", 100 * mb, need - 1)
        assertNotNull(problem)
        assertTrue(problem, problem!!.contains("not enough free space") && problem.contains("164 MB is needed"))
    }

    @Test
    fun anUnknownSizeStillNeedsTheMargin() {
        assertNull(AddonLimits.spaceProblem("a.cgp", null, AddonLimits.SPACE_MARGIN))
        assertNotNull(AddonLimits.spaceProblem("a.cgp", null, AddonLimits.SPACE_MARGIN - 1))
    }
}
