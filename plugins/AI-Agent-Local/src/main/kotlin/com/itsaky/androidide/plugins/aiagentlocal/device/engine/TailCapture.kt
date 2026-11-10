package com.itsaky.androidide.plugins.aiagentlocal.device.engine

/** Keeps the last [cap] bytes written to it; older bytes are dropped, so memory is bounded whatever the child prints. */
internal class TailCapture(private val cap: Int) {
    private val buf = ByteArray(cap)
    private var filled = 0
    private var start = 0

    /** Total bytes seen, also those dropped. */
    var total = 0L
        private set

    val truncated: Boolean get() = total > cap

    @Synchronized
    fun write(b: ByteArray, off: Int, len: Int) {
        total += len
        if (len >= cap) { // only the last cap bytes of this block matter
            System.arraycopy(b, off + len - cap, buf, 0, cap)
            start = 0
            filled = cap
            return
        }
        val pos = (start + filled) % cap
        val first = minOf(len, cap - pos)
        System.arraycopy(b, off, buf, pos, first)
        if (len > first) System.arraycopy(b, off + first, buf, 0, len - first)
        val newFilled = filled + len
        if (newFilled > cap) {
            start = (start + (newFilled - cap)) % cap
            filled = cap
        } else {
            filled = newFilled
        }
    }

    @Synchronized
    fun bytes(): ByteArray {
        val out = ByteArray(filled)
        for (i in 0 until filled) out[i] = buf[(start + i) % cap]
        return out
    }
}
