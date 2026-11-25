package org.appdevforall.customidebuilder.core

import java.io.File
import java.io.FileOutputStream

/** What [ApkRewriter.rewrite] did, for the screen and the tests. */
class RewriteReport(
    val copied: Int,
    val dropped: List<String>,
    val added: List<String>,
    val index: String,
    /** What the name and icon edit did; null when no name or icon was chosen. */
    val branding: BrandingReport? = null,
)

/**
 * Makes the unsigned APK: every entry of the source APK copied as it is, minus the old add-on
 * bundle and the old signature, plus the chosen add-ons and a new bundle index.
 *
 * Entries are copied without being inflated or deflated, so a 400 MB APK costs one pass of file
 * IO and no CPU to speak of.
 */
object ApkRewriter {
    const val RESOURCE_TABLE = "resources.arsc"

    /** Entries that belong to the build being replaced: its add-ons, their index and its v1 signature. */
    fun isDropped(name: String): Boolean {
        if (name.startsWith("$ASSETS/${AddonKind.PLUGIN.dir}/") || name.startsWith("$ASSETS/${AddonKind.TEMPLATE.dir}/")) return true
        if (name == INDEX_APK_ENTRY) return true
        return isV1SignatureFile(name)
    }

    /** `META-INF/MANIFEST.MF` and the `.SF`, `.RSA`, `.DSA` and `.EC` files directly in `META-INF/`. */
    fun isV1SignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val rest = upper.removePrefix("META-INF/")
        if ('/' in rest) return false
        return rest == "MANIFEST.MF" || rest.endsWith(".SF") || rest.endsWith(".RSA") || rest.endsWith(".DSA") || rest.endsWith(".EC")
    }

    /**
     * Writes [output] from [source] and [addons]. [onProgress] gets the fraction done, 0 to 1;
     * [checkCancelled] is called between chunks and may throw to stop.
     */
    fun rewrite(
        source: File,
        output: File,
        addons: List<StagedAddon>,
        branding: Branding? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (Double) -> Unit = {},
    ): RewriteReport {
        val clash = addons.groupBy { it.apkEntryName }.filterValues { it.size > 1 }.keys
        if (clash.isNotEmpty()) throw ZipFormatException("two add-ons would be written to ${clash.first()}")

        // Hash first: the index needs every add-on's SHA-256 and the entry needs its CRC before its data.
        val digests = addons.associateWith { digestOf(it.file) { checkCancelled() } }
        val index = BundleIndex.render(
            addons.map { IndexLine(it.kind, it.fileName, digests.getValue(it).size, digests.getValue(it).sha256) },
        )
        val indexBytes = index.toByteArray(Charsets.UTF_8)

        // The resource table is rewritten whole, so it is read (inflated if need be) and edited before the copy starts.
        val branded =
            if (branding == null) {
                null
            } else {
                val arsc =
                    java.util.zip.ZipFile(source).use { z ->
                        val e = z.getEntry(RESOURCE_TABLE) ?: throw BrandingException("The app has no $RESOURCE_TABLE to rename or re-icon.")
                        z.getInputStream(e).use { it.readBytes() }
                    }
                checkCancelled()
                ResourceBranding.apply(arsc, branding)
            }
        val extra = branded?.files.orEmpty()

        val dropped = ArrayList<String>()
        var copied = 0
        RawZipFile(source).use { zip ->
            val keep = zip.entries.filter { e ->
                if (isDropped(e.name)) {
                    dropped.add(e.name)
                    false
                } else {
                    // Replaced below, STORED and aligned.
                    !(branded != null && e.name == RESOURCE_TABLE)
                }
            }
            val clashing = keep.firstOrNull { it.name in extra }
            if (clashing != null) throw ZipFormatException("the app already has ${clashing.name}")
            val total = (
                keep.sumOf { it.compressedSize } + addons.sumOf { digests.getValue(it).size } + indexBytes.size +
                    (branded?.table?.size ?: 0) + extra.values.sumOf { it.size }
                ).coerceAtLeast(1)
            var done = 0L
            val advance = { n: Int -> done += n; checkCancelled(); onProgress(done.toDouble() / total) }
            RawZipWriter(FileOutputStream(output)).use { writer ->
                for (entry in keep) {
                    writer.addRaw(entry) { sink -> zip.copyRaw(entry, sink, advance) }
                    copied++
                }
                for (addon in addons) {
                    val d = digests.getValue(addon)
                    writer.addStored(addon.apkEntryName, d.size, d.crc) { sink ->
                        addon.file.inputStream().use { input -> pump(input) { buf, n -> sink.write(buf, 0, n); advance(n) } }
                    }
                }
                if (branded != null) {
                    // Android 11 and later refuse an app whose resources.arsc is compressed or not 4-byte aligned;
                    // addStored does both (aligned to ZipAlignment.requiredFor).
                    writeStored(writer, RESOURCE_TABLE, branded.table, advance)
                    for ((path, bytes) in extra) writeStored(writer, path, bytes, advance)
                }
                val crc = java.util.zip.CRC32().apply { update(indexBytes) }.value
                writer.addStored(INDEX_APK_ENTRY, indexBytes.size.toLong(), crc) { sink -> sink.write(indexBytes) }
            }
        }
        val added = addons.map { it.apkEntryName } + INDEX_APK_ENTRY + (if (branded != null) listOf(RESOURCE_TABLE) + extra.keys else emptyList())
        return RewriteReport(copied, dropped, added, index, branded?.report)
    }

    private fun writeStored(writer: RawZipWriter, name: String, bytes: ByteArray, advance: (Int) -> Unit) {
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        writer.addStored(name, bytes.size.toLong(), crc) { sink ->
            sink.write(bytes)
            advance(bytes.size)
        }
    }
}
