package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadDiagnostics.Diagnosis
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the diagnosis → string-resource routing that used to live in LocalLlmBackend. Each case
 * must reach its own message; a copy/paste slip here would tell the user the wrong fix.
 */
class ModelLoadMessagesTest {

    private val context = mockk<Context>(relaxed = true)
    private val messages = ModelLoadMessages(context)

    @Test
    fun givenFileMissing_whenDescribed_thenMissingString() {
        messages.describe(Diagnosis.FileMissing)
        verify { context.getString(R.string.llm_load_error_missing) }
    }

    @Test
    fun givenSourceUnavailable_whenDescribed_thenUnavailableString() {
        // Must not share FileMissing's wording: one asks for a path, the other for a fresh pick.
        messages.describe(Diagnosis.SourceUnavailable)
        verify { context.getString(R.string.llm_load_error_unavailable) }
    }

    @Test
    fun givenSourceNotSeekable_whenDescribed_thenNotSeekableString() {
        // Must not share the corrupt-model wording: the model is fine, its location is the problem.
        messages.describe(Diagnosis.SourceNotSeekable)
        verify { context.getString(R.string.llm_load_error_not_seekable) }
    }

    @Test
    fun givenFileEmpty_whenDescribed_thenEmptyString() {
        messages.describe(Diagnosis.FileEmpty)
        verify { context.getString(R.string.llm_load_error_empty) }
    }

    @Test
    fun givenNotGguf_whenDescribed_thenNotGgufString() {
        messages.describe(Diagnosis.NotGguf)
        verify { context.getString(R.string.llm_load_error_not_gguf) }
    }

    @Test
    fun givenModelBusy_whenDescribed_thenBusyString() {
        messages.describe(Diagnosis.ModelBusy)
        verify { context.getString(R.string.llm_load_error_busy) }
    }

    @Test
    fun givenInitializationFailed_whenDescribed_thenRuntimeString() {
        messages.describe(Diagnosis.InitializationFailed)
        verify { context.getString(R.string.llm_load_error_runtime) }
    }

    @Test
    fun givenUnsupportedOrCorrupt_whenDescribed_thenUnsupportedString() {
        messages.describe(Diagnosis.UnsupportedOrCorrupt)
        verify { context.getString(R.string.llm_load_error_unsupported) }
    }

    @Test
    fun givenLowMemory_whenDescribed_thenNeedBeforeAvailableInBinaryGb() {
        // Argument order matters: "needs %1$s but only %2$s is free" reads backwards if swapped.
        messages.describe(Diagnosis.LowMemory(neededBytes = 3L shl 30, availableBytes = 1L shl 30))
        verify { context.getString(R.string.llm_load_error_low_memory, "3.0 GB", "1.0 GB") }
    }

    @Test
    fun givenZeroFreeMemory_whenDescribed_thenReportsZeroInMegabytes() {
        // 0 free bytes must reach the user as "0.0 MB"; "0.0 GB" would read as a formatting bug.
        every { context.getString(R.string.llm_load_error_low_memory, any(), any()) } returns "low"
        assertEquals("low", messages.describe(Diagnosis.LowMemory(neededBytes = 1L shl 30, availableBytes = 0L)))
        verify { context.getString(R.string.llm_load_error_low_memory, "1.0 GB", "0.0 MB") }
    }

    @Test
    fun givenHeadroomFloor_whenDescribed_thenNeedRendersInMegabytes() {
        // The 256 MB floor is the figure users see most often; it must not collapse to "0.2 GB".
        every { context.getString(R.string.llm_load_error_low_memory, any(), any()) } returns "low"
        messages.describe(Diagnosis.LowMemory(neededBytes = 256L shl 20, availableBytes = 12L shl 20))
        verify { context.getString(R.string.llm_load_error_low_memory, "256.0 MB", "12.0 MB") }
    }
}
