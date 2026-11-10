package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import java.io.File

/** What the `/proc` scan needs; replaced by a fake in tests. */
interface ProcFs {
    fun pids(): List<Int>

    /** The real uid of [pid] (the `Uid:` line of `status`), or null when it cannot be read. */
    fun uidOf(pid: Int): Int?

    /** The command line of [pid]: its NUL-separated arguments; null when it cannot be read. */
    fun cmdline(pid: Int): List<String>?
}

class JavaProcFs(private val root: File = File("/proc")) : ProcFs {
    override fun pids(): List<Int> = root.list().orEmpty().mapNotNull { it.toIntOrNull() }

    override fun uidOf(pid: Int): Int? = try {
        File(root, "$pid/status").readLines().firstOrNull { it.startsWith("Uid:") }?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
    } catch (e: Exception) {
        null
    }

    override fun cmdline(pid: Int): List<String>? = try {
        String(File(root, "$pid/cmdline").readBytes(), Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/**
 * The fallback of the quiet-IDE gate, used only when the host gives no build-status service: a scan of `/proc` for processes of the
 * app's **own uid** that look like a Gradle or Java daemon.
 *
 * **What is matched, and why.** The IDE runs its builds and its project sync in a separate JVM process (the Gradle tooling server and
 * the Gradle daemon) under the app's own uid. A process counts when its command line has a first argument whose file name is `java`
 * (or `java` with a version suffix) or any argument containing `org.gradle`, `GradleDaemon`, `gradle-launcher` or `GradleWrapperMain`.
 * The app's own process (the package name as its command line) and this plugin's engine programs (named `libpart1_*.so`) never
 * match. This is deliberately coarse: an idle daemon also counts as busy, because the accepted state of the phone jobs is "no Gradle
 * child" (plan 8.3 item 10), and a measurement beside a live daemon was seen to read about 30 % low (plan E21). It does not see
 * indexing inside the app's own process. [startsSeen] counts the moments the gate has seen the scan turn from quiet to busy.
 */
class ProcQuietIdeGate(private val proc: ProcFs, private val ownUid: Int) : QuietIdeGate {
    @Volatile private var starts = 0L
    @Volatile private var wasBusy = false

    override fun isBusy(): Boolean {
        val busy = scan()
        if (busy && !wasBusy) starts++
        wasBusy = busy
        return busy
    }

    override fun startsSeen(): Long = starts

    private fun scan(): Boolean {
        for (pid in proc.pids()) {
            if (proc.uidOf(pid) != ownUid) continue
            val args = proc.cmdline(pid) ?: continue
            if (matches(args)) return true
        }
        return false
    }

    companion object {
        private val MARKERS = listOf("org.gradle", "GradleDaemon", "gradle-launcher", "GradleWrapperMain")

        internal fun matches(args: List<String>): Boolean {
            if (args.isEmpty()) return false
            val name = args[0].substringAfterLast('/')
            if (name.startsWith("libpart1_")) return false
            if (name == "java" || Regex("java[0-9.]*").matches(name)) return true
            return args.any { a -> MARKERS.any { a.contains(it) } }
        }
    }
}
