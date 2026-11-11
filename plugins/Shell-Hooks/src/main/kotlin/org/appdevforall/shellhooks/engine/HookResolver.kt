package org.appdevforall.shellhooks.engine

import java.io.File
import java.io.IOException

/**
 * A script ready to run. [executable] is what runs; for a project hook it is a private copy of
 * the bytes the user trusted, so the file cannot change between the check and the run.
 * [original] is the script the user sees and edits.
 */
data class ResolvedScript(
    val executable: File,
    val original: File,
    val source: ScriptSource,
    val hooksDir: File,
)

/** Turns an event into the scripts to run for it. */
fun interface ScriptResolver {
    fun resolve(event: HookEvent): List<ResolvedScript>
}

/** What the user turned on; read on every event, so a change applies to the next one. */
interface HookSwitches {
    val enabled: Boolean
    val projectHooksEnabled: Boolean

    fun isHookEnabled(hook: Hook): Boolean
}

/**
 * Finds the user's global scripts for an event, then the project's own, which run only when
 * project hooks are on and the user trusted their exact content. An untrusted project script is
 * skipped and listed in [log] for review.
 */
class HookResolver(
    private val paths: HookPaths,
    private val switches: HookSwitches,
    private val trust: TrustStore,
    private val log: HookLog,
    private val verifiedDir: File,
    private val warn: (String) -> Unit = {},
) : ScriptResolver {
    override fun resolve(event: HookEvent): List<ResolvedScript> {
        if (!switches.enabled || !switches.isHookEnabled(event.hook)) return emptyList()
        val global =
            HookDiscovery.discover(paths.globalHooks, event.hook.id).map {
                ResolvedScript(it, it, ScriptSource.GLOBAL, paths.globalHooks)
            }
        val projectDir = event.projectDir
        if (!switches.projectHooksEnabled || projectDir == null) return global
        val root = HookPaths.projectHooks(projectDir)
        val project =
            HookDiscovery.discover(root, event.hook.id, requireExecutable = false)
                .mapNotNull { verify(projectDir, root, it) }
        return global + project
    }

    private fun verify(
        projectDir: File,
        root: File,
        script: File,
    ): ResolvedScript? {
        val relativePath = script.relativeTo(root).path
        val bytes =
            try {
                ScriptReview.readAtMost(script, ScriptReview.MAX_BYTES)
            } catch (e: IOException) {
                warn("Shell Hooks: cannot read ${script.path}: ${e.message}")
                return null
            }
        if (bytes == null) {
            warn("Shell Hooks: ${script.path} is larger than ${ScriptReview.MAX_BYTES / 1024} KB; it never runs")
            log.addPending(PendingTrust(projectDir, relativePath, script, sha256 = "", tooLarge = true))
            return null
        }
        val sha = TrustStore.sha256(bytes)
        if (!trust.isTrusted(projectDir, relativePath, sha)) {
            log.addPending(PendingTrust(projectDir, relativePath, script, sha))
            return null
        }
        log.removePending(projectDir, relativePath)
        val copy = verifiedCopy(bytes, sha) ?: return null
        return ResolvedScript(copy, script, ScriptSource.PROJECT, root)
    }

    // Named by content hash, so one copy serves every run of the same bytes. Written to a temporary
    // name first: a half-written copy must never be executable under the final name.
    private fun verifiedCopy(
        bytes: ByteArray,
        sha: String,
    ): File? =
        try {
            verifiedDir.mkdirs()
            val copy = File(verifiedDir, sha)
            val existing = if (copy.isFile) ScriptReview.readAtMost(copy, ScriptReview.MAX_BYTES) else null
            if (existing == null || TrustStore.sha256(existing) != sha) {
                val temp = File(verifiedDir, "$sha$TEMP_SUFFIX")
                temp.writeBytes(bytes)
                temp.setExecutable(true, true)
                if (!temp.renameTo(copy)) throw IOException("rename failed")
            }
            copy.setExecutable(true, true)
            copy
        } catch (e: IOException) {
            warn("Shell Hooks: cannot stage a trusted copy: ${e.message}")
            null
        }

    companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}

/** The private copies of trusted project scripts, one per content hash. */
object VerifiedCopies {
    /**
     * Deletes every copy in [dir] whose hash no trust entry still names, and any half-written
     * temporary file. Returns how many files it deleted.
     */
    fun prune(
        dir: File,
        trusted: Set<String>,
    ): Int =
        dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(HookResolver.TEMP_SUFFIX) || it.name !in trusted) }
            ?.count { it.delete() }
            ?: 0
}
