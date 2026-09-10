package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import java.io.File
import java.nio.file.Files

/**
 * Shared path-containment guard for filesystem tool handlers: confines every
 * model-supplied path to the current project root. The root comes from
 * [projectRootProvider] (host-wired), then a legacy system property, then [DEFAULT_ROOT].
 */
object PathGuard {

    private const val TAG = "$LOG_PREFIX.PathGuard"

    /**
     * Workspace parent used when nothing else resolves. Public because it is also the marker for
     * "no project is open", which callers deriving a per-project identity have to reject.
     */
    const val DEFAULT_ROOT = "/storage/emulated/0/AndroidIDEProjects"

    /** Host-backed supplier of the current project root; queried on every resolution. */
    @Volatile
    private var projectRootProvider: (() -> String?)? = null

    /** Test-only override; when non-null it wins over everything else. */
    @Volatile
    private var projectRootOverride: String? = null

    /**
     * Installs (or clears, with `null`) the host-backed project-root supplier.
     * @param provider supplier of the project root, or null to clear.
     */
    fun setProjectRootProvider(provider: (() -> String?)?) {
        projectRootProvider = provider
    }

    /**
     * Forces a specific root, or clears it with `null`. Tests only.
     * @param root the root to force, or null to clear.
     */
    fun setProjectRootForTesting(root: String?) {
        projectRootOverride = root
    }

    /**
     * Resolves the active project root (best-effort); `user.dir` is never consulted
     * (it is "/" on Android) so [isValidRoot] can reject it and the guard fails closed.
     * @return the resolved root path (not guaranteed valid).
     */
    fun projectRoot(): String =
        projectRootOverride
            ?: projectRootProvider?.invoke()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("project.dir")
            ?: DEFAULT_ROOT

    /**
     * A usable root is a non-blank, existing directory that isn't the filesystem root "/"
     * ("/" means no project is open, and would confine nothing).
     * @param root candidate root.
     * @return true if [root] is a usable project root.
     */
    private fun isValidRoot(root: File): Boolean {
        val canonical = try { root.canonicalPath } catch (e: Exception) { return false }
        return canonical.isNotBlank() && canonical != File.separator && root.isDirectory
    }

    /**
     * Resolves [path] against the project root, verifying the canonical result is inside it.
     * @param path a relative or absolute path from the model.
     * @return the resolved [File] when contained, or null when it escapes or there is no valid root.
     */
    fun resolveWithin(path: String): File? {
        val rootFile = File(projectRoot())
        if (!isValidRoot(rootFile)) {
            Log.e(TAG, "No valid project root ('${rootFile.path}'); rejecting path: $path")
            return null
        }
        val root = rootFile.canonicalPath
        val file = if (path.startsWith("/")) File(path) else File(rootFile, path)
        val canonical = file.canonicalPath

        val rootWithSep = if (root.endsWith(File.separator)) root else root + File.separator
        val contained = canonical == root || canonical.startsWith(rootWithSep)
        if (!contained) {
            Log.e(TAG, "Path escape attempt: $canonical is outside project root $root")
        }
        return if (contained) file else null
    }

    /** Directories skipped when searching for a file by name — large/generated/noise. */
    private val SKIP_DIRS = setOf("build", ".git", ".gradle", ".idea", "node_modules", ".cxx")

    /**
     * Directory names no tool may write into. [SKIP_DIRS] only constrains the basename
     * *search*; containment alone happily resolves ".git/config" or "build/output.txt",
     * and corrupting the git database is unrecoverable for a user with no other checkout.
     */
    private val WRITE_DENY_DIRS = SKIP_DIRS

    /** Exact filenames no tool may write to — build/signing configuration and secrets. */
    private val WRITE_DENY_NAMES = setOf(
        "local.properties",
        "gradle-wrapper.jar",
        "gradle-wrapper.properties",
    )

    /** Extensions no tool may write to — keystores and other credential material. */
    private val WRITE_DENY_EXTENSIONS = setOf("jks", "keystore", "p12", "pfx", "pem")

    /**
     * Explains why [file] is off-limits to writes, for a path already known to be in-root.
     * Containment is necessary but not sufficient: generated trees, the git database, and
     * signing material are all inside the project yet must never be machine-edited.
     * @param file an in-root file (as returned by [resolveWithin]).
     * @return a human/model-readable reason, or null when the file is a legal write target.
     */
    fun writeDenialReason(file: File): String? {
        val root = File(projectRoot())
        val relative = try {
            file.canonicalFile.relativeToOrNull(root.canonicalFile)?.path
        } catch (e: Exception) {
            null
        } ?: file.name

        val segments = relative.split(File.separatorChar).filter { it.isNotEmpty() }
        segments.dropLast(1).firstOrNull { it in WRITE_DENY_DIRS }?.let { dir ->
            return "'$relative' is inside '$dir/', which is generated or internal and must not be edited"
        }
        if (file.name in WRITE_DENY_NAMES) {
            return "'${file.name}' is build configuration and must not be edited by a tool"
        }
        if (file.extension.lowercase() in WRITE_DENY_EXTENSIONS) {
            return "'${file.name}' looks like signing/credential material and must not be edited"
        }
        return null
    }

    /**
     * Finds in-root files whose name equals [fileName] (case-insensitive), skipping
     * generated/hidden dirs and symlinks, so a bare name resolves to a real path.
     * @param fileName the name to match (its basename is used).
     * @param limit max results.
     * @return matching files (possibly empty); more than one means ambiguous.
     */
    fun findByName(fileName: String, limit: Int = 20): List<File> {
        val target = baseNameOf(fileName).trim()
        if (target.isEmpty()) return emptyList()
        return walkProject(limit) { it.name.equals(target, ignoreCase = true) }
    }

    /**
     * Candidates for a path that doesn't exist, in two tiers because they are not equally
     * trustworthy. Callers may act on a lone [exact] match; anything else is a guess only the model
     * or the user can settle.
     * @property exact files whose name is exactly the one asked for, in a different folder.
     * @property byStem files with the same name but a different extension — the "right class, wrong
     *   language" miss, which an exact-name search never finds.
     */
    data class PathSuggestions(val exact: List<String>, val byStem: List<String>) {
        /** Every candidate, exact matches first. */
        val all: List<String> get() = exact + byStem

        /**
         * The one candidate worth correcting to without asking: a single exact-name match (even when
         * same-stem files also exist — the extension the model asked for settles it), or a single
         * same-stem match when nothing carries that name. Null when there is a choice to make;
         * within a tier the order is filesystem-walk order, so picking the first of several would
         * silently edit an arbitrary file.
         */
        val unambiguous: String?
            get() = exact.singleOrNull() ?: byStem.singleOrNull()?.takeIf { exact.isEmpty() }
    }

    /**
     * Suggests real project files for a path that doesn't exist, so a wrong guess is corrected in
     * one turn.
     * @param path the path the model asked for.
     * @param limit maximum suggestions across both tiers.
     * @return the candidates, possibly empty.
     */
    fun suggestPathsFor(path: String, limit: Int = 3): PathSuggestions {
        val empty = PathSuggestions(emptyList(), emptyList())
        val target = baseNameOf(path).trim()
        if (target.isEmpty()) return empty
        val stem = target.substringBeforeLast('.').lowercase()
        if (stem.isEmpty()) return empty

        val root = File(projectRoot())
        val exact = findByName(target, limit)
        val byStem = if (exact.size >= limit) emptyList() else {
            walkProject(limit - exact.size) { it.nameWithoutExtension.lowercase() == stem && it !in exact }
        }
        fun relative(files: List<File>) = files.map { it.relativeToOrSelf(root).path }
        return PathSuggestions(relative(exact), relative(byStem))
    }

    /**
     * Walks the project for files matching [predicate], skipping generated/hidden trees and
     * refusing to descend symlinks (which could otherwise walk outside the root).
     * @param limit maximum results.
     * @param predicate the file test.
     * @return matching in-root files.
     */
    private fun walkProject(limit: Int, predicate: (File) -> Boolean): List<File> {
        val root = File(projectRoot())
        if (!isValidRoot(root)) return emptyList()
        val rootWithSep = root.canonicalPath.let { if (it.endsWith(File.separator)) it else it + File.separator }

        return root.walkTopDown()
            .onEnter { dir ->
                (dir == root || (dir.name !in SKIP_DIRS && !dir.name.startsWith("."))) &&
                    (dir == root || !Files.isSymbolicLink(dir.toPath()))
            }
            .filter { it.isFile && predicate(it) }
            // Re-verify containment so a symlinked file resolving outside the root is dropped.
            .filter { it.canonicalPath.startsWith(rootWithSep) }
            .take(limit)
            .toList()
    }

    /**
     * Returns the last path segment, tolerating both '/' and '\' separators.
     * @param path the path.
     * @return the basename.
     */
    private fun baseNameOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    /** Outcome of [resolve]; callers match on it to act or to explain the miss. */
    sealed interface Resolution {
        /**
         * The path resolved to an existing in-root entry.
         * @property file the resolved file or directory.
         */
        data class Resolved(val file: File) : Resolution

        /**
         * A bare name matched several files; ask the user to disambiguate.
         * @property baseName the searched name.
         * @property matches the candidate files.
         */
        data class Ambiguous(val baseName: String, val matches: List<File>) : Resolution

        /** No existing match, but the path stayed in-root — it simply doesn't exist. */
        object NotFound : Resolution

        /** The path had no in-root interpretation at all (a containment escape). */
        object Escaped : Resolution
    }

    /**
     * Resolves a model-supplied path to a project entry, enforcing root containment.
     * Tries in-root, then slash-stripped relative, then a project-wide basename search.
     * @param path a relative, absolute, or bare file name from the model.
     * @return the matching [Resolution].
     */
    fun resolve(path: String): Resolution {
        val primary = resolveWithin(path)
        val relative = if (path.startsWith("/")) resolveWithin(path.removePrefix("/")) else null

        (primary?.takeIf { it.exists() } ?: relative?.takeIf { it.exists() })?.let {
            return Resolution.Resolved(it)
        }

        val matches = findByName(path)
        when (matches.size) {
            1 -> return Resolution.Resolved(matches[0])
            0 -> {}
            else -> return Resolution.Ambiguous(baseNameOf(path), matches)
        }

        return if (primary == null && relative == null) Resolution.Escaped else Resolution.NotFound
    }
}
