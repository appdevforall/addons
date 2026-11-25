package org.appdevforall.customidebuilder.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** A zip this code cannot read or write: encrypted, ZIP64, damaged or duplicated entries. */
class ZipFormatException(message: String) : IOException(message)

/**
 * One central-directory record of a zip. Everything the writer needs to copy the entry without
 * touching its compressed bytes: [nameBytes] stay exactly as stored.
 */
class RawZipEntry(
    val nameBytes: ByteArray,
    val versionMadeBy: Int,
    val flags: Int,
    val method: Int,
    val dosTime: Int,
    val dosDate: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val internalAttributes: Int,
    val externalAttributes: Long,
    val localHeaderOffset: Long,
) {
    val name: String = String(nameBytes, Charsets.UTF_8)
    val isDirectory: Boolean get() = name.endsWith("/")
    val isStored: Boolean get() = method == METHOD_STORED
}

const val METHOD_STORED = 0

private const val SIG_LOCAL = 0x04034b50L
private const val SIG_CENTRAL = 0x02014b50L
private const val SIG_EOCD = 0x06054b50L
private const val SIG_ZIP64_LOCATOR = 0x07064b50L
private const val FLAG_ENCRYPTED = 0x0001
private const val FLAG_DATA_DESCRIPTOR = 0x0008

/**
 * Reads a zip's central directory and hands out entries' compressed bytes as stored. The file is
 * never inflated and the APK Signing Block between the entries and the central directory is
 * simply never read, which is why a signed APK can be reused as input.
 */
class RawZipFile(private val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val entries: List<RawZipEntry>

    init {
        try {
            entries = readCentralDirectory()
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    private fun readCentralDirectory(): List<RawZipEntry> {
        val length = raf.length()
        if (length < 22) throw ZipFormatException("${file.name} is too small to be a zip file")
        val tailSize = minOf(length, 22L + 0xFFFF).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(length - tailSize)
        raf.readFully(tail)
        var eocd = -1
        for (i in tailSize - 22 downTo 0) {
            if (u32(tail, i) == SIG_EOCD && i + 22 + u16(tail, i + 20) == tailSize) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw ZipFormatException("${file.name} has no zip end record")
        if (eocd >= 20 && u32(tail, eocd - 20) == SIG_ZIP64_LOCATOR) {
            throw ZipFormatException("${file.name} is a ZIP64 archive, which is not supported")
        }
        val disk = u16(tail, eocd + 4)
        val cdDisk = u16(tail, eocd + 6)
        val count = u16(tail, eocd + 10)
        val cdSize = u32(tail, eocd + 12)
        val cdOffset = u32(tail, eocd + 16)
        if (disk != 0 || cdDisk != 0) throw ZipFormatException("${file.name} is a multi-part zip")
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw ZipFormatException("${file.name} is a ZIP64 archive, which is not supported")
        }
        if (cdOffset + cdSize > length) throw ZipFormatException("${file.name} has a damaged central directory")
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)

        val result = ArrayList<RawZipEntry>(count)
        val seen = HashSet<String>()
        var p = 0
        repeat(count) {
            if (p + 46 > cd.size || u32(cd, p) != SIG_CENTRAL) {
                throw ZipFormatException("${file.name} has a damaged central directory")
            }
            val flags = u16(cd, p + 8)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            val compressed = u32(cd, p + 20)
            val size = u32(cd, p + 24)
            val offset = u32(cd, p + 42)
            if (p + 46 + nameLen + extraLen + commentLen > cd.size) {
                throw ZipFormatException("${file.name} has a damaged central directory")
            }
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
            val name = String(nameBytes, Charsets.UTF_8)
            if (flags and FLAG_ENCRYPTED != 0) throw ZipFormatException("$name is encrypted, which is not supported")
            if (compressed == 0xFFFFFFFFL || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
                throw ZipFormatException("$name needs ZIP64, which is not supported")
            }
            if (!seen.add(name)) throw ZipFormatException("${file.name} holds $name twice")
            result.add(
                RawZipEntry(
                    nameBytes = nameBytes,
                    versionMadeBy = u16(cd, p + 4),
                    flags = flags,
                    method = u16(cd, p + 10),
                    dosTime = u16(cd, p + 12),
                    dosDate = u16(cd, p + 14),
                    crc = u32(cd, p + 16),
                    compressedSize = compressed,
                    size = size,
                    internalAttributes = u16(cd, p + 36),
                    externalAttributes = u32(cd, p + 38),
                    localHeaderOffset = offset,
                ),
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    /** Where [entry]'s data starts, read from its local header (whose extra field may differ from the central one). */
    fun dataOffset(entry: RawZipEntry): Long {
        val header = ByteArray(30)
        raf.seek(entry.localHeaderOffset)
        try {
            raf.readFully(header)
        } catch (e: java.io.EOFException) {
            throw ZipFormatException("${entry.name} has a damaged local header")
        }
        if (u32(header, 0) != SIG_LOCAL) throw ZipFormatException("${entry.name} has a damaged local header")
        val start = entry.localHeaderOffset + 30 + u16(header, 26) + u16(header, 28)
        if (start + entry.compressedSize > raf.length()) throw ZipFormatException("${entry.name} runs past the end of the file")
        return start
    }

    /** Copies [entry]'s compressed bytes, unchanged, to [out]. */
    fun copyRaw(entry: RawZipEntry, out: OutputStream, onBytes: (Int) -> Unit = {}) {
        raf.seek(dataOffset(entry))
        val buffer = ByteArray(64 * 1024)
        var left = entry.compressedSize
        while (left > 0) {
            val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw ZipFormatException("${entry.name} runs past the end of the file")
            out.write(buffer, 0, n)
            onBytes(n)
            left -= n
        }
    }

    override fun close() = raf.close()
}

/** What zipalign requires of an entry whose data is stored uncompressed. */
object ZipAlignment {
    const val DEFAULT = 4
    const val SHARED_LIBRARY = 16384

    fun requiredFor(name: String): Int = if (name.endsWith(".so")) SHARED_LIBRARY else DEFAULT

    /** Stored entries of [apk] whose data is not where [requiredFor] says it should start. */
    fun misaligned(apk: File): List<String> =
        RawZipFile(apk).use { zip ->
            zip.entries
                .filter { it.isStored && !it.isDirectory }
                .filter { zip.dataOffset(it) % requiredFor(it.name) != 0L }
                .map { it.name }
        }
}

/**
 * A minimal zip writer that can emit entries whose data is already compressed, which
 * `java.util.zip.ZipOutputStream` cannot. No ZIP64 (an APK is far below 4 GiB and 65535 entries),
 * no data descriptors and no archive comment. Stored entries are padded through the local header's
 * extra field so their data starts where [ZipAlignment.requiredFor] says.
 */
class RawZipWriter(output: OutputStream) : Closeable {
    private class Written(val entry: RawZipEntry, val flags: Int, val offset: Long)

    private val out = BufferedOutputStream(output, 64 * 1024)
    private val written = ArrayList<Written>()
    private val names = HashSet<String>()
    private var offset = 0L

    /**
     * Writes [source] with its method, CRC, sizes and timestamps as they are. [copyData] must write
     * exactly `source.compressedSize` bytes. The data-descriptor flag is cleared because the sizes
     * go in the header.
     */
    fun addRaw(source: RawZipEntry, copyData: (OutputStream) -> Unit) {
        val flags = source.flags and FLAG_DATA_DESCRIPTOR.inv()
        begin(source, flags, copyData)
    }

    /** Writes a new stored entry of [size] bytes with the given [crc], dated 1 January 1980. */
    fun addStored(name: String, size: Long, crc: Long, writeData: (OutputStream) -> Unit) {
        val entry = RawZipEntry(
            nameBytes = name.toByteArray(Charsets.UTF_8),
            versionMadeBy = 20,
            flags = 0x0800,
            method = METHOD_STORED,
            dosTime = 0,
            dosDate = DOS_1980,
            crc = crc,
            compressedSize = size,
            size = size,
            internalAttributes = 0,
            externalAttributes = 0,
            localHeaderOffset = 0,
        )
        begin(entry, entry.flags, writeData)
    }

    private fun begin(entry: RawZipEntry, flags: Int, writeData: (OutputStream) -> Unit) {
        if (!names.add(entry.name)) throw ZipFormatException("${entry.name} would be written twice")
        if (written.size >= 0xFFFE) throw ZipFormatException("too many zip entries")
        requireZip32(entry.compressedSize, "${entry.name}'s size")
        requireZip32(entry.size, "${entry.name}'s size")
        val padding =
            if (entry.isStored && !entry.isDirectory) {
                val align = ZipAlignment.requiredFor(entry.name)
                ((align - (offset + 30 + entry.nameBytes.size) % align) % align).toInt()
            } else {
                0
            }
        val headerOffset = offset
        u32(SIG_LOCAL)
        u16(20)
        u16(flags)
        u16(entry.method)
        u16(entry.dosTime)
        u16(entry.dosDate)
        u32(entry.crc)
        u32(entry.compressedSize)
        u32(entry.size)
        u16(entry.nameBytes.size)
        u16(padding)
        raw(entry.nameBytes)
        raw(ByteArray(padding))
        checkRange()
        val dataStart = offset
        writeData(CountingSink())
        if (offset - dataStart != entry.compressedSize) {
            throw ZipFormatException("${entry.name}: wrote ${offset - dataStart} bytes where ${entry.compressedSize} were expected")
        }
        written.add(Written(entry, flags, headerOffset))
    }

    /**
     * Appends the central directory and the end record. The output stream is closed, also when this
     * throws; the error that stopped the writing is the one that is thrown.
     */
    override fun close() {
        try {
            finish()
        } catch (e: Throwable) {
            try {
                out.close()
            } catch (closing: Throwable) {
                e.addSuppressed(closing)
            }
            throw e
        }
        out.close()
    }

    private fun finish() {
        val cdStart = offset
        requireZip32(cdStart, "the central directory's offset")
        for (w in written) {
            val e = w.entry
            u32(SIG_CENTRAL)
            u16(e.versionMadeBy)
            u16(20)
            u16(w.flags)
            u16(e.method)
            u16(e.dosTime)
            u16(e.dosDate)
            u32(e.crc)
            u32(e.compressedSize)
            u32(e.size)
            u16(e.nameBytes.size)
            u16(0)
            u16(0)
            u16(0)
            u16(e.internalAttributes)
            u32(e.externalAttributes)
            u32(w.offset)
            raw(e.nameBytes)
        }
        val cdSize = offset - cdStart
        requireZip32(cdSize, "the central directory's size")
        checkRange()
        u32(SIG_EOCD)
        u16(0)
        u16(0)
        u16(written.size)
        u16(written.size)
        u32(cdSize)
        u32(cdStart)
        u16(0)
    }

    private inner class CountingSink : OutputStream() {
        override fun write(b: Int) {
            out.write(b)
            offset++
            checkRange()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            offset += len
            checkRange()
        }
    }

    /** The header, the padding and the data must all end below the reserved 0xFFFFFFFF, or later offsets no longer fit. */
    private fun checkRange() = requireZip32(offset, "the zip's length")

    private fun u16(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        offset += 2
    }

    private fun u32(v: Long) {
        for (shift in 0..24 step 8) out.write(((v ushr shift) and 0xFF).toInt())
        offset += 4
    }

    private fun raw(bytes: ByteArray) {
        out.write(bytes)
        offset += bytes.size
    }

    private companion object {
        const val DOS_1980 = 0x0021
    }
}

private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

private fun u32(b: ByteArray, i: Int): Long = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

/** A file's size, CRC-32 and SHA-256 (lowercase hex). */
data class FileDigest(val size: Long, val crc: Long, val sha256: String)

/** Reads [file] once, reporting each chunk, and returns its [FileDigest]. */
fun digestOf(file: File, onBytes: (Int) -> Unit = {}): FileDigest {
    val crc = java.util.zip.CRC32()
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    var size = 0L
    file.inputStream().use { input -> pump(input) { buf, n -> crc.update(buf, 0, n); sha.update(buf, 0, n); size += n; onBytes(n) } }
    return FileDigest(size, crc.value, sha.digest().joinToString("") { "%02x".format(it) })
}

/** A ZIP32 field holds 0 to 0xFFFFFFFE; 0xFFFFFFFF means "see the ZIP64 record". Throws when [value] does not fit. */
internal fun requireZip32(value: Long, what: String) {
    if (value < 0 || value >= 0xFFFFFFFFL) throw ZipFormatException("$what would pass 4 GiB, which needs ZIP64")
}

internal inline fun pump(input: InputStream, each: (ByteArray, Int) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return
        each(buffer, n)
    }
}
