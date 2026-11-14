package org.appdevforall.shareyourapps

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A JSON object with nothing after it. org.json's own `JSONObject(text)` — on Android and the
 * JVM alike — stops at the closing brace and silently ignores anything after it, so `{}ffg`
 * would pass as `{}`.
 */
fun strictJsonObject(text: String): JSONObject {
    val t = JSONTokener(text)
    val v = t.nextValue()
    if (v !is JSONObject) throw JSONException("it must be a JSON object, in { }")
    if (t.nextClean() != 0.toChar()) throw JSONException("there's unexpected text after the closing }")
    return v
}

class CatalogException(message: String) : Exception(message)

/** What loading `catalog.json` found: the catalog, and a problem to show the owner, if any. */
class CatalogLoad(val catalog: Catalog?, val problem: String?)

/**
 * Loads [file]. A file that can't be read is renamed `catalog.json.bad`, replacing any earlier
 * one, before the owner starts again with an empty catalog — so the next save can't overwrite
 * it, and it can still be recovered by hand. [catalog] null means "start empty".
 */
fun loadCatalogFile(file: java.io.File): CatalogLoad {
    if (!file.exists()) return CatalogLoad(null, null)
    val why = try {
        return CatalogLoad(Catalog.parse(file.readText()), null)
    } catch (e: Exception) { e.message ?: e.toString() }
    val bad = java.io.File(file.parentFile, file.name + ".bad")
    bad.delete()
    val kept = file.renameTo(bad)
    return CatalogLoad(null,
        if (kept) "catalog.json couldn't be read, so this is a new, empty catalog. The unreadable file is kept as catalog.json.bad, beside it, in case it can be fixed: $why"
        else "catalog.json couldn't be read, so this is a new, empty catalog: $why")
}

data class Product(
    val name: String,
    val project: String?,      // relative to shared storage, e.g. CodeOnTheGoProjects/MyApp
    val apk: String?,
    val apkPublished: Boolean,
    val sourceZipPublished: Boolean,
    val carousel: List<String>,
    val descriptionHtml: String?,
    val dateAdded: String?,
    val ordinal: Int,
    /** The product's own Extra JSON: owner-defined, with documented keys whatsNew, license, author and website. */
    val extra: JSONObject = JSONObject(),
    /** For each picture or video in [carousel], by its path: what it shows, and a video's captions. */
    val mediaText: Map<String, MediaText> = emptyMap(),
) {
    val published: Boolean get() = (apkPublished && apk != null) || sourceZipPublished

    /** Why publishing leaves this product out — it has nothing to download — or null if it's included. */
    val leftOutReason: String? get() = when {
        published -> null
        apk == null -> "has no APK chosen, and Publish the source isn't ticked"
        else -> "has Publish the APK and Publish the source both unticked"
    }
}

/**
 * What a picture or video shows, for buyers who can't see it, and a video's captions: a WebVTT
 * file, relative to shared storage. Either may be missing.
 */
data class MediaText(val description: String? = null, val captions: String? = null) {
    val isEmpty: Boolean get() = description.isNullOrBlank() && captions == null
}

/** catalog.json: the whole catalog, in the plugin's private storage. */
data class Catalog(
    val storeDir: String?,     // the Store directory currently being served, or null
    val storeName: String,
    val storeDescriptionHtml: String?,
    val headerImage: String?,
    val pin: String?,
    val templateOverride: String?,
    val extra: JSONObject,
    val products: List<Product>,
    /**
     * A Store directory handed to the server whose publish hasn't finished. If Code On The Go's
     * process dies between the server switching and [storeDir] being saved, this is the one the
     * server may really be serving, so it's never removed as a leftover.
     */
    val pendingDir: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("storeDir", storeDir ?: JSONObject.NULL)
        put("pendingDir", pendingDir ?: JSONObject.NULL)
        put("storeName", storeName)
        put("storeDescriptionHtml", storeDescriptionHtml ?: JSONObject.NULL)
        put("headerImage", headerImage ?: JSONObject.NULL)
        put("pin", pin ?: JSONObject.NULL)
        put("templateOverride", templateOverride ?: JSONObject.NULL)
        put("extra", extra)
        put("products", JSONArray().apply {
            for (p in products) put(JSONObject().apply {
                put("name", p.name)
                put("project", p.project ?: JSONObject.NULL)
                put("apk", p.apk ?: JSONObject.NULL)
                put("apkPublished", p.apkPublished)
                put("sourceZipPublished", p.sourceZipPublished)
                put("carousel", JSONArray(p.carousel))
                put("descriptionHtml", p.descriptionHtml ?: JSONObject.NULL)
                put("dateAdded", p.dateAdded ?: JSONObject.NULL)
                put("ordinal", p.ordinal)
                put("extra", p.extra)
                if (p.mediaText.isNotEmpty()) put("mediaText", JSONObject().apply {
                    p.mediaText.forEach { (path, t) -> put(path, JSONObject().apply {
                        t.description?.let { put("description", it) }; t.captions?.let { put("captions", it) } }) } })
            })
        })
    }

    companion object {
        private val PIN = Regex("[0-9]{1,6}")

        fun parse(text: String): Catalog {
            val o = try { strictJsonObject(text) } catch (e: Exception) {
                throw CatalogException("catalog.json isn't valid JSON: ${e.message}")
            }
            val extra = when {
                !o.has("extra") || o.isNull("extra") -> JSONObject()
                o.get("extra") is JSONObject -> o.getJSONObject("extra")
                else -> throw CatalogException("\"extra\" must be a JSON object")
            }
            val pin = str(o, "pin")?.takeIf { it.isNotEmpty() }
            if (pin != null && !PIN.matches(pin)) throw CatalogException("\"pin\" must be up to 6 digits")
            val arr = if (o.has("products") && !o.isNull("products")) o.getJSONArray("products") else JSONArray()
            val products = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val carousel = if (p.has("carousel") && !p.isNull("carousel")) p.getJSONArray("carousel") else JSONArray()
                Product(
                    name = str(p, "name") ?: throw CatalogException("product ${i + 1} has no \"name\""),
                    project = str(p, "project"),
                    apk = str(p, "apk"),
                    apkPublished = p.optBoolean("apkPublished", false),
                    sourceZipPublished = p.optBoolean("sourceZipPublished", false),
                    carousel = (0 until carousel.length()).map { carousel.getString(it) },
                    descriptionHtml = str(p, "descriptionHtml"),
                    dateAdded = str(p, "dateAdded"),
                    ordinal = p.optInt("ordinal", i + 1),
                    extra = p.optJSONObject("extra") ?: JSONObject(),
                    mediaText = p.optJSONObject("mediaText")?.let { m -> m.keys().asSequence().associateWith { k ->
                        val t = m.optJSONObject(k) ?: JSONObject()
                        MediaText(str(t, "description"), str(t, "captions")) } } ?: emptyMap(),
                )
            }
            return Catalog(
                storeDir = str(o, "storeDir"),
                pendingDir = str(o, "pendingDir"),
                storeName = str(o, "storeName") ?: "Share Your Apps",
                storeDescriptionHtml = str(o, "storeDescriptionHtml"),
                headerImage = str(o, "headerImage"),
                pin = pin,
                templateOverride = str(o, "templateOverride"),
                extra = extra,
                products = products,
            )
        }

        /** A string field, or null when missing or JSON null (org.json differs between Android and the JVM here). */
        private fun str(o: JSONObject, key: String): String? =
            if (!o.has(key) || o.isNull(key)) null else o.get(key).toString()
    }
}

/** A file's modification time and size, to notice when something else has changed it. */
data class FileStamp(val modified: Long, val length: Long) {
    companion object {
        fun of(f: java.io.File): FileStamp? = if (f.isFile) FileStamp(f.lastModified(), f.length()) else null
    }
}

enum class Reload { NONE, RELOAD, CONFLICT }

/**
 * Whether the catalog should be reloaded from catalog.json: [loaded] is the file as it was at the
 * last load or save, [current] as it is now, and [unsaved] whether there are edits not yet saved.
 * An unchanged file needs nothing; a changed one is reloaded — unless there are unsaved edits,
 * which are kept rather than lost, and reported as a conflict.
 */
fun reloadDecision(loaded: FileStamp?, current: FileStamp?, unsaved: Boolean): Reload = when {
    loaded == current -> Reload.NONE
    unsaved -> Reload.CONFLICT
    else -> Reload.RELOAD
}
