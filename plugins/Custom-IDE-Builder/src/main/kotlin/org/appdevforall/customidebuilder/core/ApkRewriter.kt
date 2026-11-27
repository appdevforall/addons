package org.appdevforall.customidebuilder.core

import java.io.File
import java.io.FileOutputStream

/** What [ApkRewriter.rewrite] did, for the screen and the tests. */
class RewriteReport(
    val copied: Int,
    val dropped: List<String>,
    val added: List<String>,
    val index: String,
)

/**
 * Makes the unsigned APK: every entry of the source APK copied as it is, minus the old add-on
 * bundle and the old signature, plus the chosen add-ons and a new bundle index.
 *
 * Entries are copied without being inflated or deflated, so a 400 MB APK costs one pass of file
 * IO and no CPU to speak of.
 */
object ApkRewriter {
    /** Entries that belong to the build being replaced: its add-ons, their index and its v1 signature. */
    fun isDropped(name: String): Boolean {
        if (isBundledAddon(name)) return true
        if (name == INDEX_APK_ENTRY) return true
        return isV1SignatureFile(name)
    }

    /**
     * A bundled add-on of the host: a file directly in `assets/plugins/` or `assets/templates/` with its
     * kind's extension, not hidden and not `core.cgt`, the rule Code on the Go's build stages them by
     * (`BundledAddonFiles.isAddonName`). Anything else in those folders is the IDE's own: CoGo reads
     * the XML files in `assets/templates/xml/` when it creates layout, menu and drawable files.
     */
    fun isBundledAddon(name: String): Boolean =
        AddonKind.values().any { kind ->
            val prefix = "$ASSETS/${kind.dir}/"
            val file = name.removePrefix(prefix)
            name.startsWith(prefix) && '/' !in file && !file.startsWith(".") &&
                file.endsWith(".${kind.extension}", ignoreCase = true) && !file.equals("core.cgt", ignoreCase = true)
        }

    /** `META-INF/MANIFEST.MF`, the `.SF`, `.RSA`, `.DSA` and `.EC` files and the reserved `SIG-*` names, directly in `META-INF/`. */
    fun isV1SignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val rest = upper.removePrefix("META-INF/")
        if ('/' in rest) return false
        return rest == "MANIFEST.MF" || rest.startsWith("SIG-") || rest.endsWith(".SF") || rest.endsWith(".RSA") || rest.endsWith(".DSA") || rest.endsWith(".EC")
    }

    /**
     * Writes [output] from [source] and [addons]. [onProgress] gets the fraction done, 0 to 1;
     * [checkCancelled] is called between chunks and may throw to stop.
     */
    fun rewrite(
        source: File,
        output: File,
        addons: List<StagedAddon>,
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

        val dropped = ArrayList<String>()
        var copied = 0
        RawZipFile(source).use { zip ->
            val keep = zip.entries.filter { e -> if (isDropped(e.name)) { dropped.add(e.name); false } else true }
            val total = (keep.sumOf { it.compressedSize } + addons.sumOf { digests.getValue(it).size } + indexBytes.size).coerceAtLeast(1)
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
                val crc = java.util.zip.CRC32().apply { update(indexBytes) }.value
                writer.addStored(INDEX_APK_ENTRY, indexBytes.size.toLong(), crc) { sink -> sink.write(indexBytes) }
            }
            // Only now: the writer has finished the central directory, so a failure there is not reported as done.
            onProgress(1.0)
        }
        return RewriteReport(copied, dropped, addons.map { it.apkEntryName } + INDEX_APK_ENTRY, index)
    }
}
