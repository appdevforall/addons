package org.appdevforall.shareyourapps.server

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Hands the access log files to other apps through the share sheet — read-only, one grant per
 * share, and only those five files. The logs live in private storage because they record
 * buyers' IP addresses and browsers, so this is the only way they leave the app.
 */
class LogProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "org.appdevforall.shareyourapps.server.logs"
        private val NAME = Regex("""access\.log(\.[1-4])?""")

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

        /** "share-your-apps-server-log.txt" for the current file, "share-your-apps-server-log-2.txt" for older ones. */
        fun displayName(name: String) = "share-your-apps-server-log" + name.removePrefix("access.log").replace(".", "-") + ".txt"

        /** The existing log files, current first, then oldest last. */
        fun files(ctx: Context): List<File> = (listOf(StoreLog.FILE_NAME) + (1 until StoreLog.KEEP).map { "${StoreLog.FILE_NAME}.$it" })
            .map { File(Store.logDir(ctx), it) }.filter { it.isFile }

        /** Opens the share sheet with [names] attached, so the owner can send them anywhere. */
        fun share(ctx: Context, names: List<String>) {
            if (names.isEmpty()) return
            val uris = ArrayList(names.map { uri(it) })
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_SUBJECT, "Share Your Apps Server logs")
            // The clip carries the read grant for every file to whichever app is chosen.
            send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Send logs"))
        }
    }

    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (!NAME.matches(name)) return null
        return File(Store.logDir(context ?: return null), name).takeIf { it.isFile }
    }

    override fun getType(uri: Uri) = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("the logs can only be read")
        val f = file(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Name and size, which receiving apps use to label the attachment. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        if (f != null) c.addRow(cols.map { col ->
            when (col) { OpenableColumns.DISPLAY_NAME -> displayName(f.name); OpenableColumns.SIZE -> f.length(); else -> null }
        })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
