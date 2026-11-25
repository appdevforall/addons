package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The contact strings are written into every config, blank as "", the Bilibili link always "", and read back. */
class ContactBrandingTest {
    private val icons = BrandIcons(
        launcher = Density.values().associateWith { ArscSupport.png("L-${it.qualifier}") },
        foreground = Density.values().associateWith { ArscSupport.png("F-${it.qualifier}") },
        notification = Density.values().associateWith { ArscSupport.png("N-${it.qualifier}") },
        mark = Density.values().associateWith { ArscSupport.png("M-${it.qualifier}") },
    )

    private val full = Contacts(
        website = "https://my.example/",
        contactEmail = "hello@my.example",
        feedbackEmail = " bugs@my.example ",
        supportUrl = "https://my.example/donate",
        forumUrl = "https://forum.my.example/",
        telegramUrl = "https://t.me/mine",
        youtubeUrl = "https://youtube.com/@mine",
    )

    private fun strings(bytes: ByteArray) = ArscSupport.strings(ArscSupport.load(bytes), "string")

    @Test
    fun everyKeyGetsTheUsersValueInTheDefaultAndInTheLocale() {
        val r = ResourceBranding.apply(ArscSupport.tableWithContacts(), Branding("My IDE", icons, full))
        val s = strings(r.table)
        val expected = mapOf(
            "about_website_url" to "https://my.example/",
            "adfa_email" to "hello@my.example",
            "feedback_email" to "bugs@my.example",
            "github_sponsors_url" to "https://my.example/donate",
            "github_discussions_url" to "https://forum.my.example/",
            "telegram_channel_url" to "https://t.me/mine",
            "youtube_channel_url" to "https://youtube.com/@mine",
            "bilibili_video_url" to "",
        )
        for ((key, value) in expected) for (cfg in listOf("", "de")) assertEquals("$key [$cfg]", value, s["string/$key [$cfg]"])
        assertEquals(16, r.report.contactEntries)
        // The name edit is unaffected.
        assertEquals("My IDE", s["string/app_name [de]"])
    }

    @Test
    fun blankFieldsAreWrittenAsEmptyStringsAndBilibiliIsAlwaysEmpty() {
        val r = ResourceBranding.apply(ArscSupport.tableWithContacts(), Branding("My IDE", icons, Contacts(website = "https://my.example/")))
        val s = strings(r.table)
        for (cfg in listOf("", "de")) {
            assertEquals("https://my.example/", s["string/about_website_url [$cfg]"])
            for (key in ArscSupport.CONTACT_DEFAULTS.keys - "about_website_url") assertEquals("$key [$cfg]", "", s["string/$key [$cfg]"])
        }
    }

    @Test
    fun withoutContactsTheContactStringsStayAsTheyAre() {
        val r = ResourceBranding.apply(ArscSupport.tableWithContacts(), Branding("My IDE", icons))
        val s = strings(r.table)
        for ((key, value) in ArscSupport.CONTACT_DEFAULTS) assertEquals(value, s["string/$key [de]"])
        assertEquals(0, r.report.contactEntries)
    }

    @Test
    fun aKeyMissingFromTheAppIsNotedAndTheRestAreWritten() {
        val r = ResourceBranding.apply(ArscSupport.tableWithContacts(without = setOf("telegram_channel_url")), Branding("My IDE", icons, full))
        assertEquals(14, r.report.contactEntries)
        assertTrue(r.report.notes.toString(), r.report.notes.any { "telegram_channel_url" in it })
        assertEquals("the builder adds no key the app lacks", false, strings(r.table).containsKey("string/telegram_channel_url []"))
    }

    @Test
    fun theRestOfTheTableIsUnchanged() {
        val source = ArscSupport.tableWithContacts()
        val r = ResourceBranding.apply(source, Branding("My IDE", icons, full))
        assertEquals(ArscSupport.ids(ArscSupport.load(source)), ArscSupport.ids(ArscSupport.load(r.table)))
        val s = strings(r.table)
        assertEquals("https://github.com/appdevforall/CodeOnTheGo", s["string/url []"])
        assertEquals("About %1\$s", s["string/formatted []"])
    }

    @Test
    fun invalidContactsAreRefusedBeforeAnythingIsWritten() {
        try {
            ResourceBranding.apply(ArscSupport.tableWithContacts(), Branding("My IDE", icons, Contacts(feedbackEmail = "not an email")))
            fail("wrote an invalid email")
        } catch (e: BrandingException) {
            assertTrue(e.message, e.message!!.startsWith("Feedback email:"))
        }
    }

    @Test
    fun theReadBackCatchesAContactThatWasNotWritten() {
        val source = ArscSupport.tableWithContacts()
        // The source still holds App Dev For All's values: checking it against the user's must fail.
        try {
            ResourceBranding.verify(source, ResourceBranding.fingerprint(ArscSupport.load(source)), Branding(ArscSupport.OLD, icons, full), emptyMap())
            fail("the read-back accepted contact strings that were not written")
        } catch (e: BrandingException) {
            assertTrue(e.message, e.message!!.contains("about_website_url") && e.message!!.contains("not written back"))
        }
        // And the edit itself passes it.
        val good = ResourceBranding.apply(source, Branding("My IDE", icons, full))
        ResourceBranding.verify(good.table, ResourceBranding.fingerprint(ArscSupport.load(source)), Branding("My IDE", icons, full), good.files)
    }

    @Test
    fun aContactStringHoldingTheAppNameIsNotRenamedFirst() {
        // A key that held the name is overwritten with the user's value, not with the renamed text.
        val t = ArscSupport.load(ArscSupport.tableWithContacts())
        t.iterator().next().getOrCreate("", "string", "about_website_url").setValueAsString("Code on the Go site")
        t.refreshFull()
        val r = ResourceBranding.apply(t.bytes, Branding("My IDE", icons, full))
        assertEquals("https://my.example/", strings(r.table)["string/about_website_url []"])
        assertEquals("only the strings outside the contact keys count as renamed", 4, r.report.otherStringsReplaced)
    }

    @Test
    fun aContactStringThatIsAReferenceIsRefused() {
        val t = ArscSupport.load(ArscSupport.tableWithContacts())
        val p = t.iterator().next()
        val target = p.getOrCreate("", "string", "plain").resourceId
        p.getOrCreate("de", "string", "feedback_email").setValueAsRaw(com.reandroid.arsc.value.ValueType.REFERENCE, target)
        t.refreshFull()
        try {
            ResourceBranding.apply(t.bytes, Branding("My IDE", icons, full))
            fail("wrote over a reference")
        } catch (e: BrandingException) {
            assertTrue(e.message, e.message!!.contains("feedback_email") && e.message!!.contains("not plain text"))
        }
    }
}
