package org.appdevforall.customidebuilder.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Whether the installed Code on the Go can carry the copy's own contact details. A Code on the Go that can has the
 * string [CONTACT_MARKER] (the About page's website link), added together with the other contact strings
 * [ContactBranding] writes; one without it keeps App Dev For All's details in code, where no resource edit reaches.
 */
object HostSupport {
    /** Must equal `plugin.min_ide_version` in src/main/AndroidManifest.xml; HostSupportTest compares the two. */
    const val MIN_IDE_VERSION = "26.48"

    const val CONTACT_MARKER = "about_website_url"

    /** True when [arsc] has a [CONTACT_MARKER] string. Throws [BrandingException] when it is not a resource table. */
    fun hasContactStrings(arsc: ByteArray): Boolean {
        val table = ResourceBranding.loadTable(arsc)
        for (pkg in table) {
            val resource = pkg.getResource("string", CONTACT_MARKER) ?: continue
            if (resource.iterator().hasNext()) return true
        }
        return false
    }

    /** The refusal for a Code on the Go without the contact strings. [hostVersionName] is null when it could not be read. */
    fun tooOldMessage(hostVersionName: String?): String =
        "This needs Code on the Go $MIN_IDE_VERSION or newer, which lets a copy replace App Dev For All's contact details. " +
            "The installed Code on the Go (${hostVersionName ?: "version unknown"}) is too old."

    /** Why the app at [apk] cannot be built with contact details, or null when it can. Reads only its resource table. */
    fun problem(apk: File, hostVersionName: String?): String? {
        val arsc =
            try {
                ZipFile(apk).use { z ->
                    val e = z.getEntry(ApkRewriter.RESOURCE_TABLE) ?: return "The app has no ${ApkRewriter.RESOURCE_TABLE}, so its contact details cannot be changed."
                    z.getInputStream(e).use { it.readBytes() }
                }
            } catch (e: java.io.IOException) {
                return "The installed app's APK could not be read: ${e.message ?: e.javaClass.simpleName}"
            }
        return try {
            if (hasContactStrings(arsc)) null else tooOldMessage(hostVersionName)
        } catch (e: BrandingException) {
            e.message
        }
    }
}
