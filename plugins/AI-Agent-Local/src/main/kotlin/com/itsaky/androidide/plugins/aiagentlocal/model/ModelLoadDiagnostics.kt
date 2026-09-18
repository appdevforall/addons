package com.itsaky.androidide.plugins.aiagentlocal.model

import java.io.InputStream

/**
 * Classifies why a native model load failed, as a pure function of the file, free memory, and the
 * native loader's error text, so it is unit-testable off-device. The caller maps the result to a
 * user-facing message from strings.xml (this object holds no Context and no display text).
 * See ADFA "Better error messages …".
 */
object ModelLoadDiagnostics {

    /** Headroom floor: a small model still needs a KV cache, which scales with context, not size. */
    private const val MIN_HEADROOM_BYTES = 256L * 1024 * 1024

    /** Floor for [refuseBeforeLoad]; the same allowance the pre-flight estimate budgets for. */
    private const val MIN_RUN_BYTES = ModelMemory.RUN_BUFFER_BYTES

    /** Marks a reference the document provider owns, rather than a plain filesystem path. */
    private const val CONTENT_SCHEME = "content://"

    /** Most likely cause of a load failure; the caller resolves each case to a user-facing string. */
    sealed interface Diagnosis {
        /** A configured filesystem path with nothing at it — the file was deleted or moved. */
        data object FileMissing : Diagnosis

        /**
         * A picked document that can no longer be reached: deleted, renamed, on unmounted storage,
         * or its persisted read grant was revoked (clearing the IDE's app data does that).
         * Distinct from [FileMissing] because the fix is to pick the model again, not to restore a
         * path — and distinct from [UnsupportedOrCorrupt], which would send the user chasing a
         * corruption that isn't there. See ADFA-5253.
         */
        data object SourceUnavailable : Diagnosis

        /**
         * The picked document is streamed rather than stored on the device, so its descriptor is a
         * pipe the loader cannot `mmap` or re-open. Its own case because the alternative is
         * reporting a perfectly good model as corrupt; the fix is to download it (ADFA-5253).
         */
        data object SourceNotSeekable : Diagnosis

        /**
         * The document opened, but the path standing in for it cannot be opened by name — the only
         * way the native loader uses it. Its own case because "pick the model again" is useless
         * advice for a file sitting where the user left it; see [OpenModelFile.isReopenable].
         */
        data object SourceNotReopenable : Diagnosis

        data object FileEmpty : Diagnosis
        data object NotGguf : Diagnosis
        /**
         * @param neededBytes the free-RAM headroom the check required (see [diagnose])
         * @param availableBytes free RAM the OS reported
         */
        data class LowMemory(val neededBytes: Long, val availableBytes: Long) : Diagnosis

        /** A model is already resident in the process-global run loop (a state issue, not the file). */
        data object ModelBusy : Diagnosis

        /** The file is a valid GGUF but the loader couldn't allocate its context/buffers (memory pressure). */
        data object InitializationFailed : Diagnosis

        /** The file is a valid GGUF but the loader rejected the weights themselves (format/quantization/corruption). */
        data object UnsupportedOrCorrupt : Diagnosis
    }

    /**
     * Why an already-open model failed to load.
     *
     * Takes the model's size and a stream factory rather than a path: since ADFA-5253 the loader is
     * handed the procfs path of a held descriptor, on which `File.length()` reports 0 and
     * `File.exists()` says nothing about the underlying document.
     *
     * Weights are mmap'd, so this tests a conservative headroom rather than the file size:
     * overestimating would blame a corrupt model on memory. [ContextSizePolicy] charges the mmap'd
     * weights instead, because it sizes the cache before the load pages them in.
     *
     * @param sizeBytes the model's size, or negative if the source could not report one
     * @param availableMemoryBytes free RAM reported by the OS, or negative if unknown
     * @param nativeError the load failure's message text, or null when unavailable
     * @param openStream opens a fresh read stream over the model, or returns null when it is gone
     * @return the most likely cause of the load failure
     */
    fun diagnose(
        sizeBytes: Long,
        availableMemoryBytes: Long,
        nativeError: String? = null,
        openStream: () -> InputStream?,
    ): Diagnosis {
        // Only a NEGATIVE size means "unknown"; 0 is a genuine empty file.
        if (sizeBytes == 0L) return Diagnosis.FileEmpty

        // Checked before the header read so a source that vanished under us is not mis-reported as
        // a malformed one — "pick it again" and "it's corrupt" send the user to different places.
        if (!isReadable(openStream)) return Diagnosis.SourceUnavailable
        if (!GgufModelInspector.isGguf(openStream)) return Diagnosis.NotGguf

        // "Already loaded" is a run-loop state problem, not a file or memory one, so report it
        // before the memory heuristic — otherwise a busy loop is mis-reported as low memory.
        if (indicatesModelBusy(nativeError)) return Diagnosis.ModelBusy

        // Headroom only: a fraction of the file, floored for KV-cache-dominated small models.
        val requiredHeadroomBytes = maxOf(MIN_HEADROOM_BYTES, sizeBytes / 4)
        // Only a NEGATIVE reading means "unknown"; 0 is a genuine out-of-memory reading.
        if (availableMemoryBytes in 0L until requiredHeadroomBytes) {
            return Diagnosis.LowMemory(requiredHeadroomBytes, availableMemoryBytes)
        }

        // The file is a valid GGUF and RAM looks sufficient, so trust the native failure reason:
        // an allocation failure points at memory pressure, anything else at an unsupported/corrupt model.
        return if (indicatesInitFailure(nativeError)) Diagnosis.InitializationFailed
        else Diagnosis.UnsupportedOrCorrupt
    }

    /**
     * Why a model could not be opened at all, before any load was attempted.
     *
     * @param modelReference the configured model, as a `content://` URI or a filesystem path
     */
    fun diagnoseUnopenable(modelReference: String): Diagnosis =
        if (modelReference.startsWith(CONTENT_SCHEME)) Diagnosis.SourceUnavailable
        else Diagnosis.FileMissing

    /**
     * Whether to refuse a load outright, before ggml aborts the process trying it. Weighs only the
     * compute buffers, so it stays far more permissive than [diagnose]'s attribution headroom:
     * the memory-warning dialog lets the user proceed, and a refusal here must not overrule that.
     *
     * @param availableMemoryBytes free RAM reported by the OS, or negative if unknown
     * @return the shortfall to refuse with, or null to attempt the load
     */
    fun refuseBeforeLoad(availableMemoryBytes: Long): Diagnosis.LowMemory? =
        // Only a NEGATIVE reading means "unknown"; 0 is a genuine out-of-memory reading.
        if (availableMemoryBytes in 0L until MIN_RUN_BYTES) {
            Diagnosis.LowMemory(MIN_RUN_BYTES, availableMemoryBytes)
        } else {
            null
        }

    /** Whether the model can still be opened for reading at all. */
    private fun isReadable(openStream: () -> InputStream?): Boolean = try {
        openStream()?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    // The markers below mirror the messages thrown by LLamaAndroid.load(); keep them in sync with
    // that file. Matching on text is best-effort — an unrecognized message falls back to
    // UnsupportedOrCorrupt, which is the safe default for a valid-looking file.
    private fun indicatesModelBusy(nativeError: String?): Boolean =
        nativeError?.contains("already loaded", ignoreCase = true) == true

    private fun indicatesInitFailure(nativeError: String?): Boolean {
        val error = nativeError ?: return false
        return error.contains("new_context", ignoreCase = true) ||
            error.contains("new_batch", ignoreCase = true) ||
            error.contains("new_sampler", ignoreCase = true)
    }
}
