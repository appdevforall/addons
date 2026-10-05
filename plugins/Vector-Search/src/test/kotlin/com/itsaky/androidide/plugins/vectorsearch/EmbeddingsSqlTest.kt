package com.itsaky.androidide.plugins.vectorsearch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingsSqlTest {

    private val identity = EmbedderIdentity(EmbedderKey("gemini", "gemini-embedding-001"), 768)
    private val root = File("/projects/app")

    @Test
    fun givenEachBoundStatement_whenItsArgsAreBuilt_thenTheyFillEveryPlaceholder() {
        // A missing value shifts the rest into the wrong slots: a wrong answer, not an error.
        assertEquals(
            placeholders(EmbeddingsSql.WHERE_IDENTITY),
            EmbeddingsSql.identityArgs(identity).size,
        )
        assertEquals(
            placeholders(EmbeddingsSql.WHERE_ROOTS_AND_IDENTITY),
            EmbeddingsSql.scopedArgs("roots", identity).size,
        )
        assertEquals(
            placeholders(EmbeddingsSql.COUNT_SCOPED),
            EmbeddingsSql.scopedArgs("roots", identity).size,
        )
        assertEquals(
            placeholders(EmbeddingsSql.SUMMARIZE_PATH_RANGE),
            EmbeddingsSql.pathRangeArgs(root).size,
        )
        assertEquals(1, placeholders(EmbeddingsSql.STORED_MODELS))
        assertEquals(1, placeholders(EmbeddingsSql.WHERE_ROOTS))
    }

    @Test
    fun givenEveryStatement_whenRead_thenItCarriesNoLiteralValues() {
        // Values only arrive through placeholders; a quote would mean one was spliced in.
        listOf(
            EmbeddingsSql.CREATE_TABLE, EmbeddingsSql.CREATE_INDEX_FILE_PATH,
            EmbeddingsSql.CREATE_INDEX_SCOPE, EmbeddingsSql.DROP_TABLE, EmbeddingsSql.WHERE_IDENTITY,
            EmbeddingsSql.WHERE_ROOTS_AND_IDENTITY, EmbeddingsSql.COUNT_SCOPED,
            EmbeddingsSql.SUMMARIZE_PATH_RANGE, EmbeddingsSql.STORED_MODELS, EmbeddingsSql.WHERE_ROOTS,
        ).forEach { statement ->
            assertFalse(statement, statement.contains('\'') || statement.contains('"'))
        }
    }

    @Test
    fun givenAFileUnderTheRoot_whenRanged_thenItIsInside() {
        assertTrue(inRange("/projects/app/src/Main.kt"))
    }

    @Test
    fun givenSiblingsThatShareThePrefix_whenRanged_thenTheyAreOutside() {
        // A plain prefix match would count another project's rows as this one's.
        assertFalse(inRange("/projects/app2/Main.kt"))
        assertFalse(inRange("/projects/app-old/Main.kt"))
        assertFalse(inRange("/projects/app"))
    }

    /** Whether SQLite's BINARY comparison, which matches String.compareTo for ASCII, keeps [path]. */
    private fun inRange(path: String): Boolean {
        val (from, until) = EmbeddingsSql.pathRangeArgs(root)
        return path >= from && path < until
    }

    private fun placeholders(statement: String): Int = statement.count { it == '?' }
}
