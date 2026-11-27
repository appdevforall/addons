package org.appdevforall.sqliteexplorer.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsTest {
    // As ADFA-7297's DatabaseTools.encodeResult writes it.
    private val page =
        JSONObject(
            """
            {"columns":["id","big","ratio","odd","name","data","gone"],
             "column_types":["INTEGER","INTEGER","REAL","REAL","TEXT","BLOB",null],
             "rows":[[1,"9007199254740993",2,"NaN","Ada",{"hex":"00ff10","bytes":3,"truncated":false},null],
                     [2,-5,2.5,"-Infinity","long text",{"hex":"abcd","bytes":4096,"truncated":true},null]],
             "storage_classes":["iirrtbn","iirrtbn"],
             "row_count":2,"offset":100,"has_more":true,"next_offset":102,
             "truncated_cells":2,"truncated_cell_indexes":[[1,4],[1,5]],"note":"Paging re-runs the query."}
            """,
        )

    @Test
    fun `a query page reads every storage class`() {
        val p = QueryPage.parse(page)

        assertEquals(listOf("id", "big", "ratio", "odd", "name", "data", "gone"), p.columns)
        assertEquals(listOf("INTEGER", "INTEGER", "REAL", "REAL", "TEXT", "BLOB", null), p.columnTypes)
        val first = p.rows[0]
        assertEquals("1", first[0].display)
        assertEquals("9007199254740993", first[1].display)
        assertEquals("2.0", first[2].display)
        assertEquals("NaN", first[3].display)
        assertEquals("Ada", first[4].display)
        val blob = first[5] as Cell.Blob
        assertEquals("00ff10", blob.hex)
        assertEquals(3, blob.bytes)
        assertFalse(blob.truncated)
        assertEquals("BLOB, 3 bytes: 00ff10", blob.display)
        assertEquals(Cell.Null, first[6])
        assertEquals("NULL", first[6].display)
        assertEquals(100, p.offset)
        assertTrue(p.hasMore)
        assertEquals(102L, p.nextOffset)
        assertEquals(2, p.truncatedCells)
    }

    @Test
    fun `cut cells are marked, a TEXT cell only through the index list`() {
        val second = QueryPage.parse(page).rows[1]
        assertEquals("-5", second[1].display)
        assertEquals("2.5", second[2].display)
        assertTrue((second[4] as Cell.Text).truncated)
        assertTrue((second[5] as Cell.Blob).truncated)
        assertEquals("BLOB, 4096 bytes: abcd...", second[5].display)
        assertFalse(QueryPage.parse(page).rows[0][4].truncated)
    }

    @Test
    fun `the last page has no next offset`() {
        val last = JSONObject(page.toString()).put("has_more", false).apply { remove("next_offset") }
        val p = QueryPage.parse(last)
        assertFalse(p.hasMore)
        assertNull(p.nextOffset)
    }

    @Test
    fun `a server without ADFA-7297 is refused`() {
        val noTypes = JSONObject(page.toString()).apply { remove("column_types") }
        val noClasses = JSONObject(page.toString()).apply { remove("storage_classes") }
        val base64 =
            JSONObject(page.toString()).apply {
                getJSONArray("rows").getJSONArray(0).put(5, JSONObject().put("base64", "AP8Q").put("bytes", 3).put("truncated", false))
            }
        assertThrows(UnsupportedServerException::class.java) { QueryPage.parse(noTypes) }
        assertThrows(UnsupportedServerException::class.java) { QueryPage.parse(noClasses) }
        assertThrows(UnsupportedServerException::class.java) { QueryPage.parse(base64) }
    }

    @Test
    fun `a row that does not match the columns is refused`() {
        val short = JSONObject(page.toString()).apply { getJSONArray("rows").getJSONArray(0).remove(6) }
        assertThrows(McpException::class.java) { QueryPage.parse(short) }
    }

    @Test
    fun `a result with no columns and no rows parses`() {
        val empty = JSONObject("""{"columns":[],"column_types":[],"rows":[],"storage_classes":[],"offset":0,"has_more":false}""")
        val p = QueryPage.parse(empty)
        assertTrue(p.columns.isEmpty())
        assertTrue(p.rows.isEmpty())
    }

    @Test
    fun `databases, schema, stats and integrity parse`() {
        val dbs = Database.parseList(JSONObject("""{"databases":[{"path":"/data/data/a/databases/notes.db","available":true,"connections":2},{"path":"/x/old.db","available":false,"connections":0}]}"""))
        assertEquals("notes.db", dbs[0].name)
        assertTrue(dbs[0].available)
        assertFalse(dbs[1].available)

        val schema =
            Schema.parse(
                JSONObject(
                    """{"tables":[{"name":"note","table":"note","sql":"CREATE TABLE note(id)","truncated":false}],
                        "indexes":[{"name":"sqlite_autoindex_note_1","table":"note","sql":null,"truncated":false}],
                        "views":[],"triggers":[],"truncated":false,"truncated_cells":0}""",
                ),
            )
        assertEquals("note", schema.tables.single().name)
        assertNull(schema.indexes.single().sql)
        assertFalse(schema.truncated)

        val stats = DatabaseStats.parse(JSONObject("""{"page_count":4,"page_size":4096,"freelist_count":0,"size_bytes":16384,"row_counts":{"note":"12"}}"""))
        assertEquals(16384L, stats.sizeBytes)
        assertEquals(mapOf("note" to "12"), stats.rowCounts)
        val skipped = DatabaseStats.parse(JSONObject("""{"page_count":null,"page_size":4096,"row_counts_skipped":"The database has 12 tables."}"""))
        assertNull(skipped.pageCount)
        assertNull(skipped.rowCounts)
        assertEquals("The database has 12 tables.", skipped.rowCountsSkipped)

        val report = IntegrityReport.parse(JSONObject("""{"check":"quick_check","ok":true,"messages":["ok"]}"""))
        assertTrue(report.ok)
        assertEquals(listOf("ok"), report.messages)
    }

    @Test
    fun `a query plan becomes an indented tree`() {
        val plan =
            QueryPlan.parse(
                JSONObject(
                    """{"plan":[{"id":2,"parent":0,"notused":0,"detail":"SCAN note"},
                                {"id":5,"parent":0,"notused":0,"detail":"CORRELATED SCALAR SUBQUERY 1"},
                                {"id":9,"parent":5,"notused":0,"detail":"SEARCH tag USING INDEX t (n=?)"}]}""",
                ),
            )
        assertEquals(listOf("SCAN note", "CORRELATED SCALAR SUBQUERY 1", "  SEARCH tag USING INDEX t (n=?)"), plan.asTree())
    }
}
