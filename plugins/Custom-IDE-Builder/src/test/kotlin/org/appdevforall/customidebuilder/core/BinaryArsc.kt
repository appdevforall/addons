package org.appdevforall.customidebuilder.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a resources.arsc byte by byte, following the platform's ResourceTypes.h rather than ARSCLib's own writer, so
 * the tests can feed ARSCLib a table it did not make: a styled string (bold span), a UTF-8 or UTF-16 string pool,
 * a default and a German config. One package (0x7f), one `string` type.
 */
object BinaryArsc {
    class Str(val key: String, val default: String, val german: String? = null, val boldFrom: Int = -1, val boldTo: Int = -1)

    fun build(strings: List<Str>, utf8: Boolean): ByteArray {
        // Global pool: styled strings must come first, then the rest, then the span tag names.
        val values = ArrayList<String>()
        val styledStrings = strings.filter { it.boldFrom >= 0 }
        val plainStrings = strings.filter { it.boldFrom < 0 }
        val indexOf = HashMap<Pair<String, String>, Int>()
        fun add(owner: String, value: String): Int {
            val i = values.size
            values.add(value)
            indexOf[owner to value] = i
            return i
        }
        for (s in styledStrings) add(s.key + "/d", s.default)
        for (s in plainStrings) add(s.key + "/d", s.default)
        for (s in strings) if (s.german != null) add(s.key + "/de", s.german)
        val boldName = values.size
        values.add("b")
        val spans = styledStrings.map { listOf(intArrayOf(boldName, it.boldFrom, it.boldTo)) }
        val globalPool = pool(values, spans, utf8)

        val typeNames = pool(listOf("string"), emptyList(), utf8)
        val keyNames = pool(strings.map { it.key }, emptyList(), utf8)

        val typeSpec = ByteBuffer.allocate(16 + 4 * strings.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0202); putShort(16); putInt(16 + 4 * strings.size)
            put(1); put(0); putShort(0); putInt(strings.size)
            repeat(strings.size) { putInt(0) }
        }.array()

        fun typeChunk(german: Boolean): ByteArray {
            val present = strings.withIndex().filter { !german || it.value.german != null }
            val entriesStart = 84 + 4 * strings.size
            val entryBytes = ByteBuffer.allocate(16 * present.size).order(ByteOrder.LITTLE_ENDIAN)
            val offsets = IntArray(strings.size) { -1 }
            for ((n, e) in present.withIndex()) {
                offsets[e.index] = n * 16
                val value = if (german) e.value.german!! else e.value.default
                entryBytes.putShort(8); entryBytes.putShort(0); entryBytes.putInt(e.index)
                entryBytes.putShort(8); entryBytes.put(0); entryBytes.put(0x03); entryBytes.putInt(indexOf.getValue((e.value.key + if (german) "/de" else "/d") to value))
            }
            val size = entriesStart + entryBytes.capacity()
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x0201); b.putShort(84); b.putInt(size)
            b.put(1); b.put(0); b.putShort(0); b.putInt(strings.size); b.putInt(entriesStart)
            // ResTable_config, 64 bytes: size, then zeros, with the language in the locale field for German.
            val cfg = ByteArray(64)
            ByteBuffer.wrap(cfg).order(ByteOrder.LITTLE_ENDIAN).putInt(64)
            if (german) { cfg[8] = 'd'.code.toByte(); cfg[9] = 'e'.code.toByte() }
            b.put(cfg)
            for (o in offsets) b.putInt(o)
            b.put(entryBytes.array())
            return b.array()
        }

        val pkgHeader = 288
        val pkgBody = ByteArrayOutputStream().apply {
            write(typeNames); write(keyNames); write(typeSpec); write(typeChunk(false)); write(typeChunk(true))
        }.toByteArray()
        val pkg = ByteBuffer.allocate(pkgHeader + pkgBody.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0200); putShort(pkgHeader.toShort()); putInt(pkgHeader + pkgBody.size)
            putInt(0x7f)
            val name = "org.example.host"
            for (i in 0 until 128) putShort(if (i < name.length) name[i].code.toShort() else 0)
            putInt(pkgHeader); putInt(1); putInt(pkgHeader + typeNames.size); putInt(strings.size); putInt(0)
            put(pkgBody)
        }.array()

        val total = 12 + globalPool.size + pkg.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0002); putShort(12); putInt(total); putInt(1)
            put(globalPool); put(pkg)
        }.array()
    }

    private fun pool(strings: List<String>, spans: List<List<IntArray>>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) {
            offsets.add(data.size())
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                require(s.length < 0x80 && bytes.size < 0x80)
                data.write(s.length); data.write(bytes.size); data.write(bytes); data.write(0)
            } else {
                require(s.length < 0x8000)
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                for (c in s) { data.write(c.code and 0xFF); data.write(c.code shr 8) }
                data.write(0); data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val styleData = ByteArrayOutputStream()
        val styleOffsets = ArrayList<Int>()
        for (list in spans) {
            styleOffsets.add(styleData.size())
            for (sp in list) for (v in sp) for (k in 0 until 4) styleData.write(v ushr (8 * k))
            repeat(4) { styleData.write(0xFF) }
        }
        if (spans.isNotEmpty()) repeat(8) { styleData.write(0xFF) }
        val headerSize = 28
        val stringsStart = headerSize + 4 * strings.size + 4 * spans.size
        val stylesStart = if (spans.isEmpty()) 0 else stringsStart + data.size()
        val size = stringsStart + data.size() + styleData.size()
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0x0001); b.putShort(headerSize.toShort()); b.putInt(size)
        b.putInt(strings.size); b.putInt(spans.size); b.putInt(if (utf8) 0x100 else 0); b.putInt(stringsStart); b.putInt(stylesStart)
        for (o in offsets) b.putInt(o)
        for (o in styleOffsets) b.putInt(o)
        b.put(data.toByteArray()); b.put(styleData.toByteArray())
        return b.array()
    }
}
