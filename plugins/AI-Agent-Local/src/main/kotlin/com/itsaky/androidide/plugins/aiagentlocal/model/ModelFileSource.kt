package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * What a selected model file is called and how big it is.
 *
 * @property displayName the name to show the user; never blank
 * @property sizeBytes the file's size, or null when it could not be established — the pre-flight
 *   then has nothing to weigh and skips the check rather than guessing
 */
data class ModelFileInfo(val displayName: String, val sizeBytes: Long?)

/**
 * Reads a selected model file's metadata and bytes, whether it came from the document picker as a
 * `content://` URI or from a saved filesystem path. An interface, so the memory pre-flight can be
 * exercised against ordinary files without a device.
 */
interface ModelFileSource {

    /**
     * Name and size together — for a content URI that is one provider query, where asking
     * separately costs two IPC round trips for one row. Do NOT call on the main thread.
     *
     * @param context supplies the resolver that holds the picker's permission grant
     * @param uriString the selected model, as a `content://` URI or a filesystem path
     */
    fun info(context: Context, uriString: String): ModelFileInfo

    /** Opens the model for reading; null when it cannot be opened. Not for the main thread. */
    fun openStream(context: Context, uriString: String): InputStream?

    /**
     * Whether the model can still be opened right now: a configured model can be deleted or
     * unmounted underneath the settings screen, and the stored path says nothing about that.
     * Tri-state, because a provider that stayed silent is no reason to tell the user to re-pick a
     * model that is intact. Reports rather than logs, and not for the main thread.
     *
     * @return what the probe found; [SourceReachability.UNKNOWN] leaves the screen's status alone
     */
    fun readability(context: Context, uriString: String): SourceReachability

    /** Decoded last path segment — a cheap name that at least avoids raw `%3A` escapes. */
    fun fallbackDisplayName(uriOrPath: String): String

    /**
     * Turn the picker's one-off read grant for [uriString] into a persistable one, so the model is
     * still readable after the IDE is restarted — nothing is copied into private storage, so that
     * grant is the only thing keeping it reachable (ADFA-5253). A no-op for a filesystem path.
     *
     * @return true when the model will still be readable after a restart
     */
    fun persistAccess(context: Context, uriString: String): Boolean

    /**
     * Whether a durable read grant for [uriString] is held right now, which is what decides
     * — asked again on every visit rather than remembered from the selection — whether the pane
     * still has to warn that the model may need picking again after a restart. Not for the main
     * thread.
     *
     * Tri-state, because the two directions are not symmetric: inventing a caveat is worse than
     * none, but erasing one that a real `persistAccess` failure raised tells the user a selection
     * is fine when the next restart will break it.
     *
     * @return true for a filesystem path; null when the answer cannot be established, which leaves
     *   whatever the pane already says about the grant standing
     */
    fun hasPersistedAccess(context: Context, uriString: String): Boolean?

    /**
     * Give back the persistable read grant the picker took for [uriString], for a model the user
     * ended up not keeping — the grant table has a hard per-app limit. A no-op for a filesystem
     * path, and for a grant that was never held.
     */
    fun releaseAccess(context: Context, uriString: String)
}

/**
 * [ModelFileSource] over the document provider and the filesystem.
 *
 * Every lookup degrades rather than throwing: an unnamed file falls back to its path, and an
 * unknown size is reported as unknown. A model the user picked is not a place to fail hard.
 *
 * @param onError reports a failed lookup, so a silently skipped pre-flight can still be explained
 */
class ContentModelFileSource(
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : ModelFileSource {

    override fun info(context: Context, uriString: String): ModelFileInfo =
        if (uriString.startsWith(CONTENT_SCHEME)) {
            documentInfo(context, uriString) ?: ModelFileInfo(fallbackDisplayName(uriString), null)
        } else {
            ModelFileInfo(fallbackDisplayName(uriString), fileSize(uriString))
        }

    override fun openStream(context: Context, uriString: String): InputStream? = try {
        if (uriString.startsWith(CONTENT_SCHEME)) {
            context.contentResolver.openInputStream(Uri.parse(uriString))
        } else {
            File(uriString).takeIf { it.isFile }?.inputStream()
        }
    } catch (e: Exception) {
        onError("could not open $uriString", e)
        null
    }

    override fun readability(context: Context, uriString: String): SourceReachability =
        if (uriString.startsWith(CONTENT_SCHEME)) {
            // Confirmed: one FileNotFoundException covers a deletion and a dead provider alike.
            confirmedGone {
                probeOpenable({ context.contentResolver.openInputStream(Uri.parse(uriString)) }) {
                    onError("could not reach $uriString", it)
                }
            }
        } else {
            // Confirmed on this branch too, so a GONE is an answer given twice for every reference
            // — a stat that lost a race with a mount refuses a pick over a model that is fine.
            confirmedGone { probeFilePath(uriString) { onError("could not stat $uriString", it) } }
        }

    override fun fallbackDisplayName(uriOrPath: String): String =
        (try {
            Uri.decode(uriOrPath)
        } catch (e: Exception) {
            uriOrPath
        }).substringAfterLast('/')

    override fun persistAccess(context: Context, uriString: String): Boolean {
        if (!uriString.startsWith(CONTENT_SCHEME)) return true
        return try {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (e: Exception) {
            // A provider that hands out non-persistable grants, or a grant table that is full.
            onError("could not persist the read grant for $uriString", e)
            false
        }
    }

    override fun hasPersistedAccess(context: Context, uriString: String): Boolean? {
        if (!uriString.startsWith(CONTENT_SCHEME)) return true
        return try {
            context.contentResolver.persistedUriPermissions
                .any { it.isReadPermission && it.uri.toString() == uriString }
        } catch (e: Exception) {
            // "Could not tell", never "it is fine": a resolver that will not answer must not be
            // the thing that clears a caveat a failed persistAccess put there.
            onError("could not read the persisted read grants for $uriString", e)
            null
        }
    }

    override fun releaseAccess(context: Context, uriString: String) {
        if (!uriString.startsWith(CONTENT_SCHEME)) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Nothing was held, or it was already released: the no-op this documents.
        } catch (e: Exception) {
            onError("could not release the read grant for $uriString", e)
        }
    }

    /** One query for both columns; null when the provider answered with neither. */
    private fun documentInfo(context: Context, uriString: String): ModelFileInfo? = try {
        context.contentResolver
            .query(Uri.parse(uriString), arrayOf(NAME_COLUMN, SIZE_COLUMN), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(NAME_COLUMN)
                val sizeIndex = cursor.getColumnIndex(SIZE_COLUMN)
                val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { cursor.getString(it) }
                    ?.takeIf { it.isNotBlank() }
                val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { cursor.getLong(it) }
                    ?.takeIf { it > 0L }
                ModelFileInfo(name ?: fallbackDisplayName(uriString), size)
            }
    } catch (e: Exception) {
        onError("could not read the metadata of $uriString", e)
        null
    }

    private fun fileSize(path: String): Long? = try {
        File(path).length().takeIf { it > 0L }
    } catch (e: Exception) {
        onError("could not read the size of $path", e)
        null
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
        val NAME_COLUMN: String = OpenableColumns.DISPLAY_NAME
        val SIZE_COLUMN: String = OpenableColumns.SIZE
    }
}
