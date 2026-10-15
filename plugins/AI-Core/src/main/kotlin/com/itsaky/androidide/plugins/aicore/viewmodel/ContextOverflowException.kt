package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind

/**
 * A model turn the backend refused because the request did not fit its context window, as it
 * reported through `FailureKind.CONTEXT_TOO_LONG`. A Retry after it compacts the history first.
 */
internal class ContextOverflowException(message: String) : BackendFailure(message, FailureKind.CONTEXT_TOO_LONG)
