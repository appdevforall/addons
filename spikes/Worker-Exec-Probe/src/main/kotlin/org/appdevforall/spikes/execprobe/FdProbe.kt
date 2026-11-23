package org.appdevforall.spikes.execprobe

import android.net.LocalServerSocket
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.itsaky.androidide.plugins.PluginContext
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ADFA-7281 spike 2: can the plugin hand a model file it opened (through SAF, as AI-Agent-Local
 * does since ADFA-5253) to the worker process, and can the worker load it the way llama.cpp does?
 *
 * For each source the plugin opens a read fd, sends it to `--recv-fd` over an abstract unix
 * socket with SCM_RIGHTS, and **closes its own copy straight after sending**, so everything the
 * child manages afterwards proves the child's copy is independent of the plugin's.
 */
internal class FdProbe(private val ctx: PluginContext) {

    private val out = StringBuilder()
    private var failures = 0

    private fun info(s: String) { out.append("INFO  ").append(s).append('\n') }
    private fun pass(s: String) { out.append("PASS  ").append(s).append('\n') }
    private fun fail(s: String) { failures++; out.append("FAIL  ").append(s).append('\n') }

    /**
     * @param safUris documents picked through SAF, with their persisted read grant
     * @param plainFiles files opened by path, as a control for the SAF case
     */
    fun run(safUris: List<Uri>, plainFiles: List<File>): String {
        val path = (javaClass.classLoader as? BaseDexClassLoader)?.findLibrary("cogo_exec_probe")
        if (path == null) {
            fail("probe not extracted")
            return finish()
        }
        val exe = File(path)
        if (safUris.isEmpty()) info("no SAF document picked yet: use the menu item 'Pick model for fd probe (SAF)'")

        val resolver = ctx.androidContext.applicationContext.contentResolver
        for (uri in safUris) {
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "?"
            val pfd = try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                fail("SAF $name: openFileDescriptor threw ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            if (pfd == null) {
                fail("SAF $name: openFileDescriptor returned null")
                continue
            }
            // A plain path for the same document, to compare: Download is where Get-AI-Models
            // puts models, and where the user picked from in this spike.
            sendAndCheck(exe, "SAF $name", pfd, "/storage/emulated/0/Download/$name")
        }
        for (f in plainFiles) {
            val pfd = try {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                fail("path ${f.name}: open threw ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            sendAndCheck(exe, "path ${f.name}", pfd, null)
        }
        return finish()
    }

    private fun finish(): String {
        out.append(if (failures == 0) "\nRESULT: all checks passed\n" else "\nRESULT: $failures check(s) FAILED\n")
        return out.toString()
    }

    /** Sends [pfd] to a fresh `--recv-fd` child, closes it, and checks what the child could do. */
    private fun sendAndCheck(exe: File, label: String, pfd: ParcelFileDescriptor, plainPath: String?) {
        val size = pfd.statSize
        val parentFdNum = pfd.fd
        info("$label: size ${size} bytes, parent fd $parentFdNum -> ${readlink("/proc/self/fd/$parentFdNum")}")

        val name = "cogo-fdprobe-" + UUID.randomUUID()
        val server = LocalServerSocket(name)
        var sendError: Throwable? = null
        val acceptor = Thread {
            try {
                server.accept().use { s ->
                    s.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                    s.outputStream.write("pfd=$parentFdNum\n".toByteArray())
                    s.outputStream.flush()
                    // Close the plugin's copy now: the child must not depend on it.
                    pfd.close()
                    s.inputStream.bufferedReader().readLine() // "done"
                }
            } catch (t: Throwable) {
                sendError = t
            }
        }.apply { isDaemon = true; start() }

        val args = mutableListOf(exe.absolutePath, "--recv-fd", name)
        if (plainPath != null) args += plainPath
        val p = ProcessBuilder(args).start()
        val stdout = p.inputStream.bufferedReader().readText().trim()
        val stderr = p.errorStream.bufferedReader().readText().trim()
        val done = p.waitFor(120, TimeUnit.SECONDS)
        acceptor.join(5000)
        runCatching { server.close() }
        runCatching { pfd.close() }

        if (!done || p.exitValue() != 0 || sendError != null) {
            fail("$label: child exit=${if (done) p.exitValue() else "timeout"} sendError=$sendError out='$stdout' err='${stderr.take(300)}'")
            return
        }
        info("$label: child $stdout")

        fun section(key: String): String? =
            Regex("\"$key\":(\\{[^}]*\\}|null)").find(stdout)?.groupValues?.get(1)

        val received = section("received_fd")
        if (received?.contains("\"ok\":true") == true && received.contains("\"gguf\":true")) {
            pass("$label: received fd mmaps and reads as GGUF after the plugin closed its copy (${rate(received)})")
        } else {
            fail("$label: received fd unusable: $received")
        }
        val viaPath = section("proc_self_fd")
        if (viaPath?.contains("\"ok\":true") == true && viaPath.contains("\"gguf\":true")) {
            pass("$label: /proc/self/fd/N reopens by path in the child, so llama.cpp's path loader works (${rate(viaPath)})")
        } else {
            fail("$label: /proc/self/fd/N reopen failed in the child: $viaPath")
        }
        info("$label: parent's /proc/<ppid>/fd/<n> from the child: ${section("proc_parent_fd")}")
        if (plainPath != null) info("$label: plain path $plainPath from the child: ${section("plain_path")}")
    }

    private fun rate(json: String): String {
        val ms = Regex("\"touch_ms\":([0-9.]+)").find(json)?.groupValues?.get(1)
        val mbs = Regex("\"mb_per_s\":([0-9.]+)").find(json)?.groupValues?.get(1)
        return "paged through in $ms ms, $mbs MB/s"
    }

    private fun readlink(path: String): String =
        runCatching { android.system.Os.readlink(path) }.getOrElse { "<${it.message}>" }
}
