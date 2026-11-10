package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineRunner
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.Judge
import com.itsaky.androidide.plugins.aiagentlocal.device.store.DeviceKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A whole T1 pass on the recorded engine lines of the two phones (D5: engine 0.2.1 rows and 0.2.3 probes from P1-03S and P1-03P; D1: the same
 * runs of the second phone; every line was checked for identifiers before it was copied, see `exits.json`), through the pass, the real store
 * and `regenerate()`. The descriptors are written for `validate.py --production` when `-Dpart1.engineOutDir` is given.
 */
class QuickEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun countOrigin(x: Any?, origin: String): Int = when (x) {
        is Map<*, *> -> (if (x["origin"] == origin && x.containsKey("value")) 1 else 0) + x.values.sumOf { countOrigin(it, origin) }
        is List<*> -> x.sumOf { countOrigin(it, origin) }
        else -> 0
    }

    private fun labelsOf(x: Any?, out: MutableList<List<*>>, path: String = "", hits: MutableList<String> = ArrayList()): List<String> {
        when (x) {
            is Map<*, *> -> {
                val c = x["conditions"]
                if (c is Map<*, *> && c["labels"] is List<*> && (c["labels"] as List<*>).contains("gpu_rate_unstable")) hits.add(path)
                x.forEach { (k, v) -> labelsOf(v, out, "$path/$k", hits) }
            }
            is List<*> -> x.forEachIndexed { i, v -> labelsOf(v, out, "$path[$i]", hits) }
        }
        return hits
    }

    @Test
    fun givenTheRecordedRowsOfD5_whenAPassRuns_thenTheDescriptorHasMeasuredAppLeavesAndTheRightKeyAndTier() {
        val r = Rig(File(tmp.root, "d5"), "D5")
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        val d = r.store.readDescriptor()!!
        assertTrue(countOrigin(d, "measured_app") >= 15)
        assertEquals(0, countOrigin(d, "measured_shell"))
        assertEquals("T1", Json.obj(Json.obj(d["run"], "run")["tier"], "t")["value"])
        assertEquals(DeviceKey.of(r.fingerprint, EngineRunner.EXPECTED_ENGINE_VERSION), Json.obj(Json.obj(d["run"], "run")["device_key"], "k")["value"])
        val clusters = Json.arr(d["clusters"], "cl").map { Json.obj(it, "c") }
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { Json.obj(it["read_1core_gbs"], "r")["origin"] == "measured_app" })
        assertEquals(listOf(2L, 1L), clusters.map { Json.obj(it["rank"], "r")["value"] }) // the first cluster is the slow one
        Judge.write("p104a2-d5", r.store.readStatic()!!, r.store.readRows().lines, r.store.readDbRecord(), d)
    }

    @Test
    fun givenTheRecordedRowsOfD1_whenAPassRuns_thenThreeClustersAreRankedAndTheGpuLeafCarriesTheUnstableLabel() {
        val r = Rig(File(tmp.root, "d1"), "D1")
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        assertEquals(listOf("pinprobe", "C2-read-k0", "C2-read-k1", "C2-read-k2", "C3-read-2fast", "C4-read-all", "C7-gpu-alone"), r.tags())
        // two fastest CPUs are those of the fastest cluster (6 and 7)
        val c3 = r.engine.commands.first { it.tag == "C3-read-2fast" }
        assertEquals("6+7", c3.args[c3.args.indexOf("--cores") + 1])
        val d = r.store.readDescriptor()!!
        val clusters = Json.arr(d["clusters"], "cl").map { Json.obj(it, "c") }
        assertEquals(3, clusters.size)
        assertEquals(listOf(3L, 2L, 1L), clusters.map { Json.obj(it["rank"], "r")["value"] })
        val hits = labelsOf(d, ArrayList())
        assertTrue("a leaf with the gpu_rate_unstable label: $hits", hits.isNotEmpty())
        assertTrue(hits.any { it.contains("gpu") })
        assertTrue(countOrigin(d, "measured_app") >= 15)
        Judge.write("p104a2-d1", r.store.readStatic()!!, r.store.readRows().lines, r.store.readDbRecord(), d)
        assertNotNull(r.pass()["pin_probe"])
    }

    // ---- the time budget ---------------------------------------------------------------------------------------------

    /** The D5 T0 object with the CPUs regrouped into [groups] (the pin probe and rows of the recorded D5 run are then served for them). */
    private fun rigWithClusters(name: String, groups: List<List<Int>>): Rig {
        val r = Rig(File(tmp.root, name), "D5")
        val s = LinkedHashMap(r.store.readStatic()!!)
        val related = HashMap<Long, List<Long>>()
        for (g in groups) for (c in g) related[c.toLong()] = g.map { it.toLong() }
        s["cpus"] = Json.arr(s["cpus"], "cpus").map { c -> LinkedHashMap(Json.obj(c, "c")).also { it["related_cpus"] = related[(it["index"] as Number).toLong()] } }
        check(r.store.writeStatic(s))
        for (k in 2 until groups.size) r.overrides["C2-read-k$k"] = { cmd -> Lines.ok("d5-row-c2-fast-ok-v0.2.1.out", "C2-read-k4", cmd.tag!!).also { r.clock.now += (((it.json["duration_s"] as Number).toDouble()) * 1000).toLong() } }
        return r
    }

    @Test
    fun givenTheRecordedRowDurationsAndNoWaits_whenAPassRunsOnTwoThreeAndFourClusters_thenItTakesUnder90Seconds() {
        // arithmetic (recorded durations of the D5 run, seconds): pin probe 2.0 + C2 k0 10.97 + C2 fast 10.4 (per further cluster)
        // + C3 10.2 + C4 11.1 + GPU 1.8; the no-wait budget of the plan is 90 s.
        val groups = mapOf(
            2 to listOf((0..3).toList(), (4..7).toList()),
            3 to listOf((0..3).toList(), listOf(4, 5), listOf(6, 7)),
            4 to listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5), listOf(6, 7)),
        )
        val seconds = HashMap<Int, Double>()
        for ((n, g) in groups) {
            val r = rigWithClusters("t$n", g)
            val res = r.run()
            assertEquals("$n clusters", QuickStatus.COMPLETED, res.status)
            assertEquals("no waits", emptyList<Long>(), r.clock.sleeps)
            assertEquals(1 + n + 1 + 1 + 1, r.engine.commands.size)
            seconds[n] = res.elapsedMs / 1000.0
            assertTrue("$n clusters took ${res.elapsedMs} ms", res.elapsedMs < 90_000)
        }
        assertTrue(seconds.getValue(2) < seconds.getValue(3) && seconds.getValue(3) < seconds.getValue(4))
        println("T1 budget (recorded durations, no waits): 2 clusters ${seconds[2]} s, 3 clusters ${seconds[3]} s, 4 clusters ${seconds[4]} s")
    }
}
