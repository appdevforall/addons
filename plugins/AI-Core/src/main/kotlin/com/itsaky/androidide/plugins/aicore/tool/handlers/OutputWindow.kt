package com.itsaky.androidide.plugins.aicore.tool.handlers

/** Marks where a window dropped text, shared so the build and log windows read the same. */
internal const val TRUNCATION_MARKER = "...[truncated]..."

/** The slice of a build or app/IDE log handed to the model, and whether it starts at an error. */
internal data class OutputWindow(
    val text: String,
    val anchoredOnError: Boolean,
)
