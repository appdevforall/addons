package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.aiagentlocal.format.ByteSize
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadDiagnostics.Diagnosis

/**
 * Presentation layer for [ModelLoadDiagnostics]: renders a [Diagnosis] as user-facing text.
 *
 * Split out of [LocalLlmBackend] so the inference engine holds no Android string resources — it
 * classifies and throws, this class decides wording. Because a [ModelLoadException] crosses the
 * plugin boundary (consumers live in other plugins with isolated classloaders and cannot see
 * [Diagnosis]), the text has to be resolved on this side of the boundary; the exception still
 * carries the structured [Diagnosis] so any in-module caller can re-render it differently.
 */
internal class ModelLoadMessages(private val context: Context) {

    /**
     * @param diagnosis the classified cause of a load failure
     * @return display-ready text explaining the failure and what the user can do about it
     */
    fun describe(diagnosis: Diagnosis): String = when (diagnosis) {
        Diagnosis.FileMissing -> context.getString(R.string.llm_load_error_missing)
        Diagnosis.SourceUnavailable -> context.getString(R.string.llm_load_error_unavailable)
        Diagnosis.SourceNotSeekable -> context.getString(R.string.llm_load_error_not_seekable)
        Diagnosis.SourceNotReopenable -> context.getString(R.string.llm_load_error_not_reopenable)
        Diagnosis.FileEmpty -> context.getString(R.string.llm_load_error_empty)
        Diagnosis.NotGguf -> context.getString(R.string.llm_load_error_not_gguf)
        is Diagnosis.LowMemory -> context.getString(
            R.string.llm_load_error_low_memory,
            ByteSize.format(diagnosis.neededBytes),
            ByteSize.format(diagnosis.availableBytes),
        )
        Diagnosis.ModelBusy -> context.getString(R.string.llm_load_error_busy)
        Diagnosis.InitializationFailed -> context.getString(R.string.llm_load_error_runtime)
        Diagnosis.UnsupportedOrCorrupt -> context.getString(R.string.llm_load_error_unsupported)
    }
}
