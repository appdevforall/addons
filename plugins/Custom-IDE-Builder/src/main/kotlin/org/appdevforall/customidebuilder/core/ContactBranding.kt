package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.ValueType

/**
 * Writes [Contacts] into the string resources Code on the Go reads its contact details from. Code on the Go hides a
 * row or button whose string is empty, so a blank field is written as "". Each key is set in every config it has
 * (the default and each language), so no language keeps App Dev For All's details.
 */
object ContactBranding {
    /** The Bilibili video on the About page; a copy never shows it. */
    const val BILIBILI_KEY = "bilibili_video_url"

    /** The resource each field is written to. `adfa_email` is About > Email, whose mailto: link is built from it. */
    val KEYS: Map<ContactField, String> = linkedMapOf(
        ContactField.WEBSITE to HostSupport.CONTACT_MARKER,
        ContactField.CONTACT_EMAIL to "adfa_email",
        ContactField.FEEDBACK_EMAIL to "feedback_email",
        ContactField.SUPPORT_URL to "github_sponsors_url",
        ContactField.FORUM_URL to "github_discussions_url",
        ContactField.TELEGRAM_URL to "telegram_channel_url",
        ContactField.YOUTUBE_URL to "youtube_channel_url",
    )

    /** Every key this writes, to the value it gets: the trimmed field, or "" for a blank one and for [BILIBILI_KEY]. */
    fun values(contacts: Contacts): Map<String, String> {
        val t = contacts.trimmed()
        val out = LinkedHashMap<String, String>()
        for ((field, key) in KEYS) out[key] = t[field]
        out[BILIBILI_KEY] = ""
        return out
    }

    /**
     * Sets every entry of every key in [pkg] and counts them into [written] by key. Refuses contacts that
     * [ContactCheck] refuses, and an entry that is not plain text (a reference or a styled string), which a plain
     * string written over it would change in a way the read-back cannot check.
     */
    internal fun write(pkg: PackageBlock, contacts: Contacts, written: MutableMap<String, Int>) {
        val problems = ContactCheck.problems(contacts)
        if (problems.isNotEmpty()) throw BrandingException(problems.joinToString(" "))
        for ((key, value) in values(contacts)) {
            val resource = pkg.getResource("string", key) ?: continue
            for (entry in resource) {
                if (entry.valueType != ValueType.STRING || entry.valueAsString == null || entry.resValue.dataAsPoolString?.hasStyle() == true) {
                    throw BrandingException("$key [${entry.resConfig.qualifiers}] is not plain text, so it cannot be replaced.")
                }
                entry.setValueAsString(value)
                written[key] = (written[key] ?: 0) + 1
            }
        }
    }

    /** Checks every entry of every key in the reloaded [table] holds what [values] says. */
    internal fun verify(table: TableBlock, contacts: Contacts) {
        val expected = values(contacts)
        for (pkg in table) {
            for ((key, value) in expected) {
                val resource = pkg.getResource("string", key) ?: continue
                for (entry in resource) {
                    if (entry.valueType != ValueType.STRING || entry.valueAsString != value) {
                        throw BrandingException("$key [${entry.resConfig.qualifiers}] was not written back as given.")
                    }
                }
            }
        }
    }
}
