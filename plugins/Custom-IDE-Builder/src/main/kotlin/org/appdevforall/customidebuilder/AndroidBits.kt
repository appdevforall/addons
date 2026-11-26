package org.appdevforall.customidebuilder

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/** What the screen needs to know about the Code on the Go that is running. */
class HostInfo(
    val packageName: String,
    val apk: File,
    val splitDirs: List<String>,
    val label: String,
    val versionName: String?,
    val minSdk: Int,
)

/** The Android-only reads. Everything with logic in it is in `core`, where it can be tested on a JVM. */
object AndroidBits {
    const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * The HOST's APK. The plugin's own context would name the .cgp. `applicationContext` of the
     * context the host gave the plugin is the host's; [fallback] (the screen's own context, also
     * the host's) is used if that ever turns out to be the plugin file.
     */
    fun hostInfo(pluginContext: Context?, fallback: Context): HostInfo {
        var app = (pluginContext ?: fallback).applicationContext
        if (app.applicationInfo.sourceDir.endsWith(".cgp", ignoreCase = true)) app = fallback.applicationContext
        val info = app.applicationInfo
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val versionName = pm.getPackageInfo(app.packageName, 0).versionName
        return HostInfo(
            packageName = app.packageName,
            apk = File(info.sourceDir),
            splitDirs = info.splitSourceDirs?.toList().orEmpty(),
            label = pm.getApplicationLabel(info).toString(),
            versionName = versionName,
            minSdk = info.minSdkVersion,
        )
    }

    fun displayName(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }

    /** The size the provider reports for [uri] in bytes, or null when it does not say. */
    fun displaySize(context: Context, uri: Uri): Long? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { n -> n >= 0 } else null
            }
        } catch (e: Exception) {
            null
        }

    /**
     * Copies what [uri] holds into [target], stopping at [limit] bytes. [checkCancelled] is called
     * between buffers and may throw to stop; [target] is then left partly written for the caller to delete.
     */
    fun copyToFile(context: Context, uri: Uri, target: File, limit: Long = Long.MAX_VALUE, checkCancelled: () -> Unit = {}) {
        target.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { source ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw IOException("The file is larger than expected.")
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    fun readBytes(context: Context, uri: Uri, limit: Int): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { source ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > limit) throw IOException("The file is larger than expected.")
            }
            return out.toByteArray()
        }
    }

    /**
     * The display names of what is directly in the folder [tree]. Empty when the provider will not
     * list it, so a caller cannot tell an empty folder from one it could not read; the name check
     * after saving (see [displayName]) is what catches a collision then.
     */
    fun childNames(context: Context, tree: Uri): Set<String> =
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val names = HashSet<String>()
                while (cursor.moveToNext()) cursor.getString(0)?.let { names.add(it) }
                names
            } ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

    /** Creates [name] in the folder [tree] (a tree URI from ACTION_OPEN_DOCUMENT_TREE) and returns its URI. The provider may pick another name if [name] is taken: read it back with [displayName]. */
    fun createInTree(context: Context, tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(context.contentResolver, parent, APK_MIME, name)
            ?: throw IOException("The folder would not create $name. Choose the folder again.")
    }

    /**
     * Copies [source] into the document [target]. [checkCancelled] is called between buffers and may
     * throw to stop; [target] then holds a partial file, which the caller removes with [deleteDocument].
     */
    fun copyFileTo(context: Context, source: File, target: Uri, checkCancelled: () -> Unit = {}, onBytes: (Long) -> Unit) {
        val out = context.contentResolver.openOutputStream(target, "w") ?: throw IOException("Cannot write to the folder.")
        out.use { sink ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    total += n
                    onBytes(total)
                }
            }
        }
    }

    /** Removes the document [uri]; false if the provider would not (a failed cleanup is not worth a second error). */
    fun deleteDocument(context: Context, uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            false
        }

    /** A readable name for a tree URI, such as `Download/Builds`. */
    fun treeLabel(tree: Uri): String =
        try {
            DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "the storage root" }
        } catch (e: Exception) {
            tree.toString()
        }
}
