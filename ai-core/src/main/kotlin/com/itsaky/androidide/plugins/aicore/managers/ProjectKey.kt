package com.itsaky.androidide.plugins.aicore.managers

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import java.io.File
import java.security.MessageDigest

private const val TAG = "$LOG_PREFIX.ProjectKey"

/**
 * Turns the open project's root into the short, stable string that namespaces its chat history.
 *
 * The digest is a storage key, not a security boundary: it only has to be stable across releases
 * (like a Keystore alias) and collision-free on one device.
 */
object ProjectKey {

    /**
     * Namespace used when no project is open. A real digest here would give every rootless state
     * one shared history again, which is the bug this class exists to prevent, so it is named
     * instead of hashed.
     */
    const val NO_PROJECT = "noproject"

    /** Hex characters kept from the SHA-256; 64 bits is far past collision range for one device. */
    private const val KEY_LENGTH = 16

    /**
     * The namespace for the project open right now. Safe to call from the main thread; it asks the
     * host for the open project and does no I/O beyond canonicalizing the path it gets back.
     *
     * @return the current project's key, [NO_PROJECT] when the host named a root that is no
     *   particular project, or null when nothing answered at all. Null is not "the project
     *   changed" — a caller holding a binding should keep it rather than swap the user onto an
     *   empty history over a hiccup. [PathGuard.rawProjectRoot] rather than `projectRoot()`
     *   precisely so those two stay apart: the latter's fallback answers for a host that did not.
     */
    fun current(): String? = try {
        PathGuard.rawProjectRoot()?.let(::forRoot)
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve the open project; leaving the chat history where it is", e)
        null
    }

    /**
     * The namespace for a given project root.
     *
     * Canonicalizes first, so the same project reached through a symlink or a trailing slash lands
     * in one namespace rather than two.
     *
     * @param root the project root path, or null.
     * @return the digest of [root], or [NO_PROJECT] when it names no particular project.
     */
    fun forRoot(root: String?): String {
        if (root.isNullOrBlank()) return NO_PROJECT
        val canonical = canonicalize(root)
        if (canonical == File.separator) return NO_PROJECT
        // PathGuard's fallback means "nothing resolved", not "this project"; hashing it would pool
        // every unresolved root under one convincing-looking key. Canonicalized on both sides: the
        // workspace sits behind a mount, so the resolved path is not the literal constant.
        if (canonical == PathGuard.DEFAULT_ROOT || canonical == canonicalize(PathGuard.DEFAULT_ROOT)) {
            return NO_PROJECT
        }
        return sha256Hex(canonical).take(KEY_LENGTH)
    }

    /**
     * @param path a filesystem path.
     * @return [path] with symlinks and `..` segments resolved, or merely absolute when the
     *   filesystem declines to answer.
     */
    private fun canonicalize(path: String): String = try {
        File(path).canonicalPath
    } catch (e: Exception) {
        File(path).absolutePath
    }

    /**
     * @param value the string to digest.
     * @return the lowercase hex SHA-256 of [value]'s UTF-8 bytes.
     */
    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
