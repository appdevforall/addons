package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class EngineCommandsTest {

    private fun line(c: EngineCommand) = c.args.joinToString(" ")

    @Test
    fun givenTheThreeProbes_whenBuilt_thenTheyAreTheEngineCommandLines() {
        val s = EngineCommands.staticProbe()
        assertEquals(EngineProgram.MEMBW, s.program)
        assertEquals("--static-probe --json --max-seconds 2", line(s))
        assertNull(s.tag)
        val g = EngineCommands.gpuProbe()
        assertEquals(EngineProgram.GPUBW, g.program)
        assertEquals("--probe --json --max-seconds 5", line(g)) // above the probe's documented 3 s worst case
        assertEquals(5, g.maxSeconds)
        val p = EngineCommands.pinProbe(listOf(0, 1, 2, 3, 4, 5, 6, 7))
        assertEquals("--pin-probe --cores 0,1,2,3,4,5,6,7 --spin-ms 2000 --json --max-seconds 52", line(p))
        assertEquals(52, p.maxSeconds)
        assertEquals(CommandKind.PIN_PROBE, p.kind)
    }

    @Test
    fun givenTheT1Rows_whenBuilt_thenTheyHaveTheDocumentsCommandLinesAndTails() {
        val tail = "--json --tier T1 --mem-limit-mib 1694 --fit-buffer --max-seconds 120"
        assertEquals("--kernel K02 --threads 1 --cores 4 --warmup-s 3 --window-s 6 --runs 1 --tag C2-read-k1 $tail", line(EngineCommands.c2Read(1, 4, 1694)))
        assertEquals("--kernel K02 --threads 2 --cores 6+7 --warmup-s 3 --window-s 6 --runs 1 --tag C3-read-2fast $tail", line(EngineCommands.c3Read2Fast(6, 7, 1694)))
        assertEquals("--kernel K02 --threads 4 --cores 0+1+2+3 --warmup-s 3 --window-s 6 --runs 1 --tag C4-read-all $tail", line(EngineCommands.c4ReadAll(listOf(0, 1, 2, 3), 1694)))
        val gpu = EngineCommands.c7GpuAlone(1694)
        assertEquals("--modes 0 --per-wg 4096 --tag C7-gpu-alone --json --tier T1 --mem-limit-mib 1694 --fit-buffer --max-seconds 180", line(gpu))
        assertEquals(EngineProgram.GPUBW, gpu.program)
        assertEquals("C7-gpu-alone", gpu.tag)
        assertEquals(180, gpu.maxSeconds)
        assertEquals(120, EngineCommands.c2Read(0, 0, 1).maxSeconds)
        assertEquals("C2-read-k1", EngineCommands.c2Read(1, 4, 1694).tag)
    }

    @Test
    fun givenBadArguments_whenBuilt_thenTheyAreRefused() {
        val bad = listOf<() -> Any>(
            { EngineCommands.c2Read(-1, 0, 100) }, { EngineCommands.c2Read(0, -1, 100) }, { EngineCommands.c2Read(0, 0, 0) },
            { EngineCommands.c3Read2Fast(1, 1, 100) }, { EngineCommands.c4ReadAll(emptyList(), 100) }, { EngineCommands.c4ReadAll(listOf(1, 2, 1), 100) },
            { EngineCommands.c7GpuAlone(-5) }, { EngineCommands.pinProbe(emptyList()) }, { EngineCommands.pinProbe(listOf(0), 0) },
        )
        for ((i, b) in bad.withIndex()) {
            try {
                b()
                fail("case $i accepted")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun givenAProgram_whenNamed_thenTheFileIsTheLibraryTheLoaderExtracts() {
        assertEquals("libpart1_membw.so", EngineProgram.MEMBW.fileName)
        assertEquals("libpart1_gpubw.so", EngineProgram.GPUBW.fileName)
        assertEquals("part1_membw", EngineProgram.MEMBW.libraryName)
        assertEquals("part1_gpubw", EngineProgram.GPUBW.libraryName)
    }

    @Test
    fun givenAllowUnpinned_whenAC2RowIsBuilt_thenTheOptionSitsBeforeTheTagAndOtherwiseIsAbsent() {
        val plain = EngineCommands.c2Read(0, 0, 100)
        assertEquals(false, plain.args.contains("--allow-unpinned"))
        val un = EngineCommands.c2Read(2, 6, 100, allowUnpinned = true)
        assertEquals("C2-read-k2", un.tag)
        val a = un.args
        assertEquals(listOf("--warmup-s", "3", "--window-s", "6", "--runs", "1", "--allow-unpinned", "--tag", "C2-read-k2"), a.subList(a.indexOf("--warmup-s"), a.indexOf("--tag") + 2))
        assertEquals(plain.args.size + 1, EngineCommands.c2Read(0, 0, 100, allowUnpinned = true).args.size)
    }
}
