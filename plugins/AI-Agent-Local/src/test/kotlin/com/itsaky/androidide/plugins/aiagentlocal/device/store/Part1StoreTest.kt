package com.itsaky.androidide.plugins.aiagentlocal.device.store

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.DescriptorGenerator
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.Recorded
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Part1StoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = "part1-engine/0.2.4"
    private val fp = "synthetic/store-test:14/SYN.1/1:user/test-keys"

    private fun dir() = File(tmp.root, "part1")
    private fun store(ops: FileOps = FileOps.Real, cap: Long = Part1Store.DEFAULT_MAX_TOTAL_BYTES, archives: Int = 3) = Part1Store(dir(), ops, cap, archives)

    private fun staticTree(): Map<String, Any?> {
        val name = Part1TestResources.goldenCaseNames().first { it.endsWith("t0-no-rows-no-record") }
        return Part1TestResources.obj("part1/golden/$name/static.json")
    }

    /** A recorded row of the D5 phone: the engine version of the packaged engine, and the tag the app would have started. */
    private fun row(fixture: String = "d5-row-c2-ok-v0.2.1.out", tag: String? = null): Map<String, Any?> {
        var line = Recorded.lastLine(fixture).replace("part1-engine/0.2.1", engine)
        if (tag != null) line = line.replace(Regex("\"tag\":\"[^\"]+\""), "\"tag\":\"$tag\"")
        return Json.parseObject(line)
    }

    @Test
    fun givenAFreshDirectory_whenBound_thenTheKeyIsStoredAndTheSecondBindIsTheSame() {
        val s = store()
        val key = DeviceKey.of(fp, engine)
        assertEquals(64, key.length)
        assertTrue(s.bindDevice(fp, engine) is BindResult.Fresh)
        assertEquals(key + "\n", File(dir(), "key.txt").readText())
        val again = store().bindDevice(fp, engine) as BindResult.Same
        assertEquals(key, again.key)
    }

    @Test
    fun givenNoFingerprint_whenBound_thenNothingIsWrittenAndNoRowIsAccepted() {
        val s = store()
        assertTrue(s.bindDevice(null, engine) is BindResult.Unkeyed)
        assertTrue(s.bindDevice("", engine) is BindResult.Unkeyed)
        assertFalse(dir().exists())
        assertTrue(s.appendRow(row()) is AppendResult.Rejected)
    }

    @Test
    fun givenTheKeyFormula_whenComparedWithTheGeneratorsDeviceKey_thenTheyAreEqual() {
        val staticTree = LinkedHashMap(staticTree())
        val ident = LinkedHashMap(Json.obj(staticTree["identity"], "i")); ident["build_fingerprint"] = fp; staticTree["identity"] = ident
        val d = DescriptorGenerator.generateTree(staticTree, com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.RowLog.parse(listOf(Json.toOrg(row()).toString())), null, null, 0.05, emptyList())
        val leaf = Json.obj(Json.obj(d["run"], "run")["device_key"], "k")
        assertEquals("inferred", leaf["origin"])
        assertEquals(DeviceKey.of(fp, engine), leaf["value"])
    }

    @Test
    fun givenFiles_whenWrittenAndRead_thenTheyRoundTripAndAreAtomic() {
        val s = store()
        s.bindDevice(fp, engine)
        assertTrue(s.writeStatic(staticTree()))
        assertEquals(emptyList<String>(), Json.diff(staticTree(), s.readStatic()))
        assertFalse(File(dir(), "static.json.tmp").exists())
        assertTrue(s.appendRow(row()) is AppendResult.Appended)
        assertEquals(1, s.readRows().lines.size)
    }

    @Test
    fun givenACrashBetweenTheTempFileAndTheRename_whenWritten_thenTheOldFileIsIntactAndNoTempFileRemains() {
        val s = store()
        s.bindDevice(fp, engine)
        assertTrue(s.writeStatic(staticTree()))
        val before = File(dir(), "static.json").readText()
        val failing = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) = FileOps.Real.writeAndSync(file, bytes)
            override fun moveReplacing(from: File, to: File) { throw IOException("crash") }
            override fun delete(file: File) = FileOps.Real.delete(file)
        }
        val s2 = store(failing)
        val changed = LinkedHashMap(staticTree()); changed["plugin_version"] = "9.9.9"
        assertFalse(s2.writeStatic(changed))
        assertEquals(before, File(dir(), "static.json").readText())
        assertFalse(File(dir(), "static.json.tmp").exists())
        assertNull(s.readStatic()!!["plugin_version"])
        // a failure while writing the temp file
        val failing2 = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) { file.writeBytes(bytes.copyOf(bytes.size / 2)); throw IOException("disk full") }
            override fun moveReplacing(from: File, to: File) = FileOps.Real.moveReplacing(from, to)
            override fun delete(file: File) = FileOps.Real.delete(file)
        }
        assertFalse(store(failing2).writeStatic(changed))
        assertEquals(before, File(dir(), "static.json").readText())
        assertFalse(File(dir(), "static.json.tmp").exists())
        // a row append that fails keeps the log
        s.bindDevice(fp, engine); s.appendRow(row())
        val rowsBefore = File(dir(), "rows.jsonl").readText()
        val s3 = store(failing); s3.bindDevice(fp, engine)
        assertTrue(s3.appendRow(row("d5-row-c3-ok.out")) is AppendResult.IoFailed)
        assertEquals(rowsBefore, File(dir(), "rows.jsonl").readText())
    }

    @Test
    fun givenAStaleTempFileAndHalfWrittenFiles_whenRead_thenTheyAreTreatedAsAbsent() {
        val s = store()
        s.bindDevice(fp, engine)
        File(dir(), "static.json.tmp").writeText("{\"format\":\"part1-sta")
        assertNull(s.readStatic())
        assertEquals(FileState.ABSENT, s.inspect()["static.json"])
        File(dir(), "static.json").writeText("{\"format\":\"part1-static-v1\",\"identity\":{") // half written
        assertNull(s.readStatic())
        assertEquals(FileState.CORRUPT, s.inspect()["static.json"])
        File(dir(), "static.json").writeText("""{"format":"something-else"}""")
        assertNull(s.readStatic())
        File(dir(), "static.json").writeText("[1,2]")
        assertNull(s.readStatic())
        File(dir(), "dbrecord.json").writeText("not json")
        assertNull(s.readDbRecord()); assertEquals(FileState.CORRUPT, s.inspect()["dbrecord.json"])
        File(dir(), "device-descriptor-v0.2.json").writeText("""{"schema_version":"0.1"}""")
        assertNull(s.readDescriptor())
        // a rewrite replaces the corrupt file
        assertTrue(s.writeStatic(staticTree()))
        assertEquals(FileState.OK, s.inspect()["static.json"])
    }

    @Test
    fun givenARowLogWithAHalfWrittenLastLine_whenRead_thenOnlyTheValidRowsAreKeptAndTheLineIsCounted() {
        val s = store()
        s.bindDevice(fp, engine)
        s.appendRow(row()); s.appendRow(row("d5-row-c3-ok.out"))
        File(dir(), "rows.jsonl").appendText("{\"format\":\"part1-rows-v1\",\"tag\":\"C4-rea")
        val r = s.readRows()
        assertEquals(2, r.lines.size)
        assertEquals(1, r.skipped)
        assertEquals(FileState.CORRUPT, s.inspect()["rows.jsonl"])
        // the next append rewrites the log without the broken line
        s.appendRow(row("d5-row-c4-ok.out"))
        assertEquals(3, s.readRows().lines.size); assertEquals(0, s.readRows().skipped)
        // blank lines and comments are ignored, a wrong row is dropped
        File(dir(), "rows.jsonl").writeText("# note\n\n" + Json.toOrg(row()) + "\n" + """{"format":"part1-rows-v1"}""" + "\n")
        assertEquals(1, s.readRows().lines.size); assertEquals(1, s.readRows().skipped)
    }

    @Test
    fun givenRowsOfAnotherEngineOrNotRows_whenAppended_thenTheyAreRejected() {
        val s = store()
        s.bindDevice(fp, engine)
        val other = LinkedHashMap(row()); other["engine_version"] = "part1-engine/0.2.3"
        val r = s.appendRow(other) as AppendResult.Rejected
        assertTrue(r.reason.contains("another engine"))
        assertTrue(s.appendRow(mapOf("format" to "part1-rows-v1")) is AppendResult.Rejected)
        assertEquals(0, s.readRows().lines.size)
        assertFalse(File(dir(), "rows.jsonl").exists())
    }

    @Test
    fun givenAChangedFingerprint_whenBound_thenTheOldFilesAreArchivedByTheFirst12HexOfTheOldKeyAndTheStoreIsFresh() {
        val s = store()
        s.bindDevice(fp, engine)
        s.writeStatic(staticTree()); s.appendRow(row()); s.writeDbRecord(mapOf("format" to "part1-dbrecord-v1")); s.writeDescriptor(mapOf("schema_version" to "0.2"))
        val oldKey = DeviceKey.of(fp, engine)
        val r = store().bindDevice(fp + "-ota", engine) as BindResult.Changed
        assertEquals(oldKey, r.oldKey)
        val arch = File(dir(), "old/${oldKey.take(12)}")
        assertEquals(arch, r.archive)
        assertEquals(setOf("static.json", "rows.jsonl", "dbrecord.json", "device-descriptor-v0.2.json", "key.txt"), arch.list()!!.toSet())
        assertEquals(oldKey + "\n", File(arch, "key.txt").readText())
        val live = dir().list()!!.toSet()
        assertEquals(setOf("old", "key.txt"), live) // nothing else is left
        assertEquals(DeviceKey.of(fp + "-ota", engine) + "\n", File(dir(), "key.txt").readText())
        val fresh = store(); fresh.bindDevice(fp + "-ota", engine)
        assertNull(fresh.readStatic()); assertEquals(0, fresh.readRows().lines.size)
    }

    @Test
    fun givenAnEngineChange_whenBound_thenTheKeyChangesToo() {
        val s = store(); s.bindDevice(fp, engine); s.writeStatic(staticTree())
        val r = store().bindDevice(fp, "part1-engine/0.2.5")
        assertTrue(r is BindResult.Changed)
        assertTrue(File(dir(), "old/${DeviceKey.of(fp, engine).take(12)}/static.json").isFile)
    }

    @Test
    fun givenDataWithoutAUsableKey_whenBound_thenItIsArchivedAsUnkeyed() {
        val s = store(); s.bindDevice(fp, engine); s.writeStatic(staticTree())
        File(dir(), "key.txt").writeText("not a key\n")
        val r = store().bindDevice(fp, engine) as BindResult.Changed
        assertEquals(File(dir(), "old/unkeyed"), r.archive)
        assertTrue(File(dir(), "old/unkeyed/static.json").isFile)
        // data without any key file
        File(dir(), "key.txt").delete()
        store().writeStatic(staticTree())
        assertTrue(store().bindDevice(fp, engine) is BindResult.Changed)
    }

    @Test
    fun givenManyKeyChanges_whenBound_thenOnlyTheNewestArchivesAreKept() {
        for (i in 1..5) {
            val s = store(archives = 2)
            s.bindDevice("$fp-$i", engine)
            s.writeStatic(staticTree())
            File(dir(), "old/${DeviceKey.of("$fp-${i - 1}", engine).take(12)}").setLastModified(1000L * i) // the archive made by this bind
        }
        store(archives = 2).bindDevice("$fp-6", engine)
        val kept = File(dir(), "old").list()!!.toSet()
        assertEquals(2, kept.size)
        assertEquals(setOf(DeviceKey.of("$fp-4", engine).take(12), DeviceKey.of("$fp-5", engine).take(12)), kept)
    }

    @Test
    fun givenAMoveThatFails_whenBound_thenItIsAnIoFailureAndTheNextBindRepeatsIt() {
        val s = store(); s.bindDevice(fp, engine); s.writeStatic(staticTree()); s.appendRow(row())
        var failOnce = true
        val flaky = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) = FileOps.Real.writeAndSync(file, bytes)
            override fun moveReplacing(from: File, to: File) { if (failOnce && from.name == "rows.jsonl") { failOnce = false; throw IOException("busy") }; FileOps.Real.moveReplacing(from, to) }
            override fun delete(file: File) = FileOps.Real.delete(file)
        }
        assertTrue(store(flaky).bindDevice("$fp-new", engine) is BindResult.IoFailed)
        assertTrue(File(dir(), "key.txt").isFile) // the key moves last: the store still names the old device
        assertTrue(store(flaky).bindDevice("$fp-new", engine) is BindResult.Changed)
        assertTrue(File(dir(), "old/${DeviceKey.of(fp, engine).take(12)}/rows.jsonl").isFile)
        assertTrue(File(dir(), "old/${DeviceKey.of(fp, engine).take(12)}/static.json").isFile)
        assertFalse(File(dir(), "rows.jsonl").exists())
    }

    @Test
    fun givenTheSizeCap_whenRowsAreAppended_thenTheOldestRowsAreRotatedOutAndTheNewestStays() {
        val one = Json.toOrg(row()).toString().toByteArray().size + 1
        val key = 65 // key.txt counts against the cap
        val s = store(cap = (key + one * 3 + 10).toLong())
        s.bindDevice(fp, engine)
        val tags = listOf("C2-read-k0", "C2-read-k1", "C2-read-k2", "C2-read-k3", "C2-read-k4")
        var rotated = 0
        for (t in tags) rotated += (s.appendRow(row(tag = t)) as AppendResult.Appended).rotatedOut
        val kept = s.readRows().lines.map { Json.parseObject(it)["tag"] }
        assertEquals(listOf("C2-read-k2", "C2-read-k3", "C2-read-k4"), kept)
        assertEquals(2, rotated)
        assertTrue(File(dir(), "rows.jsonl").length() <= one * 3 + 10)
        // the newest row stays even when it alone exceeds the cap
        val tiny = Part1Store(File(tmp.root, "tiny"), FileOps.Real, 10, 3); tiny.bindDevice(fp, engine)
        assertEquals(0, (tiny.appendRow(row(tag = "C2-read-k0")) as AppendResult.Appended).rotatedOut)
        assertEquals(1, (tiny.appendRow(row(tag = "C2-read-k1")) as AppendResult.Appended).rotatedOut)
        assertEquals(listOf("C2-read-k1"), tiny.readRows().lines.map { Json.parseObject(it)["tag"] })
        // other files count against the cap
        val d2 = File(tmp.root, "second"); val s2 = Part1Store(d2, FileOps.Real, (key + one * 3 + 10).toLong(), 3); s2.bindDevice(fp, engine)
        File(d2, "static.json").writeText("x".repeat(one * 2))
        for (t in tags) s2.appendRow(row(tag = t))
        assertEquals(1, s2.readRows().lines.size)
        // a file larger than the whole cap is not written
        assertFalse(store(cap = 100).writeStatic(staticTree()))
    }

    @Test
    fun givenStoredInputs_whenRegenerated_thenTheDescriptorEqualsWhatTheGeneratorGivesAndTheFilesAreWritten() {
        val s = store(); s.bindDevice(fp, engine)
        val st = Part1TestResources.obj("part1/golden/" + Part1TestResources.goldenCaseNames().first { it.endsWith("t1-three-clusters-exact") } + "/static.json")
        assertTrue(s.writeStatic(st))
        val index = Part1TestResources.realIndex()
        val res = s.regenerate(index, "1.0") as RegenerateResult.Regenerated
        assertEquals(FileState.OK, s.inspect()["dbrecord.json"]); assertEquals(FileState.OK, s.inspect()["device-descriptor-v0.2.json"])
        assertEquals(emptyList<String>(), Json.diff(res.descriptor, s.readDescriptor()))
        assertTrue(res.descriptor.values.all { it !is Int })
        assertEquals("1.0", Json.obj(Json.obj(res.descriptor["run"], "r")["plugin_version"], "p")["value"])
        assertEquals(listOf("static.json", "dbrecord.json"), (Json.obj(res.descriptor["meta"], "m")["generated_from"] as List<*>))
        assertEquals(Part1Store.MATCH_SOURCE, s.readDbRecord()!!["match_source"])
        val rec = s.readDbRecord()!!
        assertEquals(index.sha256, Json.obj(rec["index"], "i")["index_sha256"])
    }

    @Test
    fun givenNoOrBrokenInputs_whenRegenerated_thenItFailsWithAReasonAndWritesNothing() {
        val s = store(); s.bindDevice(fp, engine)
        val index = Part1TestResources.realIndex()
        assertEquals("static.json is absent or corrupt", (s.regenerate(index) as RegenerateResult.Failed).reason)
        File(dir(), "static.json").writeText("{")
        assertTrue(s.regenerate(index) is RegenerateResult.Failed)
        assertFalse(File(dir(), "device-descriptor-v0.2.json").exists())
        // an input that does not match its format
        val bad = LinkedHashMap(staticTree()); bad["cpus"] = "not a list"
        s.writeStatic(bad)
        val r = s.regenerate(index) as RegenerateResult.Failed
        assertTrue(r.reason.startsWith("an input is not valid: "))
        assertFalse(File(dir(), "device-descriptor-v0.2.json").exists())
    }
}
