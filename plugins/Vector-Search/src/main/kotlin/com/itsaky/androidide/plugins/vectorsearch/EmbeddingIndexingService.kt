package com.itsaky.androidide.plugins.vectorsearch

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import com.itsaky.androidide.plugins.PluginLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Names this class in every line it logs; the host's own `[pluginId]` prefix does not say which
 * class of the plugin wrote a line.
 */
private const val TAG = "EmbeddingIndexing"

/**
 * How many chunks one embedder holds within a project, as the settings screen reports it.
 *
 * @param backendId the backend that produced them
 * @param modelId the embedding model that produced them
 * @param dimensions the width of its vectors
 * @param chunkCount how many rows it holds
 */
data class IndexedEmbedder(
    val backendId: String,
    val modelId: String,
    val dimensions: Int,
    val chunkCount: Int,
)

/**
 * SQLite helper for embeddings storage; the schema itself is in [EmbeddingsSql].
 *
 * @param logger the owning plugin's log, or null before the plugin has a context
 */
class EmbeddingsDbHelper(context: Context, private val logger: PluginLogger?) :
    SQLiteOpenHelper(context, EmbeddingsSql.DB_NAME, null, EmbeddingsSql.DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(EmbeddingsSql.CREATE_TABLE)
        db.execSQL(EmbeddingsSql.CREATE_INDEX_FILE_PATH)
        db.execSQL(EmbeddingsSql.CREATE_INDEX_SCOPE)
    }

    /**
     * Rebuilds the table rather than migrating rows into it.
     *
     * The data is not migratable in principle: a v1 row's vector came from an embedder the schema
     * never recorded, so there is no value to backfill the provenance columns with, and a vector
     * of unknown origin is exactly what the columns exist to keep out of a ranking. Re-indexing is
     * cheap next to serving results from a space nothing can identify.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        logger?.info("$TAG: rebuilding the index for schema $oldVersion -> $newVersion")
        recreate(db)
    }

    /**
     * A downgrade is a rebuild too, and the default would throw.
     *
     * The plugin can be rolled back with its database left behind; letting the helper throw would
     * make every search fail instead of costing one re-index.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        logger?.info("$TAG: rebuilding the index for downgrade $oldVersion -> $newVersion")
        recreate(db)
    }

    private fun recreate(db: SQLiteDatabase) {
        db.execSQL(EmbeddingsSql.DROP_TABLE)
        onCreate(db)
    }
}

/**
 * Service for indexing code files into embeddings stored in a local SQLite database.
 *
 * Walks the project tree and chunks files; the vectors themselves are produced by the caller, which
 * owns the embedder, and arrive here already stamped with the identity that produced them.
 *
 * @param context an Android context, for the database only
 * @param logger the owning plugin's log, or null before the plugin has a context
 */
class EmbeddingIndexingService(
    private val context: Context,
    private val logger: PluginLogger?,
) {

    private val dbHelper = EmbeddingsDbHelper(context, logger)

    /**
     * Stores a batch of embeddings in one transaction.
     *
     * A batch rather than a row at a time: an index build writes thousands of rows, and a
     * transaction per row is the difference between seconds and minutes on a device. The batch is
     * all-or-nothing, so a failure mid-write cannot leave the index holding half a batch.
     *
     * @param rootsKey the project roots these chunks were collected from
     * @param embeddings the chunks to store, already embedded
     */
    fun storeEmbeddings(rootsKey: String, embeddings: List<CodeEmbedding>) {
        if (embeddings.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            embeddings.forEach { storeEmbedding(db, rootsKey, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        logger?.debug("$TAG: stored ${embeddings.size} embeddings")
    }

    /**
     * Collects and returns all code files in a directory that can be chunked and indexed.
     *
     * @param projectRoot Root directory of the project
     * @param maxFiles Maximum number of files to collect (default 500)
     * @return List of code files ready for chunking
     */
    fun collectFiles(projectRoot: File, maxFiles: Int = 500): List<File> {
        return collectCodeFiles(projectRoot, maxFiles)
    }

    fun languageFor(file: File): String {
        return getLanguageFromExtension(file.extension)
    }

    /**
     * Retrieves the embeddings [identity] produced, and only those.
     *
     * Filtered in SQL rather than after the read: the filter is the guard that keeps two vector
     * spaces out of one ranking, and it also keeps a large stale index from being materialised in
     * memory only to be discarded.
     *
     * @param identity the embedder whose vectors the caller can compare against
     * @param rootsKey the project whose rows to read, or null to read every project's
     * @return the matching embeddings, empty when the index holds none from that embedder
     */
    fun getAllEmbeddings(
        identity: EmbedderIdentity,
        rootsKey: String? = null,
    ): List<CodeEmbedding> {
        val db = dbHelper.readableDatabase
        val embeddings = mutableListOf<CodeEmbedding>()

        val (where, args) =
            if (rootsKey == null) {
                EmbeddingsSql.WHERE_IDENTITY to EmbeddingsSql.identityArgs(identity)
            } else {
                EmbeddingsSql.WHERE_ROOTS_AND_IDENTITY to EmbeddingsSql.scopedArgs(rootsKey, identity)
            }
        db.query(
            EmbeddingsSql.TABLE,
            EmbeddingsSql.READ_COLUMNS,
            where,
            args,
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                embeddings.add(readEmbedding(cursor))
            }
        }

        return embeddings
    }

    /**
     * How many rows [identity] holds for [rootsKey], without reading any of them.
     *
     * This is what lets a restart reuse the index a previous session paid for: the in-memory
     * build marker is gone, but the rows are not, and re-embedding a whole project is billed. A
     * build the process died inside is reused as it stands — partial ranking is the cheaper wrong.
     *
     * @return the row count, zero when this project was never indexed by this embedder
     */
    fun countEmbeddings(rootsKey: String, identity: EmbedderIdentity): Int =
        dbHelper.readableDatabase.rawQuery(
            EmbeddingsSql.COUNT_SCOPED,
            EmbeddingsSql.scopedArgs(rootsKey, identity),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /**
     * What the index holds for files under [projectRoot], one entry per embedder, largest first.
     * Counted by path rather than by roots, since a search scoped to one module still indexes the
     * open project.
     *
     * @return the embedders with rows here, empty when nothing under [projectRoot] is indexed
     */
    fun summarize(projectRoot: File): List<IndexedEmbedder> {
        return dbHelper.readableDatabase.rawQuery(
            EmbeddingsSql.SUMMARIZE_PATH_RANGE,
            EmbeddingsSql.pathRangeArgs(projectRoot),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        IndexedEmbedder(
                            backendId = cursor.getString(0),
                            modelId = cursor.getString(1),
                            dimensions = cursor.getInt(2),
                            chunkCount = cursor.getInt(3),
                        )
                    )
                }
            }
        }
    }

    /** How many distinct files under [projectRoot] have rows, whichever embedder wrote them. */
    fun countFiles(projectRoot: File): Int =
        dbHelper.readableDatabase.rawQuery(
            EmbeddingsSql.COUNT_FILES_PATH_RANGE,
            EmbeddingsSql.pathRangeArgs(projectRoot),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** Whether any project has rows, so a clear would delete something. */
    fun hasAnyRows(): Boolean =
        dbHelper.readableDatabase.rawQuery(EmbeddingsSql.HAS_ANY_ROW, null)
            .use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    /**
     * The embedding models whose rows [rootsKey] holds, so a rebuild can say which one it replaces.
     *
     * @return the distinct model ids, empty when [rootsKey] was never indexed
     */
    fun storedModels(rootsKey: String): List<String> =
        dbHelper.readableDatabase.rawQuery(
            EmbeddingsSql.STORED_MODELS,
            arrayOf(rootsKey),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    /**
     * Releases the SQLite connection. Call from the plugin's dispose() so the open
     * database handle doesn't outlive the plugin when it is unloaded/reloaded.
     */
    fun close() {
        dbHelper.close()
        logger?.debug("$TAG: database closed")
    }

    /**
     * Clears the embeddings of one project, or of every project.
     *
     * Scoped by default because one database holds every project a user searched: an unscoped
     * delete before rebuilding project B would discard the index — and the embedding spend —
     * of project A, and switching back and forth would re-embed both in turn.
     *
     * @param rootsKey the project to clear, or null to clear the whole database
     */
    fun clearIndex(rootsKey: String? = null) {
        val db = dbHelper.writableDatabase
        val deleted =
            if (rootsKey == null) db.delete(EmbeddingsSql.TABLE, null, null)
            else db.delete(EmbeddingsSql.TABLE, EmbeddingsSql.WHERE_ROOTS, arrayOf(rootsKey))
        logger?.info("$TAG: cleared $deleted rows from the index")
    }

    /** Reads one row in [EmbeddingsSql.READ_COLUMNS] order. */
    private fun readEmbedding(cursor: android.database.Cursor): CodeEmbedding {
        val buffer = cursor.getBlob(7)
        val embedding = FloatArray(buffer.size / Float.SIZE_BYTES)
        val byteBuffer = ByteBuffer.wrap(buffer)
        for (i in embedding.indices) {
            embedding[i] = byteBuffer.float
        }

        return CodeEmbedding(
            key = cursor.getString(0),
            filePath = cursor.getString(1),
            chunkText = cursor.getString(2),
            language = cursor.getString(3),
            chunkIndex = cursor.getInt(4),
            startLine = cursor.getInt(5),
            endLine = cursor.getInt(6),
            embedding = embedding,
            identity = EmbedderIdentity(
                EmbedderKey(cursor.getString(8), cursor.getString(9)),
                cursor.getInt(10),
            ),
        )
    }

    private fun storeEmbedding(db: SQLiteDatabase, rootsKey: String, embedding: CodeEmbedding) {
        db.insertWithOnConflict(
            EmbeddingsSql.TABLE,
            null,
            EmbeddingsSql.rowValues(rootsKey, embedding),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun collectCodeFiles(root: File, maxCount: Int): List<File> {
        val files = mutableListOf<File>()
        val skipDirs = setOf("build", ".gradle", "node_modules", ".git", "dist", "out")

        fun walk(dir: File) {
            if (files.size >= maxCount) return
            if (dir.name.startsWith(".") && dir.name != ".") return
            if (dir.name in skipDirs) return

            try {
                dir.listFiles()?.forEach { file ->
                    // Cap additions here (return@forEach is continue, not break), not just descent.
                    if (files.size >= maxCount) return@forEach
                    when {
                        file.isDirectory -> walk(file)
                        file.isFile && isCodeFile(file) -> files.add(file)
                    }
                }
            } catch (e: Exception) {
                logger?.warn("$TAG: could not walk ${dir.absolutePath}", e)
            }
        }

        walk(root)
        return files
    }

    private fun isCodeFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("kt", "java", "xml", "gradle", "kts", "py", "js", "ts")
    }

    private fun getLanguageFromExtension(ext: String): String {
        return when (ext.lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "xml" -> "xml"
            "gradle" -> "gradle"
            "py" -> "python"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            else -> "text"
        }
    }
}
