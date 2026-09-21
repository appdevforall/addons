package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A model file held open for the native loader.
 *
 * [nativePath] is a path llama.cpp can `fopen` and `mmap`. For a document picked through SAF that is
 * `/proc/self/fd/N` for the descriptor this handle owns: opening that procfs entry re-opens the
 * underlying inode with an independent file offset, so the native loader behaves exactly as it does
 * for a real path — without copying multiple gigabytes into private storage first. See ADFA-5253.
 *
 * IMPORTANT: the descriptor must stay open for as long as the model is resident. Closing it
 * invalidates the procfs entry, and the pages the loader has mapped are the only thing keeping the
 * model alive after that. [close] is therefore the unload path's job, not the load path's.
 *
 * @property nativePath the path to hand the native loader
 * @property sizeBytes the model's size, or -1 when the descriptor names no regular file
 */
class OpenModelFile(
    val nativePath: String,
    val sizeBytes: Long,
    private val descriptor: Closeable?,
) : Closeable {

    /**
     * Whether [nativePath] can be `mmap`ed and re-opened, which everything above assumes. A
     * streaming provider (Drive, OneDrive) hands back a pipe instead, for which `statSize` is -1
     * and each [openStream] eats bytes the loader never sees, so the caller must refuse it.
     */
    val isSeekable: Boolean get() = sizeBytes >= 0

    /**
     * Whether [nativePath] can be opened *by name*, which is all the native loader ever does with
     * it. That open re-resolves to the real inode against this app's own credentials rather than
     * the SAF grant, so removable storage can refuse it where the descriptor was not. ADFA-5253.
     */
    fun isReopenable(): Boolean = try {
        openStream()?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Opens an independent read stream over the same bytes the native loader sees — header
     * inspection must never disturb the loader's own file offset.
     *
     * @return the stream, or null when the source became unreadable
     */
    fun openStream(): InputStream? = try {
        FileInputStream(nativePath)
    } catch (_: Exception) {
        null
    }

    override fun close() {
        try {
            descriptor?.close()
        } catch (_: Exception) {
            // Already closed, or the provider died with it — there is nothing left to release.
        }
    }
}

/**
 * What a reachability probe found. [GONE] and [UNKNOWN] must never be collapsed: a resident
 * multi-gigabyte model is the memory pressure that gets a `DocumentsProvider` process killed, and
 * reading that silence as a deletion evicts a model that is fine. Only [GONE] evicts. ADFA-5253.
 */
enum class SourceReachability {
    /** The source answered, and the model is there. */
    REACHABLE,

    /** The source answered: the model is gone — deleted, unmounted, or the read grant was revoked. */
    GONE,

    /** The source did not answer, or answered gone only once: no evidence about the model. */
    UNKNOWN,
}

/**
 * Runs [probe] and reports a [SourceReachability.GONE] only when a second ask agrees: the resolver
 * turns provider death into the same `FileNotFoundException` a deleted document gives, so only the
 * re-ask — which restarts the provider — separates them. Sleeps, so never on the main thread.
 *
 * @param probe one reachability question, asked at most twice
 * @return the probe's answer, an unconfirmed [SourceReachability.GONE] downgraded to UNKNOWN
 */
internal fun confirmedGone(probe: () -> SourceReachability): SourceReachability {
    val first = probe()
    if (first != SourceReachability.GONE) return first
    try {
        Thread.sleep(GONE_CONFIRM_DELAY_MS)
    } catch (_: InterruptedException) {
        // Interrupted mid-confirmation: nothing was established, and GONE evicts gigabytes.
        Thread.currentThread().interrupt()
        return SourceReachability.UNKNOWN
    }
    return probe()
}

/** Long enough for a provider killed under memory pressure to be restarted for the second ask. */
private const val GONE_CONFIRM_DELAY_MS = 250L

/**
 * One ask of a document, through whatever [open] the caller reaches it with — an input stream for
 * the metadata reads, a file descriptor for the native loader. Shared by both sources because which
 * exception means gone is a contract they have to agree on; as two copies they drifted once already.
 *
 * @param open opens the document; null without throwing is the provider saying nothing
 * @param onError reports an exception that is evidence neither way
 */
internal fun probeOpenable(
    open: () -> Closeable?,
    onError: (Throwable) -> Unit,
): SourceReachability = try {
    open()?.use { SourceReachability.REACHABLE }
    // No handle and no failure is not the provider saying the document is gone.
        ?: SourceReachability.UNKNOWN
} catch (_: FileNotFoundException) {
    // A deleted document, but also every provider-death path: only the re-ask decides.
    SourceReachability.GONE
} catch (_: SecurityException) {
    // The persisted grant is gone, which is as final as a deletion from here.
    SourceReachability.GONE
} catch (e: Exception) {
    // Anything the resolver did not convert on its way out; not evidence either way.
    onError(e)
    SourceReachability.UNKNOWN
}

/**
 * One ask of a filesystem path. Readability, not just existence: a file the loader cannot open is
 * gone as far as it cares. Shared for the same reason as [probeOpenable].
 *
 * @param onError reports an exception that is evidence neither way
 */
internal fun probeFilePath(path: String, onError: (Throwable) -> Unit): SourceReachability = try {
    if (File(path).let { it.isFile && it.canRead() }) SourceReachability.REACHABLE
    else SourceReachability.GONE
} catch (e: Exception) {
    onError(e)
    SourceReachability.UNKNOWN
}

/**
 * Opens the user's selected model for the native loader, in place and without copying it.
 * An interface so the backend's load path can be exercised without a device.
 */
interface NativeModelSource {

    /**
     * @param modelReference the configured model, as a `content://` URI or a filesystem path
     * @return an open handle the caller owns and must [OpenModelFile.close], or null when the
     *   model cannot be reached at all (deleted, unmounted, or the read grant was revoked)
     */
    fun open(modelReference: String): OpenModelFile?

    /**
     * Whether [modelReference] still resolves to something readable, reading none of it.
     *
     * A resident model cannot answer this itself: the descriptor the loader holds keeps the
     * deleted inode alive, so the mapped pages outlive the file and the model keeps replying from
     * a document the user has thrown away. Only a fresh open off the reference can tell.
     *
     * @return what the probe found; [SourceReachability.UNKNOWN] when the source stayed silent
     */
    fun reachabilityOf(modelReference: String): SourceReachability

    /**
     * Give back the persistable read grant for [modelReference], a model a later selection
     * replaced. Held until this side rather than released when the replacement was picked: every
     * check that actually refuses a model runs here, and with nothing copied any more that grant
     * is the only thing keeping the replaced model readable (ADFA-5253).
     *
     * A no-op for a filesystem path, and for a grant that was never held.
     */
    fun releaseAccess(modelReference: String)
}

/**
 * [NativeModelSource] over the document provider and the filesystem.
 *
 * @param context supplies the resolver holding the picker's persisted read grant
 * @param onError reports a failed open, so a bare "model unavailable" can still be explained
 */
class ContentNativeModelSource(
    private val context: Context,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : NativeModelSource {

    override fun open(modelReference: String): OpenModelFile? =
        if (modelReference.startsWith(CONTENT_SCHEME)) openDocument(modelReference)
        else openFile(modelReference)

    /**
     * Takes the document's descriptor and hands the native loader its procfs path. `"r"` is the
     * only mode asked for, which is all the persisted grant covers. The descriptor need not be a
     * file — a pipe is reported through [OpenModelFile.isSeekable] for the caller to refuse.
     */
    private fun openDocument(uriString: String): OpenModelFile? = try {
        context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
            ?.let { OpenModelFile("$FD_DIR${it.fd}", it.statSize, it) }
    } catch (e: Exception) {
        onError("could not open the selected model $uriString", e)
        null
    }

    /**
     * One binder round trip for a document, one stat for a path — nothing is read, so this is cheap
     * enough to ask before every generation. [SourceReachability.GONE] is only ever an answer the
     * source gave twice (see [confirmedGone]), on either branch; anything less is
     * [SourceReachability.UNKNOWN].
     */
    override fun reachabilityOf(modelReference: String): SourceReachability =
        if (modelReference.startsWith(CONTENT_SCHEME)) documentReachability(modelReference)
        else fileReachability(modelReference)

    /** Confirmed, because one `FileNotFoundException` cannot tell a deletion from a dead provider. */
    private fun documentReachability(uriString: String): SourceReachability = confirmedGone {
        probeOpenable({ context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r") }) {
            onError("could not reach the selected model $uriString", it)
        }
    }

    /**
     * Confirmed like the document branch, so [reachabilityOf]'s contract — a GONE is only ever an
     * answer given twice — holds for every reference, not only the ones that go through a provider.
     */
    private fun fileReachability(path: String): SourceReachability = confirmedGone {
        probeFilePath(path) { onError("could not stat the model file $path", it) }
    }

    override fun releaseAccess(modelReference: String) {
        if (!modelReference.startsWith(CONTENT_SCHEME)) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(modelReference),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Nothing was held, or it was already released: the no-op this documents.
        } catch (e: Exception) {
            onError("could not release the read grant for $modelReference", e)
        }
    }

    private fun openFile(path: String): OpenModelFile? = try {
        File(path).takeIf { it.isFile }?.let { OpenModelFile(it.absolutePath, it.length(), null) }
    } catch (e: Exception) {
        onError("could not open the model file $path", e)
        null
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
        const val FD_DIR = "/proc/self/fd/"
    }
}
