package com.itsaky.androidide.plugins.aicore.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Upper bound on waiting for a cleared ViewModel's coroutines to end. */
private const val CLEAR_TIMEOUT_MS = 5_000L

/**
 * Clears each of [viewModels] as its owner would, then waits until every coroutine it started has
 * ended.
 *
 * Call it before `Dispatchers.resetMain()`. A stopped run unwinds on an IO thread and switches to
 * Main for its cleanup; when that switch overlaps resetMain(), kotlinx-coroutines-test throws
 * "Dispatchers.Main is used concurrently with setting it" and fails whichever test is tearing
 * down at that moment.
 */
internal fun clearAndAwait(viewModels: Iterable<ViewModel>) {
    viewModels.forEach { viewModel ->
        val scopeJob = viewModel.viewModelScope.coroutineContext.job
        val store = ViewModelStore()
        ViewModelProvider(store, Owning(viewModel))[viewModel.javaClass]
        store.clear()
        runBlocking { withTimeout(CLEAR_TIMEOUT_MS) { scopeJob.join() } }
    }
}

/** Hands an existing ViewModel to a store, the public way to put one there. */
private class Owning(private val viewModel: ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
}
