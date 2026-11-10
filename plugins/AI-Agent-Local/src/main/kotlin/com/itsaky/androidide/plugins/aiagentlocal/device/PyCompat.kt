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

    /**
     * Python `repr(str)`: single quotes unless the text holds a single and no double quote; backslash, the
     * quote, `\t \n \r` escaped; every other character that `str.isprintable()` calls non-printable (general
     * categories Cc, Cf, Cs, Co, Cn, Zl, Zp and Zs except the ASCII space) as a backslash escape with 2, 4 or 8 lower-case hex digits (x, u or U)
     *. A lone surrogate is escaped; a surrogate pair is one astral character. "Unassigned"
     * follows the JDK's Unicode tables, which can be older than CPython 3.12's (Unicode 15.0) for characters
     * assigned recently.
     */
    fun repr(s: String): String {
        val q = if (s.contains('\'') && !s.contains('"')) '"' else '\''
        val sb = StringBuilder().append(q)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == q.code || cp == '\\'.code -> sb.append('\\').appendCodePoint(cp)
                cp == '\n'.code -> sb.append("\\n")
                cp == '\r'.code -> sb.append("\\r")
                cp == '\t'.code -> sb.append("\\t")
                !isPrintable(cp) -> sb.append(escape(cp))
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.append(q).toString()
    }

    /** Python `str.isprintable()` for one code point. */
    private fun isPrintable(cp: Int): Boolean {
        if (cp == ' '.code) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
            -> false
            else -> true
        }
    }

    private fun escape(cp: Int): String = when {
        cp < 0x100 -> String.format(Locale.ROOT, "\\x%02x", cp)
        cp < 0x10000 -> String.format(Locale.ROOT, "\\u%04x", cp)
        else -> String.format(Locale.ROOT, "\\U%08x", cp)
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
     * Python `math.fsum` over numbers: an exactly rounded sum (P1-04D, planner decision on P1-04C F02). Every item is
     * added as the exact decimal value of its double, and the total is rounded once to the nearest double, ties to
     * even, so the spelling (`8` or `8.0`) and the order of the items cannot change the result. An empty list gives
     * 0.0; negative zero is kept only when every item is negative zero (as `math.fsum` does). A total outside the
     * double range raises, as `math.fsum` raises OverflowError. Items are Long or Double (see [num]).
     */
    fun fsum(values: List<Any?>): Double {
        var exact = BigDecimal.ZERO
        for (v in values) {
            val d = num(v)
            require(!d.isNaN() && !d.isInfinite()) { "fsum: a non-finite value" }
            exact = exact.add(BigDecimal(d))
        }
        val r = exact.toDouble()
        if (r.isInfinite()) throw ArithmeticException("intermediate overflow in fsum")
        return if (r == 0.0) 0.0 else r // BigDecimal has no negative zero; Python's fsum returns 0.0 for a zero total
    }

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
