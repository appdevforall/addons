package com.itsaky.androidide.plugins.aiagentlocal.device.store

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.DescriptorGenerator
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.Row
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.RowLog
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DbRecordBuilder
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndex
import java.io.File
import java.io.IOException

/** State of one stored file: not there, readable and well formed, or present but not usable (treated as absent). */
enum class FileState { ABSENT, OK, CORRUPT }

sealed class BindResult {
    /** No key was stored; this device and engine are now the owners. */
    class Fresh(val key: String) : BindResult()

    /** The stored key is this key. */
    class Same(val key: String) : BindResult()

    /** The key changed: the old files were moved to [archive] (`old/<first 12 hex of the old key>`) and the store starts fresh. */
    class Changed(val oldKey: String, val archive: File?) : BindResult()

    /**
     * The key changed and the old files could not be archived because `old/<id>` cannot be created. Data of another key must
     * never stay live, so the old live files were **deleted** and the store starts fresh under the new key. This is its own
     * outcome: the old device's data is gone, not archived.
     */
    class Discarded(val oldKey: String) : BindResult()

    /** No fingerprint is known, so no key can be made; nothing was changed. */
    object Unkeyed : BindResult()

    /**
     * A file operation failed and the key change did **not** happen: the new key is not written, `key.txt` still names the old
     * key (an interrupted archive is repeated by the next bind), and until a bind succeeds this store returns no stored data
     * and accepts no row.
     */
    class IoFailed(val reason: String) : BindResult()
}

sealed class AppendResult {
    /** The row was stored; [rotatedOut] older rows were removed to stay under the size cap. */
    class Appended(val rotatedOut: Int) : AppendResult()
    class Rejected(val reason: String) : AppendResult()
    class IoFailed(val reason: String) : AppendResult()
}

sealed class RegenerateResult {
    class Regenerated(val descriptor: Map<String, Any?>) : RegenerateResult()
    class Failed(val reason: String) : RegenerateResult()
}

/** Rows read from the log: the valid lines, and how many lines were dropped as corrupt. */
class StoredRows(val lines: List<String>, val skipped: Int)

/**
 * The plugin's Part 1 files under `<plugin data dir>/part1/`: `static.json`, `rows.jsonl`, `dbrecord.json`,
 * `device-descriptor-v0.2.json`, and `key.txt` (the device key, one line).
 *
 * - **Atomic writes:** every write goes to a temporary file and is renamed over the target ([AtomicWriter]).
 * - **Corruption:** a file that does not parse, or whose `format` is wrong, reads as absent. In `rows.jsonl` a line that is
 *   not a valid `part1-rows-v1` row is dropped (a half-written last line, say) and counted.
 * - **Size cap:** [maxTotalBytes] is enforced on **every** write ([writeStatic], [writeDbRecord], [writeDescriptor],
 *   [writeProbeFacts], [appendRow]). It counts everything the store keeps on disk: the live files (`static.json`, `rows.jsonl`,
 *   `dbrecord.json`, `device-descriptor-v0.2.json`, `probe-facts.json`, `key.txt`), every archive under `old/` and the temporary
 *   files (`*.tmp`). To make room, the oldest archives are deleted first, then (for the row log) the oldest rows. A write that
 *   cannot fit **even with every archive gone** is refused (a file) or rejected (a row) **before** any archive is deleted.
 *   **Worst-case disk use:** a write is charged for what it adds, a key change is not: it moves the old files into an archive
 *   and writes a new 65-byte `key.txt`, and up to [maxArchives] archives keep their old files. So at rest the store holds at most
 *   `maxTotalBytes + 65 * maxArchives` bytes (the key files of the key changes since the last write), plus the 2-byte
 *   `bind-failed.marker` while a bind has failed. While one atomic write is under way the old file and its temporary copy
 *   coexist, so at most `2 * maxTotalBytes + 65 * maxArchives` (with the default 1 MiB and 3 archives: 2 MiB + 195 bytes).
 * - **Device key:** [bindDevice] compares the key of this device and engine with `key.txt`. A change is **all or nothing**:
 *   it archives every file under `old/<first 12 hex of the old key>/` and starts fresh; at most [maxArchives] archives are
 *   kept. If the files cannot be archived the new key is not written ([BindResult.IoFailed]); if `old/<id>` cannot even be
 *   created the old live files are deleted ([BindResult.Discarded]). Data of another key never stays live under a new key.
 *   **Nothing is read and nothing is written until [bindDevice] has succeeded in this instance** (a fresh instance is unbound,
 *   so a new process cannot see another key's files either). A failed key change also leaves the file `bind-failed.marker`;
 *   a bind that succeeds removes it.
 * - **Temporary files:** `*.tmp` left by a crash are removed by every [bindDevice] (start-up) and are never archived; a key
 *   change that cannot remove them fails.
 *
 * Not thread-safe: use one instance from one worker thread. Blocking I/O: not for the main thread. Logs nothing.
 */
class Part1Store(
    val dir: File,
    private val ops: FileOps = FileOps.Real,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    val maxArchives: Int = 3,
) {
    companion object {
        const val STATIC = "static.json"
        const val ROWS = "rows.jsonl"
        const val DBRECORD = "dbrecord.json"
        const val DESCRIPTOR = "device-descriptor-v0.2.json"
        const val KEY = "key.txt"
        const val PROBE_FACTS = "probe-facts.json"
        const val MARKER = "bind-failed.marker"
        const val PROBE_FACTS_FORMAT = "part1-probe-facts-v1"
        const val PASS = "t1-pass.json"
        const val PASS_FORMAT = "part1-pass-v1"
        const val DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024L
        const val MATCH_SOURCE = "in-app device matcher (DeviceIndexMatcher) over the identity stored in static.json: platform, board, device, hardware"
        private val LIVE = listOf(STATIC, ROWS, DBRECORD, DESCRIPTOR, PROBE_FACTS, PASS, KEY)
    }

    private val writer = AtomicWriter(ops)
    private var engineId: String? = null

    /** Nothing is read or written until a bind has succeeded in this instance (the files may be another key's). */
    private val bound: Boolean get() = engineId != null

    private fun file(name: String) = File(dir, name)

    // ---- device key ---------------------------------------------------------------------------------------------

    /**
     * Binds the store to this device and engine. [fingerprint] is `Build.FINGERPRINT` (never logged here); [engineId] is
     * `part1-engine/<version>`. Rows of another engine are refused by [appendRow].
     */
    fun bindDevice(fingerprint: String?, engineId: String): BindResult {
        this.engineId = null // set only when this bind succeeds
        if (fingerprint.isNullOrEmpty()) return BindResult.Unkeyed
        val key = DeviceKey.of(fingerprint, engineId)
        return try {
            val r = bind(key)
            this.engineId = engineId
            runCatching { ops.delete(file(MARKER)) }
            r
        } catch (e: IOException) {
            runCatching { writer.write(file(MARKER), "1\n".toByteArray()) }
            BindResult.IoFailed("the files could not be moved, deleted or written (${e.javaClass.simpleName})")
        }
    }

    private fun bind(key: String): BindResult {
        val stored = rawText(KEY)?.trim()?.takeIf { DeviceKey.isKey(it) }
        val tempsGone = removeTemps()
        val others = LIVE.filter { it != KEY && file(it).exists() }
        if (stored == key) return BindResult.Same(key)
        if (!tempsGone) throw IOException("a temporary file could not be removed")
        if (stored == null && others.isEmpty()) {
            writeKey(key)
            return BindResult.Fresh(key)
        }
        val oldId = stored?.take(12) ?: "unkeyed"
        val result = when (val a = archive(oldId)) {
            is Archive.Moved -> BindResult.Changed(stored ?: "", a.dir)
            Archive.NothingToMove -> BindResult.Changed(stored ?: "", null)
            Archive.CannotCreate -> {
                deleteLive()
                BindResult.Discarded(stored ?: "")
            }
        }
        writeKey(key)
        prune()
        return result
    }

    private fun writeKey(key: String) = writer.write(file(KEY), (key + "\n").toByteArray())

    /** Deletes every `*.tmp` directly in the store directory; false when one could not be deleted. */
    private fun removeTemps(): Boolean {
        var all = true
        for (f in dir.listFiles { x -> x.isFile && x.name.endsWith(".tmp") }.orEmpty()) {
            if (!ops.delete(f) && f.exists()) all = false
        }
        return all
    }

    /** Deletes every live file (key last); throws when one cannot be deleted, so the old key then stays and the bind fails. */
    private fun deleteLive() {
        for (name in LIVE) {
            val f = file(name)
            if (f.exists() && !ops.delete(f) && f.exists()) throw IOException("cannot delete $name")
        }
    }

    private sealed class Archive {
        class Moved(val dir: File) : Archive()
        object NothingToMove : Archive()
        object CannotCreate : Archive()
    }

    /** Moves every live file into `old/<id>/` (key last, so an interrupted move is repeated by the next bind). A failed move throws. */
    private fun archive(id: String): Archive {
        val target = File(File(dir, "old"), id)
        var moved = false
        for (name in LIVE) { // KEY is last in LIVE
            val f = file(name)
            if (!f.exists()) continue
            if (!ops.makeDirs(target)) return Archive.CannotCreate
            ops.moveReplacing(f, File(target, name))
            moved = true
        }
        return if (moved) Archive.Moved(target) else Archive.NothingToMove
    }

    private fun prune() {
        for (d in archives().dropLast(maxArchives)) d.deleteRecursively()
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    private fun readTextOrNull(name: String): String? = if (bound) rawText(name) else null

    private fun rawText(name: String): String? {
        val f = file(name)
        return try {
            if (f.isFile) String(f.readBytes(), Charsets.UTF_8) else null
        } catch (e: IOException) {
            null
        }
    }

    private fun readObject(name: String, check: (Map<String, Any?>) -> Boolean): Map<String, Any?>? {
        val text = readTextOrNull(name) ?: return null
        return try {
            Json.parseObject(text, name).takeIf(check)
        } catch (e: JsonException) {
            null
        }
    }

    private val staticCheck: (Map<String, Any?>) -> Boolean = { it["format"] == "part1-static-v1" }
    private val dbCheck: (Map<String, Any?>) -> Boolean = { it["format"] == DbRecordBuilder.FORMAT }
    private val descriptorCheck: (Map<String, Any?>) -> Boolean = { it["schema_version"] == DescriptorGenerator.SCHEMA_VERSION }

    fun readStatic(): Map<String, Any?>? = readObject(STATIC, staticCheck)
    fun readDbRecord(): Map<String, Any?>? = readObject(DBRECORD, dbCheck)
    fun readDescriptor(): Map<String, Any?>? = readObject(DESCRIPTOR, descriptorCheck)

    /** The state of the four data files: absent, usable, or corrupt (present but read as absent). */
    fun inspect(): Map<String, FileState> = if (!bound) mapOf(STATIC to FileState.ABSENT, DBRECORD to FileState.ABSENT, DESCRIPTOR to FileState.ABSENT, ROWS to FileState.ABSENT) else mapOf(
        STATIC to state(STATIC) { readStatic() },
        DBRECORD to state(DBRECORD) { readDbRecord() },
        DESCRIPTOR to state(DESCRIPTOR) { readDescriptor() },
        ROWS to when {
            !file(ROWS).exists() -> FileState.ABSENT
            readRows().skipped > 0 -> FileState.CORRUPT
            else -> FileState.OK
        },
    )

    private fun state(name: String, read: () -> Any?): FileState = when {
        !file(name).exists() -> FileState.ABSENT
        read() != null -> FileState.OK
        else -> FileState.CORRUPT
    }

    /** The valid rows of the log in the order they were written; lines that are not valid `part1-rows-v1` rows are skipped. */
    fun readRows(): StoredRows {
        val text = readTextOrNull(ROWS) ?: return StoredRows(emptyList(), 0)
        val good = ArrayList<String>()
        var skipped = 0
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val ok = try {
                Row.parse(Json.parse(t, ROWS)); true
            } catch (e: JsonException) {
                false
            }
            if (ok) good.add(t) else skipped++
        }
        return StoredRows(good, skipped)
    }

    // ---- writes -------------------------------------------------------------------------------------------------

    private fun writeObject(name: String, tree: Map<String, Any?>, check: (Map<String, Any?>) -> Boolean): Boolean {
        if (!bound || !check(tree)) return false
        val bytes = try {
            (Json.toOrg(tree).toString() + "\n").toByteArray(Charsets.UTF_8)
        } catch (e: JsonException) {
            return false
        }
        return writeCapped(name, bytes)
    }

    /** Writes [bytes] as [name] when everything the store keeps, with this file replaced, stays within [maxTotalBytes] (oldest archives are deleted to make room). */
    private fun writeCapped(name: String, bytes: ByteArray): Boolean {
        if (!fits(name, bytes.size.toLong())) return false
        return try {
            writer.write(file(name), bytes)
            true
        } catch (e: IOException) {
            false
        }
    }

    /** True when [needed] bytes can replace [name] within the cap, after deleting as many of the oldest archives as that takes. */
    private fun fits(name: String, needed: Long): Boolean {
        if (usedBytes(name) - archiveBytes() + needed > maxTotalBytes) return false // cannot fit even with every archive gone: delete nothing
        while (usedBytes(name) + needed > maxTotalBytes) {
            if (!deleteOldestArchive()) return false
        }
        return true
    }

    /** Bytes on disk of everything the store keeps (live files, archives, `*.tmp`), not counting [replaced] and its temporary file. */
    private fun usedBytes(replaced: String?): Long {
        var sum = 0L
        for (f in dir.listFiles().orEmpty()) {
            if (f.isFile && f.name != replaced && f.name != "$replaced.tmp") sum += f.length()
        }
        for (a in archives()) sum += treeBytes(a)
        return sum
    }

    private fun archiveBytes(): Long = archives().sumOf { treeBytes(it) }

    private fun treeBytes(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun archives(): List<File> =
        File(dir, "old").listFiles { f -> f.isDirectory }?.sortedWith(compareBy({ it.lastModified() }, { it.name })) ?: emptyList()

    private fun deleteOldestArchive(): Boolean {
        val oldest = archives().firstOrNull() ?: return false
        return oldest.deleteRecursively() || !oldest.exists()
    }

    fun writeStatic(static: Map<String, Any?>): Boolean = writeObject(STATIC, static, staticCheck)
    fun writeDbRecord(record: Map<String, Any?>): Boolean = writeObject(DBRECORD, record, dbCheck)
    fun writeDescriptor(descriptor: Map<String, Any?>): Boolean = writeObject(DESCRIPTOR, descriptor, descriptorCheck)

    /**
     * Writes `probe-facts.json`: the two facts of the T0 read that `part1-static-v1` has no slot for (`StaticReadResult.facts`:
     * `opencl_child` as the GPU probe gave it, and the `public.libraries.txt` hint), with the engine id this store is bound to and
     * [atEpochMs]. Same atomic rule and size cap as the other files; archived with the key. Needs a successful [bindDevice].
     */
    fun writeProbeFacts(facts: Map<String, Any?>, atEpochMs: Long): Boolean {
        val bound = engineId ?: return false
        val tree = linkedMapOf<String, Any?>("format" to PROBE_FACTS_FORMAT, "engine_version" to bound, "time_epoch_ms" to atEpochMs, "facts" to facts)
        return writeObject(PROBE_FACTS, tree) { it["format"] == PROBE_FACTS_FORMAT }
    }

    fun readProbeFacts(): Map<String, Any?>? = readObject(PROBE_FACTS) { it["format"] == PROBE_FACTS_FORMAT }

    /**
     * Writes `t1-pass.json`: the record of the last quick-measurement (T1) pass that ran (status, reason, times, what each row came to,
     * and the pin probe object as the engine gave it, which the T0 read folds into the static input again). [record] is the body; the
     * store adds the format, the bound engine id and [atEpochMs]. Same atomic rule and size cap as the other files; archived with the
     * key. Needs a successful [bindDevice].
     */
    fun writePassRecord(record: Map<String, Any?>, atEpochMs: Long): Boolean {
        val bound = engineId ?: return false
        val tree = linkedMapOf<String, Any?>("format" to PASS_FORMAT, "engine_version" to bound, "time_epoch_ms" to atEpochMs)
        for ((k, v) in record) if (k !in tree) tree[k] = v
        return writeObject(PASS, tree) { it["format"] == PASS_FORMAT }
    }

    fun readPassRecord(): Map<String, Any?>? = readObject(PASS) { it["format"] == PASS_FORMAT && it["engine_version"] == engineId }

    /** Appends one row record (a `part1-rows-v1` object, with `engine_version` equal to the bound engine's id). */
    fun appendRow(record: Map<String, Any?>): AppendResult {
        val bound = engineId ?: return AppendResult.Rejected("the store is not bound to a device and engine (call bindDevice)")
        val line = try {
            Row.parse(record)
            Json.toOrg(record).toString()
        } catch (e: JsonException) {
            return AppendResult.Rejected("not a valid part1-rows-v1 row: ${e.message}")
        }
        if (record["engine_version"] != bound) return AppendResult.Rejected("the row names another engine (${record["engine_version"]}), this store holds rows of $bound")
        val rows = ArrayList(readRows().lines)
        rows.add(line)
        // everything but the row log counts: live files, archives, temporary files; older archives go first, then older rows
        if (usedBytes(ROWS) - archiveBytes() + size(listOf(line)) > maxTotalBytes) {
            return AppendResult.Rejected("the row does not fit within the size cap of $maxTotalBytes bytes") // before any archive is deleted
        }
        var dropped = 0
        while (usedBytes(ROWS) + size(rows) > maxTotalBytes) {
            if (deleteOldestArchive()) continue
            if (rows.size > 1) {
                rows.removeAt(0)
                dropped++
            } else {
                return AppendResult.Rejected("the row does not fit within the size cap of $maxTotalBytes bytes")
            }
        }
        return try {
            writer.write(file(ROWS), (rows.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            AppendResult.Appended(dropped)
        } catch (e: IOException) {
            AppendResult.IoFailed("the row log could not be written (${e.javaClass.simpleName})")
        }
    }

    private fun size(rows: List<String>): Long = rows.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }

    // ---- regenerate ---------------------------------------------------------------------------------------------

    /**
     * Runs the device matcher, the record builder and the descriptor generator over the stored inputs and writes
     * `dbrecord.json` and `device-descriptor-v0.2.json`. Needs a stored `static.json`; rows are optional (a T0 descriptor).
     * Never throws: a problem is a [RegenerateResult.Failed] with the reason.
     */
    fun regenerate(index: DeviceIndex, pluginVersion: String? = null): RegenerateResult {
        try {
            val static = readStatic() ?: return RegenerateResult.Failed("static.json is absent or corrupt")
            val identity = (static["identity"] as? Map<*, *>).orEmpty()
            fun id(k: String) = (identity[k] as? String).orEmpty()
            val record = DbRecordBuilder.buildTree(id("platform"), id("board"), id("device"), id("hardware"), MATCH_SOURCE, index, index.sha256)
            val rows: List<Row> = RowLog.parse(readRows().lines)
            val names = ArrayList<String>().also {
                it.add(STATIC)
                if (rows.isNotEmpty()) it.add(ROWS)
                it.add(DBRECORD)
            }
            // one plain tree (Long, Double, String, Boolean, List, Map): what was written is what is returned
            val descriptor = Json.obj(
                Json.fromOrg(Json.toOrg(DescriptorGenerator.generateTree(static, rows, record, pluginVersion, DescriptorGenerator.DEFAULT_PEAK_TOLERANCE, names))),
                "descriptor",
            )
            if (!writeDbRecord(record)) return RegenerateResult.Failed("dbrecord.json could not be written")
            if (!writeDescriptor(descriptor)) return RegenerateResult.Failed("the descriptor could not be written")
            return RegenerateResult.Regenerated(descriptor)
        } catch (e: JsonException) {
            return RegenerateResult.Failed("an input is not valid: ${e.message}")
        } catch (e: Exception) {
            return RegenerateResult.Failed("regenerate failed (${e.javaClass.simpleName})")
        }
    }
}
