package org.appdevforall.shareyourapps

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import java.util.concurrent.RecursiveTask
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Chooses which project files go into a source zip: everything, minus the always-excluded
 * list, minus whatever the project's .gitignore files exclude. Only regular files and
 * directories are considered: symlinks aren't followed, and named pipes, sockets and device
 * files are skipped — reading a pipe would block the publish indefinitely.
 */
object SourceFiles {

    /** Build output, caches and IDE state (.cg is CodeOnTheGo's), at any depth. */
    private val DENIED_DIRS = setOf(
        ".git", "build", ".gradle", ".idea", ".cxx", ".externalNativeBuild", ".kotlin", "captures", ".cg",
    )
    /** release.properties holds signing passwords in CodeOnTheGo's project templates. */
    private val DENIED_FILES = setOf(
        "local.properties", "keystore.properties", "release.properties", ".DS_Store", "Thumbs.db",
    )
    /** IDE modules, heap dumps, and signing keystores in every common format. */
    private val DENIED_SUFFIXES = listOf(".iml", ".hprof", ".jks", ".keystore", ".p12", ".pfx")

    fun denied(name: String, isDir: Boolean): Boolean =
        if (isDir) name in DENIED_DIRS
        else name in DENIED_FILES || DENIED_SUFFIXES.any { name.endsWith(it) }

    /**
     * Every file to include, in a stable order — the same order a plain depth-first walk gives. Folders are listed in
     * parallel, on [threads] threads: on a phone's shared storage, each file checked waits on Android's storage layer,
     * so several at once is much faster. Each file's size and date are kept from the listing, so nothing asks again.
     */
    fun collect(project: File, threads: Int = 6): List<SourceEntry> {
        val pool = ForkJoinPool(threads)
        try { return pool.invoke(Walk(project, "", emptyList())) } finally { pool.shutdown() }
    }

    private class Walk(val dir: File, val rel: String, val inherited: List<IgnoreRule>) : RecursiveTask<List<SourceEntry>>() {
        override fun compute(): List<SourceEntry> {
            val rules = inherited + Gitignore.load(File(dir, ".gitignore"), rel)
            val children = dir.listFiles()?.sortedBy { it.name } ?: return emptyList()
            // A file's entry, or a subfolder's walk, forked to run in parallel; joined below, in order.
            val parts = ArrayList<Any>(children.size)
            for (child in children) {
                val attrs = try {
                    Files.readAttributes(child.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    continue
                }
                if (!attrs.isRegularFile && !attrs.isDirectory) continue // symlink, pipe, socket, device
                val isDir = attrs.isDirectory
                val path = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (denied(child.name, isDir)) continue
                if (Gitignore.ignored(rules, path, isDir)) continue
                parts += if (isDir) Walk(child, path, rules).also { it.fork() }
                         else SourceEntry(path, child, attrs.size(), attrs.lastModifiedTime().toMillis())
            }
            val out = ArrayList<SourceEntry>()
            for (p in parts) if (p is Walk) out += p.join() else out += p as SourceEntry
            return out
        }
    }

    /**
     * Writes an uncompressed (stored) zip — fast to write, and the server gzips it afterward. Stored entries must
     * declare their size and CRC before their data, so files are read ahead, in parallel on [threads] threads: each
     * small file once, into memory, where its CRC is computed and from where it's written; a large one, over
     * [SMALL] bytes, is still read twice, CRC first, to keep memory small. At most [AHEAD] files, and [AHEAD_BYTES]
     * of small files' contents, are read ahead of the one being written. [added] is told each file's size as it goes
     * in, for the publish's progress. The zip is byte for byte what a one-file-at-a-time writer makes.
     */
    fun writeStoredZip(entries: List<SourceEntry>, zip: File, added: (Long) -> Unit = {}, threads: Int = 6) {
        if (entries.isEmpty()) throw PublishException("the project has no files to put in a source zip")
        class Read(val crc: Long, val bytes: ByteArray?)
        fun crcOf(f: File): Long { val c = CRC32(); val buf = ByteArray(64 * 1024); f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; c.update(buf, 0, n) } }; return c.value }
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val ahead = ArrayDeque<Pair<SourceEntry, Future<Read>>>()
            var next = 0; var aheadBytes = 0L
            fun fill(writing: Int) {
                while (next < entries.size && next - writing < AHEAD && aheadBytes < AHEAD_BYTES) {
                    val e = entries[next++]
                    if (e.size <= SMALL) aheadBytes += e.size
                    ahead.addLast(e to pool.submit<Read> {
                        if (e.size <= SMALL) { val b = e.file.readBytes(); Read(CRC32().apply { update(b) }.value, b) } else Read(crcOf(e.file), null)
                    })
                }
            }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip), 64 * 1024)).use { zos ->
                zos.setMethod(ZipOutputStream.STORED)
                for (k in entries.indices) {
                    fill(k)
                    val (e, f) = ahead.removeFirst()
                    val r = f.get()
                    if (e.size <= SMALL) aheadBytes -= e.size
                    val size = r.bytes?.size?.toLong() ?: e.file.length()
                    zos.putNextEntry(ZipEntry(e.path).apply { method = ZipEntry.STORED; this.size = size; compressedSize = size; crc = r.crc; time = e.modified })
                    if (r.bytes != null) zos.write(r.bytes) else e.file.inputStream().use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                    added(size)
                }
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally { pool.shutdownNow() }
    }

    private const val SMALL = 1L shl 20          // 1 MB: read once into memory
    private const val AHEAD = 32                  // files read ahead of the one being written
    private const val AHEAD_BYTES = 16L shl 20    // and at most 16 MB of their contents
}

/** A file for a source zip: its path inside the zip, the file, and its size and date, from the listing. */
class SourceEntry(val path: String, val file: File, val size: Long, val modified: Long)

class IgnoreRule(val base: String, val negate: Boolean, val dirOnly: Boolean, val anchored: Boolean, val regex: Regex)

/** The common .gitignore rules: `#` comments, `!` negation, trailing `/`, `*`, `?`, `**`, `[...]`. */
object Gitignore {

    fun load(file: File, base: String): List<IgnoreRule> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { parse(it, base) }
    }

    fun parse(raw: String, base: String): IgnoreRule? {
        var line = raw.trimEnd()
        if (line.isEmpty() || line.startsWith("#")) return null
        var negate = false
        if (line.startsWith("!")) { negate = true; line = line.substring(1) }
        if (line.startsWith("\\")) line = line.substring(1)
        var dirOnly = false
        if (line.endsWith("/")) { dirOnly = true; line = line.trimEnd('/') }
        if (line.isEmpty()) return null
        val anchored = line.contains('/')
        line = line.trimStart('/')
        return IgnoreRule(base, negate, dirOnly, anchored, Regex(globToRegex(line)))
    }

    /** Last matching rule wins, as in git. [path] is relative to the project root. */
    fun ignored(rules: List<IgnoreRule>, path: String, isDir: Boolean): Boolean {
        var ignored = false
        for (r in rules) {
            if (r.dirOnly && !isDir) continue
            val rel = when {
                r.base.isEmpty() -> path
                path.startsWith(r.base + "/") -> path.substring(r.base.length + 1)
                else -> continue
            }
            val subject = if (r.anchored) rel else rel.substringAfterLast('/')
            if (r.regex.matches(subject)) ignored = !r.negate
        }
        return ignored
    }

    fun globToRegex(glob: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3; continue }
                glob.startsWith("/**", i) && i + 3 == glob.length -> { sb.append("(?:/.*)?"); i += 3; continue }
                glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '[' -> {
                    val end = glob.indexOf(']', i + 1)
                    if (end < 0) sb.append("\\[") else {
                        var cls = glob.substring(i + 1, end)
                        if (cls.startsWith("!")) cls = "^" + cls.substring(1)
                        sb.append('[').append(cls.replace("\\", "\\\\")).append(']')
                        i = end
                    }
                }
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return sb.toString()
    }
}
