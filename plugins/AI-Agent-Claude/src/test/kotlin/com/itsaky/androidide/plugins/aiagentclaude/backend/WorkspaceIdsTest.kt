package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What a typed workspace id becomes, which is a request header, so a bad one must never pass. */
class WorkspaceIdsTest {

    @Test
    fun givenNothingTyped_whenChecked_thenNoWorkspaceIsSent() {
        assertEquals(WorkspaceIds.Result.None, WorkspaceIds.normalize(null))
        assertEquals(WorkspaceIds.Result.None, WorkspaceIds.normalize("   "))
        assertNull(WorkspaceIds.headerValue(""))
    }

    @Test
    fun givenARealId_whenChecked_thenItIsTrimmedAndKept() {
        assertEquals(
            WorkspaceIds.Result.Valid("wrkspc_01AbCdEf"),
            WorkspaceIds.normalize("  wrkspc_01AbCdEf \n")
        )
    }

    @Test
    fun givenALineBreakInside_whenChecked_thenItIsRefused() {
        // A pasted value that carried one would add a header of its own to every request.
        assertEquals(WorkspaceIds.Result.Invalid, WorkspaceIds.normalize("wrkspc_1\r\nx-api-key: other"))
        assertNull(WorkspaceIds.headerValue("wrkspc_1\nx"))
    }

    @Test
    fun givenSpacesOrNonAscii_whenChecked_thenItIsRefused() {
        assertEquals(WorkspaceIds.Result.Invalid, WorkspaceIds.normalize("wrkspc 01"))
        assertEquals(WorkspaceIds.Result.Invalid, WorkspaceIds.normalize("wrkspc_é"))
    }

    @Test
    fun givenAnAbsurdlyLongPaste_whenChecked_thenItIsRefused() {
        assertEquals(WorkspaceIds.Result.Invalid, WorkspaceIds.normalize("w".repeat(500)))
    }

    @Test
    fun givenAWorkspace_whenHeadersAreBuilt_thenItTravelsWithTheKey() {
        val headers = ClaudeHttpClient.authHeaders("sk-ant-x", "wrkspc_01")

        assertEquals("sk-ant-x", headers["x-api-key"])
        assertEquals("wrkspc_01", headers[WorkspaceIds.HEADER])
        assertEquals("2023-06-01", headers["anthropic-version"])
    }

    @Test
    fun givenNoOrABadWorkspace_whenHeadersAreBuilt_thenNoWorkspaceHeaderIsSent() {
        // A key in a workspace must not be sent with one it was never asked for.
        assertFalse(ClaudeHttpClient.authHeaders("sk-ant-x", null).containsKey(WorkspaceIds.HEADER))
        assertFalse(ClaudeHttpClient.authHeaders("sk-ant-x", "bad\nvalue").containsKey(WorkspaceIds.HEADER))
    }

    @Test
    fun givenNoKey_whenHeadersAreBuilt_thenNoKeyHeaderIsSent() {
        assertFalse(ClaudeHttpClient.authHeaders("", null).containsKey("x-api-key"))
    }
}
