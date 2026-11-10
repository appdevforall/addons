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
