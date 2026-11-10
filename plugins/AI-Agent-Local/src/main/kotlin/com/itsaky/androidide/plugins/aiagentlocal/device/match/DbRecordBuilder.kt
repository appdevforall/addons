package com.itsaky.androidide.plugins.aiagentlocal.device.match

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import org.json.JSONObject

/**
 * Builds the `part1-dbrecord-v1` object that is the third input of the descriptor generator:
 * the match result, who ran the match, the index provenance and the one index record selected.
 * Port of `db_record()` in `descriptor-v02/build_examples.py`.
 */
object DbRecordBuilder {

    const val FORMAT = "part1-dbrecord-v1"

    /**
     * Runs the match rule on the identity values and returns the record as an `org.json` object.
     * [matchSource] says who ran the match and on which values (required by the generator).
     * [indexSha256] is the sha256 of the decompressed index; it defaults to [DeviceIndex.sha256].
     */
    fun build(
        platform: String,
        board: String,
        device: String,
        hardware: String,
        matchSource: String,
        index: DeviceIndex,
        indexSha256: String? = index.sha256,
    ): JSONObject = Json.toOrg(buildTree(platform, board, device, hardware, matchSource, index, indexSha256)) as JSONObject

    internal fun buildTree(
        platform: String,
        board: String,
        device: String,
        hardware: String,
        matchSource: String,
        index: DeviceIndex,
        indexSha256: String?,
    ): Map<String, Any?> {
        requireNotNull(indexSha256) { "index_sha256 is required: load the index from a stream or pass it" }
        val res = DeviceIndexMatcher.match(platform, board, device, hardware, index)
        val rec = res.key?.let { k -> index.records.first { it["key"] == k } }
        return linkedMapOf(
            "format" to FORMAT,
            "match" to res.toTree(),
            "match_source" to matchSource,
            "index" to linkedMapOf(
                "index_version" to index.tree["index_version"],
                "snapshot_date" to index.tree["snapshot_date"],
                "source_digest" to index.tree["source_digest"],
                "index_sha256" to indexSha256,
            ),
            "record" to rec,
        )
    }
}
