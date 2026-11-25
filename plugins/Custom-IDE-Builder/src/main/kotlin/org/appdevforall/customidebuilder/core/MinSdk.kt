package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/** The lowest Android version a plugin says it runs on. */
sealed class MinSdk {
    class Known(val api: Int) : MinSdk()

    /** `minSdkVersion` is there but is not a number: a preview codename such as "VanillaIceCream", or a reference. */
    class Unknown(val raw: String?) : MinSdk()

    /** No `minSdkVersion`: Android reads that as API 1. */
    object Unspecified : MinSdk()
}

object PluginMinSdk {
    private const val MANIFEST = "AndroidManifest.xml"
    /** A plugin's compiled manifest is a few KiB; anything this large is not one worth reading. */
    const val MAX_MANIFEST = 1024 * 1024

    /** Reads the minimum from a compiled (binary) manifest, without the platform's parser. */
    fun fromManifest(bytes: ByteArray): MinSdk {
        val manifest = AndroidManifestBlock.load(ByteArrayInputStream(bytes))
        val number = manifest.minSdkVersion
        if (number != null) return MinSdk.Known(number)
        val sdk = manifest.documentElement?.getElement("uses-sdk") ?: return MinSdk.Unspecified
        val attribute = sdk.getAttributes().asSequence().firstOrNull { it.name == "minSdkVersion" } ?: return MinSdk.Unspecified
        return MinSdk.Unknown(attribute.valueAsString)
    }

    /**
     * Reads it from a .cgp or .apk; null when the file has no readable manifest. A manifest larger
     * than [MAX_MANIFEST] is [MinSdk.Unknown]. The size the archive claims is not used: the bytes
     * are counted as they are read, so an entry that understates its size cannot fill memory.
     */
    fun fromArchive(file: File): MinSdk? =
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(MANIFEST) ?: return null
                val bytes = zip.getInputStream(entry).use { readCapped(it, MAX_MANIFEST) } ?: return MinSdk.Unknown(null)
                fromManifest(bytes)
            }
        } catch (e: OutOfMemoryError) {
            MinSdk.Unknown(null)
        } catch (e: Exception) {
            null
        }

    /** All of [input], or null once it holds more than [cap] bytes. */
    internal fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > cap) return null
            out.write(buffer, 0, n)
        }
    }
}

/** What the host reads from a plugin's manifest to install it: the ID and the class it starts. */
class PluginIdentity(val packageName: String?, val pluginId: String, val mainClass: String)

/**
 * Reads a plugin's identity from its compiled (binary) manifest, the way Code on the Go's PluginLoader.getPluginMetadata
 * does: `plugin.id` from the application's meta-data, else the package name, and `plugin.main_class`, without which the
 * host does not load the plugin. PackageManager.getPackageArchiveInfo is not used: it refuses an APK whose minSdkVersion
 * is above the device's, so a plugin for a newer Android would be reported as unreadable instead of warned about (F50).
 */
object PluginManifest {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_VALUE = 0x01010024

    /** Null when the manifest has no application, no `plugin.main_class`, or neither a `plugin.id` nor a package name. */
    fun identity(bytes: ByteArray): PluginIdentity? {
        val manifest = AndroidManifestBlock.load(ByteArrayInputStream(bytes))
        val application = manifest.applicationElement ?: return null
        // As in the Bundle the platform fills: the last entry of a name wins, and only text values are strings.
        val meta = HashMap<String, String?>()
        for (element in application.listElements("meta-data")) {
            val name = text(element, ATTR_NAME, "name") ?: continue
            meta[name] = text(element, ATTR_VALUE, "value")
        }
        val mainClass = meta["plugin.main_class"]
        if (mainClass.isNullOrEmpty()) return null
        val packageName = manifest.packageName
        val id = meta["plugin.id"] ?: packageName ?: return null
        return PluginIdentity(packageName, id, mainClass)
    }

    /**
     * Reads it from a .cgp; null when the file is not a zip, has no manifest, or the manifest is larger than
     * [PluginMinSdk.MAX_MANIFEST] or not one ARSCLib can read.
     */
    fun fromArchive(file: File): PluginIdentity? =
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(MANIFEST) ?: return null
                val bytes = zip.getInputStream(entry).use { PluginMinSdk.readCapped(it, PluginMinSdk.MAX_MANIFEST) } ?: return null
                identity(bytes)
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }

    /** The attribute's text, found by its android resource ID or, in a manifest without IDs, by name; null if it is not text. */
    private fun text(element: ResXmlElement, id: Int, name: String): String? {
        val attribute = element.searchAttributeByResourceId(id) ?: element.searchAttributeByName(name) ?: return null
        return if (attribute.valueType == ValueType.STRING) attribute.valueAsString else null
    }
}

/** A plugin the copy would carry on an Android older than the plugin supports. */
sealed class MinSdkWarning(val label: String, val message: String) {
    /** [device] is the Android of the phone the builder runs on, named when it too is older than [required]. */
    class TooNew(label: String, val required: Int, val host: Int, val device: Int? = null) :
        MinSdkWarning(
            label,
            "$label needs ${AndroidVersions.name(required)} or newer. This copy of Code on the Go installs on " +
                "${AndroidVersions.name(host)} and newer, so on a device below ${AndroidVersions.name(required)} " +
                "$label will not work." +
                (if (device != null && device < required) " This device has ${AndroidVersions.name(device)}." else ""),
        )

    class Unknown(label: String, val raw: String?) :
        MinSdkWarning(label, "$label asks for an Android version this builder could not read${raw?.let { " ($it)" }.orEmpty()}, so it may not work on every device this copy runs on.")
}

object MinSdkCheck {
    /**
     * One warning for each plugin whose minimum is above [hostMinSdk] or could not be read. A plugin with no minimum
     * never warns. [deviceApi], the Android of this phone, is only named in the message.
     */
    fun warnings(hostMinSdk: Int, plugins: List<Pair<String, MinSdk?>>, deviceApi: Int? = null): List<MinSdkWarning> =
        plugins.mapNotNull { (label, min) ->
            when (min) {
                is MinSdk.Known -> if (min.api > hostMinSdk) MinSdkWarning.TooNew(label, min.api, hostMinSdk, deviceApi) else null
                is MinSdk.Unknown -> MinSdkWarning.Unknown(label, min.raw)
                MinSdk.Unspecified, null -> null
            }
        }
}

object AndroidVersions {
    private val NAMES = mapOf(
        21 to "Android 5.0", 22 to "Android 5.1", 23 to "Android 6.0", 24 to "Android 7.0", 25 to "Android 7.1",
        26 to "Android 8.0", 27 to "Android 8.1", 28 to "Android 9", 29 to "Android 10", 30 to "Android 11",
        31 to "Android 12", 32 to "Android 12L", 33 to "Android 13", 34 to "Android 14", 35 to "Android 15",
        36 to "Android 16",
    )

    /** `Android 11 (API 30)`; for a level not in the table, `Android API 40`. */
    fun name(api: Int): String = NAMES[api]?.let { "$it (API $api)" } ?: "Android API $api"
}
