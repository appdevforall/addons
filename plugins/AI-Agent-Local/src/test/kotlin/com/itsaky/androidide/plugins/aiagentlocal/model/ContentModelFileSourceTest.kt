package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException

/**
 * The read grant is the only thing keeping a picked model reachable — it is read in place, never
 * copied — so persisting it is what makes a selection survive a restart (ADFA-5253).
 */
class ContentModelFileSourceTest {

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var uri: Uri
    private val errors = mutableListOf<String>()
    private val source = ContentModelFileSource { what, _ -> errors += what }

    @Before
    fun setup() {
        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        uri = mockk(relaxed = true)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns uri
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun givenContentUri_whenPersistAccess_thenTakesPersistableReadPermission() {
        assertTrue(source.persistAccess(context, CONTENT_URI))

        verify { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun givenFilesystemPath_whenPersistAccess_thenNoGrantIsNeeded() {
        assertTrue(source.persistAccess(context, "/sdcard/Download/model.gguf"))

        verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
    }

    @Test
    fun givenNonPersistableGrant_whenPersistAccess_thenReportsFailureWithoutThrowing() {
        every { resolver.takePersistableUriPermission(any(), any()) } throws
            SecurityException("No persistable permission grants found")

        assertFalse(source.persistAccess(context, CONTENT_URI))
        assertEquals(1, errors.size)
    }

    @Test
    fun givenContentUri_whenReleaseAccess_thenGivesTheReadGrantBack() {
        source.releaseAccess(context, CONTENT_URI)

        verify {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test
    fun givenAHeldReadGrant_whenAskedWhetherAccessPersists_thenItDoes() {
        every { resolver.persistedUriPermissions } returns listOf(readGrant(CONTENT_URI))

        assertEquals(true, source.hasPersistedAccess(context, CONTENT_URI))
    }

    @Test
    fun givenOnlyAGrantForAnotherDocument_whenAskedWhetherAccessPersists_thenItDoesNot() {
        // Derived on every visit rather than remembered from the pick, so a grant dropped since —
        // a revoked one, or a full grant table — brings the "may need re-picking" caveat back.
        every { resolver.persistedUriPermissions } returns listOf(readGrant(OTHER_CONTENT_URI))

        assertEquals(false, source.hasPersistedAccess(context, CONTENT_URI))
    }

    @Test
    fun givenAResolverThatCannotAnswer_whenAskedWhetherAccessPersists_thenItSaysSoRatherThanGuessing() {
        // Neither direction is safe to guess: inventing a caveat is as wrong as clearing one that a
        // real persistAccess failure raised, so the caller is told nothing was established.
        every { resolver.persistedUriPermissions } throws SecurityException("denied")

        assertNull(source.hasPersistedAccess(context, CONTENT_URI))
        assertEquals(1, errors.size)
    }

    @Test
    fun givenFilesystemPath_whenAskedWhetherAccessPersists_thenNoGrantIsNeeded() {
        assertEquals(true, source.hasPersistedAccess(context, "/sdcard/Download/model.gguf"))

        verify(exactly = 0) { resolver.persistedUriPermissions }
    }

    @Test
    fun givenDeletedDocument_whenProbed_thenGoneWithoutReportingAnError() {
        every { resolver.openInputStream(uri) } throws
            FileNotFoundException("open failed: ENOENT (No such file or directory)")

        assertEquals(SourceReachability.GONE, source.readability(context, CONTENT_URI))
        // A model that is gone is an answer for the caller, not a lookup failure to log.
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun givenAProviderThatServesOnTheSecondAsk_whenProbed_thenItIsReachableRatherThanGone() {
        // The resolver turns provider death into the FileNotFoundException a deletion gives, so
        // only the re-ask keeps this from telling the user to re-pick a model that is intact.
        every { resolver.openInputStream(uri) } throws FileNotFoundException() andThen
            ByteArrayInputStream(ByteArray(4))

        assertEquals(SourceReachability.REACHABLE, source.readability(context, CONTENT_URI))
    }

    @Test
    fun givenAProviderThatStaysSilentOnTheSecondAsk_whenProbed_thenTheAnswerIsUnknown() {
        // Neither ask established anything, and only GONE may say "select the model again".
        every { resolver.openInputStream(uri) } throws FileNotFoundException() andThen null

        assertEquals(SourceReachability.UNKNOWN, source.readability(context, CONTENT_URI))
    }

    @Test
    fun givenOpenableDocument_whenProbed_thenReachableAndTheStreamIsClosed() {
        val stream = spyk(ByteArrayInputStream(ByteArray(4)))
        every { resolver.openInputStream(uri) } returns stream

        assertEquals(SourceReachability.REACHABLE, source.readability(context, CONTENT_URI))
        verify { stream.close() }
    }

    @Test
    fun givenAProviderThatAnswersWithNothing_whenProbed_thenTheAnswerIsUnknown() {
        // No stream and no failure is not the provider saying the document is gone.
        every { resolver.openInputStream(uri) } returns null

        assertEquals(SourceReachability.UNKNOWN, source.readability(context, CONTENT_URI))
    }

    @Test
    fun givenMissingFilesystemPath_whenProbed_thenGone() {
        // Confirmed like the document branch: it is asked twice before it answers GONE.
        assertEquals(SourceReachability.GONE, source.readability(context, "/sdcard/Download/gone.gguf"))
    }

    @Test
    fun givenAFileThatIsBackOnTheSecondAsk_whenProbed_thenItIsReachableRatherThanGone() {
        // A stat that lost a race with a mount used to refuse the pick outright on this branch.
        val file = File.createTempFile("model", ".gguf").apply { delete(); deleteOnExit() }
        // Lands inside the confirmation delay, so the second ask is the one that finds it.
        Thread { Thread.sleep(50); file.writeBytes(ByteArray(4)) }.start()

        assertEquals(SourceReachability.REACHABLE, source.readability(context, file.absolutePath))
    }

    @Test
    fun givenExistingFile_whenProbed_thenReachable() {
        val file = File.createTempFile("model", ".gguf").apply { deleteOnExit() }

        assertEquals(SourceReachability.REACHABLE, source.readability(context, file.absolutePath))
    }

    /** A persisted read grant on [uriString], as the resolver reports one. */
    private fun readGrant(uriString: String): UriPermission {
        val granted = mockk<Uri>(relaxed = true)
        every { granted.toString() } returns uriString
        return mockk<UriPermission>(relaxed = true).also {
            every { it.uri } returns granted
            every { it.isReadPermission } returns true
        }
    }

    private companion object {
        const val CONTENT_URI = "content://com.android.providers.downloads/document/42"
        const val OTHER_CONTENT_URI = "content://com.android.providers.downloads/document/43"
    }
}
