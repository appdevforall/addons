package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.xml.StyleDocument
import java.io.ByteArrayInputStream

/** A small resource table shaped like Code on the Go's: the names, configs and icon resources the rewrite touches. */
object ArscSupport {
    const val OLD = "Code on the Go"
    const val PKG = "org.example.host"

    fun table(): ByteArray {
        val t = TableBlock()
        val p = t.newPackage(0x7f, PKG)
        for (config in listOf("", "de", "fr", "es-rMX")) {
            p.getOrCreate(config, "string", "app_name").setValueAsString(OLD)
        }
        // The protected string shares its pool item with app_name until app_name changes.
        p.getOrCreate("", "string", "made_with_code_on_the_go").setValueAsString(OLD)
        p.getOrCreate("de", "string", "made_with_code_on_the_go").setValueAsString(OLD)
        p.getOrCreate("", "string", "download_codeonthego_message").setValueAsString("Download Code On The Go from the website")
        p.getOrCreate("", "string", "welcome").setValueAsString("Welcome to Code On The Go!")
        p.getOrCreate("de", "string", "welcome").setValueAsString("Willkommen bei Code on  the Go, dem Editor")
        p.getOrCreate("", "string", "nbsp").setValueAsString("Code\u00A0on\u00A0the\u00A0Go")
        p.getOrCreate("", "string", "dollar").setValueAsString("Use Code on the Go today")
        p.getOrCreate("", "string", "url").setValueAsString("https://github.com/appdevforall/CodeOnTheGo")
        p.getOrCreate("", "string", "plain").setValueAsString("Nothing to see")
        p.getOrCreate("", "string", "gone").setValueAsString("Code on the Gone")
        p.getOrCreate("", "string", "formatted").setValueAsString("About %1\$s")
        p.getOrCreate("", "bool", "flag").setValueAsBoolean(true)
        p.getOrCreate("", "integer", "answer").setValueAsRaw(com.reandroid.arsc.value.ValueType.DEC, 42)

        p.getOrCreate("", "drawable", "ic_launcher").setValueAsString("res/drawable/ic_launcher.xml")
        p.getOrCreate("", "drawable", "ic_launcher_foreground").setValueAsString("res/drawable/ic_launcher_foreground.xml")
        p.getOrCreate("", "drawable", "ic_launcher_fg_vector").setValueAsString("res/drawable/ic_launcher_fg_vector.xml")
        p.getOrCreate("", "drawable", "ic_cogo_notification").setValueAsString("res/drawable/ic_cogo_notification.xml")
        p.getOrCreate("", "drawable", "ic_other").setValueAsString("res/drawable/ic_other.xml")
        // The in-app logo: a vector with one entry and no density, as in Code on the Go.
        p.getOrCreate("", "drawable", "cogo_brand_mark").setValueAsString("res/drawable/cogo_brand_mark.xml")
        for ((cfg, dir) in mapOf("mdpi" to "mipmap-mdpi-v4", "hdpi" to "mipmap-hdpi-v4", "xhdpi" to "mipmap-xhdpi-v4", "xxhdpi" to "mipmap-xxhdpi-v4", "xxxhdpi" to "mipmap-xxxhdpi-v4")) {
            p.getOrCreate(cfg, "mipmap", "ic_launcher").setValueAsString("res/$dir/ic_launcher.png")
            p.getOrCreate(cfg, "mipmap", "ic_launcher_round").setValueAsString("res/$dir/ic_launcher_round.png")
            p.getOrCreate(cfg, "mipmap", "ic_launcher_foreground").setValueAsString("res/$dir/ic_launcher_foreground.png")
        }
        p.getOrCreate("anydpi-v26", "mipmap", "ic_launcher").setValueAsString("res/mipmap-anydpi-v26/ic_launcher.xml")
        p.getOrCreate("anydpi-v26", "mipmap", "ic_launcher_round").setValueAsString("res/mipmap-anydpi-v26/ic_launcher_round.xml")
        t.refreshFull()
        return t.bytes
    }

    /** [table] plus one entry whose text carries markup (a bold word), the way a `<b>` in strings.xml is stored. */
    fun tableWithStyledString(): ByteArray {
        val t = TableBlock.load(ByteArrayInputStream(table()))
        val p = t.iterator().next()
        val styled = StyleDocument.parseStyledString("Welcome to <b>Code on the Go</b> now")
        p.getOrCreate("", "string", "styled").setValueAsString(styled)
        t.refreshFull()
        return t.bytes
    }

    fun load(bytes: ByteArray): TableBlock = TableBlock.load(ByteArrayInputStream(bytes))

    /** `type/name [config]` to the entry's string value, for every string-valued entry of the given types. */
    fun strings(table: TableBlock, vararg types: String): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (p in table) for (type in types) {
            val resources = p.getResources(type)
            while (resources.hasNext()) {
                val r = resources.next()
                for (e in r) out["$type/${r.name} [${e.resConfig.qualifiers.trimStart('-')}]"] = e.valueAsString
            }
        }
        return out
    }

    /** `type/name` to the resource id, which must not change. */
    fun ids(table: TableBlock): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (p in table) {
            val resources = p.resources
            while (resources.hasNext()) {
                val r = resources.next()
                out["${r.type}/${r.name}"] = r.resourceId
            }
        }
        return out
    }

    /** A binary AndroidManifest.xml with the given minSdkVersion written as a number. */
    fun manifest(minSdk: Int?): ByteArray {
        val m = AndroidManifestBlock()
        m.packageName = "org.example.plugin"
        if (minSdk != null) m.setMinSdkVersion(minSdk)
        m.refreshFull()
        return m.bytes
    }

    /** A binary manifest whose minSdkVersion is text, as for a preview release. */
    fun manifestWithCodename(codename: String): ByteArray {
        val m = AndroidManifestBlock()
        m.packageName = "org.example.plugin"
        m.setMinSdkVersion(26)
        val sdk = m.documentElement.getElement("uses-sdk")
        val attribute = sdk.getAttributes().asSequence().first { it.name == "minSdkVersion" }
        attribute.setValueAsString(codename)
        m.refreshFull()
        return m.bytes
    }

    fun png(tag: String): ByteArray = ("PNG-$tag").toByteArray()
}
