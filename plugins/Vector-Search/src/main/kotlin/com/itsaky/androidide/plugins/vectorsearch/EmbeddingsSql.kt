package com.itsaky.androidide.plugins.vectorsearch

import android.content.ContentValues
import java.io.File
import java.nio.ByteBuffer

/**
 * Every SQL statement the index runs, and the schema they run against, in one place.
 *
 * Each statement is a compile-time constant: values are only ever bound through `?` placeholders,
 * never spliced into the text, so nothing a project path or model id contains can change a query.
 */
internal object EmbeddingsSql {

    /** The database file, in the plugin's own data directory. */
    const val DB_NAME = "embeddings.db"

    /**
     * Schema version: 3 since ADFA-6054 added the provenance and roots columns. Bump it for any
     * column change: [EmbeddingsDbHelper.onUpgrade] rebuilds from scratch, so forgetting leaves
     * every existing install querying a table that no longer matches this file.
     */
    const val DB_VERSION = 3

    /** Table holding one row per indexed chunk. */
    const val TABLE = "embeddings"

    /** Column names, shared by the statements below and by [rowValues]. */
    private object Columns {
        const val KEY = "key"
        const val FILE_PATH = "file_path"
        const val CHUNK_TEXT = "chunk_text"
        const val LANGUAGE = "language"
        const val CHUNK_INDEX = "chunk_index"
        const val START_LINE = "start_line"
        const val END_LINE = "end_line"
        const val EMBEDDING = "embedding"
        const val EMBEDDER_BACKEND = "embedder_backend"
        const val EMBEDDER_MODEL = "embedder_model"
        const val EMBEDDER_DIMENSIONS = "embedder_dimensions"
        const val ROOTS_KEY = "roots_key"
    }

    // --- Schema --------------------------------------------------------------------------------

    const val CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS $TABLE (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ${Columns.KEY} TEXT UNIQUE,
            ${Columns.FILE_PATH} TEXT,
            ${Columns.CHUNK_TEXT} TEXT,
            ${Columns.LANGUAGE} TEXT,
            ${Columns.CHUNK_INDEX} INTEGER,
            ${Columns.START_LINE} INTEGER,
            ${Columns.END_LINE} INTEGER,
            ${Columns.EMBEDDING} BLOB,
            ${Columns.EMBEDDER_BACKEND} TEXT NOT NULL,
            ${Columns.EMBEDDER_MODEL} TEXT NOT NULL,
            ${Columns.EMBEDDER_DIMENSIONS} INTEGER NOT NULL,
            ${Columns.ROOTS_KEY} TEXT NOT NULL
        )
    """

    /** Serves the per-file deletes and the settings screen's path-range summary. */
    const val CREATE_INDEX_FILE_PATH =
        "CREATE INDEX IF NOT EXISTS idx_file_path ON $TABLE(${Columns.FILE_PATH})"

    /** Every ranking query filters on the roots and the whole identity, so it is one index. */
    const val CREATE_INDEX_SCOPE =
        "CREATE INDEX IF NOT EXISTS idx_scope ON $TABLE(" +
            "${Columns.ROOTS_KEY}, ${Columns.EMBEDDER_BACKEND}, " +
            "${Columns.EMBEDDER_MODEL}, ${Columns.EMBEDDER_DIMENSIONS})"

    const val DROP_TABLE = "DROP TABLE IF EXISTS $TABLE"

    // --- Reads ---------------------------------------------------------------------------------

    /** Columns read back by [EmbeddingIndexingService.getAllEmbeddings]. */
    val READ_COLUMNS = arrayOf(
        Columns.KEY, Columns.FILE_PATH, Columns.CHUNK_TEXT, Columns.LANGUAGE, Columns.CHUNK_INDEX,
        Columns.START_LINE, Columns.END_LINE, Columns.EMBEDDING, Columns.EMBEDDER_BACKEND,
        Columns.EMBEDDER_MODEL, Columns.EMBEDDER_DIMENSIONS,
    )

    /** One embedder's rows. Bind [identityArgs]. */
    const val WHERE_IDENTITY =
        "${Columns.EMBEDDER_BACKEND} = ? AND ${Columns.EMBEDDER_MODEL} = ? AND " +
            "${Columns.EMBEDDER_DIMENSIONS} = ?"

    /** One embedder's rows within one project's roots. Bind [scopedArgs]. */
    const val WHERE_ROOTS_AND_IDENTITY = "${Columns.ROOTS_KEY} = ? AND $WHERE_IDENTITY"

    /** How many rows one embedder holds for one project. Bind [scopedArgs]. */
    const val COUNT_SCOPED = "SELECT COUNT(*) FROM $TABLE WHERE $WHERE_ROOTS_AND_IDENTITY"

    /**
     * Chunk counts per embedder for the paths in a half-open range, largest first. Bind
     * [pathRangeArgs]; a range rather than `LIKE`, so it uses the index and takes no wildcard.
     */
    const val SUMMARIZE_PATH_RANGE =
        "SELECT ${Columns.EMBEDDER_BACKEND}, ${Columns.EMBEDDER_MODEL}, " +
            "${Columns.EMBEDDER_DIMENSIONS}, COUNT(*) FROM $TABLE " +
            "WHERE ${Columns.FILE_PATH} >= ? AND ${Columns.FILE_PATH} < ? " +
            "GROUP BY ${Columns.EMBEDDER_BACKEND}, ${Columns.EMBEDDER_MODEL}, " +
            "${Columns.EMBEDDER_DIMENSIONS} ORDER BY COUNT(*) DESC"

    /** How many distinct files have rows in a half-open path range. Bind [pathRangeArgs]. */
    const val COUNT_FILES_PATH_RANGE =
        "SELECT COUNT(DISTINCT ${Columns.FILE_PATH}) FROM $TABLE " +
            "WHERE ${Columns.FILE_PATH} >= ? AND ${Columns.FILE_PATH} < ?"

    /** Whether the index holds any row at all, for any project. Binds nothing. */
    const val HAS_ANY_ROW = "SELECT EXISTS(SELECT 1 FROM $TABLE)"

    /** The embedding models one project's rows came from. Bind the roots key. */
    const val STORED_MODELS =
        "SELECT DISTINCT ${Columns.EMBEDDER_MODEL} FROM $TABLE WHERE ${Columns.ROOTS_KEY} = ?"

    // --- Writes --------------------------------------------------------------------------------

    /** One project's rows, for a scoped delete. Bind the roots key. */
    const val WHERE_ROOTS = "${Columns.ROOTS_KEY} = ?"

    /** The row [embedding] is stored as under [rootsKey], with its vector as big-endian floats. */
    fun rowValues(rootsKey: String, embedding: CodeEmbedding): ContentValues {
        val buffer = ByteBuffer.allocate(embedding.embedding.size * Float.SIZE_BYTES)
        for (f in embedding.embedding) {
            buffer.putFloat(f)
        }
        return ContentValues().apply {
            put(Columns.KEY, embedding.key)
            put(Columns.FILE_PATH, embedding.filePath)
            put(Columns.CHUNK_TEXT, embedding.chunkText)
            put(Columns.LANGUAGE, embedding.language)
            put(Columns.CHUNK_INDEX, embedding.chunkIndex)
            put(Columns.START_LINE, embedding.startLine)
            put(Columns.END_LINE, embedding.endLine)
            put(Columns.EMBEDDING, buffer.array())
            put(Columns.EMBEDDER_BACKEND, embedding.identity.backendId)
            put(Columns.EMBEDDER_MODEL, embedding.identity.modelId)
            put(Columns.EMBEDDER_DIMENSIONS, embedding.identity.dimensions)
            put(Columns.ROOTS_KEY, rootsKey)
        }
    }

    // --- Arguments -----------------------------------------------------------------------------

    /** The values [WHERE_IDENTITY] binds, in its order. */
    fun identityArgs(identity: EmbedderIdentity): Array<String> =
        arrayOf(identity.backendId, identity.modelId, identity.dimensions.toString())

    /** The values [WHERE_ROOTS_AND_IDENTITY] and [COUNT_SCOPED] bind, in their order. */
    fun scopedArgs(rootsKey: String, identity: EmbedderIdentity): Array<String> =
        arrayOf(rootsKey) + identityArgs(identity)

    /**
     * The half-open range [SUMMARIZE_PATH_RANGE] binds: every path that starts with `root/` sorts
     * inside it, and `root` itself, `root2` or `root-x` sort outside.
     *
     * @return the inclusive lower bound and exclusive upper bound, compared as SQLite BINARY text
     */
    fun pathRangeArgs(root: File): Array<String> {
        val dir = root.absolutePath.trimEnd(File.separatorChar)
        // The separator's successor is the first character no "dir/..." path can have after dir.
        return arrayOf("$dir${File.separatorChar}", "$dir${File.separatorChar + 1}")
    }
}
