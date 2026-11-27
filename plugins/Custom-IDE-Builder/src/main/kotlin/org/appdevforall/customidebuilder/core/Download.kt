package org.appdevforall.customidebuilder.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Where bytes come from. The real one is [HttpFetcher]; tests pass a fake. */
fun interface Fetcher {
    fun open(url: String): InputStream
}

/** The only network access this plugin makes: HTTPS to the addons host, nothing else. */
object DownloadPolicy {
    fun isAllowed(url: String, host: String = Catalog.HOST): Boolean =
        try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(host, ignoreCase = true) && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)
        } catch (e: java.net.URISyntaxException) {
            false
        }
}

class HttpFetcher(
    private val host: String = Catalog.HOST,
    private val timeoutMs: Int = 20_000,
) : Fetcher {
    /** Follows up to three redirects, each of which must pass [DownloadPolicy] too. */
    override fun open(url: String): InputStream {
        var current = url
        repeat(4) {
            if (!DownloadPolicy.isAllowed(current, host)) throw DownloadException("Refusing to download from $current: only https://$host is allowed.")
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "Custom-IDE-Builder")
            when (val code = connection.responseCode) {
                200 -> return connection.inputStream
                301, 302, 303, 307, 308 -> {
                    val next = connection.getHeaderField("Location") ?: throw DownloadException("Redirect without a location from $current")
                    connection.disconnect()
                    current = resolveRedirect(current, next)
                }
                else -> {
                    connection.disconnect()
                    throw DownloadException("The server answered $code for $current")
                }
            }
        }
        throw DownloadException("Too many redirects from $url")
    }
}

/** The URL a redirect from [current] to [location] points at. A malformed `Location` is a [DownloadException], not an unchecked one. */
internal fun resolveRedirect(current: String, location: String): String =
    try {
        URI(current).resolve(location).toString()
    } catch (e: IllegalArgumentException) {
        throw DownloadException("Redirect from $current to an address that is not valid", e)
    } catch (e: java.net.URISyntaxException) {
        throw DownloadException("Redirect from $current to an address that is not valid", e)
    }

/** Downloads and verifies catalog add-ons. */
class AddonDownloader(
    private val fetcher: Fetcher,
    /** Free bytes where a file in the given folder would go; a parameter so a test can say a disk is full. */
    private val usableSpace: (File) -> Long,
) {
    /** A separate constructor, not a default, so `AddonDownloader { ... }` still reads as the [Fetcher]. */
    constructor(fetcher: Fetcher) : this(fetcher, { it.usableSpace })

    /** The catalog text, at most [MAX_CATALOG_BYTES]. [checkCancelled] is called between reads and may throw to stop. */
    fun fetchCatalog(url: String = Catalog.URL, checkCancelled: () -> Unit = {}): String =
        fetcher.open(url).use { input ->
            val out = java.io.ByteArrayOutputStream()
            pump(input) { buf, n ->
                checkCancelled()
                out.write(buf, 0, n)
                if (out.size() > MAX_CATALOG_BYTES) throw DownloadException("The catalog is larger than expected.")
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }

    /**
     * Downloads [addon] into [dir] as `<slug>.cgp|cgt` and checks its size and SHA-256 against the
     * catalog; on any mismatch the file is deleted and the call throws. It reads no more than
     * the promised size plus one byte, so a server cannot make it fill the disk, and it refuses
     * before the first byte when the promised size is over [AddonLimits.MAX_BYTES] or [dir] lacks room for it.
     * [checkCancelled] is called between reads and before the check and the rename, and may throw to
     * stop; the partial file is deleted then too.
     */
    fun download(addon: CatalogAddon, dir: File, checkCancelled: () -> Unit = {}, onBytes: (Long) -> Unit = {}): File {
        AddonLimits.sizeProblem(addon.name, addon.size)?.let { throw DownloadException(it) }
        dir.mkdirs()
        AddonLimits.spaceProblem(addon.name, addon.size, usableSpace(dir))?.let {
            dir.delete() // only if empty: the folder this call just made
            throw DownloadException(it)
        }
        val target = File(dir, addon.fileName)
        val partial = File(dir, addon.fileName + ".part")
        try {
            var total = 0L
            fetcher.open(addon.downloadUrl).use { input ->
                partial.outputStream().use { out ->
                    pump(input) { buf, n ->
                        checkCancelled()
                        total += n
                        if (total > addon.size) throw DownloadException("${addon.name}: the download is larger than the catalog says (${addon.size} bytes).")
                        out.write(buf, 0, n)
                        onBytes(total)
                    }
                }
            }
            checkCancelled()
            Catalog.verifyDownload(partial, addon)?.let { throw DownloadException(it) }
            checkCancelled()
            target.delete()
            if (!partial.renameTo(target)) throw DownloadException("${addon.name}: could not save the download.")
            return target
        } finally {
            partial.delete()
        }
    }

    companion object {
        const val MAX_CATALOG_BYTES = 4 * 1024 * 1024
    }
}
