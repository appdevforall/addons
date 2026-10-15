package com.itsaky.androidide.plugins.aicore.viewmodel

/**
 * A model turn the backend refused for its settings (a refused key, billing, a model the account
 * lacks), as it reported through `FailureKind.CONFIGURATION`. Its error row offers Open settings.
 */
internal class BackendSettingsException(message: String) : RuntimeException(message)
