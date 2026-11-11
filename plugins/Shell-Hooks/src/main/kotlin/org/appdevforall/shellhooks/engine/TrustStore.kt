package org.appdevforall.shellhooks.engine

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** The few string operations [TrustStore] needs, so it runs on SharedPreferences or a map. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Stores [value] under [key]; null removes the key. */
    fun putString(
        key: String,
        value: String?,
    )

    fun keys(): Set<String>
}

/**
 * Which project hooks the user approved. A script can arrive with a project (a cloned repository,
 * an AI agent's edit, a plugin that can only write project files), so a script found there runs
 * only once the user has read it and trusted that exact content: approval is keyed on the
 * project, the script's path inside the hooks folder, and the SHA-256 of its bytes. Editing the
 * script, or adding one, needs a new approval.
 *
 * This does not stop a plugin that can run commands or write files in-process: such a plugin can
 * already write the global hooks folder, or these approvals, directly.
 */
class TrustStore(
    private val store: KeyValueStore,
) {
    fun isTrusted(
        projectDir: File,
        relativePath: String,
        sha256: String,
    ): Boolean = read(projectDir)[relativePath] == sha256

    /** Trusts this content of [relativePath]; a previous approval of other content is replaced. */
    fun trust(
        projectDir: File,
        relativePath: String,
        sha256: String,
    ) {
        synchronized(this) {
            write(projectDir, read(projectDir) + (relativePath to sha256))
        }
    }

    /** Forgets every approval for [projectDir]. */
    fun revoke(projectDir: File) {
        synchronized(this) { store.putString(key(projectDir), null) }
    }

    /** Projects with at least one trusted hook, as their canonical paths, sorted. */
    fun trustedProjects(): List<String> =
        store.keys().filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.sorted()

    /** How many scripts are trusted in [projectDir]. */
    fun trustedCount(projectDir: File): Int = read(projectDir).size

    /** Every trusted content hash, across all projects. */
    fun trustedShas(): Set<String> =
        store.keys().filter { it.startsWith(PREFIX) }.flatMap { readKey(it).values }.toSet()

    private fun read(projectDir: File): Map<String, String> = readKey(key(projectDir))

    private fun readKey(key: String): Map<String, String> {
        val raw = store.getString(key) ?: return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val path = runCatching { String(Base64.getDecoder().decode(line.substring(tab + 1)), Charsets.UTF_8) }
                    .getOrNull() ?: return@mapNotNull null
                path to line.substring(0, tab)
            }.toMap()
    }

    // One line per script: "<sha256>\t<base64 path>". Base64, because a file name may hold a tab
    // or a newline.
    private fun write(
        projectDir: File,
        entries: Map<String, String>,
    ) {
        val value =
            entries.entries.sortedBy { it.key }.joinToString("\n") { (path, sha) ->
                sha + "\t" + Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))
            }
        store.putString(key(projectDir), value.ifEmpty { null })
    }

    private fun key(projectDir: File) = PREFIX + canonical(projectDir)

    companion object {
        private const val PREFIX = "trust:"

        fun canonical(dir: File): String = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
