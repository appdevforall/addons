package org.appdevforall.customidebuilder.core

import java.net.URI
import java.net.URISyntaxException

/**
 * The fields of [Contacts], in the order the screen shows them. [label] is the field's name on the screen and at the
 * start of its problems; [isEmail] says whether it takes an email address or a link.
 */
enum class ContactField(val label: String, val isEmail: Boolean) {
    WEBSITE("Website", false),
    CONTACT_EMAIL("Contact email", true),
    FEEDBACK_EMAIL("Feedback email", true),
    SUPPORT_URL("Support link", false),
    FORUM_URL("Discussion forum", false),
    TELEGRAM_URL("Telegram channel", false),
    YOUTUBE_URL("YouTube channel", false),
}

/**
 * The copy's own contact details, which replace App Dev For All's on its About page, its "Support our work" button
 * and its feedback and crash-report emails. A blank field means the copy shows nothing there.
 */
data class Contacts(
    val website: String = "",
    val contactEmail: String = "",
    val feedbackEmail: String = "",
    val supportUrl: String = "",
    val forumUrl: String = "",
    val telegramUrl: String = "",
    val youtubeUrl: String = "",
) {
    operator fun get(field: ContactField): String =
        when (field) {
            ContactField.WEBSITE -> website
            ContactField.CONTACT_EMAIL -> contactEmail
            ContactField.FEEDBACK_EMAIL -> feedbackEmail
            ContactField.SUPPORT_URL -> supportUrl
            ContactField.FORUM_URL -> forumUrl
            ContactField.TELEGRAM_URL -> telegramUrl
            ContactField.YOUTUBE_URL -> youtubeUrl
        }

    fun with(field: ContactField, value: String): Contacts =
        when (field) {
            ContactField.WEBSITE -> copy(website = value)
            ContactField.CONTACT_EMAIL -> copy(contactEmail = value)
            ContactField.FEEDBACK_EMAIL -> copy(feedbackEmail = value)
            ContactField.SUPPORT_URL -> copy(supportUrl = value)
            ContactField.FORUM_URL -> copy(forumUrl = value)
            ContactField.TELEGRAM_URL -> copy(telegramUrl = value)
            ContactField.YOUTUBE_URL -> copy(youtubeUrl = value)
        }

    /** Every field with the spaces around it removed: what is written into the copy. */
    fun trimmed(): Contacts = ContactField.values().fold(Contacts()) { acc, f -> acc.with(f, get(f).trim()) }
}

/**
 * Checks the contact details before a build. A blank field is always accepted: it hides that row or button. Text
 * around a value is trimmed first, as it is when it is written.
 *
 * Decision on `%`: a link may hold `%` only as the start of a percent escape (`%` and two hex digits), and an email
 * address may not hold it at all. Code on the Go reads these strings with `getString(id)` and no format arguments,
 * which returns the stored text as it is (no `String.format` runs), and it passes a link to the consent dialog as a
 * format argument, never as the pattern; so a stored `%` is never read as a format marker. A link still goes
 * through `Uri.parse`, where a `%` that does not start an escape would be decoded into something else.
 *
 * An email address is pasted unescaped into `mailto:<address>?subject=...`, so it is held to a plain form: letters,
 * digits, `.`, `_`, `+` and `-` before the `@`, and dot-separated labels of letters, digits and `-` after it. That
 * leaves out every character that changes the link's meaning (`?`, `&`, `#`, `%`, `/`, `:`, `<`, `>`, `"`).
 */
object ContactCheck {
    const val MAX_URL_LENGTH = 2048
    const val MAX_EMAIL_LENGTH = 254

    /** Characters that would change a `mailto:` link built from the address. */
    const val MAILTO_SPECIAL = "?&#%/:<>\""

    private val ESCAPE = Regex("%[0-9A-Fa-f]{2}")
    private val LOCAL_PART = Regex("[A-Za-z0-9._+-]+")
    private val DOMAIN_LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?")

    /** Every problem with [contacts], each starting with the field's label; empty when all may be written. */
    fun problems(contacts: Contacts): List<String> =
        ContactField.values().mapNotNull { f -> problem(f, contacts[f])?.let { "${f.label}: $it" } }

    /** What is wrong with [raw] for [field], in words for the screen, or null when it may be written. */
    fun problem(field: ContactField, raw: String): String? = if (field.isEmail) emailProblem(raw) else urlProblem(raw)

    fun urlProblem(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        if (url.length > MAX_URL_LENGTH) return "the link is longer than $MAX_URL_LENGTH characters."
        if (url.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) {
            return "the link cannot hold a line break or other control character."
        }
        if (url.any { Character.isWhitespace(it) || Character.isSpaceChar(it) }) return "the link cannot hold spaces."
        if ('%' in url.replace(ESCAPE, "")) return "a % in a link must start an escape such as %20."
        val uri =
            try {
                URI(url)
            } catch (e: URISyntaxException) {
                return "this is not a valid link. Enter a full address such as https://example.org/."
            }
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return "the link must start with https://."
        if (uri.rawUserInfo != null) return "the link cannot hold a user name or password before the host."
        if (uri.host.isNullOrEmpty()) return "the link needs a host name, such as example.org. Write an international name in its xn-- form."
        return null
    }

    fun emailProblem(raw: String): String? {
        val email = raw.trim()
        if (email.isEmpty()) return null
        if (email.length > MAX_EMAIL_LENGTH) return "the address is longer than $MAX_EMAIL_LENGTH characters."
        if (email.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) {
            return "the address cannot hold a line break or other control character."
        }
        if (email.any { Character.isWhitespace(it) || Character.isSpaceChar(it) }) return "the address cannot hold spaces."
        if (email.startsWith("mailto:", ignoreCase = true)) return "enter the address alone, without mailto:."
        if (email.count { it == '@' } != 1) return "enter one address such as name@example.org."
        if (email.any { it in MAILTO_SPECIAL }) return "the address cannot hold any of ${MAILTO_SPECIAL.toList().joinToString(" ")}."
        val local = email.substringBefore('@')
        val domain = email.substringAfter('@')
        if (local.isEmpty() || local.length > 64 || !LOCAL_PART.matches(local) || local.startsWith('.') || local.endsWith('.') || ".." in local) {
            return "the part before @ is not a usable mailbox name."
        }
        val labels = domain.split('.')
        if (labels.size < 2 || labels.any { it.length > 63 || !DOMAIN_LABEL.matches(it) } || labels.last().none { it.isLetter() }) {
            return "the part after @ must be a domain such as example.org. Write an international name in its xn-- form."
        }
        return null
    }
}
