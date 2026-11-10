package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClChildTest {
    @Test
    fun givenTheRecordedGpuProbe_whenTheChildListIsRead_thenEveryCandidateIsNotLoaded() {
        val probe = jsonOf(Recorded.lastLine("d5-gpuprobe-v0.2.3.out"))
        val c = OpenClChild.parse(probe)
        assertEquals(4, c.size)
        assertEquals(listOf("libOpenCL.so", "libOpenCL-pixel.so", "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so"), c.map { it.name })
        assertEquals(setOf(OpenClLoad.NOT_LOADED), c.map { it.load }.toSet())
        assertEquals(setOf("dlerror"), c.map { it.outcome }.toSet())
    }

    @Test
    fun givenNotTriedLoadedAndTimeoutOutcomes_whenRead_thenNotTriedIsUnknown() {
        val probe = mapOf<String, Any?>(
            "opencl_child" to listOf(
                mapOf("name" to "a", "loaded" to true, "outcome" to "loaded"),
                mapOf("name" to "b", "loaded" to false, "outcome" to "timeout"),
                mapOf("name" to "c", "loaded" to false, "outcome" to "not_tried"),
                mapOf("name" to "d", "loaded" to false, "outcome" to "crashed", "signal" to 11L),
                mapOf("name" to "e", "loaded" to true, "outcome" to "dlerror"), // inconsistent: not loaded
            ),
        )
        assertEquals(
            listOf(OpenClLoad.LOADED, OpenClLoad.NOT_LOADED, OpenClLoad.UNKNOWN, OpenClLoad.NOT_LOADED, OpenClLoad.NOT_LOADED),
            OpenClChild.parse(probe).map { it.load },
        )
        assertEquals(emptyList<OpenClCandidate>(), OpenClChild.parse(emptyMap()))
        assertEquals("unknown", OpenClChild.toFacts(OpenClChild.parse(probe))[2]["load"])
    }
}
