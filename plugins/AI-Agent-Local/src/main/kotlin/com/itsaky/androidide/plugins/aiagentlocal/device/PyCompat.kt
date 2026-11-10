package com.itsaky.androidide.plugins.aiagentlocal.device

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale

/**
 * The few Python behaviours the Kotlin port must copy exactly, because the Python reference
 * (`match.py`, `generate.py`, `part1lib.py`) is the specification.
 */
internal object PyCompat {

    /** Python truthiness of a JSON tree value. */
    fun truthy(x: Any?): Boolean = when (x) {
        null -> false
        is Boolean -> x
        is String -> x.isNotEmpty()
        is Long -> x != 0L
        is Double -> x != 0.0
        is BigInteger -> x.signum() != 0
        is Map<*, *> -> x.isNotEmpty()
        is List<*> -> x.isNotEmpty()
        else -> true
    }

    /** Python `str(x)` for the values that occur in the inputs (`None`, `True`, numbers, text). */
    fun str(x: Any?): String = when (x) {
        null -> "None"
        is Boolean -> if (x) "True" else "False"
        is String -> x
        is Long, is BigInteger -> x.toString()
        is Double -> doubleRepr(x)
        is List<*> -> x.joinToString(", ", "[", "]") { repr(it) }
        else -> x.toString()
    }

    private fun repr(x: Any?): String = if (x is String) repr(x) else str(x)

    /** Python `repr(str)`: single quotes unless the text holds a single and no double quote. */
    fun repr(s: String): String {
        val q = if (s.contains('\'') && !s.contains('"')) '"' else '\''
        val sb = StringBuilder().append(q)
        for (ch in s) {
            when {
                ch == q || ch == '\\' -> sb.append('\\').append(ch)
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch == '\u007f' -> sb.append(String.format(Locale.ROOT, "\\x%02x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append(q).toString()
    }

    /** Python `repr(float)` for the common cases (shortest round trip, `1.0` keeps its `.0`). */
    fun doubleRepr(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e16) return String.format(Locale.ROOT, "%.1f", d)
        return d.toString()
    }

    /** Python `==` between two tree values (numbers by value). */
    fun eq(a: Any?, b: Any?): Boolean = when {
        Json.isNumber(a) && Json.isNumber(b) -> Json.numEq(a, b)
        a is Map<*, *> && b is Map<*, *> -> a.size == b.size && a.keys.all { b.containsKey(it) && eq(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { eq(a[it], b[it]) }
        else -> a == b
    }

    /** Python `x in values` for a list. */
    fun contains(values: List<Any?>, x: Any?): Boolean = values.any { eq(it, x) }

    /** Numeric value of a tree number (comparisons in the Python code are between plain numbers). */
    fun num(x: Any?): Double = Json.double(x, "$")

    /** Python `round(x, 1)`: exact decimal value of the double, ties to even. */
    fun round1(x: Double): Double = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toDouble()

    /** Python `"%.1f" % x`. */
    fun format1(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString()

    /**
     * Python 3.12 `sum()` over numbers: exact integer addition while the items are integers, and the
     * compensated (Neumaier) summation once a float shows up. Returns Long (all integers) or Double.
     */
    fun sum(values: List<Any?>): Number {
        var iResult = 0L
        var i = 0
        while (i < values.size && values[i] is Long) {
            iResult = Math.addExact(iResult, values[i] as Long)
            i++
        }
        if (i == values.size) return iResult
        var f = iResult.toDouble()
        var c = 0.0
        while (i < values.size) {
            val x = num(values[i])
            val t = f + x
            c += if (Math.abs(f) >= Math.abs(x)) (f - t) + x else (x - t) + f
            f = t
            i++
        }
        if (c != 0.0 && !c.isNaN() && !c.isInfinite()) f += c
        return f
    }

    /** `round(sum, 1)` as Python returns it: an int stays an int. */
    fun round1(x: Number): Number = if (x is Long) x else round1(x as Double)

    fun isSpace(c: Char): Boolean = c.isWhitespace() || c == ' ' || c == '\u001c' || c == '\u001d' || c == '\u001e' || c == '\u001f' || c == '\u0085'

    /** Python `str.strip()`. */
    fun strip(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isSpace(s[a])) a++
        while (b > a && isSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** Python `str.split()` (whitespace runs, no empty items). */
    fun split(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && isSpace(s[i])) i++
            val start = i
            while (i < s.length && !isSpace(s[i])) i++
            if (i > start) out.add(s.substring(start, i))
        }
        return out
    }

    fun lower(s: String): String = s.lowercase(Locale.ROOT)

    /**
     * Python `fnmatch.fnmatchcase(name, pattern)` (what `fnmatch.fnmatch` does on POSIX): `*`, `?`,
     * `[seq]`, `[!seq]`; the whole name must match; `*` and `?` also match a newline.
     */
    fun fnmatch(name: String, pattern: String): Boolean = fnmatchAt(name, 0, pattern, 0)

    private fun fnmatchAt(name: String, ni0: Int, pat: String, pi0: Int): Boolean {
        var ni = ni0
        var pi = pi0
        while (pi < pat.length) {
            val pc = pat[pi]
            when (pc) {
                '*' -> {
                    while (pi < pat.length && pat[pi] == '*') pi++
                    if (pi == pat.length) return true
                    for (k in ni..name.length) if (fnmatchAt(name, k, pat, pi)) return true
                    return false
                }
                '?' -> {
                    if (ni >= name.length) return false
                    ni++
                    pi++
                }
                '[' -> {
                    var j = pi + 1
                    if (j < pat.length && pat[j] == '!') j++
                    if (j < pat.length && pat[j] == ']') j++
                    while (j < pat.length && pat[j] != ']') j++
                    if (j >= pat.length) { // no closing bracket: '[' is a literal
                        if (ni >= name.length || name[ni] != '[') return false
                        ni++
                        pi++
                    } else {
                        if (ni >= name.length) return false
                        var stuff = pat.substring(pi + 1, j)
                        var negate = false
                        if (stuff.startsWith("!")) { negate = true; stuff = stuff.substring(1) }
                        if (setMatches(stuff, name[ni]) == negate) return false
                        ni++
                        pi = j + 1
                    }
                }
                else -> {
                    if (ni >= name.length || name[ni] != pc) return false
                    ni++
                    pi++
                }
            }
        }
        return ni == name.length
    }

    /** Membership in the body of a `[...]` set: single characters and `a-z` ranges (a backslash is literal). */
    private fun setMatches(stuff: String, ch: Char): Boolean {
        var i = 0
        while (i < stuff.length) {
            if (i + 2 < stuff.length && stuff[i + 1] == '-') {
                if (ch >= stuff[i] && ch <= stuff[i + 2]) return true
                i += 3
            } else {
                if (ch == stuff[i]) return true
                i++
            }
        }
        return false
    }
}
