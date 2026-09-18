package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the file behind a resident model and reports when it goes away, so its gigabytes are
 * given back at deletion time rather than at the user's next message.
 *
 * Best-effort by contract: a provider that does not notify simply never fires, and the
 * before-generation reachability check stays the guarantee. Nothing here may be the only thing
 * standing between a deleted model and a reply.
 */
interface ModelSourceWatcher {

    /**
     * @param modelReference the resident model, as a `content://` URI or a filesystem path
     * @param onGone invoked, off the caller's thread, when the file looks gone; may fire more than
     *   once and may fire spuriously, so the callback must confirm before acting
     * @return a handle that stops the watch, or null when this source cannot be watched
     */
    fun watch(modelReference: String, onGone: () -> Unit): Closeable?
}

/**
 * [ModelSourceWatcher] over the document provider and the filesystem.
 *
 * Never the main thread, and never a thread the caller owns: a document watch arrives on a private
 * [HandlerThread], started with the first such watch and stopped with the last so an idle plugin
 * holds no thread, and a filesystem watch on [FileObserver]'s own. See ADFA-5253.
 *
 * @param onError reports a failed registration, so a silently unwatched model can be explained
 */
class PlatformModelSourceWatcher(
    private val context: Context,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : ModelSourceWatcher {

    /** Guards [thread] and [handler]; both are touched from watch and from close. */
    private val lock = Any()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Live watches, so the last one out stops the thread. */
    private var watchCount = 0

    override fun watch(modelReference: String, onGone: () -> Unit): Closeable? = try {
        if (modelReference.startsWith(CONTENT_SCHEME)) {
            watchDocument(modelReference, onGone)
        } else {
            watchFile(modelReference, onGone)
        }
    } catch (e: Exception) {
        onError("could not watch $modelReference", e)
        null
    }

    /**
     * Registers on the document URI *and* on [parentChildrenUriOf] it, which is where a provider
     * actually notifies a delete and is no descendant of the document URI. Both stay hints, never
     * verdicts — the parent's URI fires for every sibling too — so the callback confirms first.
     *
     * Debounced for that reason: the parent's URI fires for every change in the folder, and the
     * folder the help pages steer users to is Downloads, where every completed download lands.
     * Each notification the callback acts on costs a binder probe under the backend's generation
     * lock, so a burst is collapsed into one ask [NOTIFY_DEBOUNCE_MS] after it stops.
     */
    private fun watchDocument(uriString: String, onGone: () -> Unit): Closeable {
        val uri = Uri.parse(uriString)
        val handler = acquireHandler()
        val fire = Runnable { onGone() }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacks(fire)
                handler.postDelayed(fire, NOTIFY_DEBOUNCE_MS)
            }
        }
        try {
            context.contentResolver.registerContentObserver(uri, true, observer)
            // Null for a document at the root of its volume; the direct watch then stands alone.
            parentChildrenUriOf(uri)?.let {
                context.contentResolver.registerContentObserver(it, true, observer)
            }
        } catch (e: Exception) {
            // Whichever registration landed goes back too: watch() is about to return nothing, and
            // an observer left behind would hold onGone for the process's life — dispatching to a
            // HandlerThread releaseHandler() has just quit.
            unregisterQuietly(observer)
            releaseHandler()
            throw e
        }
        // One unregister covers both registrations — the resolver keys them by observer.
        return closeOnce {
            try {
                // Ahead of the unregister: a debounced notification still queued would otherwise
                // reach onGone after the watch was closed, on a thread that is about to quit.
                handler.removeCallbacks(fire)
                context.contentResolver.unregisterContentObserver(observer)
            } finally {
                releaseHandler()
            }
        }
    }

    /** Unregisters on a path that is already failing, where the failure to report is the first. */
    private fun unregisterQuietly(observer: ContentObserver) {
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            onError("could not unregister a half-registered model observer", e)
        }
    }

    /**
     * `DELETE_SELF` covers the delete; `MOVE_SELF` covers a rename or a move to another volume,
     * which breaks a configured path just as thoroughly.
     */
    private fun watchFile(path: String, onGone: () -> Unit): Closeable? {
        val file = File(path)
        if (!file.isFile) return null
        val observer = object : FileObserver(file, DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, path: String?) = onGone()
        }
        // The framework holds FileObserver weakly and stops watching once it is collected, so the
        // returned handle keeps the only strong reference alive for as long as the watch is wanted.
        observer.startWatching()
        return closeOnce { observer.stopWatching() }
    }

    /**
     * A handle whose second [Closeable.close] is a no-op. [releaseHandler] counts live watches, so
     * a double close would stop the delivery thread out from under the watches still using it.
     */
    private fun closeOnce(release: () -> Unit): Closeable {
        val closed = AtomicBoolean(false)
        return Closeable { if (closed.compareAndSet(false, true)) release() }
    }

    /** Starts the delivery thread on the first watch. */
    private fun acquireHandler(): Handler = synchronized(lock) {
        if (thread == null) {
            thread = HandlerThread(THREAD_NAME).also {
                it.start()
                handler = Handler(it.looper)
            }
        }
        watchCount++
        handler!!
    }

    /** Stops the delivery thread with the last watch, so an idle plugin holds no thread. */
    private fun releaseHandler() = synchronized(lock) {
        watchCount--
        if (watchCount <= 0) {
            watchCount = 0
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
        const val THREAD_NAME = "LocalLlm-ModelWatch"

        /** Short enough that a real delete is still acted on promptly; see [watchDocument]. */
        const val NOTIFY_DEBOUNCE_MS = 300L
    }
}

/**
 * The children URI of [uri]'s parent document, which is where a `DocumentsProvider` notifies a
 * delete. Drops the last element of the document id — `primary:Download/model.gguf` gives
 * `primary:Download`. Top-level so it is testable without a `ContentObserver` and a `HandlerThread`.
 *
 * @return the parent's children URI, or null when the id names no parent to derive
 */
internal fun parentChildrenUriOf(uri: Uri): Uri? = try {
    val documentId = DocumentsContract.getDocumentId(uri)
    documentId.substringBeforeLast(DOCUMENT_ID_SEPARATOR, "")
        .takeIf { it.isNotEmpty() && it != documentId }
        ?.let { DocumentsContract.buildChildDocumentsUri(uri.authority, it) }
} catch (_: Exception) {
    // Not a document URI, or an id this provider shapes some other way.
    null
}

/** How every provider that nests documents separates the elements of a document id. */
private const val DOCUMENT_ID_SEPARATOR = '/'
