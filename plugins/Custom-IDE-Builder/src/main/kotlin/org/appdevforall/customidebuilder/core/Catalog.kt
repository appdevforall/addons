package org.appdevforall.customidebuilder.core

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** An entry of the addons catalog this plugin can bundle: a plugin or a template. */
class CatalogAddon(
    val kind: AddonKind,
    val slug: String,
    val addonId: String,
    val name: String,
    val version: String,
    val summary: String,
    /** `""` when the addon states no minimum. */
    val minAppVersion: String,
    val downloadUrl: String,
    val sha256: String,
    val size: Long,
) {
    /** The name the download is saved under, which also decides its kind: `<slug>.cgp` or `<slug>.cgt`. */
    val fileName: String get() = "$slug.${kind.extension}"
}

class CatalogException(message: String) : Exception(message)

/** The result of [Catalog.parse]: usable entries, and how many were skipped as malformed. */
class ParsedCatalog(val addons: List<CatalogAddon>, val skipped: Int)

/** A Code on the Go release number, `YY.ww`, compared segment by segment as integers. */
object AppVersion {
    private val NUMBER = Regex("\\d+(\\.\\d+)*")

    /** The first dotted number in [text] (so `v26.45-abc123-release` is 26.45), or null if it has none. */
    fun parse(text: String?): List<Int>? =
        text?.let { NUMBER.find(it) }?.value?.split('.')?.map { it.toIntOrNull() ?: return null }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

object Catalog {
    const val URL = "https://addons.appdevforall.org/v2/catalog.json"
    const val HOST = "addons.appdevforall.org"

    private val SLUG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val SHA256 = Regex("[a-f0-9]{64}")

    /**
     * Reads a v2 catalog (site/catalog.v2.schema.json in the addons repository). Snippets and code
     * actions are not bundled and are left out. An entry missing a required field, with a slug
     * that is not a file name or a digest that is not SHA-256, is skipped and counted: the
     * download could not be verified, so it must not be offered.
     */
    fun parse(json: String): ParsedCatalog {
        val root =
            try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw CatalogException("The catalog is not valid JSON.")
            }
        if (root.optInt("schemaVersion", -1) != 2) throw CatalogException("This is not a version 2 catalog.")
        val array = root.optJSONArray("addons") ?: throw CatalogException("The catalog has no addons list.")
        val result = ArrayList<CatalogAddon>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val kind = AddonKind.values().firstOrNull { it.name.equals(o.optString("type"), ignoreCase = true) } ?: continue
            val download = o.optJSONObject("download")
            val slug = o.optString("slug")
            val sha = download?.optString("sha256").orEmpty()
            val size = download?.optLong("size", -1) ?: -1
            val url = download?.optString("url").orEmpty()
            val name = o.optString("name")
            if (download == null || !SLUG.matches(slug) || !SHA256.matches(sha) || size < 0 || url.isEmpty() || name.isEmpty()) {
                skipped++
                continue
            }
            result.add(
                CatalogAddon(
                    kind = kind,
                    slug = slug,
                    addonId = o.optString("addonId"),
                    name = name,
                    version = o.optString("version"),
                    summary = o.optString("summary"),
                    minAppVersion = o.optString("minAppVersion"),
                    downloadUrl = url,
                    sha256 = sha,
                    size = size,
                ),
            )
        }
        return ParsedCatalog(result, skipped)
    }

    /** The entries to offer, and whether [hostVersionName] could be read to decide. */
    class Offer(val addons: List<CatalogAddon>, val hostVersionKnown: Boolean)

    /**
     * Plugins and templates whose `minAppVersion` is at most the host's version, plugins first, each
     * kind in name order. If the host's version cannot be read nothing can be ruled out, so
     * everything is offered and [Offer.hostVersionKnown] is false.
     */
    fun offer(addons: List<CatalogAddon>, hostVersionName: String?): Offer {
        val host = AppVersion.parse(hostVersionName)
        val fits =
            addons.filter { a ->
                val min = AppVersion.parse(a.minAppVersion)
                host == null || min == null || AppVersion.compare(min, host) <= 0
            }
        return Offer(fits.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() })), host != null)
    }

    /** Null when [file] is exactly what the catalog promised, else why not. */
    fun verifyDownload(file: File, addon: CatalogAddon): String? {
        if (file.length() != addon.size) return "${addon.name}: the download is ${file.length()} bytes, the catalog says ${addon.size}"
        val actual = digestOf(file).sha256
        if (actual != addon.sha256) return "${addon.name}: the download does not match the catalog's SHA-256"
        return null
    }
}
