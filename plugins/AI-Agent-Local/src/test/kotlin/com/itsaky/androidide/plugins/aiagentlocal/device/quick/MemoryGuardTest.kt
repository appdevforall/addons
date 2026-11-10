package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryGuardTest {
    @Test
    fun givenMemTotalAndMemAvailable_whenTheLimitIsComputed_thenItIsTheSmallerOf55PercentAndSeventyPercentInMib() {
        // D5-like: 55 % of 3817152 kB = 2099433 kB (2050 MiB); 70 % of 2300000 kB = 1610000 kB (1572 MiB)
        assertEquals(1572L, MemoryGuard.limitMib(MemInfo(3_817_152L, 2_300_000L)))
        // plenty available: the total decides
        assertEquals(2050L, MemoryGuard.limitMib(MemInfo(3_817_152L, 3_500_000L)))
        // exact boundary: 55 % of 10000 kB = 5500 kB, 70 % of 7857 kB = 5499 kB: the available figure wins by one kB
        assertEquals(5499L / 1024, MemoryGuard.limitMib(MemInfo(10_000L, 7_857L)))
        // never below 1
        assertEquals(1L, MemoryGuard.limitMib(MemInfo(1_000L, 1_000L)))
        // D1-like
        assertEquals(5190L, MemoryGuard.limitMib(MemInfo(11_827_500L, 7_593_280L))) // the recorded L of the phone run
    }

    @Test
    fun givenProcMeminfoText_whenRead_thenBothFiguresAreParsedAndAMissingOneGivesNull() {
        val ok = ProcMemoryProbe(FakeFiles(mutableMapOf("/proc/meminfo" to "MemTotal:        3817152 kB\nMemFree:  1 kB\nMemAvailable:    2300000 kB\n")))
        val m = ok.read()!!
        assertEquals(3_817_152L, m.totalKb); assertEquals(2_300_000L, m.availKb)
        assertNull(ProcMemoryProbe(FakeFiles(mutableMapOf("/proc/meminfo" to "MemTotal: 5 kB\n"))).read())
        assertNull(ProcMemoryProbe(FakeFiles(mutableMapOf())).read())
        assertNull(ProcMemoryProbe(FakeFiles(mutableMapOf("/proc/meminfo" to "MemAvailable: 5 kB\nMemTotal: x kB\n"))).read())
    }
}
