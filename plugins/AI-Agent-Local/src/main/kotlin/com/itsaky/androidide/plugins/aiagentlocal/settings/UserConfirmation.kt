package com.itsaky.androidide.plugins.aiagentlocal.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A yes/no question for the user that a coroutine can await, so a flow needing consent stays one
 * readable sequence instead of being cut in half by a callback. Holds no Android types. Thread-safe,
 * and one question at a time: a second [ask] waits for the first to be answered.
 */
internal class UserConfirmation<T : Any> {

    // State, not an event: a one-shot stream drops the question if its collector dies mid-delivery.
    private val outstanding = MutableStateFlow<T?>(null)

    /**
     * The question to put to the user, re-emitted to each new collector until it is answered — so
     * the UI must be idempotent (see `AiSettingsFragment.showMemoryWarning`).
     */
    val requests: Flow<T> = outstanding.filterNotNull()

    /**
     * Whether a question is currently waiting on the user. False after process death, where the UI
     * may have been restored around a question this object no longer knows anything about — see
     * `AiSettingsFragment.dropStaleMemoryWarning`.
     */
    val hasOutstandingRequest: Boolean get() = outstanding.value != null

    /** Serializes questions, so [pending] can only ever describe one of them. */
    private val askMutex = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Publishes [request] and suspends until the UI answers. Cancelling the caller (the ViewModel
     * being cleared, say) abandons the question rather than resolving it either way.
     *
     * @param request what to ask
     * @return the user's answer
     */
    suspend fun ask(request: T): Boolean = askMutex.withLock {
        val answer = CompletableDeferred<Boolean>()
        pending = answer
        try {
            outstanding.value = request
            answer.await()
        } finally {
            pending = null
            // Withdraws the question, so the next collector is not asked one nobody is waiting on.
            outstanding.value = null
        }
    }

    /**
     * Answers the outstanding question; a no-op when there is none, or when it already has an
     * answer, so a duplicate reply from a recreated dialog is harmless.
     *
     * @param granted true to proceed, false to decline
     */
    fun answer(granted: Boolean) {
        pending?.complete(granted)
    }
}
