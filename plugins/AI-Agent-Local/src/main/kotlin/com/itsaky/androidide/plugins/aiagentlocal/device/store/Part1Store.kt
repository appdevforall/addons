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

    /** No fingerprint is known, so no key can be made; nothing was changed. */
    object Unkeyed : BindResult()

    /** A file operation failed; the store may be half archived and the next bind repeats the move. */
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
 * - **Size cap:** the live files together stay under [maxTotalBytes]; when appending a row would exceed it, the oldest rows
 *   are rotated out of the log (the newest row always stays).
 * - **Device key:** [bindDevice] compares the key of this device and engine with `key.txt`. A change archives every
 *   file under `old/<first 12 hex of the old key>/` and starts fresh; at most [maxArchives] archives are kept.
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
        const val DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024L
        const val MATCH_SOURCE = "in-app device matcher (DeviceIndexMatcher) over the identity stored in static.json: platform, board, device, hardware"
        private val LIVE = listOf(STATIC, ROWS, DBRECORD, DESCRIPTOR, KEY)
    }

    private val writer = AtomicWriter(ops)
    private var engineId: String? = null

    private fun file(name: String) = File(dir, name)

    // ---- device key ---------------------------------------------------------------------------------------------

    /**
     * Binds the store to this device and engine. [fingerprint] is `Build.FINGERPRINT` (never logged here); [engineId] is
     * `part1-engine/<version>`. Rows of another engine are refused by [appendRow].
     */
    fun bindDevice(fingerprint: String?, engineId: String): BindResult {
        if (fingerprint.isNullOrEmpty()) return BindResult.Unkeyed
        val key = DeviceKey.of(fingerprint, engineId)
        this.engineId = engineId
        return try {
            bind(key)
        } catch (e: IOException) {
            BindResult.IoFailed("the files could not be moved or written (${e.javaClass.simpleName})")
        }
    }

    private fun bind(key: String): BindResult {
        val stored = readTextOrNull(KEY)?.trim()?.takeIf { DeviceKey.isKey(it) }
        val others = LIVE.filter { it != KEY && file(it).exists() }
        if (stored == key) return BindResult.Same(key)
        if (stored == null && others.isEmpty()) {
            writeKey(key)
            return BindResult.Fresh(key)
        }
        val oldId = stored?.take(12) ?: "unkeyed"
        val archive = archive(oldId)
        writeKey(key)
        prune()
        return BindResult.Changed(stored ?: "", archive)
    }

    private fun writeKey(key: String) = writer.write(file(KEY), (key + "\n").toByteArray())

    /** Moves every live file into `old/<id>/` (key last, so an interrupted move is repeated by the next bind). */
    private fun archive(id: String): File? {
        val target = File(File(dir, "old"), id)
        var moved = false
        for (name in LIVE) { // KEY is last in LIVE
            val f = file(name)
            if (!f.exists()) continue
            if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) return null
            ops.moveReplacing(f, File(target, name))
            moved = true
        }
        return if (moved) target else null
    }

    private fun prune() {
        val old = File(dir, "old").listFiles { f -> f.isDirectory }?.sortedWith(compareBy({ it.lastModified() }, { it.name })) ?: return
        for (d in old.dropLast(maxArchives)) d.deleteRecursively()
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    private fun readTextOrNull(name: String): String? {
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
    fun inspect(): Map<String, FileState> = mapOf(
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
        if (!check(tree)) return false
        val bytes = try {
            (Json.toOrg(tree).toString() + "\n").toByteArray(Charsets.UTF_8)
        } catch (e: JsonException) {
            return false
        }
        if (bytes.size > maxTotalBytes) return false
        return try {
            writer.write(file(name), bytes)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun writeStatic(static: Map<String, Any?>): Boolean = writeObject(STATIC, static, staticCheck)
    fun writeDbRecord(record: Map<String, Any?>): Boolean = writeObject(DBRECORD, record, dbCheck)
    fun writeDescriptor(descriptor: Map<String, Any?>): Boolean = writeObject(DESCRIPTOR, descriptor, descriptorCheck)

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
        val others = LIVE.filter { it != ROWS }.sumOf { file(it).takeIf { f -> f.isFile }?.length() ?: 0L }
        var dropped = 0
        while (rows.size > 1 && others + size(rows) > maxTotalBytes) {
            rows.removeAt(0)
            dropped++
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
