package com.itsaky.androidide.plugins.aiagentlocal.device.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The file operations the store uses; tests replace them to fail between the steps of an atomic write. */
interface FileOps {
    /** Writes [bytes] to [file] (created or truncated) and forces them to disk. */
    fun writeAndSync(file: File, bytes: ByteArray)

    /** Atomically replaces [to] by [from]. Throws [IOException] when that is not possible. */
    fun moveReplacing(from: File, to: File)

    fun delete(file: File): Boolean

    object Real : FileOps {
        override fun writeAndSync(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        }

        override fun moveReplacing(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        override fun delete(file: File): Boolean = file.delete()
    }
}

/**
 * Atomic file replacement: the bytes go to `<name>.tmp` in the same directory, are synced, and the temporary file is
 * renamed over the target. A crash (or an exception) before the rename leaves the old target untouched; a leftover
 * temporary file is never read as data.
 */
internal class AtomicWriter(private val ops: FileOps) {
    fun write(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: throw IOException("no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create the directory")
        val tmp = File(dir, target.name + ".tmp")
        try {
            ops.writeAndSync(tmp, bytes)
            ops.moveReplacing(tmp, target)
        } catch (e: IOException) {
            runCatching { ops.delete(tmp) }
            throw e
        }
    }
}
