package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces a file's contents without ever leaving it half-written: the new bytes are fsync'd to a
 * temp file in the **same directory**, then moved over the target. That move is atomic, so an
 * interrupted write cannot truncate the original — unlike write-then-restore.
 */
object AtomicFileWriter {

    private const val TAG = "$LOG_PREFIX.AtomicFileWriter"

    /** Suffix of the staging file; visible in a directory listing only while a write is running. */
    private const val TEMP_SUFFIX = ".aiedit"

    /** Outcome of [replace]. */
    sealed interface Outcome {
        /** The target now holds the new bytes. */
        object Written : Outcome

        /**
         * Nothing was changed.
         * @property reason a model-readable explanation.
         */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Atomically replaces [file]'s contents with [bytes].
     * @param file the target, which must already exist and be writable.
     * @param displayPath the project-relative path to name in messages.
     * @param bytes the complete new contents.
     * @return [Outcome.Written], or [Outcome.Failed] with the original file untouched.
     */
    fun replace(file: File, displayPath: String, bytes: ByteArray): Outcome {
        if (!file.canWrite()) {
            return Outcome.Failed("File is not writable: $displayPath")
        }
        val dir = file.parentFile
            ?: return Outcome.Failed("Cannot resolve the directory of $displayPath")
        // Room for the temp copy alongside the original, before anything is touched.
        if (dir.usableSpace in 1 until bytes.size.toLong() * 2) {
            return Outcome.Failed("Not enough free space to safely write $displayPath")
        }

        // A move adopts the temp mode (0600), so carry the original's over; best effort on FAT.
        val permissions = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()

        val temp = File.createTempFile(".${file.name}.", TEMP_SUFFIX, dir)
        return try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            permissions?.let { runCatching { Files.setPosixFilePermissions(temp.toPath(), it) } }
            AgentTrace.detail("EDIT", "apply=disk staged bytes=${bytes.size} temp=${temp.name}")
            if (!moveIntoPlace(temp, file)) {
                AgentTrace.refusal("EDIT", "apply=disk path=${file.name}", "move failed; original untouched")
                return Outcome.Failed("Could not replace $displayPath — the original is unchanged")
            }
            Outcome.Written
        } finally {
            // A move consumes the temp file, so this only fires on paths that never got there.
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Moves [temp] over [file], degrading through the strategies Android's volumes support. NIO goes
     * first for its exception messages, where `renameTo` reports a bare `false`; both fallbacks
     * matter, as `ATOMIC_MOVE` is refused on some FUSE-backed emulated storage.
     * @param temp the staged file holding the new bytes.
     * @param file the destination, which always already exists.
     * @return true when [file] now holds the staged bytes.
     */
    private fun moveIntoPlace(temp: File, file: File): Boolean {
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return true
        } catch (e: AtomicMoveNotSupportedException) {
            Log.d(TAG, "Atomic move unsupported for ${file.name}; retrying as a plain replace", e)
        } catch (e: IOException) {
            Log.w(TAG, "NIO move of ${file.name} failed; falling back", e)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "NIO move of ${file.name} unsupported on this volume; falling back", e)
        }

        return try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Plain NIO move of ${file.name} failed; trying platform rename", e)
            temp.renameTo(file)
        }
    }
}
