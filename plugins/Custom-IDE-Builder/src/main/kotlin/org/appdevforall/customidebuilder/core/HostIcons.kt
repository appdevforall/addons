package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * The raster files (PNG, WebP, JPEG) the host's icon resources resolve to, read out of its APK, for the
 * look-alike check. On Android 8 and later `mipmap/ic_launcher` resolves to the adaptive icon whatever the
 * density, so the legacy PNGs every older launcher shows, and the ones a user would copy out of the APK,
 * can only be reached through the resource table.
 */
object HostIcons {
    /** The resources that draw Code on the Go's icon or logo. */
    val NAMES = setOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground", "cogo_brand_mark")

    private val TYPES = listOf("mipmap", "drawable")
    private val RASTER = listOf(".png", ".webp", ".jpg", ".jpeg")

    /** Code on the Go's table is a few MiB. */
    const val MAX_TABLE = 64 * 1024 * 1024

    /** A launcher PNG is a few KiB; anything larger is not one worth decoding. */
    const val MAX_FILE = 2 * 1024 * 1024

    /** Paths inside the APK, each once, of the raster entries of [NAMES]. A nine-patch is not a picture of the icon and is left out. */
    fun rasterPaths(arsc: ByteArray): List<String> {
        val table = TableBlock.load(ByteArrayInputStream(arsc))
        val out = LinkedHashSet<String>()
        for (pkg in table) for (type in TYPES) {
            val resources = pkg.getResources(type)
            while (resources.hasNext()) {
                val resource = resources.next()
                if (resource.name !in NAMES) continue
                for (entry in resource) {
                    if (entry.valueType != ValueType.STRING) continue
                    val path = entry.valueAsString ?: continue
                    val lower = path.lowercase()
                    if (lower.endsWith(".9.png") || RASTER.none { lower.endsWith(it) }) continue
                    out.add(path)
                }
            }
        }
        return out.toList()
    }

    /** The bytes of each of those files in [apk]; empty when the APK or its table cannot be read. An oversized entry is skipped. */
    fun rasterFiles(apk: File): List<ByteArray> =
        try {
            ZipFile(apk).use { zip ->
                val tableEntry = zip.getEntry("resources.arsc") ?: return emptyList()
                val arsc = zip.getInputStream(tableEntry).use { PluginMinSdk.readCapped(it, MAX_TABLE) } ?: return emptyList()
                rasterPaths(arsc).mapNotNull { path ->
                    val entry = zip.getEntry(path) ?: return@mapNotNull null
                    zip.getInputStream(entry).use { PluginMinSdk.readCapped(it, MAX_FILE) }
                }
            }
        } catch (e: OutOfMemoryError) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
}
