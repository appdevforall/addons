package com.itsaky.androidide.plugins.aiagentmcp.client

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.errors.runCatchingRecoverable
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "$LOG_PREFIX.McpCallHandle"

/**
 * One tool call's way to be stopped: its cancel flag and the connection its current request uses.
 *
 * Per call, not per session. Every call to a server, the health probe's listing and the relisting
 * of a SQLite debugger entry share one [McpSession]; a connection slot on the session held whichever
 * request had published last, so a cancel dropped another request's socket (the probe's listing
 * was then marked degraded) and the cancelled call ran on to its timeout. A listing passes no
 * handle, so no call's cancel can reach it.
 *
 * The handshake, the same one the tool worker uses with its flag: a request publishes its
 * connection and then reads the flag ([attach]); [cancel] sets the flag and then takes the
 * connection. Both sides are volatile, so however the two interleave, one sees the other: either
 * the request finds the flag, or the cancel finds the connection. A cancel that lands before the
 * request has a connection at all, while it reads its credentials from the Keystore for instance,
 * is caught by the first.
 *
 * Finding the connection is not enough on its own: [attach] runs before the connection is
 * connected, and a `disconnect()` then does nothing (Android's and the JDK's both return early with
 * no connection to drop), so the first write would connect and send the request anyway. The
 * request reads the flag a second time once its connection is up, before it writes anything
 * ([throwIfCancelled]); a cancel between the two reads is caught there. One after the second read
 * disconnects a connection that is up: Android then fails the write rather than sending it. (The
 * JDK, which only the tests run on, reconnects instead.)
 */
class McpCallHandle {

    @Volatile private var cancelled = false

    private val connection = AtomicReference<HttpURLConnection?>(null)

    /** Whether [cancel] has been called. */
    val isCancelled: Boolean get() = cancelled

    /**
     * Stops the call: a request not yet sent never is, and one in flight loses its socket.
     *
     * A disconnect that fails with an Exception or a LinkageError is survived, since the call is
     * being abandoned anyway; any other Error, an OutOfMemoryError above all, propagates.
     */
    fun cancel() {
        cancelled = true
        connection.getAndSet(null)?.let { conn ->
            runCatchingRecoverable { conn.disconnect() }
                .onFailure { e -> Log.d(TAG, "Dropping a cancelled call's connection failed: $e") }
        }
    }

    /**
     * Publishes [conn] as the connection this call's current request uses, then reads the flag.
     *
     * Called before [conn] is connected; [throwIfCancelled] reads the flag again once it is.
     *
     * @param conn the request's connection, opened but not yet connected.
     * @throws InterruptedIOException when the call was cancelled, which is how a dropped connection
     *   reports a cancel too.
     */
    internal fun attach(conn: HttpURLConnection) {
        connection.set(conn)
        if (cancelled) {
            connection.compareAndSet(conn, null)
            throw InterruptedIOException("The tool call was cancelled before its request was sent")
        }
    }

    /**
     * Reads the flag again, once the request's connection is connected and before anything is
     * written to it: a cancel that landed after [attach] read the flag disconnected a connection
     * that was not connected yet, which does nothing.
     *
     * @throws InterruptedIOException when the call was cancelled.
     */
    internal fun throwIfCancelled() {
        if (cancelled) throw InterruptedIOException("The tool call was cancelled before its request was sent")
    }

    /**
     * Forgets the connection once its request has ended, so a late [cancel] does not disconnect a
     * connection whose socket may have gone back to the pool.
     */
    internal fun detach() {
        connection.set(null)
    }
}
