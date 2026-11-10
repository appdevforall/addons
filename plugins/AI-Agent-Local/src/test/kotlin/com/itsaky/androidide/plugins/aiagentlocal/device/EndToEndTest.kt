package com.itsaky.androidide.plugins.aiagentlocal.device

import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PinProbe
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommands
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineRunner
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.FakeLauncher
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.FakeLocator
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.FakeProcess
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.Recorded
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.D5Like
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.Judge
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.StaticReader
import com.itsaky.androidide.plugins.aiagentlocal.device.store.AppendResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.BindResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.DeviceKey
import com.itsaky.androidide.plugins.aiagentlocal.device.store.Part1Store
import com.itsaky.androidide.plugins.aiagentlocal.device.store.RegenerateResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.RowRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole chain on the JVM: fake reads of a D5-like phone (the recorded probes), the runner replaying the recorded D5 rows,
 * the store, `regenerate()`. The descriptor must be one `validate.py --production` accepts, with `measured_app` leaves. The
 * recorded rows were written by engine 0.2.1; the test serves them with the packaged engine's version (0.2.4) and with
 * the tag the app would have started for the second cluster (`C2-read-k1`): the only two edits.
 */
class EndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = EngineRunner.EXPECTED_ENGINE_VERSION

    private fun served(fixture: String, tagFrom: String? = null, tagTo: String? = null): ByteArray {
        var t = Recorded.text(fixture).replace("part1-engine/0.2.1", engine).replace("part1-engine/0.2.3", engine)
        if (tagFrom != null) t = t.replace("\"tag\":\"$tagFrom\"", "\"tag\":\"$tagTo\"")
        return t.toByteArray()
    }

    private fun countLeaves(x: Any?, out: MutableMap<String, Int>) {
        when (x) {
            is Map<*, *> -> {
                val o = x["origin"]
                if (o is String && x.containsKey("value") && x.containsKey("conditions")) out.merge(o, 1, Int::plus)
                x.values.forEach { countLeaves(it, out) }
            }
            is List<*> -> x.forEach { countLeaves(it, out) }
        }
    }

    @Test
    fun givenAD5LikePhoneAndTheRecordedD5Rows_whenReadRunStoredAndRegenerated_thenTheDescriptorHasMeasuredAppLeavesAndTheRightKey() {
        val phone = D5Like()
        val t0 = phone.reader().read("1.1.0")

        // the runner replays the recorded output, one process per invocation
        val launcher = FakeLauncher { argv ->
            val a = argv.drop(1)
            when {
                "--static-probe" in a -> FakeProcess(stdout = Recorded.staticProbe024())
                "--pin-probe" in a -> FakeProcess(stdout = served("d5-pinprobe-v0.2.3.out"))
                "C2-read-k0" in a -> FakeProcess(stdout = served("d5-row-c2-ok-v0.2.1.out"))
                "C2-read-k1" in a -> FakeProcess(stdout = served("d5-row-c2-fast-ok-v0.2.1.out", "C2-read-k4", "C2-read-k1"))
                "C3-read-2fast" in a -> FakeProcess(stdout = served("d5-row-c3-ok.out"))
                "C4-read-all" in a -> FakeProcess(stdout = served("d5-row-c4-ok.out"))
                "C7-gpu-alone" in a -> FakeProcess(stdout = served("d5-gpu-row-ok.out"))
                else -> throw AssertionError("unexpected command $argv")
            }
        }
        val runner = EngineRunner(FakeLocator(), launcher, File(tmp.root, "part1/work"))

        // the pin probe is folded into the T0 object
        val pin = runner.run(EngineCommands.pinProbe((0..7).toList())) as EngineResult.Ok
        val folded = StaticReader.foldPinProbe(t0.static, PinProbe.parse(pin.json))

        val limit = 1694L
        val results = listOf(
            EngineCommands.c2Read(0, 0, limit), EngineCommands.c2Read(1, 4, limit), EngineCommands.c3Read2Fast(4, 5, limit),
            EngineCommands.c4ReadAll((0..7).toList(), limit), EngineCommands.c7GpuAlone(limit),
        ).map { runner.run(it) }
        assertTrue(results.all { it is EngineResult.Ok })

        val fingerprint = (Json.obj(folded["identity"], "i")["build_fingerprint"]) as String
        val store = Part1Store(File(tmp.root, "plugin-data/part1"))
        val bound = store.bindDevice(fingerprint, engine) as BindResult.Fresh
        assertEquals(DeviceKey.of(fingerprint, engine), bound.key)
        assertTrue(store.writeStatic(folded))
        val lines = ArrayList<String>()
        for (r in results) {
            val row = RowRecord.withConditions((r as EngineResult.Ok).json, t0.conditions)
            assertTrue(store.appendRow(row) is AppendResult.Appended)
            lines.add(Json.toOrg(row).toString())
        }
        val res = store.regenerate(Part1TestResources.realIndex(), "1.1.0") as RegenerateResult.Regenerated
        val d = res.descriptor

        // what the descriptor says
        assertEquals(DeviceKey.of(fingerprint, engine), Json.obj(Json.obj(d["run"], "run")["device_key"], "k")["value"])
        assertEquals("T1", Json.obj(Json.obj(d["run"], "run")["tier"], "t")["value"])
        val origins = LinkedHashMap<String, Int>()
        countLeaves(d, origins)
        assertTrue("measured_app leaves: $origins", (origins["measured_app"] ?: 0) >= 15)
        assertEquals(null, origins["measured_shell"])
        val core0 = Json.arr(d["cores"], "cores").map { Json.obj(it, "c") }[0]
        assertEquals("measured_app", Json.obj(core0["pin"], "pin")["origin"])
        assertEquals("pin probe (MIDR read from sysfs)", Json.obj(core0["midr"], "midr")["source"])
        assertEquals("database", Json.obj(Json.obj(d["database"], "db")["match"], "m")["origin"])
        val clusters = Json.arr(d["clusters"], "cl").map { Json.obj(it, "c") }
        assertTrue(clusters.all { Json.obj(it["read_1core_gbs"], "r")["origin"] == "measured_app" })
        assertEquals(listOf(2L, 1L), clusters.map { Json.obj(it["rank"], "r")["value"] }) // cluster 0 is the slow one

        // the files the next start finds
        assertEquals(emptyList<String>(), Json.diff(d, store.readDescriptor()))
        assertEquals(5, store.readRows().lines.size)
        Judge.write("e2e-d5-like", folded, lines, store.readDbRecord(), d)
    }
}
