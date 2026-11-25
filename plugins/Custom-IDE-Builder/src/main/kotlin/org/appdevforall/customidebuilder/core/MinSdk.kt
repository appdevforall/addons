package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
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
    private const val MAX_MANIFEST = 4 * 1024 * 1024

    /** Reads the minimum from a compiled (binary) manifest, without the platform's parser. */
    fun fromManifest(bytes: ByteArray): MinSdk {
        val manifest = AndroidManifestBlock.load(ByteArrayInputStream(bytes))
        val number = manifest.minSdkVersion
        if (number != null) return MinSdk.Known(number)
        val sdk = manifest.documentElement?.getElement("uses-sdk") ?: return MinSdk.Unspecified
        val attribute = sdk.getAttributes().asSequence().firstOrNull { it.name == "minSdkVersion" } ?: return MinSdk.Unspecified
        return MinSdk.Unknown(attribute.valueAsString)
    }

    /** Reads it from a .cgp or .apk; null when the file has no readable manifest. */
    fun fromArchive(file: File): MinSdk? =
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(MANIFEST) ?: return null
                if (entry.size > MAX_MANIFEST) return null
                fromManifest(zip.getInputStream(entry).use { it.readBytes() })
            }
        } catch (e: Exception) {
            null
        }
}

/** A plugin the copy would carry on an Android older than the plugin supports. */
sealed class MinSdkWarning(val label: String, val message: String) {
    class TooNew(label: String, val required: Int, val host: Int) :
        MinSdkWarning(
            label,
            "$label needs ${AndroidVersions.name(required)} or newer. This copy of Code on the Go installs on " +
                "${AndroidVersions.name(host)} and newer, so on an older device $label will not work.",
        )

    class Unknown(label: String, val raw: String?) :
        MinSdkWarning(label, "$label asks for an Android version this builder could not read${raw?.let { " ($it)" }.orEmpty()}, so it may not work on every device this copy runs on.")
}

object MinSdkCheck {
    /** One warning for each plugin whose minimum is above [hostMinSdk] or could not be read. A plugin with no minimum never warns. */
    fun warnings(hostMinSdk: Int, plugins: List<Pair<String, MinSdk?>>): List<MinSdkWarning> =
        plugins.mapNotNull { (label, min) ->
            when (min) {
                is MinSdk.Known -> if (min.api > hostMinSdk) MinSdkWarning.TooNew(label, min.api, hostMinSdk) else null
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
