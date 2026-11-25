package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream

/**
 * PNG bytes of the user's icon, per density, for the three places Code on the Go shows it. A map may be
 * empty only if the matching resources are to stay as they are.
 *
 * - [launcher]: the whole icon (48 dp square) for `ic_launcher` and `ic_launcher_round`.
 * - [foreground]: the picture inside the central 66 dp of a 108 dp transparent canvas, for `ic_launcher_foreground`
 *   (the splash screen and the adaptive-icon layer) and `ic_launcher_fg_vector` (the navigation drawer header).
 * - [notification]: a white silhouette on transparent, 24 dp, for `ic_cogo_notification`.
 * - [mark]: the whole picture, [IconSizes.MARK_DP] square, for `cogo_brand_mark`, the logo on the welcome screen,
 *   the main screen, About and the editor sidebar header.
 */
class BrandIcons(
    val launcher: Map<Density, ByteArray>,
    val foreground: Map<Density, ByteArray>,
    val notification: Map<Density, ByteArray>,
    val mark: Map<Density, ByteArray>,
)

/** What the user chose: a new name, a new icon, or both. A null part is left as the host has it. */
class Branding(val appName: String?, val icons: BrandIcons?)

class BrandingException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BrandingReport(
    /** Configs of `app_name` changed. */
    val appNameEntries: Int,
    /** Other strings that held "Code on the Go" and were changed (older hosts; none from Code on the Go with ADFA-7237). */
    val otherStringsReplaced: Int,
    /** Strings that hold the name but carry markup, left alone because rewriting them could garble them. */
    val styledSkipped: Int,
    /** Strings left alone because they are on [BrandingRules.KEEP_LITERAL]. */
    val protectedKept: Int,
    /** Icon entries (one per resource and config) pointed at a new file. */
    val iconEntriesRepointed: Int,
    /** Problems that did not stop the build, for the screen's log. */
    val notes: List<String>,
)

class BrandedResources(val table: ByteArray, val files: Map<String, ByteArray>, val report: BrandingReport)

object BrandingRules {
    /**
     * Strings that must keep naming Code on the Go whatever the copy is called: the credit line written into
     * Git commits (and the setting that describes it), the pointer to the upstream download page and the
     * invitation to contribute to the upstream project, plus the one string referenced from the manifest, which
     * cannot take a format argument. Copied from `KEEP_LITERAL` (`PROTECTED` + `LEFT_LITERAL_XML`) in
     * app/src/test/java/com/itsaky/androidide/resources/AppNameInStringsTest.kt of Code on the Go (ADFA-7237).
     * Keep it equal to that set.
     */
    val KEEP_LITERAL: Set<String> = setOf(
        "made_with_code_on_the_go",
        "idepref_git_add_commit_watermark_summary",
        "download_codeonthego_message",
        "summary_contribute",
        "permdesc_bind_logger_service",
    )

    /**
     * "Code on the Go" in any capitalisation with whitespace (including a no-break space) between the words.
     * Words must be separated: that leaves "CodeOnTheGo" in URLs and package names alone.
     */
    val NAME: Regex = Regex("code[\\s\\u00A0]+on[\\s\\u00A0]+the[\\s\\u00A0]+go(?!\\p{L})", RegexOption.IGNORE_CASE)

    const val APP_NAME_KEY = "app_name"

    /** Directory inside the APK for the new icon files. */
    const val ICON_DIR = "res/custom-ide-builder"

    /**
     * The `ic_launcher_fg_vector` name is the foreground used in the navigation drawer header. `cogo_brand_mark`
     * is a vector in Code on the Go (one entry, no density) that the app's own screens show as its logo.
     */
    enum class IconRole(val resources: Set<String>, val fileStem: String) {
        LAUNCHER(setOf("ic_launcher", "ic_launcher_round"), "launcher"),
        FOREGROUND(setOf("ic_launcher_foreground", "ic_launcher_fg_vector"), "foreground"),
        NOTIFICATION(setOf("ic_cogo_notification"), "notification"),
        MARK(setOf("cogo_brand_mark"), "mark"),
    }

    /** Resource types that hold those icons in Code on the Go: `drawable` (the manifest icon, splash, notification, logo) and `mipmap`. */
    val ICON_TYPES = listOf("drawable", "mipmap")
}

/**
 * Edits the host's compiled resource table (`resources.arsc`): the app name, and the files the icon
 * resources of [BrandingRules.IconRole] resolve to. Resource IDs are untouched, so the manifest, the layouts and the code keep
 * working with no change. The table is read and rebuilt with ARSCLib; nothing is parsed by hand.
 */
object ResourceBranding {
    /** `RES_TABLE_TYPE`, the first chunk type of a resources.arsc. */
    private const val RES_TABLE_TYPE = 0x0002

    /** [checkEdit] is [verify]; a parameter only so a test can see that the check runs on the bytes returned. */
    fun apply(
        arsc: ByteArray,
        branding: Branding,
        checkEdit: (ByteArray, Map<String, String>, Branding, Map<String, ByteArray>) -> Unit = ::verify,
    ): BrandedResources {
        // ARSCLib trusts the sizes in the header and allocates by them, so a file that is not a table is refused first.
        val header = java.nio.ByteBuffer.wrap(arsc).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (arsc.size < 12 || header.getShort(0).toInt() != RES_TABLE_TYPE || header.getInt(4) != arsc.size) {
            throw BrandingException("The app's resource table is not in a format this builder knows.")
        }
        val table =
            try {
                TableBlock.load(ByteArrayInputStream(arsc))
            } catch (e: Exception) {
                throw BrandingException("The app's resource table could not be read: ${e.message ?: e.javaClass.simpleName}", e)
            }
        val before = fingerprint(table)
        val notes = ArrayList<String>()
        var appNameEntries = 0
        var otherReplaced = 0
        var styled = 0
        var kept = 0
        var repointed = 0
        val files = LinkedHashMap<String, ByteArray>()

        for (pkg in table) {
            val newName = branding.appName
            if (newName != null) {
                val strings = pkg.getResources("string")
                while (strings.hasNext()) {
                    val resource = strings.next()
                    val key = resource.name
                    for (entry in resource) {
                        val value = entry.valueAsString
                        if (key == BrandingRules.APP_NAME_KEY) {
                            if (entry.valueType != ValueType.STRING || value == null) {
                                notes.add("app_name [${entry.resConfig.qualifiers}] is not plain text, left as it is")
                            } else {
                                entry.setValueAsString(newName)
                                appNameEntries++
                            }
                            continue
                        }
                        if (value == null || entry.valueType != ValueType.STRING) continue
                        if (!BrandingRules.NAME.containsMatchIn(value)) continue
                        if (key in BrandingRules.KEEP_LITERAL) {
                            kept++
                            continue
                        }
                        if (entry.resValue.dataAsPoolString?.hasStyle() == true) {
                            styled++
                            continue
                        }
                        entry.setValueAsString(BrandingRules.NAME.replace(value) { newName })
                        otherReplaced++
                    }
                }
            }
            val icons = branding.icons
            if (icons != null) repointed += repointIcons(pkg, icons, files, notes)
        }
        if (branding.appName != null && appNameEntries == 0) {
            throw BrandingException("The app's resource table has no app_name string, so it cannot be renamed.")
        }
        table.refreshFull()
        val bytes = table.bytes
        checkEdit(bytes, before, branding, files)
        return BrandedResources(bytes, files, BrandingReport(appNameEntries, otherReplaced, styled, kept, repointed, notes))
    }

    private fun repointIcons(pkg: PackageBlock, icons: BrandIcons, files: MutableMap<String, ByteArray>, notes: MutableList<String>): Int {
        var count = 0
        for (role in BrandingRules.IconRole.values()) {
            val byDensity =
                when (role) {
                    BrandingRules.IconRole.LAUNCHER -> icons.launcher
                    BrandingRules.IconRole.FOREGROUND -> icons.foreground
                    BrandingRules.IconRole.NOTIFICATION -> icons.notification
                    BrandingRules.IconRole.MARK -> icons.mark
                }
            if (byDensity.isEmpty()) continue
            for (type in BrandingRules.ICON_TYPES) {
                val resources = pkg.getResources(type)
                while (resources.hasNext()) {
                    val resource = resources.next()
                    if (resource.name !in role.resources) continue
                    for (entry in resource) {
                        val where = "$type/${resource.name} [${entry.resConfig.qualifiers}]"
                        if (entry.valueType != ValueType.STRING || entry.valueAsString?.startsWith("res/") != true) {
                            // An alias such as @mipmap/ic_launcher resolves to an entry changed here anyway.
                            notes.add("$where is not a file, left as it is")
                            continue
                        }
                        val density = Density.nearest(entry.resConfig.densityValue, byDensity.keys) ?: continue
                        val path = "${BrandingRules.ICON_DIR}/${role.fileStem}-${density.qualifier}.png"
                        files.getOrPut(path) { byDensity.getValue(density) }
                        entry.setValueAsString(path)
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Every entry's identity and, for a plain number, colour, boolean or reference, its value: what the edit
     * must leave unchanged. Strings are left out (their pool indexes move), and so are maps (styles, arrays, plurals),
     * which this code never edits.
     */
    internal fun fingerprint(table: TableBlock): Map<String, String> {
        val out = HashMap<String, String>()
        for (pkg in table) {
            val types = pkg.resources
            while (types.hasNext()) {
                val resource = types.next()
                for (entry in resource) {
                    val key = "${pkg.id}:${resource.hexId}:${entry.resConfig.qualifiers}"
                    val type = entry.valueType
                    val simple = entry.resValue
                    out[key] =
                        when {
                            // Maps (styles, arrays, plurals) and empty entries have no single value.
                            type == null || entry.isComplex || simple == null -> "map"
                            type == ValueType.STRING -> "string"
                            else -> "$type:${simple.data}"
                        }
                }
            }
        }
        return out
    }

    /** Reloads what was written and checks the edit did what was asked and nothing else. */
    internal fun verify(bytes: ByteArray, before: Map<String, String>, branding: Branding, files: Map<String, ByteArray>) {
        val reloaded =
            try {
                TableBlock.load(ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                throw BrandingException("The edited resource table cannot be read back: ${e.message ?: e.javaClass.simpleName}", e)
            }
        val after = fingerprint(reloaded)
        if (after != before) {
            val gone = before.keys - after.keys
            val changed = before.filter { it.key in after && after[it.key] != it.value }.keys
            throw BrandingException("Editing the resource table changed more than intended (${gone.size} entries lost, ${changed.size} altered).")
        }
        val name = branding.appName
        for (pkg in reloaded) {
            if (name != null) {
                val entries = pkg.getEntries("string", BrandingRules.APP_NAME_KEY)
                while (entries.hasNext()) {
                    val e = entries.next()
                    if (e.valueType == ValueType.STRING && e.valueAsString != name) throw BrandingException("app_name was not written back as given.")
                }
            }
            for (type in BrandingRules.ICON_TYPES) {
                val resources = pkg.getResources(type)
                while (resources.hasNext()) {
                    for (e in resources.next()) {
                        val v = e.valueAsString
                        if (e.valueType == ValueType.STRING && v != null && v.startsWith(BrandingRules.ICON_DIR + "/") && v !in files) {
                            throw BrandingException("An icon points at $v, which is not among the files to add.")
                        }
                    }
                }
            }
        }
    }
}
