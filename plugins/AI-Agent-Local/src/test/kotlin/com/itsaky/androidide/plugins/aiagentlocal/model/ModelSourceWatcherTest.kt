package com.itsaky.androidide.plugins.aiagentlocal.model

import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A `DocumentsProvider` notifies a delete against the parent's children URI, which is not a
 * path-prefix descendant of the document URI — so a document-only watch never sees the deletion it
 * exists to catch, and the gigabytes stay mapped until the next message. See ADFA-5253.
 */
class ModelSourceWatcherTest {

    private lateinit var parentUri: Uri

    @Before
    fun setup() {
        parentUri = mockk(relaxed = true)
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.buildChildDocumentsUri(any(), any()) } returns parentUri
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    @Test
    fun givenANestedDocument_whenDerivingTheWatchTarget_thenItIsTheParentsChildrenUri() {
        val uri = documentUri("primary:Download/model.gguf")

        assertEquals(parentUri, parentChildrenUriOf(uri))
        io.mockk.verify { DocumentsContract.buildChildDocumentsUri(AUTHORITY, "primary:Download") }
    }

    @Test
    fun givenADeeplyNestedDocument_whenDerivingTheWatchTarget_thenOnlyTheLastElementIsDropped() {
        val uri = documentUri("primary:Download/models/gguf/model.gguf")

        assertEquals(parentUri, parentChildrenUriOf(uri))
        io.mockk.verify {
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, "primary:Download/models/gguf")
        }
    }

    @Test
    fun givenADocumentAtTheRootOfItsVolume_whenDerivingTheWatchTarget_thenThereIsNone() {
        // No parent element to drop; the direct watch is all there is.
        assertNull(parentChildrenUriOf(documentUri("primary:model.gguf")))
    }

    @Test
    fun givenSomethingThatIsNotADocumentUri_whenDerivingTheWatchTarget_thenThereIsNone() {
        val uri = mockk<Uri>(relaxed = true)
        every { DocumentsContract.getDocumentId(uri) } throws IllegalArgumentException("not a document")

        assertNull(parentChildrenUriOf(uri))
    }

    private fun documentUri(documentId: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.authority } returns AUTHORITY
        every { DocumentsContract.getDocumentId(uri) } returns documentId
        return uri
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
