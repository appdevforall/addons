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
        File(dir(), "static.json").writeText(Json.toOrg(staticTree()).toString() + "\n") // an unbound store writes nothing
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
        assertEquals(DeviceKey.of(fp, engine) + "\n", File(dir(), "key.txt").readText()) // the key moves last: the store still names the old device
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
        // a row that alone does not fit (F13: the cap is enforced on every write) is rejected, and the log stays as it was
        val tiny = Part1Store(File(tmp.root, "tiny"), FileOps.Real, key + one.toLong() - 1, 3); tiny.bindDevice(fp, engine)
        assertTrue(tiny.appendRow(row(tag = "C2-read-k0")) is AppendResult.Rejected)
        assertFalse(File(tmp.root, "tiny/rows.jsonl").exists())
        val exact = Part1Store(File(tmp.root, "exact"), FileOps.Real, (key + one).toLong(), 3); exact.bindDevice(fp, engine)
        assertEquals(0, (exact.appendRow(row(tag = "C2-read-k0")) as AppendResult.Appended).rotatedOut)
        assertEquals(1, (exact.appendRow(row(tag = "C2-read-k1")) as AppendResult.Appended).rotatedOut)
        assertEquals(listOf("C2-read-k1"), exact.readRows().lines.map { Json.parseObject(it)["tag"] })
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

    // ---- P1-04A1F: F01, F03, F04, F13, F16 ------------------------------------------------------------------

    private fun keyOf(f: String) = DeviceKey.of(f, engine) + "\n"

    private fun filled(): Part1Store {
        val s = store(); s.bindDevice(fp, engine)
        s.writeStatic(staticTree()); s.appendRow(row()); s.writeDbRecord(mapOf("format" to "part1-dbrecord-v1")); s.writeDescriptor(mapOf("schema_version" to "0.2"))
        s.writeProbeFacts(mapOf("opencl_child" to listOf<Any>()), 1L)
        return s
    }

    @Test
    fun givenARegularFileNamedOld_whenTheKeyChanges_thenTheOldFilesAreDeletedNotAdoptedAndTheOutcomeIsDiscarded() {
        // F01: the review's reproduction
        filled()
        File(dir(), "old").writeText("in the way")
        val b = store()
        val r = b.bindDevice("$fp-B", engine) as BindResult.Discarded
        assertEquals(DeviceKey.of(fp, engine), r.oldKey)
        assertEquals(keyOf("$fp-B"), File(dir(), "key.txt").readText())
        assertEquals(setOf("old", "key.txt"), dir().list()!!.toSet()) // no live file of device A
        val again = store(); assertTrue(again.bindDevice("$fp-B", engine) is BindResult.Same)
        assertNull(again.readStatic()); assertEquals(0, again.readRows().lines.size); assertNull(again.readProbeFacts())
        assertNull(b.readStatic()); assertEquals(0, b.readRows().lines.size)
        assertTrue(b.appendRow(row()) is AppendResult.Appended) // the new device can go on
    }

    private class FullDisk(val failMoves: Boolean = true, val failWrites: Boolean = true) : FileOps {
        override fun writeAndSync(file: File, bytes: ByteArray) { if (failWrites) { file.writeBytes(bytes.copyOf(bytes.size / 2)); throw IOException("No space left on device") } else FileOps.Real.writeAndSync(file, bytes) }
        override fun moveReplacing(from: File, to: File) { if (failMoves) throw IOException("No space left on device") else FileOps.Real.moveReplacing(from, to) }
        override fun delete(file: File) = FileOps.Real.delete(file)
    }

    @Test
    fun givenAFullDisk_whenTheKeyChanges_thenTheNewKeyIsNotWrittenNoReadReturnsTheOldDataAndNoRowIsAccepted() {
        // F01 and F03: all or nothing
        filled()
        val b = store(FullDisk())
        assertTrue(b.bindDevice("$fp-B", engine) is BindResult.IoFailed)
        assertEquals(keyOf(fp), File(dir(), "key.txt").readText()) // the new key was not written
        assertNull(b.readStatic()); assertNull(b.readDbRecord()); assertNull(b.readDescriptor()); assertNull(b.readProbeFacts())
        assertEquals(0, b.readRows().lines.size)
        assertEquals(FileState.ABSENT, b.inspect()["static.json"])
        // F03: no row until a bind succeeds
        val rowsBefore = File(dir(), "rows.jsonl").readText()
        assertTrue(b.appendRow(row()) is AppendResult.Rejected)
        assertFalse(b.writeStatic(staticTree()))
        assertEquals(rowsBefore, File(dir(), "rows.jsonl").readText())
        // a store bound to the new key on the same files also fails and also returns nothing
        val c = store(FullDisk()); assertTrue(c.bindDevice("$fp-B", engine) is BindResult.IoFailed)
        assertNull(c.readStatic()); assertTrue(c.appendRow(row()) is AppendResult.Rejected)
        // when the disk works again the bind succeeds by archiving, and the row goes to the new device
        val d = store(); assertTrue(d.bindDevice("$fp-B", engine) is BindResult.Changed)
        assertTrue(d.appendRow(row()) is AppendResult.Appended)
        assertEquals(1, d.readRows().lines.size)
        assertEquals(1, File(dir(), "old/${DeviceKey.of(fp, engine).take(12)}/rows.jsonl").readLines().size) // not the new device's row
    }

    @Test
    fun givenABindThatFailedHalfWay_whenARowIsAppendedBeforeAnySuccessfulBind_thenItIsRejected() {
        // F03: the review's scenario IoFailed, Appended, Changed
        filled()
        var failOnce = true
        val flaky = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) = FileOps.Real.writeAndSync(file, bytes)
            override fun moveReplacing(from: File, to: File) { if (failOnce && from.name == "rows.jsonl") { failOnce = false; throw IOException("busy") }; FileOps.Real.moveReplacing(from, to) }
            override fun delete(file: File) = FileOps.Real.delete(file)
        }
        val b = store(flaky)
        assertTrue(b.bindDevice("$fp-B", engine) is BindResult.IoFailed)
        assertTrue(b.appendRow(row()) is AppendResult.Rejected)
        assertFalse(b.writeStatic(staticTree())) // no file is written either, until a bind succeeds
        assertFalse(b.writeProbeFacts(mapOf("a" to 1L), 1L))
        assertFalse(File(dir(), "static.json").exists())
        assertTrue(b.bindDevice("$fp-B", engine) is BindResult.Changed)
        assertEquals(0, b.readRows().lines.size)
        assertEquals(1, File(dir(), "old/${DeviceKey.of(fp, engine).take(12)}/rows.jsonl").readLines().size)
    }

    @Test
    fun givenALeftOverTempFile_whenTheStoreStartsOrTheKeyChanges_thenItIsRemovedAndNeverSurvivesAKeyChange() {
        // F04: the review's scenario
        filled()
        File(dir(), "static.json.tmp").writeText("""{"format":"part1-static-v1","identity":{"build_fingerprint":"device A"}}""")
        File(dir(), "rows.jsonl.tmp").writeText("half")
        // start-up on the same key: removed
        assertTrue(store().bindDevice(fp, engine) is BindResult.Same)
        assertFalse(File(dir(), "static.json.tmp").exists()); assertFalse(File(dir(), "rows.jsonl.tmp").exists())
        // key change with a temporary file present: it does not survive, and is not archived
        File(dir(), "static.json.tmp").writeText("device A")
        assertTrue(store().bindDevice("$fp-B", engine) is BindResult.Changed)
        assertEquals(setOf("old", "key.txt"), dir().list()!!.toSet())
        assertFalse(File(dir(), "old/${DeviceKey.of(fp, engine).take(12)}/static.json.tmp").exists())
        // a temporary file that cannot be removed makes the key change fail
        File(dir(), "static.json.tmp").writeText("device B")
        val stubborn = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) = FileOps.Real.writeAndSync(file, bytes)
            override fun moveReplacing(from: File, to: File) = FileOps.Real.moveReplacing(from, to)
            override fun delete(file: File) = false
        }
        assertTrue(store(stubborn).bindDevice("$fp-C", engine) is BindResult.IoFailed)
        assertEquals(keyOf("$fp-B"), File(dir(), "key.txt").readText())
        // an unkeyed or fresh start also cleans
        File(dir(), "static.json.tmp").delete()
        File(tmp.root, "fresh").mkdirs(); File(tmp.root, "fresh/dbrecord.json.tmp").writeText("x")
        assertTrue(Part1Store(File(tmp.root, "fresh")).bindDevice(fp, engine) is BindResult.Fresh)
        assertFalse(File(tmp.root, "fresh/dbrecord.json.tmp").exists())
    }

    @Test
    fun givenFilesAndArchivesAndTempFiles_whenAnyFileIsWritten_thenTheTotalNeverExceedsTheCap() {
        // F13: enforced on every write; counts live files, archives and temporary files
        val st = staticTree()
        val sz = (Json.toOrg(st).toString() + "\n").toByteArray().size.toLong()
        val key = 65L
        val cap = key + sz * 2 + 50
        val d = File(tmp.root, "capped")
        fun total() = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val s = Part1Store(d, FileOps.Real, cap, 3)
        s.bindDevice(fp, engine)
        assertTrue(s.writeStatic(st)); assertTrue(total() <= cap)
        // a second file of the same size still fits; a third does not
        assertTrue(s.writeDbRecordSized(sz)); assertTrue(total() <= cap)
        assertFalse(s.writeDescriptor(mapOf("schema_version" to "0.2", "pad" to "x".repeat(sz.toInt()))))
        assertTrue(total() <= cap)
        // a temporary file counts: with one present (a crash right now) the write is refused
        File(d, "x.tmp").writeText("t".repeat(sz.toInt()))
        assertFalse(s.writeStatic(st.toMutableMap().also { it["plugin_version"] = "p" })) // old file replaced, but the temp file leaves no room
        assertTrue(total() <= cap + sz)
        File(d, "x.tmp").delete()
        // an archive counts, and the oldest archive is deleted to make room
        val s2 = Part1Store(File(tmp.root, "capped2"), FileOps.Real, cap, 3)
        s2.bindDevice(fp, engine); assertTrue(s2.writeStatic(st))
        s2.bindDevice("$fp-B", engine) // archives device A's static.json (sz bytes)
        assertTrue(File(tmp.root, "capped2/old/${DeviceKey.of(fp, engine).take(12)}/static.json").isFile)
        assertTrue(s2.writeStatic(st)); assertTrue(s2.writeDbRecordSized(sz)) // 65 + 2 sz + archive sz > cap: the archive goes
        assertFalse(File(tmp.root, "capped2/old/${DeviceKey.of(fp, engine).take(12)}").exists())
        assertTrue(File(tmp.root, "capped2").walkTopDown().filter { it.isFile }.sumOf { it.length() } <= cap)
        // an archive makes a row rotate nothing out before the archive is gone
        // a probe-facts write is capped as well
        val s3 = Part1Store(File(tmp.root, "capped3"), FileOps.Real, 100, 3); s3.bindDevice(fp, engine)
        assertFalse(s3.writeProbeFacts(mapOf("pad" to "x".repeat(200)), 1L))
    }

    private fun Part1Store.writeDbRecordSized(size: Long): Boolean {
        val base = (Json.toOrg(mapOf("format" to "part1-dbrecord-v1", "pad" to "")).toString() + "\n").toByteArray().size
        return writeDbRecord(mapOf("format" to "part1-dbrecord-v1", "pad" to "x".repeat((size - base).toInt())))
    }

    @Test
    fun givenProbeFacts_whenWrittenAndTheKeyChanges_thenTheyAreAtomicReadBackAndArchivedWithTheKey() {
        // F16
        val s = store()
        assertFalse(s.writeProbeFacts(mapOf("a" to 1L), 5L)) // not bound
        s.bindDevice(fp, engine)
        val facts = linkedMapOf<String, Any?>("opencl_child" to listOf(linkedMapOf("name" to "libOpenCL.so", "load" to "not_loaded", "outcome" to "dlopen_failed")), "public_libraries_names_opencl" to true)
        assertTrue(s.writeProbeFacts(facts, 1_700_000_000_123L))
        assertFalse(File(dir(), "probe-facts.json.tmp").exists())
        val back = s.readProbeFacts()!!
        assertEquals("part1-probe-facts-v1", back["format"])
        assertEquals(engine, back["engine_version"])
        assertEquals(1_700_000_000_123L, back["time_epoch_ms"])
        assertEquals(emptyList<String>(), Json.diff(facts, back["facts"]))
        File(dir(), "probe-facts.json").writeText("{")
        assertNull(s.readProbeFacts())
        assertTrue(s.writeProbeFacts(facts, 2L))
        val r = store().bindDevice("$fp-B", engine) as BindResult.Changed
        assertTrue(File(r.archive, "probe-facts.json").isFile)
        assertFalse(File(dir(), "probe-facts.json").exists())
    }

    @Test
    fun givenAStoreBoundBefore_whenALaterBindFails_thenItAcceptsNoRowUntilABindSucceeds() {
        // F03 on one instance: the engine id of the earlier bind must not stay valid
        var failMoves = false
        val ops = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) = FileOps.Real.writeAndSync(file, bytes)
            override fun moveReplacing(from: File, to: File) { if (failMoves && from.name != "key.txt.tmp") throw IOException("busy"); FileOps.Real.moveReplacing(from, to) }
            override fun delete(file: File) = FileOps.Real.delete(file)
        }
        val s = store(ops)
        assertTrue(s.bindDevice(fp, engine) is BindResult.Fresh)
        assertTrue(s.appendRow(row()) is AppendResult.Appended)
        failMoves = true
        assertTrue(s.bindDevice("$fp-B", engine) is BindResult.IoFailed)
        assertTrue(s.appendRow(row()) is AppendResult.Rejected)
        assertEquals(0, s.readRows().lines.size)
        failMoves = false
        assertTrue(s.bindDevice("$fp-B", engine) is BindResult.Changed)
        assertTrue(s.appendRow(row()) is AppendResult.Appended)
    }

    @Test
    fun givenTheCapIsReachedExactly_whenAFileIsWritten_thenItIsStoredAndOneByteMoreIsNot() {
        val st = staticTree()
        val sz = (Json.toOrg(st).toString() + "\n").toByteArray().size.toLong()
        val d = File(tmp.root, "exactcap")
        val s = Part1Store(d, FileOps.Real, 65L + 2 * sz, 3)
        s.bindDevice(fp, engine)
        assertTrue(s.writeStatic(st))
        assertTrue(s.writeDbRecordSized(sz)) // total == cap
        assertFalse(s.writeDbRecordSized(sz + 1)) // total == cap + 1
        // a stale temporary file of the very file being replaced does not count (it is overwritten)
        File(d, "static.json.tmp").writeText("t".repeat(sz.toInt()))
        assertTrue(s.writeStatic(st))
        assertFalse(File(d, "static.json.tmp").exists())
    }

    @Test
    fun givenAnArchiveInTheWay_whenARowIsAppended_thenTheArchiveIsDeletedBeforeAnyRowIsRotatedOut() {
        val one = Json.toOrg(row()).toString().toByteArray().size + 1
        val d = File(tmp.root, "arch-first")
        val s = Part1Store(d, FileOps.Real, (65 + one * 2 + 10).toLong(), 3)
        s.bindDevice(fp, engine)
        assertEquals(0, (s.appendRow(row(tag = "C2-read-k0")) as AppendResult.Appended).rotatedOut)
        s.bindDevice("$fp-B", engine) // archives device A's row log (one row)
        val archive = File(d, "old/${DeviceKey.of(fp, engine).take(12)}")
        assertTrue(archive.isDirectory)
        assertEquals(0, (s.appendRow(row(tag = "C2-read-k1")) as AppendResult.Appended).rotatedOut)
        assertEquals(0, (s.appendRow(row(tag = "C2-read-k2")) as AppendResult.Appended).rotatedOut) // the archive made room
        assertFalse(archive.exists())
        assertEquals(2, s.readRows().lines.size)
    }

    // ---- P1-04A2 step 0: F03 F04 F05 F06 F10 F11 ------------------------------------------------------------

    @Test
    fun givenAWriteThatCanNeverFit_whenRefused_thenNoArchiveIsDeleted() {
        // F04: a 2 MiB descriptor and a 2 MiB row with one archive present
        val d = File(tmp.root, "never")
        val s = Part1Store(d, FileOps.Real, 4096, 3)
        s.bindDevice(fp, engine); s.appendRow(row())
        s.bindDevice("$fp-B", engine)
        val archive = File(d, "old/${DeviceKey.of(fp, engine).take(12)}")
        assertTrue(archive.isDirectory)
        assertFalse(s.writeDescriptor(mapOf("schema_version" to "0.2", "pad" to "x".repeat(2 * 1024 * 1024))))
        assertTrue(archive.isDirectory)
        val big = LinkedHashMap(row()); big["pad"] = "x".repeat(8192)
        assertTrue(s.appendRow(big) is AppendResult.Rejected)
        assertTrue(archive.isDirectory)
        assertTrue(File(archive, "rows.jsonl").isFile)
    }

    @Test
    fun givenAFreshInstanceAndAFailedKeyChange_whenReadOrWritten_thenNothingIsReturnedUntilABindSucceedsAndTheMarkerSurvivesARestart() {
        // F10
        filled()
        val unbound = store()
        assertNull(unbound.readStatic()); assertNull(unbound.readDbRecord()); assertNull(unbound.readDescriptor()); assertNull(unbound.readProbeFacts())
        assertEquals(0, unbound.readRows().lines.size)
        assertEquals(FileState.ABSENT, unbound.inspect()["static.json"])
        assertFalse(unbound.writeStatic(staticTree())); assertFalse(unbound.writeDbRecord(mapOf("format" to "part1-dbrecord-v1")))
        assertFalse(unbound.writeDescriptor(mapOf("schema_version" to "0.2")))
        assertTrue(unbound.appendRow(row()) is AppendResult.Rejected)
        assertTrue(unbound.regenerate(Part1TestResources.realIndex()) is RegenerateResult.Failed)
        // a failed key change leaves a marker; a new instance (a restart) is blocked too, and the old data is not returned
        val movesFail = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) = FileOps.Real.writeAndSync(file, bytes)
            override fun moveReplacing(from: File, to: File) { if (from.name != "bind-failed.marker.tmp") throw IOException("busy"); FileOps.Real.moveReplacing(from, to) }
            override fun delete(file: File) = FileOps.Real.delete(file)
        }
        assertTrue(store(movesFail).bindDevice("$fp-B", engine) is BindResult.IoFailed)
        assertTrue(File(dir(), Part1Store.MARKER).isFile)
        val restarted = store()
        assertNull(restarted.readStatic()); assertEquals(0, restarted.readRows().lines.size)
        assertFalse(restarted.writeStatic(staticTree()))
        // an unkeyed bind does not unblock
        assertTrue(restarted.bindDevice(null, engine) is BindResult.Unkeyed)
        assertNull(restarted.readStatic())
        // a successful bind clears the marker
        assertTrue(restarted.bindDevice("$fp-B", engine) is BindResult.Changed)
        assertFalse(File(dir(), Part1Store.MARKER).exists())
        assertNull(restarted.readStatic())
        assertTrue(restarted.writeStatic(staticTree()))
    }

    @Test
    fun givenALiveFileThatCannotBeDeleted_whenTheDiscardPathRuns_thenTheKeyChangeFailsAndTheKeyStaysTheOld() {
        // F05 (mutant V04): the archive cannot be created, then a live file cannot be deleted
        filled()
        File(dir(), "old").writeText("in the way")
        val stubborn = object : FileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) = FileOps.Real.writeAndSync(file, bytes)
            override fun moveReplacing(from: File, to: File) = FileOps.Real.moveReplacing(from, to)
            override fun delete(file: File) = if (file.name == "rows.jsonl") false else FileOps.Real.delete(file)
        }
        val b = store(stubborn)
        assertTrue(b.bindDevice("$fp-B", engine) is BindResult.IoFailed)
        assertEquals(keyOf(fp), File(dir(), "key.txt").readText())
        assertTrue(File(dir(), "rows.jsonl").isFile)
        assertNull(b.readStatic())
    }

    @Test
    fun givenThreeArchivesAndACap_whenAWriteNeedsRoom_thenTheOldestArchiveGoesFirst() {
        // F06 (mutant V05)
        val st = staticTree()
        val sz = (Json.toOrg(st).toString() + "\n").toByteArray().size.toLong()
        val d = File(tmp.root, "three")
        val s = Part1Store(d, FileOps.Real, 65L * 4 + sz * 4 + 10, 3)
        val keys = listOf("$fp-1", "$fp-2", "$fp-3", "$fp-4")
        s.bindDevice(keys[0], engine); s.writeStatic(st)
        for (i in 1..3) { s.bindDevice(keys[i], engine); s.writeStatic(st) }
        val ids = keys.take(3).map { File(d, "old/${DeviceKey.of(it, engine).take(12)}") }
        assertTrue(ids.all { it.isDirectory })
        // oldest first by modification time, set explicitly (the file system's clock is too coarse)
        ids[0].setLastModified(1_000_000_000_000L); ids[1].setLastModified(1_100_000_000_000L); ids[2].setLastModified(1_200_000_000_000L)
        assertTrue(s.writeDbRecordSized(sz * 2)) // needs room for roughly two archives
        assertFalse("the oldest archive goes first", ids[0].exists())
        assertTrue("the newest archive stays while room is made", ids[2].exists() || !ids[1].exists())
        assertFalse(ids[1].exists() && !ids[2].exists())
    }

    @Test
    fun givenThreeKeyChangesWithNoWrite_whenTheDiskUseIsCounted_thenItIsWithinCapPlus65PerArchive() {
        // F03: the stated bound
        val st = staticTree()
        val sz = (Json.toOrg(st).toString() + "\n").toByteArray().size.toLong()
        val d = File(tmp.root, "bound")
        val cap = 65L + sz
        val s = Part1Store(d, FileOps.Real, cap, 3)
        s.bindDevice(fp, engine); s.writeStatic(st)
        for (i in 1..3) s.bindDevice("$fp-$i", engine)
        val total = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("$total", total <= cap + 65L * 3)
    }

    @Test
    fun givenProbeFactsOfAnotherFormatAndAStaleKeyTemp_whenReadOrBound_thenTheFormerReadsAsAbsentAndTheLatterIsRemoved() {
        // F11 (mutants V10, V12)
        val s = store(); s.bindDevice(fp, engine)
        File(dir(), "probe-facts.json").writeText("""{"format":"something-else","facts":{}}""")
        assertNull(s.readProbeFacts())
        File(dir(), "probe-facts.json").writeText("""{"format":"part1-probe-facts-v1","facts":{}}""")
        assertTrue(s.readProbeFacts() != null)
        File(dir(), "key.txt.tmp").writeText("stale")
        File(dir(), "other.tmp").writeText("stale")
        assertTrue(store().bindDevice(fp, engine) is BindResult.Same)
        assertFalse(File(dir(), "key.txt.tmp").exists()); assertFalse(File(dir(), "other.tmp").exists())
    }

    @Test
    fun givenProbeFactsWithTheCandidatesAsTheProbeGaveThem_whenStoredAndRead_thenNothingIsReduced() {
        // F08
        val s = store(); s.bindDevice(fp, engine)
        val cand = listOf(
            linkedMapOf<String, Any?>("name" to "libOpenCL.so", "loaded" to false, "outcome" to "crashed", "error" to "dlopen failed", "signal" to 11L),
            linkedMapOf<String, Any?>("loaded" to false, "outcome" to "not_tried"), // unnamed
        )
        assertTrue(s.writeProbeFacts(linkedMapOf("opencl_child" to cand), 1L))
        val back = Json.obj(s.readProbeFacts()!!["facts"], "facts")
        assertEquals(emptyList<String>(), Json.diff(cand, back["opencl_child"]))
    }
}
