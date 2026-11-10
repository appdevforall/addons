package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PartLibTest {

    @Test
    fun givenRelatedLists_whenClustered_thenGroupsAreConnectedAndIdsFollowTheLowestCpu() {
        val info = PartLib.clusterInfo(
            linkedMapOf(0L to listOf(0L, 1L), 1L to listOf(1L), 2L to null, 3L to listOf(3L, 2L), 4L to null, 5L to listOf(0L)),
        )
        assertEquals(listOf(listOf(0L, 1L, 5L), listOf(2L, 3L), listOf(4L)), info.members)
        assertEquals("listed", info.how[0L]); assertEquals("joined", info.how[2L]); assertEquals("singleton", info.how[4L])
        assertEquals(mapOf(0L to 0, 1L to 0, 5L to 0, 2L to 1, 3L to 1, 4L to 2), info.clusterOf)
    }

    @Test
    fun givenHwcapWords_whenDecoded_thenNamedBitsFollowTheTablesAndOthersAreReported() {
        val (names, unnamed) = PartLib.decodeHwcap(BigInteger.valueOf(0b11L), BigInteger.valueOf(0b10L))
        assertEquals(listOf("fp", "asimd", "sve2"), names)
        assertEquals(emptyList<String>(), unnamed)
        val (_, un) = PartLib.decodeHwcap(BigInteger.ONE.shiftLeft(60), BigInteger.ONE.shiftLeft(70))
        assertEquals(listOf("hwcap bit 60", "hwcap2 bit 70"), un)
        assertEquals(48, PartLib.HWCAP_NAMES.size); assertEquals(64, PartLib.HWCAP2_NAMES.size)
    }

    @Test
    fun givenAnUnkLeafWithoutReasonOrABadOrigin_whenBuilt_thenItIsRefused() {
        try { PartLib.unk(""); fail() } catch (_: IllegalArgumentException) {}
        try { PartLib.leaf(1L, "guess"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(null, PartLib.unk("why")["value"])
    }
}
