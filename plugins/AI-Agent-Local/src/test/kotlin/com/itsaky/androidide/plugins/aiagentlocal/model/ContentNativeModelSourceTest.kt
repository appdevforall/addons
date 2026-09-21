package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * A resident model cannot notice its own file being deleted — the descriptor the loader holds
 * keeps the inode alive — so the reachability probe is the only thing that can (ADFA-5253).
 */
class ContentNativeModelSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var source: ContentNativeModelSource

    @Before
    fun setup() {
        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        source = ContentNativeModelSource(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun givenAPathThatStillExists_whenProbed_thenItIsReachable() {
        val model = temporaryFolder.newFile("model.gguf")

        assertEquals(SourceReachability.REACHABLE, source.reachabilityOf(model.absolutePath))
    }

    @Test
    fun givenADeletedPath_whenProbed_thenItIsGone() {
        val model = temporaryFolder.newFile("model.gguf")
        assertTrue(model.delete())

        assertEquals(SourceReachability.GONE, source.reachabilityOf(model.absolutePath))
    }

    @Test
    fun givenADirectory_whenProbed_thenItIsGone() {
        // A path that resolves but holds no model must not read as a usable one.
        val directory = temporaryFolder.newFolder("models")

        assertEquals(SourceReachability.GONE, source.reachabilityOf(directory.absolutePath))
    }

    @Test
    fun givenAPathThatComesBackBeforeTheSecondAsk_whenProbed_thenItIsReachableRatherThanGone() {
        // The file branch is confirmed too, so reachabilityOf's contract — GONE is only ever an
        // answer given twice — holds for a legacy filesystem path as well. Created well inside the
        // confirmation delay: a late first ask can only make this pass, never fail.
        val model = temporaryFolder.newFile("model.gguf")
        assertTrue(model.delete())
        Thread { Thread.sleep(20L); model.writeBytes(ByteArray(8)) }.start()

        assertEquals(SourceReachability.REACHABLE, source.reachabilityOf(model.absolutePath))
    }

    @Test
    fun givenADocumentTheProviderStillServes_whenProbed_thenItIsReachable() {
        every { resolver.openFileDescriptor(any(), "r") } returns mockk<ParcelFileDescriptor>(relaxed = true)

        assertEquals(SourceReachability.REACHABLE, source.reachabilityOf(CONTENT_URI))
    }

    @Test
    fun givenADeletedDocument_whenProbed_thenItIsGone() {
        // What a deleted document actually does: the provider throws rather than returning null.
        every { resolver.openFileDescriptor(any(), "r") } throws java.io.FileNotFoundException()

        assertEquals(SourceReachability.GONE, source.reachabilityOf(CONTENT_URI))
    }

    @Test
    fun givenAProviderThatServesOnTheSecondAsk_whenProbed_thenItIsReachableRatherThanGone() {
        // ContentResolver converts every provider-death path into FileNotFoundException before it
        // returns, so the re-ask — which restarts the provider — is what separates it from a delete.
        every { resolver.openFileDescriptor(any(), "r") } throws java.io.FileNotFoundException() andThen
            mockk<ParcelFileDescriptor>(relaxed = true)

        assertEquals(SourceReachability.REACHABLE, source.reachabilityOf(CONTENT_URI))
    }

    @Test
    fun givenAProviderThatStaysSilentOnTheSecondAsk_whenProbed_thenTheAnswerIsUnknown() {
        // Neither ask established anything, and only GONE may cost a resident model its pages.
        every { resolver.openFileDescriptor(any(), "r") } throws java.io.FileNotFoundException() andThen
            null

        assertEquals(SourceReachability.UNKNOWN, source.reachabilityOf(CONTENT_URI))
    }

    @Test
    fun givenARevokedGrant_whenProbed_thenItIsGone() {
        // As final as a deletion from here: only a fresh pick can bring the document back.
        every { resolver.openFileDescriptor(any(), "r") } throws SecurityException("no grant")

        assertEquals(SourceReachability.GONE, source.reachabilityOf(CONTENT_URI))
    }

    @Test
    fun givenAProviderThatDied_whenProbed_thenTheAnswerIsUnknownRatherThanGone() {
        // A resident multi-GB model is the pressure that kills a provider; that is not a delete.
        // Instantiated through mockk: the unit-test android.jar stubs its constructor out.
        every { resolver.openFileDescriptor(any(), "r") } throws mockk<DeadObjectException>(relaxed = true)

        assertEquals(SourceReachability.UNKNOWN, source.reachabilityOf(CONTENT_URI))
    }

    @Test
    fun givenAProviderThatAnswersWithNothing_whenProbed_thenTheAnswerIsUnknown() {
        // No descriptor and no failure is not the provider saying the document is gone.
        every { resolver.openFileDescriptor(any(), "r") } returns null

        assertEquals(SourceReachability.UNKNOWN, source.reachabilityOf(CONTENT_URI))
    }

    @Test
    fun givenAProbedDocument_whenTheProbeEnds_thenItsDescriptorIsClosed() {
        // The probe must not leak the fd it opens: one per generation would exhaust the table.
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        every { resolver.openFileDescriptor(any(), "r") } returns descriptor

        source.reachabilityOf(CONTENT_URI)

        io.mockk.verify { descriptor.close() }
    }

    @Test
    fun givenADocument_whenOpened_thenTheLoaderGetsItsProcfsPathAndSize() {
        // The contract the read-in-place change rests on: llama.cpp gets the procfs entry for the
        // descriptor this handle owns, and the size comes from statSize, not File.length().
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        every { descriptor.fd } returns 42
        every { descriptor.statSize } returns 4_294_967_296L
        every { resolver.openFileDescriptor(any(), "r") } returns descriptor

        val opened = source.open(CONTENT_URI)

        assertNotNull(opened)
        assertEquals("/proc/self/fd/42", opened!!.nativePath)
        assertEquals(4_294_967_296L, opened.sizeBytes)
        assertTrue(opened.isSeekable)
        // The descriptor belongs to the handle now: closing it here would invalidate the path.
        io.mockk.verify(exactly = 0) { descriptor.close() }
    }

    @Test
    fun givenAStreamingProvider_whenOpened_thenTheHandleIsNotSeekable() {
        // A cloud provider hands back a pipe, for which statSize is -1: the caller has to be able
        // to tell, or a perfectly good model is reported as corrupt.
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        every { descriptor.fd } returns 7
        every { descriptor.statSize } returns -1L
        every { resolver.openFileDescriptor(any(), "r") } returns descriptor

        assertFalse(source.open(CONTENT_URI)!!.isSeekable)
    }

    @Test
    fun givenAPathThatExists_whenOpened_thenItIsSeekableAtItsOwnPath() {
        // A filesystem path has no descriptor to keep, and must not read as a pipe.
        val model = temporaryFolder.newFile("model.gguf").apply { writeBytes(ByteArray(64)) }

        val opened = source.open(model.absolutePath)

        assertEquals(model.absolutePath, opened!!.nativePath)
        assertEquals(64L, opened.sizeBytes)
        assertTrue(opened.isSeekable)
    }

    @Test
    fun givenAPathTheLoaderCanOpenByName_whenAsked_thenItIsReopenable() {
        // The loader opens nativePath by name, so the handle answers for that open.
        val model = temporaryFolder.newFile("model.gguf").apply { writeBytes(ByteArray(64)) }

        assertTrue(source.open(model.absolutePath)!!.isReopenable())
    }

    @Test
    fun givenAPathNothingCanOpen_whenAsked_thenItIsNotReopenable() {
        val opened = OpenModelFile("/proc/self/fd/99999", 64L, null)

        assertFalse(opened.isReopenable())
    }

    @Test
    fun givenADeletedPath_whenOpened_thenNoHandleIsReturned() {
        val model = temporaryFolder.newFile("model.gguf")
        assertTrue(model.delete())

        assertNull(source.open(model.absolutePath))
    }

    private companion object {
        const val CONTENT_URI = "content://com.android.externalstorage.documents/document/model.gguf"
    }
}
