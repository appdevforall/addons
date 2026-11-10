package com.itsaky.androidide.plugins.aiagentlocal.device

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** A malformed or wrongly typed JSON input. [path] names the offending value, e.g. `static.cpus[2].index`. */
class JsonException(val path: String, message: String) : Exception("$path: $message")

/**
 * The only place the device code touches `org.json`.
 *
 * Android ships its own `org.json`; JVM unit tests use the `org.json:json` library. The two differ, so
 * everything here converts to a plain tree and back and the rest of the code never sees a `JSONObject`.
 * The tree a parse returns holds only: `Map<String, Any?>`, `List<Any?>`, `String`, `Boolean`, `Long`,
 * `Double` and `null`.
 *
 * Known differences between the two implementations and how they are avoided:
 *  1. Key order: `JSONObject` order differs (hash order vs insertion order). Keys are visited in sorted
 *     order and the code never depends on order.
 *  2. Number types: the same text gives Integer, Long, BigInteger, Double or BigDecimal depending on the
 *     implementation and the digits. [number] decides integer or fraction from the value.
 *  3. Integers beyond the Long range: `org.json:json` gives BigInteger, Android gives Double (precision
 *     is lost there). No Part 1 JSON format carries an integer of 2^53 or more (the HWCAP words are hex
 *     strings), so [number] rejects every integer, and every integer-valued double, of 2^53 or more in
 *     magnitude with a [JsonException] naming the JSON path: a value Android may have rounded is never
 *     used silently.
 *  4. `JSONObject.NULL` vs `null`: `opt` returns NULL for a JSON null in both; `put(key, null)` removes the
 *     key. [toOrg] always writes `JSONObject.NULL`, [fromOrg] maps NULL to null.
 *  5. Duplicate keys: `org.json:json` throws, Android keeps the last. Python keeps the last; the inputs of
 *     this code must not contain duplicates (not detectable through Android's parser).
 *  6. Leniency: both parsers accept unquoted keys, single quotes, comments and similar that strict JSON
 *     (Python `json`) rejects. The inputs are written by this project's own tools; strictness is not
 *     enforced beyond refusing trailing text.
 *  7. `optString`, `optInt` and friends coerce types silently; they are never used. A wrong type is a
 *     [JsonException] with the path.
 */
internal object Json {

    // ---- parsing and writing -------------------------------------------------------------

    /**
     * Creates the tokener [parse] uses. A test replaces it to stand in for Android's number parsing
     * (an integer literal becomes a `Long` when it fits, else a `Double`); production code never changes it.
     */
    @Volatile
    internal var tokenerFactory: (String) -> JSONTokener = { JSONTokener(it) }

    fun parse(text: String, path: String = "$"): Any? {
        try {
            val tokener = tokenerFactory(text)
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') throw JsonException(path, "text after the JSON value")
            return fromOrg(value, path)
        } catch (e: JSONException) {
            throw JsonException(path, "not valid JSON: ${e.message}")
        }
    }

    fun parseObject(text: String, path: String = "$"): Map<String, Any?> = obj(parse(text, path), path)

    /** Converts what `org.json` produced into the plain tree. */
    fun fromOrg(x: Any?, path: String = "$"): Any? = when (x) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val out = LinkedHashMap<String, Any?>()
            val keys = ArrayList<String>()
            val it = x.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sort()
            for (k in keys) out[k] = fromOrg(x.get(k), "$path.$k")
            out
        }
        is JSONArray -> (0 until x.length()).map { fromOrg(x.get(it), "$path[$it]") }
        is String, is Boolean -> x
        is Number -> number(x, path)
        else -> throw JsonException(path, "unsupported JSON value of type ${x.javaClass.name}")
    }

    /** Converts a plain tree to `org.json` objects. Never relies on key order. */
    fun toOrg(x: Any?, path: String = "$"): Any = when (x) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val o = JSONObject()
            for (k in x.keys.map { it as String }.sorted()) o.put(k, toOrg(x[k], "$path.$k"))
            o
        }
        is List<*> -> {
            val a = JSONArray()
            x.forEachIndexed { i, v -> a.put(toOrg(v, "$path[$i]")) }
            a
        }
        is String, is Boolean -> x
        is Int -> x.toLong()
        is Long, is BigInteger -> x
        is Double -> {
            if (x.isNaN() || x.isInfinite()) throw JsonException(path, "NaN and infinities cannot be written")
            x
        }
        else -> throw JsonException(path, "cannot write a value of type ${x.javaClass.name}")
    }

    // ---- numbers: one reader, one writer -------------------------------------------------

    /** The first integer a JSON number may not reach: 2^53 (rule A1, review F01). */
    private val MAX_EXACT: BigInteger = BigInteger.ONE.shiftLeft(53)

    private fun exact(bi: BigInteger, path: String): Long {
        if (bi.abs() >= MAX_EXACT) {
            throw JsonException(path, "an integer of 2^53 or more is not allowed in a Part 1 JSON document (write such a word as a hex string)")
        }
        return bi.toLong()
    }

    /**
     * Normalises any `Number` to `Long` (an integer, also an integral double below 2^53) or `Double` (a value
     * with a fraction). The decision comes from the value, never from the Java type: `9.0`, `9` and
     * `BigDecimal("9.00")` all give `9L`. An integer, or an integral double, of 2^53 or more in magnitude is a
     * [JsonException] naming [path]: it may have been rounded on the way in.
     */
    fun number(x: Number, path: String = "$"): Number {
        val big: BigDecimal? = when (x) {
            is Byte, is Short, is Int, is Long -> return exact(BigInteger.valueOf(x.toLong()), path)
            is BigInteger -> return exact(x, path)
            is BigDecimal -> x
            is Double, is Float -> {
                val d = x.toDouble()
                if (d.isNaN() || d.isInfinite()) throw JsonException(path, "NaN and infinities are not JSON numbers")
                BigDecimal(d)
            }
            else -> throw JsonException(path, "unsupported number type ${x.javaClass.name}")
        }
        val stripped = big!!.stripTrailingZeros()
        if (stripped.scale() <= 0) {
            return exact(stripped.toBigIntegerExact(), path)
        }
        return x.toDouble().also {
            if (it.isNaN() || it.isInfinite()) throw JsonException(path, "number out of range")
        }
    }

    // ---- typed reads: a wrong type is an error with the path -----------------------------

    fun isNumber(x: Any?) = x is Long || x is Double || x is BigInteger

    fun obj(x: Any?, path: String): Map<String, Any?> {
        if (x is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return x as Map<String, Any?>
        }
        throw JsonException(path, "expected an object, got ${describe(x)}")
    }

    fun arr(x: Any?, path: String): List<Any?> {
        if (x is List<*>) return x
        throw JsonException(path, "expected an array, got ${describe(x)}")
    }

    fun str(x: Any?, path: String): String =
        x as? String ?: throw JsonException(path, "expected a string, got ${describe(x)}")

    fun bool(x: Any?, path: String): Boolean =
        x as? Boolean ?: throw JsonException(path, "expected a boolean, got ${describe(x)}")

    /** An integer value (an integral number of any spelling). */
    fun long(x: Any?, path: String): Long =
        x as? Long ?: throw JsonException(path, "expected an integer, got ${describe(x)}")

    /** Any number, as a double. */
    fun double(x: Any?, path: String): Double = when (x) {
        is Long -> x.toDouble()
        is Double -> x
        is BigInteger -> x.toDouble()
        else -> throw JsonException(path, "expected a number, got ${describe(x)}")
    }

    fun describe(x: Any?): String = when (x) {
        null -> "null"
        is Map<*, *> -> "an object"
        is List<*> -> "an array"
        is String -> "a string"
        is Boolean -> "a boolean"
        is Number -> "a number"
        else -> x.javaClass.simpleName
    }

    // ---- comparison of trees (numbers by value, objects without order) -------------------

    /** JSON paths at which [a] and [b] differ (numbers equal as numbers); empty when they are equal. */
    fun diff(a: Any?, b: Any?, path: String = "$", limit: Int = 50): List<String> {
        val out = ArrayList<String>()
        diffInto(a, b, path, out, limit)
        return out
    }

    private fun diffInto(a: Any?, b: Any?, path: String, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        when {
            a is Map<*, *> && b is Map<*, *> -> {
                for (k in (a.keys + b.keys).map { it as String }.toSortedSet()) {
                    when {
                        !a.containsKey(k) -> out.add("$path.$k (only in the second)")
                        !b.containsKey(k) -> out.add("$path.$k (only in the first)")
                        else -> diffInto(a[k], b[k], "$path.$k", out, limit)
                    }
                }
            }
            a is List<*> && b is List<*> -> {
                if (a.size != b.size) out.add("$path (length ${a.size} vs ${b.size})")
                for (i in 0 until minOf(a.size, b.size)) diffInto(a[i], b[i], "$path[$i]", out, limit)
            }
            isNumber(a) && isNumber(b) -> if (!numEq(a, b)) out.add(path)
            else -> if (a != b) out.add(path)
        }
    }

    fun numEq(a: Any?, b: Any?): Boolean = when {
        a is BigInteger || b is BigInteger -> a == b
        a is Long && b is Long -> a == b
        else -> double(a, "$").compareTo(double(b, "$")) == 0 || double(a, "$") == double(b, "$")
    }
}
