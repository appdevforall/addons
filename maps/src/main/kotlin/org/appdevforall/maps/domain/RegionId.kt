package org.appdevforall.maps.domain

import java.text.Normalizer

/**
 * Pure region-id rules: slugify a user-typed name into a cache-safe id, and
 * validate one. Lives in `domain/` (no Android) so it's unit-testable on the JVM,
 * and is the single home for both halves — the data layer ([org.appdevforall.maps
 * .data.RegionCache]) and the wizard ([org.appdevforall.maps.ui.Step3SaveFragment])
 * go through here, so "what we generate" and "what we accept" can't drift apart.
 */
object RegionId {

    /**
     * Maximum id length, in characters. 64 chars stays well under the 255-byte
     * filename limit even for multi-byte scripts.
     */
    const val MAX_LEN = 64

    /**
     * Unicode-aware allowlist. First char must be a letter or digit of any script;
     * the rest may be letters, digits, combining marks (needed by scripts like
     * Arabic/Devanagari) or '-'. Everything that could escape a directory or
     * confuse the filesystem — '/', '\\', '.', whitespace, punctuation, and
     * control/format chars (incl. bidi overrides and zero-width joiners) — falls
     * outside the letter/number/mark categories and is excluded by construction.
     */
    private val PATTERN = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}-]{0,${MAX_LEN - 1}}$")

    /**
     * True iff [id] is a lowercase, path-safe, length-bounded region id. Lowercase
     * is required so case-variants can't spawn distinct-yet-colliding directories
     * on case-insensitive storage.
     */
    fun isValid(id: String): Boolean = id == id.lowercase() && PATTERN.matches(id)

    /**
     * Slugify a user-typed name into a cache-safe id. **Unicode-aware:** any
     * script's letters/digits/marks are kept ("北京", "القاهرة", "Córdoba" all
     * produce usable ids); separators, whitespace, punctuation and control/format
     * chars collapse to '-'. NFC-normalised so visually-identical names map to the
     * same id, lowercased, and length-capped. Returns "" when the name has no
     * usable characters (the caller treats blank as "can't save").
     *
     * Invariant: the result always satisfies [isValid], or is "".
     */
    fun slugify(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), "-")
            .trim('-')
            .take(MAX_LEN)
            .trim('-')
}
