package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcQuietIdeGateTest {
    private class FakeProc(val procs: MutableMap<Int, Pair<Int?, List<String>?>>) : ProcFs {
        override fun pids() = procs.keys.toList()
        override fun uidOf(pid: Int) = procs[pid]?.first
        override fun cmdline(pid: Int) = procs[pid]?.second
    }

    private val own = 10369

    @Test
    fun givenProcessesOfTheOwnUid_whenScanned_thenAJavaOrGradleDaemonIsBusyAndTheAppAndTheEngineAreNot() {
        val app = FakeProc(mutableMapOf(
            100 to (own to listOf("com.itsaky.androidide")),
            101 to (own to listOf("/data/app/x/lib/arm64/libpart1_membw.so", "--pin-probe")),
            102 to (own to listOf("sh")),
        ))
        val gate = ProcQuietIdeGate(app, own)
        assertFalse(gate.isBusy())
        app.procs[200] = own to listOf("/apex/com.android.art/bin/java", "-Xmx1g", "-cp", "x", "Main")
        assertTrue(gate.isBusy())
        app.procs.remove(200)
        assertFalse(gate.isBusy())
        app.procs[201] = own to listOf("/data/data/x/files/usr/bin/sh", "-c", "exec org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.5")
        assertTrue(gate.isBusy())
    }

    @Test
    fun givenAJavaProcessOfAnotherUid_whenScanned_thenItIsIgnoredAndAnUnreadableOneIsSkipped() {
        val p = FakeProc(mutableMapOf(
            1 to (10001 to listOf("java", "-jar", "x.jar")),
            2 to (null to listOf("java")),
            3 to (own to null),
        ))
        assertFalse(ProcQuietIdeGate(p, own).isBusy())
    }

    @Test
    fun givenTheCommandLines_whenMatched_thenTheRuleIsExactlyJavaOrAGradleMarker() {
        assertTrue(ProcQuietIdeGate.matches(listOf("java")))
        assertTrue(ProcQuietIdeGate.matches(listOf("/usr/bin/java17", "x")))
        assertTrue(ProcQuietIdeGate.matches(listOf("sh", "x/gradle-launcher-8.5.jar")))
        assertTrue(ProcQuietIdeGate.matches(listOf("x", "-Dfoo", "org.gradle.wrapper.GradleWrapperMain")))
        assertTrue(ProcQuietIdeGate.matches(listOf("x", "GradleDaemon")))
        assertFalse(ProcQuietIdeGate.matches(listOf("javascript")))
        assertFalse(ProcQuietIdeGate.matches(listOf("libpart1_gpubw.so", "org.gradle")))
        assertFalse(ProcQuietIdeGate.matches(emptyList()))
        assertFalse(ProcQuietIdeGate.matches(listOf("com.itsaky.androidide")))
    }

    @Test
    fun givenTheScanTurnsBusyTwice_whenStartsAreCounted_thenEachQuietToBusyEdgeCountsOnce() {
        val p = FakeProc(mutableMapOf(1 to (own to listOf("com.itsaky.androidide"))))
        val g = ProcQuietIdeGate(p, own)
        assertEquals(0L, g.startsSeen()); g.isBusy()
        p.procs[2] = own to listOf("java"); g.isBusy(); g.isBusy()
        assertEquals(1L, g.startsSeen())
        p.procs.remove(2); g.isBusy(); p.procs[3] = own to listOf("java"); g.isBusy()
        assertEquals(2L, g.startsSeen())
    }
}
