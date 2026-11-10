package com.itsaky.androidide.plugins.aiagentlocal.device

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {

    @Test
    fun givenNumbersOfEveryJavaType_whenNormalised_thenIntegerOrFractionIsDecidedFromTheValue() {
        assertEquals(9L, Json.number(9))
        assertEquals(9L, Json.number(9L))
        assertEquals(9L, Json.number(9.0))
        assertEquals(9L, Json.number(BigDecimal("9.00")))
        assertEquals(9L, Json.number(BigInteger.valueOf(9)))
        assertEquals(2.5, Json.number(2.5))
        assertEquals(2.5, Json.number(BigDecimal("2.50")))
        assertEquals(0.1, Json.number(0.1))
        assertEquals((1L shl 53) - 1, Json.number((1L shl 53) - 1))
        assertEquals(-((1L shl 53) - 1), Json.number(-((1L shl 53) - 1).toDouble()))
        assertEquals(0L, Json.number(-0.0)) // an integral double is an integer value
    }

    @Test
    fun givenNaNOrInfinity_whenNormalised_thenItIsRejectedWithThePath() {
        try {
            Json.number(Double.NaN, "$.x")
            fail()
        } catch (e: JsonException) {
            assertEquals("$.x", e.path)
        }
        try {
            Json.toOrg(mapOf("a" to Double.POSITIVE_INFINITY))
            fail()
        } catch (e: JsonException) {
            assertEquals("$.a", e.path)
        }
    }

    @Test
    fun givenJsonText_whenParsed_thenTheTreeHoldsOnlyPlainTypesAndNullIsNull() {
        val t = Json.parseObject("""{"b":[1,2.5,"s",true,null,{"z":9.0}],"a":null}""")
        assertEquals(listOf("a", "b"), t.keys.toList()) // sorted, whatever the library's order
        assertNull(t["a"])
        assertTrue(t.containsKey("a")) // a null value is not a missing key
        val b = Json.arr(t["b"], "b")
        assertEquals(1L, b[0])
        assertEquals(2.5, b[1])
        assertEquals("s", b[2])
        assertEquals(true, b[3])
        assertNull(b[4])
        assertEquals(9L, Json.obj(b[5], "b[5]")["z"])
    }

    @Test
    fun givenAJsonNullInAnOrgObject_whenConverted_thenItIsNullAndOnWritingItIsJsonNullNotARemovedKey() {
        val o = JSONObject()
        o.put("k", JSONObject.NULL)
        assertNull(Json.obj(Json.fromOrg(o), "o")["k"])
        val back = Json.toOrg(mapOf("k" to null, "n" to 3, "l" to listOf(null, 1.5))) as JSONObject
        assertTrue(back.has("k"))
        assertTrue(back.isNull("k"))
        assertEquals(3L, (back.get("n") as Number).toLong())
        assertTrue((back.get("l") as JSONArray).isNull(0))
    }

    @Test
    fun givenTrailingTextOrBrokenJson_whenParsed_thenTheErrorNamesThePath() {
        for (text in listOf("""{"a":1} x""", """{"a":""", "")) {
            try {
                Json.parse(text, "input")
                fail("accepted: $text")
            } catch (e: JsonException) {
                assertEquals("input", e.path)
            }
        }
    }

    @Test
    fun givenAWrongType_whenReadWithATypedHelper_thenItIsAnErrorWithThePathAndNothingIsCoerced() {
        val checks: List<Pair<String, () -> Any?>> = listOf(
            "s" to { Json.str(5L, "s") },
            "n" to { Json.long("5", "n") }, // numeric strings are not numbers
            "d" to { Json.double("1.5", "d") },
            "b" to { Json.bool("true", "b") },
            "o" to { Json.obj(listOf<Any?>(), "o") },
            "a" to { Json.arr(mapOf<String, Any?>(), "a") },
            "i" to { Json.long(1.5, "i") },
        )
        for ((path, f) in checks) {
            try {
                f()
                fail("no error for $path")
            } catch (e: JsonException) {
                assertEquals(path, e.path)
            }
        }
    }

    @Test
    fun givenTwoTrees_whenDiffed_thenNumbersCompareAsNumbersAndKeyOrderIsFree() {
        val a = Json.parse("""{"x":1,"y":[1.0,2],"z":{"p":"q"}}""")
        val b = Json.parse("""{"z":{"p":"q"},"y":[1,2.0],"x":1.0}""")
        assertEquals(emptyList<String>(), Json.diff(a, b))
        val c = Json.parse("""{"x":2,"y":[1,3],"z":{"p":"r"},"w":1}""")
        assertEquals(listOf("$.w (only in the second)", "$.x", "$.y[1]", "$.z.p"), Json.diff(a, c))
        assertFalse(Json.numEq(1L, 1.5))
    }

    @Test
    fun givenAnIntegerOfTwoToThe53OrMore_whenNormalisedOrParsed_thenItIsRejectedWithThePath() {
        val big = BigInteger.ONE.shiftLeft(53)
        val numbers: List<Number> = listOf(
            1L shl 53, -(1L shl 53), Long.MAX_VALUE, big, big.negate(), BigInteger("18446744073709551615"),
            BigDecimal("9007199254740992.0"), 9007199254740992.0, 1e300, -1e17, 1.8446744073709552E19,
        )
        for (n in numbers) {
            try {
                Json.number(n, "$.w")
                fail("accepted $n")
            } catch (e: JsonException) {
                assertEquals("$.w", e.path)
            }
        }
        for (text in listOf("""{"w":9007199254740992}""", """{"w":[0,{"v":-9007199254740992}]}""", """{"w":18446744073709551615}""",
            """{"w":9223372036854775808}""", """{"w":1e300}""", """{"w":9007199254740993}""")) {
            try {
                Json.parse(text, "in")
                fail("accepted $text")
            } catch (e: JsonException) {
                assertTrue(e.path, e.path.startsWith("in.w"))
            }
        }
        val e = try { Json.parse("""{"a":{"b":[1,9007199254740992]}}""", "in"); null } catch (x: JsonException) { x }
        assertEquals("in.a.b[1]", e!!.path)
    }

    @Test
    fun givenIntegersBelowTwoToThe53_whenParsed_thenTheyStayExactIntegersAndIntegralDoublesBecomeIntegers() {
        val t = Json.parseObject("""{"a":9007199254740991,"b":-9007199254740991,"c":9007199254740990.0,"d":2.0,"e":1e3}""")
        assertEquals(9007199254740991L, t["a"])
        assertEquals(-9007199254740991L, t["b"])
        assertEquals(9007199254740990L, t["c"])
        assertEquals(2L, t["d"])
        assertEquals(1000L, t["e"])
    }
}
