package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowPlannerTest {

    private fun ids(cs: List<ClusterPlan>) = cs.map { it.cpus }

    @Test
    fun givenOneTwoThreeAndFourClusters_whenPlanned_thenIdsFollowTheLowestCpuAndEveryClusterHasARow() {
        val one = RowPlanner.clusters(staticWith(listOf((0..3).toList())))
        assertEquals(listOf((0..3).toList()), ids(one)); assertEquals(listOf(0), one.map { it.id })
        val two = RowPlanner.clusters(staticWith(listOf((0..3).toList(), (4..7).toList())))
        assertEquals(listOf(0, 1), two.map { it.id })
        val three = RowPlanner.clusters(staticWith(listOf(listOf(0, 1, 2, 3), listOf(4, 5), listOf(6, 7))))
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5), listOf(6, 7)), ids(three)); assertEquals(listOf(0, 1, 2), three.map { it.id })
        val four = RowPlanner.clusters(staticWith(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5), listOf(6, 7))))
        assertEquals(4, four.size)
        // rows: pin probe + clusters + two-fast + all + gpu
        assertEquals(1 + 1 + 1 + 1 + 1, RowPlanner.totalRows(one, true))
        assertEquals(1 + 2 + 1 + 1 + 1, RowPlanner.totalRows(two, true))
        assertEquals(1 + 3 + 1 + 1 + 1, RowPlanner.totalRows(three, true))
        assertEquals(1 + 4 + 1 + 1 + 1, RowPlanner.totalRows(four, true))
        assertEquals(1 + 4 + 1 + 1, RowPlanner.totalRows(four, false)) // no Vulkan: no GPU row
    }

    @Test
    fun givenClusterIdsOutOfCpuOrderInTheInput_whenPlanned_thenTheyStillFollowTheLowestCpu() {
        val s = staticWith(listOf(listOf(6, 7), listOf(0, 1, 2, 3), listOf(4, 5)))
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5), listOf(6, 7)), ids(RowPlanner.clusters(s)))
    }

    @Test
    fun givenTheFirstCpuOfAClusterDoesNotPin_whenChosen_thenTheNextPinnableCpuOfTheSameClusterIsTheSubstitute() {
        val c = RowPlanner.clusters(staticWith(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)), unpinnable = setOf(4, 5)))[1]
        val choice = RowPlanner.c2Choice(c)
        assertEquals(6, choice.cpu); assertEquals(4, choice.substitutedFor); assertFalse(choice.unpinned)
        val plain = RowPlanner.c2Choice(RowPlanner.clusters(staticWith(listOf(listOf(0, 1))))[0])
        assertEquals(0, plain.cpu); assertNull(plain.substitutedFor); assertFalse(plain.unpinned)
    }

    @Test
    fun givenAClusterWithNoPinnableCpu_whenChosen_thenItIsAnUnpinnedRowOnItsFirstCpu() {
        val c = RowPlanner.clusters(staticWith(listOf(listOf(0, 1), listOf(2, 3)), unpinnable = setOf(2, 3)))[1]
        val choice = RowPlanner.c2Choice(c)
        assertEquals(2, choice.cpu); assertNull(choice.substitutedFor); assertTrue(choice.unpinned)
        assertEquals(emptyList<Int>(), c.pinnable)
    }

    @Test
    fun givenMeasuredValues_whenRanked_thenFastestFirstTiesKeepTheLowerIdAndUnmeasuredComeLast() {
        val cs = RowPlanner.clusters(staticWith(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5), listOf(6, 7))))
        assertEquals(listOf(2, 1, 0, 3), RowPlanner.ranking(cs, mapOf(0 to 4.5, 1 to 14.8, 2 to 24.1, 3 to 1.0)))
        assertEquals(listOf(1, 2, 0, 3), RowPlanner.ranking(cs, mapOf(0 to 4.5, 1 to 14.8, 2 to 14.8, 3 to 1.0))) // tie: lower id first
        assertEquals(listOf(0, 3, 1, 2), RowPlanner.ranking(cs, mapOf(0 to 4.5, 3 to 4.4))) // 1 and 2 are not measured: last, by id
        assertEquals(listOf(0, 1, 2, 3), RowPlanner.ranking(cs, emptyMap()))
        assertEquals(listOf(0, 1, 2, 3), RowPlanner.ranking(cs, mapOf(0 to 7.0, 1 to 7.0, 2 to 7.0, 3 to 7.0)))
    }

    @Test
    fun givenARanking_whenTheTwoFastestAreChosen_thenTheyComeFromTheFastestClusterThenTheNext() {
        val cs = RowPlanner.clusters(staticWith(listOf(listOf(0, 1, 2, 3), listOf(4, 5), listOf(6, 7))))
        assertEquals(listOf(6, 7), RowPlanner.twoFastest(cs, listOf(2, 1, 0)))
        assertEquals(listOf(4, 5), RowPlanner.twoFastest(cs, listOf(1, 2, 0)))
        // a fastest cluster with one pinnable CPU gives one, and the next cluster the other
        val one = RowPlanner.clusters(staticWith(listOf(listOf(0, 1, 2, 3), listOf(4, 5)), unpinnable = setOf(5)))
        assertEquals(listOf(4, 0), RowPlanner.twoFastest(one, listOf(1, 0)))
        // fewer than two pinnable CPUs: no row
        val few = RowPlanner.clusters(staticWith(listOf(listOf(0, 1)), unpinnable = setOf(1)))
        assertNull(RowPlanner.twoFastest(few, listOf(0)))
    }

    @Test
    fun givenOneCpuOnly_whenPlanned_thenThereIsOneClusterNoTwoFastRowAndAnAllRow() {
        val s = staticWith(listOf(listOf(0)))
        val cs = RowPlanner.clusters(s)
        assertEquals(listOf(listOf(0)), ids(cs))
        assertNull(RowPlanner.twoFastest(cs, listOf(0)))
        assertEquals(listOf(0), RowPlanner.allPinnable(cs))
        assertEquals(1 + 1 + 0 + 1 + 1, RowPlanner.totalRows(cs, true))
    }

    @Test
    fun givenCpusThePinProbeDidNotList_whenPlanned_thenTheyAreNotPinnable() {
        val s = LinkedHashMap(staticWith(listOf(listOf(0, 1, 2, 3))))
        @Suppress("UNCHECKED_CAST")
        val cpus = (s["cpus"] as List<Map<String, Any?>>).map { c -> LinkedHashMap(c).also { if (it["index"] == 3L) it.remove("pin") } }
        s["cpus"] = cpus
        assertEquals(listOf(0, 1, 2), RowPlanner.clusters(s)[0].pinnable)
    }

    @Test
    fun givenVulkanFacts_whenAsked_thenOnlyAPresentComputeQueueCounts() {
        assertTrue(RowPlanner.hasVulkanCompute(staticWith(listOf(listOf(0)))))
        assertFalse(RowPlanner.hasVulkanCompute(staticWith(listOf(listOf(0)), vulkanCompute = false)))
        assertFalse(RowPlanner.hasVulkanCompute(mapOf("vulkan" to mapOf("present" to true, "compute_queue" to false))))
        assertFalse(RowPlanner.hasVulkanCompute(mapOf("vulkan" to mapOf("present" to true))))
        assertFalse(RowPlanner.hasVulkanCompute(mapOf("vulkan" to mapOf("present" to false, "compute_queue" to true))))
    }

    @Test
    fun givenTheEstimateBeforeThePinProbe_whenCounted_thenEveryCpuIsAssumedToPin() {
        val cs = RowPlanner.clusters(staticWith(listOf(listOf(0, 1), listOf(2, 3)), unpinnable = setOf(0, 1, 2, 3)))
        assertEquals(1 + 2 + 0 + 0 + 1, RowPlanner.totalRows(cs, true))
        assertEquals(1 + 2 + 1 + 1 + 1, RowPlanner.totalRows(cs, true, assumeAllPinnable = true))
    }
}
