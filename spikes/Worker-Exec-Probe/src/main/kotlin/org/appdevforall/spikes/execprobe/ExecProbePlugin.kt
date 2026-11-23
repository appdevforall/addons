package org.appdevforall.spikes.execprobe

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Typeface
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.extensions.FabAction
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeUIService
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ADFA-7281 spike 1. Answers one question: can a plugin start, talk to and supervise a native
 * executable it ships as `lib<name>.so`, from inside Code on the Go's process?
 *
 * Runs every check once on activation (results in logcat under [TAG] and in
 * `exec-probe-report.txt` in the plugin's files dir) and again from the main menu item, which
 * also shows the report in a dialog with a Copy button.
 *
 * Deliberately declares `native.code` but NOT `system.commands`, to find out whether starting a
 * process needs the latter in practice.
 *
 * Throwaway: lives under spikes/, never published.
 */
class ExecProbePlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        return true
    }

    override fun activate(): Boolean {
        Thread({
            runAndRecord()
            runFdProbeAndRecord()
        }, "exec-probe").start()
        return true
    }

    override fun deactivate(): Boolean = true

    override fun dispose() {}

    // Every UIExtension method is overridden, none left to the interface defaults: built against
    // the current libs/plugin-api.jar, relying on a default threw AbstractMethodError for
    // getEditorTabs() on CoGo C-d-1020-1725 and stopped the editor from starting.
    override fun getContextMenuItems(context: ContextMenuContext): List<MenuItem> = emptyList()
    override fun getEditorTabs(): List<TabItem> = emptyList()
    override fun getSideMenuItems(): List<NavigationItem> = emptyList()
    override fun getToolbarActions(): List<ToolbarAction> = emptyList()
    override fun getFabActions(): List<FabAction> = emptyList()
    override fun getHiddenToolbarActionIds(): Set<String> = emptySet()

    /**
     * One toolbar button for the whole spike. CoGo turns every main-menu item into its own toolbar
     * button with the same default icon and ignores [MenuItem.subItems], so five items showed as five
     * identical cubes. The button opens a list instead.
     */
    override fun getMainMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = "adfa_7281_probes",
            title = "ADFA-7281 worker probes",
            action = { showActions() },
        ),
    )

    private val actions: List<Pair<String, () -> Unit>> by lazy {
        listOf(
            "Run worker exec probe (spike 1)" to { Thread({ show(runAndRecord()) }, "exec-probe").start() },
            "Pick model for fd probe (SAF)" to { pickModel() },
            "Run fd probe (spike 2)" to { Thread({ show(runFdProbeAndRecord()) }, "fd-probe").start() },
            "Run llama bench: worker vs in-process (spike 3)" to
                { Thread({ show(runBenchAndRecord(inProcess = true)) }, "llama-bench").start() },
            "Run llama bench: worker only (spike 3)" to
                { Thread({ show(runBenchAndRecord(inProcess = false)) }, "llama-bench").start() },
        )
    }

    private fun showActions() {
        val activity = context.services.get(IdeUIService::class.java)?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("ADFA-7281 worker probes")
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private val prefs get() = context.getPluginSharedPreferences("fd_probe")

    /** Opens the system picker, as AI-Agent-Local does, and keeps a persistable read grant. */
    private fun pickModel() {
        val activity = context.services.get(IdeUIService::class.java)?.getCurrentActivity() ?: return
        val registry = (activity as? ActivityResultRegistryOwner)?.activityResultRegistry ?: run {
            Log.w(TAG, "current activity has no ActivityResultRegistry")
            return
        }
        activity.runOnUiThread {
            var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
            launcher = registry.register("fd-probe-pick-" + System.nanoTime(), ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                launcher?.unregister()
                if (uri == null) return@register
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure { Log.w(TAG, "takePersistableUriPermission failed", it) }
                val saved = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty() + uri.toString()
                prefs.edit().putStringSet(KEY_URIS, saved).apply()
                Log.i(TAG, "picked $uri")
                Thread({ show(runFdProbeAndRecord()) }, "fd-probe").start()
            }
            launcher.launch(arrayOf("*/*"))
        }
    }

    private fun runBenchAndRecord(inProcess: Boolean): String {
        val model = File("/storage/emulated/0/Download/$BENCH_MODEL")
        val report = try {
            if (!model.isFile) "FAIL  $model not found" else BenchProbe(context).run(model, inProcess)
        } catch (t: Throwable) {
            "BENCH CRASHED: ${t.javaClass.name}: ${t.message}\n${Log.getStackTraceString(t)}"
        }
        report.lines().forEach { Log.i(TAG, "bench: $it") }
        Log.i(TAG, "bench: REPORT DONE")
        runCatching { File(context.getPluginFilesDir(), "bench-report.txt").writeText(report) }
        return report
    }

    private fun runFdProbeAndRecord(): String {
        val uris = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty().map(Uri::parse)
        val plain = File("/storage/emulated/0/Download").listFiles { f -> f.name.endsWith(".gguf", true) }
            ?.sortedBy { it.name }.orEmpty()
        val report = try {
            FdProbe(context).run(uris, plain)
        } catch (t: Throwable) {
            "FD PROBE CRASHED: ${t.javaClass.name}: ${t.message}\n${Log.getStackTraceString(t)}"
        }
        report.lines().forEach { Log.i(TAG, "fd: $it") }
        runCatching { File(context.getPluginFilesDir(), "fd-probe-report.txt").writeText(report) }
        return report
    }

    private fun runAndRecord(): String {
        val report = try {
            Probe(context).run()
        } catch (t: Throwable) {
            "PROBE CRASHED: ${t.javaClass.name}: ${t.message}\n${Log.getStackTraceString(t)}"
        }
        report.lines().forEach { Log.i(TAG, it) }
        runCatching { File(context.getPluginFilesDir(), "exec-probe-report.txt").writeText(report) }
            .onFailure { Log.w(TAG, "could not write report file", it) }
        context.logger.info("exec probe finished; see logcat tag $TAG")
        return report
    }

    private fun show(report: String) {
        val activity = context.services.get(IdeUIService::class.java)?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            val text = TextView(activity).apply {
                this.text = report
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(32, 24, 32, 24)
            }
            AlertDialog.Builder(activity)
                .setTitle("Worker probe")
                .setView(ScrollView(activity).apply { addView(text) })
                .setPositiveButton("Copy") { _, _ ->
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("exec probe", report))
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    companion object {
        const val TAG = "CogoExecProbe"
        private const val KEY_URIS = "uris"
        private const val BENCH_MODEL = "Qwen2.5-0.5B.Q4_K_M.gguf"
    }
}

/** One run of every check. Each check adds a PASS/FAIL/INFO line; nothing here throws on a failed check. */
private class Probe(private val ctx: PluginContext) {

    private val out = StringBuilder()
    private var failures = 0

    private fun info(s: String) { out.append("INFO  ").append(s).append('\n') }
    private fun pass(s: String) { out.append("PASS  ").append(s).append('\n') }
    private fun fail(s: String) { failures++; out.append("FAIL  ").append(s).append('\n') }

    fun run(): String {
        val app = ctx.androidContext.applicationContext
        info("device ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, abis ${Build.SUPPORTED_ABIS.joinToString()}")
        info("host ${app.packageName}, targetSdk ${app.applicationInfo.targetSdkVersion}")
        info("parent pid ${android.os.Process.myPid()}, uid ${android.os.Process.myUid()}, selinux ${readSmall("/proc/self/attr/current")}")

        val path = (javaClass.classLoader as? BaseDexClassLoader)?.findLibrary("cogo_exec_probe")
        if (path == null) {
            fail("findLibrary(\"cogo_exec_probe\") returned null: the probe was not extracted")
            return finish()
        }
        val exe = File(path)
        info("probe at $path (${exe.length()} bytes, canExecute=${exe.canExecute()})")
        info("mount: ${mountFor(exe)}")

        identify(exe)
        envPassing(exe)
        abortSurvives(exe)
        stdinEof(exe)
        socket(exe)
        forcibleKill(exe)
        spawnLatency(exe)
        return finish()
    }

    private fun finish(): String {
        out.append(if (failures == 0) "\nRESULT: all checks passed\n" else "\nRESULT: $failures check(s) FAILED\n")
        return out.toString()
    }

    private class Run(val exit: Int?, val stdout: String, val stderr: String, val startMs: Double)

    /** Starts the probe, optionally feeds stdin, waits up to [timeoutS] and collects everything. */
    private fun exec(exe: File, vararg args: String, env: Map<String, String> = emptyMap(), timeoutS: Long = 5): Run {
        val pb = ProcessBuilder(listOf(exe.absolutePath) + args)
        pb.environment().putAll(env)
        val t0 = System.nanoTime()
        val p = pb.start()
        val startMs = (System.nanoTime() - t0) / 1e6
        p.outputStream.close()
        val done = p.waitFor(timeoutS, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        return Run(
            if (done) p.exitValue() else null,
            p.inputStream.bufferedReader().readText().trim(),
            p.errorStream.bufferedReader().readText().trim(),
            startMs,
        )
    }

    private fun identify(exe: File) {
        val r = try { exec(exe, "--identify") } catch (e: Exception) {
            fail("exec --identify threw ${e.javaClass.simpleName}: ${e.message}  <-- the core question")
            return
        }
        if (r.exit == 0 && r.stdout.startsWith("{")) {
            pass("exec from plugin native dir works (start() took ${"%.1f".format(r.startMs)} ms)")
            info("child: ${r.stdout}")
            val want = BuildConfig.VERSION_NAME
            val got = Regex("\"build\":\"([^\"]*)\"").find(r.stdout)?.groupValues?.get(1)
            when {
                got == want -> pass("extracted probe matches this plugin build ($got)")
                else -> fail("STALE BINARY: extracted probe is build $got but the plugin is $want")
            }
            val parentCtx = readSmall("/proc/self/attr/current")
            if (r.stdout.contains("\"selinux\":\"$parentCtx\"")) pass("child runs in the parent's SELinux domain")
            else info("child SELinux domain differs from parent ($parentCtx)")
        } else {
            fail("--identify exit=${r.exit} stdout='${r.stdout}' stderr='${r.stderr}'")
        }
    }

    private fun envPassing(exe: File) {
        val token = UUID.randomUUID().toString()
        val r = exec(exe, "--env", env = mapOf("COGO_PROBE_TOKEN" to token))
        if (r.exit == 0 && r.stdout.contains(token)) pass("environment variables reach the child")
        else fail("--env exit=${r.exit} stdout='${r.stdout}'")
    }

    private fun abortSurvives(exe: File) {
        val r = exec(exe, "--abort")
        // Android's Process reports death by signal N as 128 + N; SIGABRT is 6.
        if (r.exit == 134) pass("child abort() seen as exit 134 (SIGABRT); IDE process still alive")
        else fail("--abort exit=${r.exit} (expected 134) stdout='${r.stdout}' stderr='${r.stderr.take(300)}'")
    }

    private fun stdinEof(exe: File) {
        val p = ProcessBuilder(exe.absolutePath, "--stdin-eof").start()
        val reader = p.inputStream.bufferedReader()
        val ready = reader.readLine()
        p.outputStream.write("12345".toByteArray())
        p.outputStream.flush()
        p.outputStream.close()
        val done = p.waitFor(3, TimeUnit.SECONDS)
        val line = reader.readText().trim()
        if (ready?.startsWith("ready ") == true && done && p.exitValue() == 0 && line.contains("\"bytes\":5")) {
            pass("child exits cleanly on stdin EOF (planned shutdown path)")
        } else {
            if (!done) p.destroyForcibly()
            fail("--stdin-eof ready='$ready' done=$done exit=${if (done) p.exitValue() else null} out='$line'")
        }
    }

    private fun socket(exe: File) {
        val name = "cogo-probe-" + UUID.randomUUID()
        val server = LocalServerSocket(name) // abstract namespace
        var peer: String? = null
        var hello: String? = null
        var serverError: Throwable? = null
        val acceptor = Thread {
            try {
                server.accept().use { s: LocalSocket ->
                    val cred = s.peerCredentials
                    peer = "pid=${cred.pid} uid=${cred.uid}"
                    hello = s.inputStream.bufferedReader().readLine()
                    s.outputStream.write("ack\n".toByteArray())
                    s.outputStream.flush()
                }
            } catch (t: Throwable) {
                serverError = t
            }
        }.apply { isDaemon = true; start() }

        val r = exec(exe, "--socket", name)
        acceptor.join(3000)
        runCatching { server.close() }

        if (r.exit == 0 && r.stdout.contains("\"reply\":\"ack\"") && hello?.startsWith("hello ") == true) {
            pass("abstract unix socket round trip works; SO_PEERCRED $peer")
            if (peer?.contains("uid=${android.os.Process.myUid()}") == true) {
                pass("peer uid matches ours: the worker can be authenticated without a bearer token")
            } else {
                fail("peer uid does not match ours: $peer")
            }
        } else {
            fail("--socket exit=${r.exit} stdout='${r.stdout}' hello='$hello' serverError=$serverError")
        }
    }

    private fun forcibleKill(exe: File) {
        // What Process.destroyForcibly() actually sends on Android.
        run {
            val p = ProcessBuilder(exe.absolutePath, "--stdin-eof").start()
            p.inputStream.bufferedReader().readLine()
            p.destroyForcibly()
            val done = p.waitFor(2, TimeUnit.SECONDS)
            val exit = if (done) p.exitValue() else null
            info("Process.destroyForcibly() ends the child with exit $exit (${when (exit) { 137 -> "SIGKILL"; 143 -> "SIGTERM: not a hard kill on Android"; else -> "?" }})")
        }
        // The supervisor's real hard-kill path: SIGKILL by the pid the worker reports.
        val p = ProcessBuilder(exe.absolutePath, "--stdin-eof").start()
        val pid = p.inputStream.bufferedReader().readLine()?.substringAfter("ready ")?.trim()?.toIntOrNull()
        if (pid == null) {
            fail("--stdin-eof did not report its pid")
            p.destroyForcibly()
            return
        }
        val t0 = System.nanoTime()
        Os.kill(pid, OsConstants.SIGKILL)
        val done = p.waitFor(2, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - t0) / 1e6
        if (done && p.exitValue() == 137) pass("Os.kill(pid, SIGKILL) hard-kills the child (exit 137) in ${"%.1f".format(ms)} ms")
        else fail("Os.kill SIGKILL done=$done exit=${if (done) p.exitValue() else null}")
    }

    private fun spawnLatency(exe: File) {
        val times = (1..10).map {
            // Plain waitFor(): waitFor(timeout) polls on Android and adds up to ~100 ms.
            val t0 = System.nanoTime()
            val p = ProcessBuilder(exe.absolutePath, "--identify").start()
            p.inputStream.readBytes()
            p.waitFor()
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        info("spawn+run+exit of --identify over 10 runs: median ${"%.1f".format(times[5])} ms, max ${"%.1f".format(times.last())} ms")
    }

    /** The /proc/self/mounts line for the deepest mount point containing [f], to spot noexec. */
    private fun mountFor(f: File): String {
        val target = f.canonicalPath
        return runCatching {
            File("/proc/self/mounts").readLines()
                .map { it.split(' ') }
                .filter { it.size >= 4 && (target == it[1] || target.startsWith(it[1].trimEnd('/') + "/")) }
                .maxByOrNull { it[1].length }
                ?.let { "${it[1]} ${it[2]} ${it[3]}" + if ("noexec" in it[3].split(',')) "  <-- noexec!" else "" }
                ?: "<no mount found>"
        }.getOrElse { "<${it.message}>" }
    }

    private fun readSmall(path: String): String =
        runCatching { File(path).readText().trim('\u0000', '\n', ' ') }.getOrElse { "<${it.message}>" }
}
