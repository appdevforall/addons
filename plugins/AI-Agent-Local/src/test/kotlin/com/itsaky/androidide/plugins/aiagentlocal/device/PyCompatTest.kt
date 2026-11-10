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
    fun givenFloats_whenSummed_thenTheSumIsExactlyRoundedLikeMathFsum() {
        assertEquals(1.0, PyCompat.fsum(List(10) { 0.1 }), 0.0) // naive addition gives 0.9999999999999999
        assertEquals(0.6, PyCompat.fsum(listOf(0.1, 0.2, 0.3)), 0.0) // naive: 0.6000000000000001
        assertEquals(4.8, PyCompat.fsum(listOf(1L, 2L, 0.5, 0.1, 0.2, 1e100, 1.0, -1e100)), 0.0) // 1e100 cancels exactly
        assertEquals(6.0, PyCompat.fsum(listOf(1L, 2L, 3L)), 0.0)
        assertEquals(1.3, PyCompat.round1(PyCompat.fsum(listOf(0.1, 0.2, 0.3, 0.7))), 0.0)
        assertEquals(0.0, PyCompat.fsum(emptyList()), 0.0)
        assertEquals(java.lang.Double.doubleToRawLongBits(0.0), java.lang.Double.doubleToRawLongBits(PyCompat.fsum(listOf(1.0, -1.0))))
    }

    @Test
    fun givenTheReviewsMixedList_whenSummedAndRounded_thenItIsMathFsumsEightyFour() {
        val items = listOf(12L, 9.6, 23.25, 8L, 7.2, 24L)
        assertEquals(84.0, PyCompat.round1(PyCompat.fsum(items)), 0.0) // exact sum 84.04999...; the CPython sum() port gave 84.1
        assertEquals(PyCompat.fsum(items), PyCompat.fsum(listOf(12.0, 9.6, 23.25, 8.0, 7.2, 24.0)), 0.0) // spelling does not matter
        assertEquals(PyCompat.fsum(items), PyCompat.fsum(items.reversed()), 0.0) // order does not matter
    }

    @Test
    fun givenAHugeTotal_whenSummed_thenItOverflowsLikeMathFsum() {
        try {
            PyCompat.fsum(listOf(1e308, 1e308))
            org.junit.Assert.fail("expected an overflow")
        } catch (e: ArithmeticException) {
            assertTrue(e.message!!.contains("overflow"))
        }
    }

    private fun checkSumResource(name: String, min: Int) {
        val cases = Json.arr(Part1TestResources.tree(name), "sum")
        assertTrue("$name has ${cases.size} cases", cases.size >= min)
        val bad = ArrayList<String>()
        for ((i, c0) in cases.withIndex()) {
            val c = Json.obj(c0, "case")
            val items = Json.arr(c["items"], "items")
            val got = PyCompat.fsum(items)
            val want = (c["sum"] as Number).toDouble()
            val ok = got == want && PyCompat.round1(got) == (c["round1"] as Number).toDouble()
            if (!ok) bad.add("#$i $items: got $got want ${c["sum"]}")
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun givenTheTwoThousandMixedLists_whenSummed_thenEveryResultEqualsMathFsumExactly() = checkSumResource("part1/pycompat-sum.json", 2011)

    @Test
    fun givenIntegralFloatsNegativeZeroCancellingValuesAndPermutations_whenSummed_thenEveryResultEqualsMathFsum() =
        checkSumResource("part1/pycompat-fsum.json", 2000)

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
