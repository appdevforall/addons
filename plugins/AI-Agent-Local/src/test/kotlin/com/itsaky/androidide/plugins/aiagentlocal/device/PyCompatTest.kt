package com.itsaky.androidide.plugins.aiagentlocal.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PyCompatTest {

    @Test
    fun givenHalfwayAndNearHalfwayValues_whenRoundedToOneDecimal_thenTheyMatchPythonRound() {
        // Values taken from Python 3.12: round(x, 1)
        val expected = mapOf(0.25 to 0.2, 0.35 to 0.3, 0.15 to 0.1, 0.45 to 0.5, 0.05 to 0.1, 1.25 to 1.2, 2.675 to 2.7, 9.95 to 9.9, (0.1 + 0.2) to 0.3)
        for ((x, r) in expected) assertEquals("round($x, 1)", r, PyCompat.round1(x), 0.0)
        assertEquals("5.0", PyCompat.format1(0.05 * 100))
        assertEquals("10.0", PyCompat.format1(0.1 * 100))
        assertEquals("7.5", PyCompat.format1(0.075 * 100))
    }

    @Test
    fun givenFloats_whenSummed_thenTheSumIsCompensatedLikePython312() {
        assertEquals(1.0, PyCompat.sum(List(10) { 0.1 }) as Double, 0.0) // naive addition gives 0.9999999999999999
        assertEquals(0.6, PyCompat.sum(listOf(0.1, 0.2, 0.3)) as Double, 0.0) // naive: 0.6000000000000001
        assertEquals(4.8, PyCompat.sum(listOf(1L, 2L, 0.5, 0.1, 0.2, 1e100, 1.0, -1e100)) as Double, 0.0)
        assertEquals(6L, PyCompat.sum(listOf(1L, 2L, 3L)))
        assertEquals(1.3, PyCompat.round1(PyCompat.sum(listOf(0.1, 0.2, 0.3, 0.7))) as Double, 0.0)
        assertEquals(6L, PyCompat.round1(6L))
    }

    @Test
    fun givenTheReviewsMixedList_whenSummedAndRounded_thenItIsCPythonsEightyFourPointOne() {
        val items = listOf(12L, 9.6, 23.25, 8L, 7.2, 24L)
        assertEquals(84.1, PyCompat.round1(PyCompat.sum(items)) as Double, 0.0) // the earlier port gave 84
    }

    @Test
    fun givenMixedIntAndFloatLists_whenSummed_thenEveryResultEqualsPython312Exactly() {
        val cases = Json.arr(Part1TestResources.tree("part1/pycompat-sum.json"), "sum")
        assertTrue(cases.size >= 2000)
        val bad = ArrayList<String>()
        for ((i, c0) in cases.withIndex()) {
            val c = Json.obj(c0, "case")
            val items = Json.arr(c["items"], "items")
            val got = PyCompat.sum(items)
            val want = (c["sum"] as Number).toDouble()
            val kind = c["kind"] as String
            val r1 = PyCompat.round1(got)
            val ok = (if (kind == "int") got is Long && got.toDouble() == want else got is Double && got.toDouble() == want) &&
                r1.toDouble() == (c["round1"] as Number).toDouble()
            if (!ok) bad.add("#$i $items: got $got want ${c["sum"]} ($kind)")
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun givenStringsWithNonPrintableCharacters_whenReprIsTaken_thenItEqualsPythonRepr() {
        val cases = Json.arr(Part1TestResources.tree("part1/pycompat-repr.json"), "repr")
        assertTrue(cases.size >= 40)
        val bad = ArrayList<String>()
        for (c0 in cases) {
            val c = Json.obj(c0, "case")
            val got = PyCompat.repr(c["text"] as String)
            if (got != c["repr"]) bad.add("expected ${c["repr"]} got $got")
        }
        assertEquals(emptyList<String>(), bad)
        // the characters the task names, written out so that a change of the resource cannot hide them
        assertEquals("'\\x85'", PyCompat.repr("\u0085"))
        assertEquals("'\\xa0'", PyCompat.repr("\u00a0"))
        assertEquals("'\\u2028'", PyCompat.repr("\u2028"))
        assertEquals("'\\u200b'", PyCompat.repr("\u200b"))
        assertEquals("'\\ufeff'", PyCompat.repr("\ufeff"))
        assertEquals("'\\ud800'", PyCompat.repr("\ud800"))
        assertEquals("'\uD83D\uDE00'", PyCompat.repr("\uD83D\uDE00")) // an astral character is printable and kept
    }

    @Test
    fun givenValues_whenTruthyOrStringified_thenPythonRulesApply() {
        assertFalse(PyCompat.truthy(null)); assertFalse(PyCompat.truthy("")); assertFalse(PyCompat.truthy(0L))
        assertFalse(PyCompat.truthy(emptyList<Any?>())); assertFalse(PyCompat.truthy(emptyMap<String, Any?>())); assertFalse(PyCompat.truthy(false))
        assertTrue(PyCompat.truthy("x")); assertTrue(PyCompat.truthy(listOf(0L))); assertTrue(PyCompat.truthy(0.5))
        assertEquals("None", PyCompat.str(null)); assertEquals("True", PyCompat.str(true)); assertEquals("2.0", PyCompat.str(2.0))
        assertEquals("['a', \"b'c\"]", PyCompat.str(listOf("a", "b'c")))
        assertEquals("'a\\\\b'", PyCompat.repr("a\\b"))
        assertEquals("'tab\\t'", PyCompat.repr("tab\t"))
    }

    @Test
    fun givenPythonWhitespace_whenStrippedOrSplit_thenItMatchesStr() {
        assertEquals("a b", PyCompat.strip("  a b\u001f\n"))
        assertEquals(listOf("a", "b", "c"), PyCompat.split("  a\tb c \n"))
        assertEquals(emptyList<String>(), PyCompat.split("  "))
    }

    @Test
    fun givenPythonFnmatchResults_whenMatchedHere_thenEveryOneAgrees() {
        val diff = Part1TestResources.obj("part1/match-differential.json")
        val cases = Json.arr(diff["fnmatch"], "fnmatch")
        assertTrue(cases.size >= 500)
        val bad = cases.map { Json.obj(it, "c") }.filter {
            PyCompat.fnmatch(it["name"] as String, it["pattern"] as String) != it["expect"]
        }.map { "'${it["name"]}' ~ '${it["pattern"]}' expected ${it["expect"]}" }
        assertEquals(emptyList<String>(), bad)
    }
}
