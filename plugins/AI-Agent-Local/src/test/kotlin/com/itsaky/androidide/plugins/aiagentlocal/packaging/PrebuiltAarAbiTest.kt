package com.itsaky.androidide.plugins.aiagentlocal.packaging

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the committed llama.cpp AAR against a bad regeneration.
 * The plugin filters packaging to one ABI, so the AAR losing that ABI - or a
 * partial native build - would only surface as an UnsatisfiedLinkError on device.
 */
class PrebuiltAarAbiTest {

    // Gradle passes the AAR path; the relative fallback keeps an IDE test runner - which sets no
    // system properties but does run from the module directory - reporting a real assertion.
    private val aar: File = File(System.getProperty("prebuiltAarPath") ?: AAR_RELATIVE_PATH)

    private val expectedAbi: String = System.getProperty("expectedAbi") ?: DEFAULT_ABI

    // One pass over the archive; every test reads this map instead of reopening it.
    private val jniLibs: Map<String, Long> by lazy {
        ZipFile(aar).use { zip ->
            zip.entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.startsWith("jni/") && it.name.endsWith(".so") }
                .associate { it.name to it.size }
        }
    }

    private fun abisPresent(): Set<String> =
        jniLibs.keys.map { it.removePrefix("jni/").substringBefore('/') }.toSet()

    @Test
    fun givenTheCommittedAar_whenLocated_thenItExists() {
        assertTrue("Missing prebuilt AAR at ${aar.absolutePath}", aar.isFile)
    }

    @Test
    fun givenTheCommittedAar_whenInspected_thenItShipsTheExpectedAbi() {
        assertTrue(
            "AAR carries $expectedAbi? Found ${abisPresent()}",
            abisPresent().contains(expectedAbi),
        )
    }

    @Test
    fun givenTheCommittedAar_whenInspected_thenTheExpectedAbiCarriesEveryNativeLibrary() {
        // A partial native build drops one .so and fails at load time, not at build time.
        val actual = jniLibs.keys
            .filter { it.startsWith("jni/$expectedAbi/") }
            .map { it.substringAfterLast('/') }
            .toSet()
        // Subset, not equality: an upstream llama.cpp bump may legitimately add a library.
        assertEquals(
            "Prebuilt AAR is missing native libraries (found $actual)",
            emptySet<String>(),
            REQUIRED_LIBS - actual,
        )
    }

    @Test
    fun givenTheCommittedAar_whenInspected_thenTheJniWrapperIsNotEmpty() {
        val size = jniLibs["jni/$expectedAbi/libllama-android.so"] ?: -1
        assertTrue("libllama-android.so is missing or empty (size=$size)", size > 0)
    }

    @Test
    fun givenTheCommittedAar_whenInspected_thenEveryLibraryIsAlignedForLargePages() {
        // Nothing else holds -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES: dropped, the .so still builds,
        // still loads on a 4 KB device, and fails only on a 16 KB one nobody tests on.
        val offenders = jniLibs.keys
            .filter { it.startsWith("jni/$expectedAbi/") }
            .associateWith { loadSegmentAlignments(it) }
            .filterValues { aligns -> aligns.isEmpty() || aligns.any { it < MIN_SEGMENT_ALIGNMENT } }
        assertEquals(
            "Native libraries are not built for $MIN_SEGMENT_ALIGNMENT-byte pages" +
                " (PT_LOAD p_align per library; empty means the ELF could not be read): $offenders",
            emptyMap<String, List<Long>>(),
            offenders,
        )
    }

    /**
     * The PT_LOAD segment alignments of one ELF64 shared library in the AAR.
     *
     * @param entryName zip entry path of the `.so`
     * @return one alignment per PT_LOAD segment; empty when the entry is not a readable ELF64, which
     *   the caller treats as a failure rather than a pass
     */
    private fun loadSegmentAlignments(entryName: String): List<Long> {
        // Only the ELF header and the program-header table are read, and both sit at the front.
        val head = ZipFile(aar).use { zip ->
            val entry = zip.getEntry(entryName) ?: return emptyList()
            zip.getInputStream(entry).use { it.readNBytes(ELF_PROBE_BYTES) }
        }
        if (head.size < ELF64_HEADER_BYTES) return emptyList()
        val elf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if (elf.getInt(0) != ELF_MAGIC_LE || head[EI_CLASS].toInt() != ELF_CLASS_64) return emptyList()

        val tableOffset = elf.getLong(E_PHOFF)
        val entrySize = elf.getShort(E_PHENTSIZE).toInt()
        val entryCount = elf.getShort(E_PHNUM).toInt()
        return (0 until entryCount).mapNotNull { index ->
            val at = tableOffset + index.toLong() * entrySize
            // A table reaching past the probe is an unfamiliar layout, so report nothing read.
            if (at < 0L || at + entrySize > head.size) return emptyList()
            val offset = at.toInt()
            if (elf.getInt(offset) != PT_LOAD) null else elf.getLong(offset + P_ALIGN)
        }
    }

    private companion object {
        const val DEFAULT_ABI = "arm64-v8a"

        const val AAR_RELATIVE_PATH = "libs/v8/llama-v8-release.aar"

        /** The 16 KB page size arm64 Android may use; segments must align to it or the loader rejects. */
        const val MIN_SEGMENT_ALIGNMENT = 16384L

        // ELF64 offsets and values, from the spec; the program-header table follows the 64-byte header.
        const val ELF_PROBE_BYTES = 4096
        const val ELF64_HEADER_BYTES = 64

        /** `\x7fELF` read as a little-endian int, the byte order every Android ABI uses. */
        const val ELF_MAGIC_LE = 0x464C457F
        const val EI_CLASS = 4
        const val ELF_CLASS_64 = 2
        const val E_PHOFF = 32
        const val E_PHENTSIZE = 54
        const val E_PHNUM = 56
        const val PT_LOAD = 1
        const val P_ALIGN = 48

        /** The libraries llama.cpp has to produce for the wrapper to load at all. */
        val REQUIRED_LIBS = setOf(
            "libggml-base.so",
            "libggml-cpu.so",
            "libggml.so",
            "libllama-android.so",
            "libllama.so",
            "libomp.so",
        )
    }
}
