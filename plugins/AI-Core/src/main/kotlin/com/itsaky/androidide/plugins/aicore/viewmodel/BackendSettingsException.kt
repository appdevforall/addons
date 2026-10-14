package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind

/**
 * A model turn the backend refused for its settings (a refused key, billing, a model the account
 * lacks), as it reported through `FailureKind.CONFIGURATION`. Its error row offers Open settings.
 */
internal class BackendSettingsException(message: String) : BackendFailure(message, FailureKind.CONFIGURATION)
