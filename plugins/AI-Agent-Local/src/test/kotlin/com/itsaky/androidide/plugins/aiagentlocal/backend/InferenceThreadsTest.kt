package com.itsaky.androidide.plugins.aiagentlocal.backend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InferenceThreadsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** The Pixel 6 Pro's `cpu_capacity`, read off the device: 4 A55, 2 A76, 2 X1. */
    private val pixel6Pro = listOf(160, 160, 160, 160, 498, 498, 1024, 1024)

    @Test
    fun givenAPhoneWithEfficiencyCores_whenPlanned_thenGenerationUsesOnlyTheFastOnesAndPrefillKeepsTheDefault() {
        // Two X1 and two A76 write faster than six threads that include the A55s; reading the
        // prompt still gains from all six, so batch stays where the engine would have put it.
        assertEquals(ThreadPlan(generation = 4, batch = 6), InferenceThreads.plan(pixel6Pro, onlineCores = 8))
    }

    @Test
    fun givenTheMidCoresAtJustUnderHalfTheFastest_whenPlanned_thenTheyAreNotTakenForEfficiencyCores() {
        // 498 / 1024 is 0.49: a cut-off at one half would have left a Pixel 6 Pro two threads.
        assertEquals(4, InferenceThreads.plan(pixel6Pro, onlineCores = 8).generation)
    }

    @Test
    fun givenAPhoneWhoseCoresAreAlike_whenPlanned_thenItKeepsTheEnginesOwnCount() {
        val plan = InferenceThreads.plan(List(8) { 1024 }, onlineCores = 8)

        assertEquals(ThreadPlan(generation = 6, batch = 6), plan)
    }

    @Test
    fun givenSmallCoresAboveTheCutOff_whenPlanned_thenNothingIsDropped() {
        // Six A55 at 0.4 of two A78: this cannot be told from a phone worth keeping them on.
        val plan = InferenceThreads.plan(List(6) { 410 } + List(2) { 1024 }, onlineCores = 8)

        assertEquals(ThreadPlan(generation = 6, batch = 6), plan)
    }

    @Test
    fun givenNoReadableCapacities_whenPlanned_thenItKeepsTheEnginesOwnCount() {
        assertEquals(ThreadPlan(6, 6), InferenceThreads.plan(emptyList(), onlineCores = 8))
        assertEquals(ThreadPlan(6, 6), InferenceThreads.plan(List(8) { 0 }, onlineCores = 8))
    }

    @Test
    fun givenFewCores_whenPlanned_thenNeitherCountIsBelowOneOrAboveTheEnginesCeiling() {
        assertEquals(ThreadPlan(1, 1), InferenceThreads.plan(List(2) { 1024 }, onlineCores = 2))
        assertEquals(ThreadPlan(1, 1), InferenceThreads.plan(emptyList(), onlineCores = 1))
        assertEquals(ThreadPlan(8, 8), InferenceThreads.plan(List(16) { 1024 }, onlineCores = 16))
    }

    @Test
    fun givenAGenerationCountAboveBatch_whenPlanned_thenItIsCappedAtBatch() {
        // Seven fast cores on an eight-core phone: batch is six, and generation never exceeds it.
        val plan = InferenceThreads.plan(List(7) { 1024 } + 100, onlineCores = 8)

        assertEquals(ThreadPlan(generation = 6, batch = 6), plan)
    }

    @Test
    fun givenASysfsTree_whenRead_thenItPlansFromEachCoresCapacity() {
        val root = sysfs(pixel6Pro.map { it.toString() })

        assertEquals(ThreadPlan(4, 6), InferenceThreads.read(root, onlineCores = 8))
    }

    @Test
    fun givenUnparseableAndMissingCapacityFiles_whenRead_thenThoseCoresAreIgnored() {
        // cpu0 holds garbage and cpu1 has no file at all; the rest still decide.
        val root = sysfs(listOf("garbage", null, "160", "160", "498", "498", "1024", "1024"))

        assertEquals(ThreadPlan(4, 6), InferenceThreads.read(root, onlineCores = 8))
    }

    @Test
    fun givenADirectoryThatDoesNotExist_whenRead_thenItKeepsTheEnginesOwnCount() {
        assertEquals(ThreadPlan(6, 6), InferenceThreads.read(File(temporaryFolder.root, "absent"), onlineCores = 8))
    }

    @Test
    fun givenEntriesThatAreNotCores_whenRead_thenTheyAreNotCounted() {
        val root = sysfs(pixel6Pro.map { it.toString() })
        // The kernel's own neighbours of cpuN: cpufreq, cpuidle, and the like.
        File(root, "cpufreq").mkdirs()
        File(root, "cpufreq/cpu_capacity").writeText("1")

        assertEquals(ThreadPlan(4, 6), InferenceThreads.read(root, onlineCores = 8))
    }

    /** Lays out `cpuN/cpu_capacity` for each entry; a null entry leaves that core with no file. */
    private fun sysfs(capacities: List<String?>): File {
        val root = temporaryFolder.newFolder()
        capacities.forEachIndexed { index, capacity ->
            val core = File(root, "cpu$index").apply { mkdirs() }
            capacity?.let { File(core, "cpu_capacity").writeText("$it\n") }
        }
        return root
    }
}
