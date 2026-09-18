package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadDiagnostics.Diagnosis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class ModelLoadDiagnosticsTest {

    /**
     * Diagnoses [file] as the backend does: its real size, and a factory that re-opens it. Since
     * ADFA-5253 the loader is handed a procfs path, so size and readability arrive separately
     * rather than being read off a `File`.
     */
    private fun diagnose(file: File, availableMemoryBytes: Long, nativeError: String? = null) =
        ModelLoadDiagnostics.diagnose(file.length(), availableMemoryBytes, nativeError) { streamOf(file) }

    private fun streamOf(file: File): InputStream? = if (file.isFile) file.inputStream() else null

    private fun tempFile(bytes: Int, magic: Boolean = true): File =
        File.createTempFile("model", ".gguf").apply {
            deleteOnExit()
            outputStream().use { out ->
                if (magic && bytes >= 4) {
                    out.write(byteArrayOf(0x47, 0x47, 0x55, 0x46)) // "GGUF"
                    out.write(ByteArray(bytes - 4))
                } else {
                    out.write(ByteArray(bytes))
                }
            }
        }

    @Test
    fun givenEmptyFile_whenDiagnosed_thenFileEmpty() {
        val d = diagnose(tempFile(0), availableMemoryBytes = 8L shl 30)
        assertEquals(Diagnosis.FileEmpty, d)
    }

    @Test
    fun givenNonGgufContent_whenDiagnosed_thenNotGguf() {
        val d = diagnose(tempFile(2048, magic = false), availableMemoryBytes = 8L shl 30)
        assertEquals(Diagnosis.NotGguf, d)
    }

    @Test
    fun givenValidGgufAndLowMemory_whenDiagnosed_thenLowMemory() {
        // 1 MB "model", only 512 KB free -> below the headroom floor.
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 512L shl 10)
        assertTrue(d is Diagnosis.LowMemory)
        // neededBytes reports the headroom that tripped the check; here the 256 MB floor dominates.
        assertEquals(256L shl 20, (d as Diagnosis.LowMemory).neededBytes)
    }

    @Test
    fun givenLargeModelAndHeadroomBelowFileSize_whenDiagnosed_thenNotLowMemory() {
        // Guards the mmap property: free RAM under the file size is not itself a shortage.
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 512L shl 20) // above the floor
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenValidGgufAndAmpleMemory_whenDiagnosed_thenUnsupportedOrCorrupt() {
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 8L shl 30)
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenUnknownMemory_whenDiagnosed_thenNotLowMemory() {
        // availMem < 0 (unreadable) must not be treated as "no memory".
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = -1L)
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenZeroFreeMemory_whenDiagnosed_thenLowMemory() {
        // 0 free bytes is a genuine out-of-memory reading (only a negative value means "unknown"),
        // so it must classify as low memory rather than falling through to unsupported/corrupt.
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 0L)
        assertTrue(d is Diagnosis.LowMemory)
        assertEquals(0L, (d as Diagnosis.LowMemory).availableBytes)
    }

    @Test
    fun givenAlreadyLoadedError_whenDiagnosed_thenModelBusy() {
        // A valid file with ample RAM but the run loop is busy must not be blamed on the file.
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 8L shl 30, nativeError = "Model already loaded")
        assertEquals(Diagnosis.ModelBusy, d)
    }

    @Test
    fun givenAlreadyLoadedErrorAndLowMemory_whenDiagnosed_thenModelBusyWinsOverMemory() {
        // "Already loaded" is a state issue, so it outranks the low-memory heuristic.
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 512L shl 10, nativeError = "Model already loaded")
        assertEquals(Diagnosis.ModelBusy, d)
    }

    @Test
    fun givenContextAllocError_whenDiagnosed_thenInitializationFailed() {
        // A valid file with ample RAM that still fails to allocate its context is memory pressure,
        // not a corrupt file.
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 8L shl 30, nativeError = "new_context() failed")
        assertEquals(Diagnosis.InitializationFailed, d)
    }

    @Test
    fun givenUnrecognizedError_whenDiagnosed_thenUnsupportedOrCorrupt() {
        // An unknown native message on a valid-looking file falls back to the safe default.
        val d = diagnose(tempFile(1 shl 20), availableMemoryBytes = 8L shl 30, nativeError = "something unexpected")
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenMemoryBelowTheComputeBuffers_whenRefuseBeforeLoad_thenRefused() {
        val refusal = ModelLoadDiagnostics.refuseBeforeLoad(availableMemoryBytes = 128L shl 20)
        assertEquals(Diagnosis.LowMemory(256L shl 20, 128L shl 20), refusal)
    }

    @Test
    fun givenZeroFreeMemory_whenRefuseBeforeLoad_thenRefused() {
        // 0 free bytes is a genuine out-of-memory reading, not an unreadable one.
        assertEquals(0L, ModelLoadDiagnostics.refuseBeforeLoad(0L)?.availableBytes)
    }

    @Test
    fun givenUnknownMemory_whenRefuseBeforeLoad_thenAllowed() {
        // availMem < 0 (unreadable) must fail open, like the pre-flight it defers to.
        assertNull(ModelLoadDiagnostics.refuseBeforeLoad(-1L))
    }

    @Test
    fun givenMemoryTheAiAssistantPreflightCallsTight_whenRefuseBeforeLoad_thenAllowed() {
        // The gate must never refuse what "Proceed anyway" authorized: an 8.5 GB model with
        // 1.5 GB free is TIGHT there (free RAM clears its 768 MB run cost), so the load proceeds
        // even though diagnose()'s size/4 attribution headroom would be 2.1 GB. See ADFA-1798.
        assertNull(ModelLoadDiagnostics.refuseBeforeLoad(availableMemoryBytes = 1536L shl 20))
    }

    @Test
    fun givenUnknownSize_whenDiagnosed_thenNotReportedAsEmpty() {
        // A provider that cannot report a size hands back -1. Only 0 is genuinely empty; treating
        // a negative as "<= 0" would tell every such user their model is a truncated download.
        val file = tempFile(1 shl 20)
        val d = ModelLoadDiagnostics.diagnose(-1L, availableMemoryBytes = 8L shl 30) { streamOf(file) }

        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenAnUnreadableSourceAndAValidSize_whenDiagnosed_thenUnavailableRatherThanNotGguf() {
        // "Re-download the model" is the wrong instruction for a file that is simply out of reach.
        val d = ModelLoadDiagnostics.diagnose(1L shl 20, availableMemoryBytes = 8L shl 30) { null }

        assertEquals(Diagnosis.SourceUnavailable, d)
    }

    @Test
    fun givenAContentUri_whenDiagnoseUnopenable_thenSourceUnavailable() {
        val d = ModelLoadDiagnostics.diagnoseUnopenable("content://com.android.providers/document/1")

        assertEquals(Diagnosis.SourceUnavailable, d)
    }

    @Test
    fun givenAFilesystemPath_whenDiagnoseUnopenable_thenFileMissing() {
        // A configured plain path that is gone really is a missing file, not a withdrawn grant.
        val d = ModelLoadDiagnostics.diagnoseUnopenable("/sdcard/Download/model.gguf")

        assertEquals(Diagnosis.FileMissing, d)
    }
}
