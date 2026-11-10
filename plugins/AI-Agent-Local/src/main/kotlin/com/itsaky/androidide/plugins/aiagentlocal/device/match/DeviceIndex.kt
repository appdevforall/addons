package com.itsaky.androidide.plugins.aiagentlocal.device.match

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The reduced database index (`part1-dbindex-v1`), as shipped in `assets/part1/`.
 *
 * [tree] is the whole index as a plain JSON tree; the accessors read what the match rule needs.
 */
class DeviceIndex internal constructor(
    internal val tree: Map<String, Any?>,
    /** sha256 (lower-case hex) of the decompressed index bytes, when loaded from a stream; else null. */
    val sha256: String?,
) {
    internal val records: List<Map<String, Any?>> =
        Json.arr(tree["records"], "index.records").mapIndexed { i, r -> Json.obj(r, "index.records[$i]") }

    internal val genericBoardPatterns: List<String> =
        Json.arr(tree["generic_board_patterns"], "index.generic_board_patterns")
            .mapIndexed { i, p -> Json.str(p, "index.generic_board_patterns[$i]") }

    internal val sharedBoards: Set<String> =
        Json.arr(tree["shared_boards"], "index.shared_boards")
            .mapIndexed { i, p -> Json.str(p, "index.shared_boards[$i]") }.toSet()

    /** Records by key; a later record with the same key wins, as in the Python dict comprehension. */
    internal val byKey: Map<String, Map<String, Any?>> =
        LinkedHashMap<String, Map<String, Any?>>().also { m ->
            records.forEachIndexed { i, r -> m[Json.str(r["key"], "index.records[$i].key")] = r }
        }

    companion object {
        /** sha256 of the asset file shipped in `assets/part1/` (the gzip bytes). */
        const val ASSET_GZ_SHA256 = "d5dff2d58b1520388e6dce5554301e75954a39deb5d8d0308c244d41ec81ea3c"

        /** sha256 of the decompressed index; it is `index.index_sha256` in a database record. */
        const val ASSET_JSON_SHA256 = "5688119ef9af440dd8acd44e7a27da0682588ba5a2b60633f141f423f5ea47a7"

        /** Path of the index in the source tree's assets (`src/main/assets/`). */
        const val ASSET_PATH = "part1/part1-dbindex-v1.json.gz"

        /**
         * Path of the index inside the built plugin. The Android Gradle plugin decompresses an asset whose
         * name ends in `.gz` and drops the suffix when it packages it, so on a device the asset is this name,
         * not [ASSET_PATH]. Its content is the same index (the same [ASSET_JSON_SHA256]).
         */
        const val PACKAGED_ASSET_PATH = "part1/part1-dbindex-v1.json"

        /**
         * Reads an index from [input] and closes it. Gzip data (magic bytes 1f 8b) is decompressed, plain
         * JSON text (UTF-8) is read as it is. The sha256 is taken over the decompressed bytes.
         */
        fun load(input: InputStream): DeviceIndex {
            val raw = input.use { it.readBytes() }
            val bytes = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                GZIPInputStream(raw.inputStream()).use { gz ->
                    val out = ByteArrayOutputStream()
                    gz.copyTo(out)
                    out.toByteArray()
                }
            } else {
                raw
            }
            val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return DeviceIndex(Json.parseObject(String(bytes, Charsets.UTF_8), "index"), hex)
        }

        /**
         * Loads the index through [open] (for example `{ assets.open(it) }` on an `AssetManager`): first the
         * packaged name, then the source name, whichever the build produced.
         */
        fun loadFromAssets(open: (String) -> InputStream): DeviceIndex {
            val stream = try {
                open(PACKAGED_ASSET_PATH)
            } catch (_: java.io.IOException) {
                open(ASSET_PATH)
            }
            return load(stream)
        }

        /** An index already parsed into a tree (the test fixture index). */
        internal fun fromTree(tree: Map<String, Any?>): DeviceIndex = DeviceIndex(tree, null)
    }
}
