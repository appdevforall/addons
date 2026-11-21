package com.itsaky.androidide.plugins.aiagentmcp.testing

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * Waits until [waiter] has finished, or is blocked entering a monitor that [holder] holds. A test
 * that lets one thread run into another's lock uses this to tell the two outcomes apart without
 * depending on how long anything takes.
 *
 * @return the state [waiter] was found in: TERMINATED or BLOCKED.
 * @throws AssertionError when neither happens within ten seconds.
 */
fun awaitFinishedOrBlockedBy(waiter: Thread, holder: Thread): Thread.State {
    val threads = ManagementFactory.getThreadMXBean()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        val state = waiter.state
        if (state == Thread.State.TERMINATED) return state
        if (state == Thread.State.BLOCKED && threads.getThreadInfo(waiter.id)?.lockOwnerId == holder.id) {
            return state
        }
        Thread.yield()
    }
    throw AssertionError("${waiter.name} neither finished nor waited for ${holder.name}: ${waiter.state}")
}
