package org.appdevforall.customidebuilder.core

import java.text.Normalizer
import java.util.Locale

/** The verdict on a name for the copy: [Ok] carries the name as it will be written, [Rejected] says why in words for the screen. */
sealed class NameVerdict {
    class Ok(val name: String) : NameVerdict()

    class Rejected(val reason: String) : NameVerdict()
}

/**
 * Checks the name the user wants to give the copy. The copy must not pass itself off as Code on the Go,
 * so a name is refused when, once look-alike characters and punctuation are ignored, it holds "cogo" or
 * "codeonthego", or holds something within two edits of "codeonthego".
 *
 * This is a guard against honest mistakes and cheap tricks, not a proof of intent: the screen also asks
 * the user to confirm that the name is their own, and that confirmation is what carries the responsibility.
 *
 * Decision on "within edit distance 2 of either": the 4-letter "cogo" is matched only as a substring, not
 * fuzzily. Two edits from a 4-letter word reach "go", "ego", "logo", "cargo" and "congo", and refusing
 * those would make the check unusable. The 11-letter "codeonthego" is matched fuzzily (two edits, anywhere
 * inside the name), which catches "Cod on the go" and "Code on teh Go" with no real name near it.
 */
object AppNameCheck {
    const val MAX_LENGTH = 50

    /** What a name is compared with, after [normalise]. */
    private val SHORT_TARGET = "cogo"
    private val LONG_TARGET = "codeonthego"

    private const val LONG_TARGET_EDITS = 2

    fun check(raw: String): NameVerdict {
        val name = raw.trim()
        if (name.isEmpty()) return NameVerdict.Rejected("Enter a name.")
        if (name.codePointCount(0, name.length) > MAX_LENGTH) return NameVerdict.Rejected("The name is longer than $MAX_LENGTH characters.")
        if (name.any { Character.isISOControl(it) }) return NameVerdict.Rejected("The name cannot hold a line break or other control character.")
        // A percent sign in a string is a format marker; Android would try to fill it in wherever the name is shown.
        if ('%' in name) return NameVerdict.Rejected("The name cannot hold a percent sign.")
        if (name.codePoints().noneMatch { Character.isLetterOrDigit(it) }) return NameVerdict.Rejected("The name needs at least one letter or digit.")
        val n = normalise(name)
        if (SHORT_TARGET in n || LONG_TARGET in n) return NameVerdict.Rejected(SIMILAR)
        if (approximatelyContains(n, LONG_TARGET, LONG_TARGET_EDITS)) return NameVerdict.Rejected(SIMILAR)
        return NameVerdict.Ok(name)
    }

    private const val SIMILAR = "This name is too close to Code on the Go. Choose a name of your own."

    /**
     * Lower case, accents and width variants folded away, look-alike digits, symbols and Cyrillic or
     * Greek letters turned into the Latin letter they imitate, then everything that is not a letter
     * or digit removed: "C0de-0n the G0!" becomes "codeonthegi".
     */
    fun normalise(name: String): String {
        // Look-alikes are mapped before decomposition (NFKD turns a Greek lunate sigma into an ordinary sigma and
        // loses the c it imitates) and again after it, for what decomposition produces.
        val lower = name.lowercase(Locale.ROOT).map { LOOK_ALIKES[it] ?: it }.joinToString("")
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            val mapped = LOOK_ALIKES[ch] ?: ch
            if (mapped.isLetterOrDigit()) sb.append(mapped)
        }
        return sb.toString()
    }

    private val LOOK_ALIKES: Map<Char, Char> = buildMap {
        // Digits and symbols.
        put('0', 'o'); put('1', 'i'); put('!', 'i'); put('|', 'i'); put('3', 'e'); put('4', 'a'); put('@', 'a')
        put('5', 's'); put('$', 's'); put('7', 't'); put('9', 'g'); put('8', 'b')
        // l, I and 1 are told apart by no font; i joins them.
        put('l', 'i')
        // Cyrillic.
        put('\u0441', 'c'); put('\u043E', 'o'); put('\u0435', 'e'); put('\u0430', 'a'); put('\u0455', 's')
        put('\u0456', 'i'); put('\u0458', 'j'); put('\u04BB', 'h'); put('\u0442', 't'); put('\u043D', 'h')
        put('\u0501', 'd'); put('\u0261', 'g'); put('\u051D', 'g'); put('\u0433', 'r')
        // Greek.
        put('\u03BF', 'o'); put('\u03B5', 'e'); put('\u03B1', 'a'); put('\u03F2', 'c'); put('\u03C4', 't')
        put('\u03B7', 'n'); put('\u03B9', 'i'); put('\u03B3', 'y'); put('\u03C3', 'o')
        // Latin letters that are not decomposed by NFKD.
        put('\u0131', 'i'); put('\u00F8', 'o'); put('\u0111', 'd'); put('\u0142', 'i')
    }

    /**
     * True when some substring of [text] is within [maxEdits] insertions, deletions or substitutions of
     * [target] (Sellers' algorithm: edit distance where the match may start and end anywhere in the text).
     */
    internal fun approximatelyContains(text: String, target: String, maxEdits: Int): Boolean {
        var previous = IntArray(target.length + 1) { it }
        for (c in text) {
            val current = IntArray(target.length + 1)
            for (j in 1..target.length) {
                val cost = if (target[j - 1] == c) 0 else 1
                current[j] = minOf(previous[j - 1] + cost, previous[j] + 1, current[j - 1] + 1)
            }
            if (current[target.length] <= maxEdits) return true
            previous = current
        }
        return previous[target.length] <= maxEdits
    }

    /** Plain edit distance between two whole strings; kept for the tests that pin [approximatelyContains]. */
    internal fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
