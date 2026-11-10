package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A started child process; the small surface the runner needs, so that JVM tests can replay recorded output. */
interface RunningProcess {
    val stdout: InputStream
    val stderr: InputStream

    /** Closes the child's stdin (the engine reads none). */
    fun closeStdin()

    /** Waits up to [timeoutMs]; true when the process has exited. */
    fun waitFor(timeoutMs: Long): Boolean

    /** Exit value; only valid after [waitFor] returned true. */
    fun exitValue(): Int

    fun destroy()
    fun destroyForcibly()
    fun isAlive(): Boolean
}

/** Starts a child process. May throw (`IOException`, `SecurityException`, ...) when the start is refused. */
interface ProcessLauncher {
    /** [argv] is an argument list, never a shell string; [env] is the complete environment (the inherited one is cleared). */
    fun start(argv: List<String>, workDir: File, env: Map<String, String>): RunningProcess
}

/** The real launcher: `ProcessBuilder` with an argument list, a cleared environment and the given working directory. */
class JvmProcessLauncher : ProcessLauncher {
    override fun start(argv: List<String>, workDir: File, env: Map<String, String>): RunningProcess {
        val pb = ProcessBuilder(argv)
        pb.directory(workDir)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)
        val p = try {
            pb.start()
        } catch (e: IOException) {
            throw e
        }
        return object : RunningProcess {
            override val stdout: InputStream get() = p.inputStream
            override val stderr: InputStream get() = p.errorStream
            override fun closeStdin() = p.outputStream.close()
            override fun waitFor(timeoutMs: Long) = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            override fun exitValue() = p.exitValue()
            override fun destroy() = p.destroy()
            override fun destroyForcibly() { p.destroyForcibly() }
            override fun isAlive() = p.isAlive
        }
    }
}
