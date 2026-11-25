package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCheckTest {
    private fun url(s: String) = ContactCheck.urlProblem(s)

    private fun email(s: String) = ContactCheck.emailProblem(s)

    @Test
    fun blankFieldsAreAcceptedAndMeanHide() {
        for (blank in listOf("", "   ", "\t")) {
            assertNull(url(blank))
            assertNull(email(blank))
        }
        assertEquals(emptyList<String>(), ContactCheck.problems(Contacts()))
    }

    @Test
    fun goodLinksPass() {
        for (good in listOf(
            "https://www.appdevforall.org/",
            "https://example.org",
            "HTTPS://Example.ORG/path?q=1&r=2#frag",
            "https://t.me/MyChannel",
            "https://youtube.com/@my-channel",
            "https://example.org:8443/a%20b",
            "https://xn--bcher-kva.example/",
            "  https://example.org/trimmed  ",
        )) {
            assertNull(good, url(good))
        }
        assertNull(url("https://example.org/" + "a".repeat(ContactCheck.MAX_URL_LENGTH - 20)))
    }

    @Test
    fun linksThatAreNotAbsoluteHttpsAreRefused() {
        for (bad in listOf("http://example.org/", "ftp://example.org/", "example.org", "//example.org/x", "/about", "mailto:a@b.org", "javascript:alert(1)")) {
            val p = url(bad)
            assertNotNull(bad, p)
            assertTrue("$bad: $p", "https://" in p!!)
        }
    }

    @Test
    fun linksWithoutAHostOrWithUserInfoAreRefused() {
        assertTrue(url("https:///path")!!.contains("host"))
        assertTrue(url("https://bücher.example/")!!.contains("xn--"))
        assertTrue(url("https://user:pass@example.org/")!!.contains("user name"))
        assertTrue(url("https://user@example.org/")!!.contains("user name"))
    }

    @Test
    fun spacesControlCharactersAndOverlongLinksAreRefused() {
        assertTrue(url("https://example.org/a b")!!.contains("spaces"))
        assertTrue(url("https://example.org/ x")!!.contains("spaces"))
        assertTrue(url("https://example.org/\nx")!!.contains("control"))
        assertTrue(url("https://example.org/\u0000")!!.contains("control"))
        assertTrue(url("https://example.org/‮x")!!.contains("control"))
        assertTrue(url("https://example.org/" + "a".repeat(ContactCheck.MAX_URL_LENGTH))!!.contains("longer"))
        assertTrue(url("https://exa<mple.org/")!!.contains("not a valid link"))
    }

    @Test
    fun aPercentSignInALinkMustStartAnEscape() {
        assertNull(url("https://example.org/a%2Fb%20c"))
        for (bad in listOf("https://example.org/100%", "https://example.org/%zz", "https://example.org/%1", "https://example.org/%s")) {
            assertTrue(bad, url(bad)!!.contains("escape"))
        }
    }

    @Test
    fun goodEmailsPass() {
        for (good in listOf("info@appdevforall.org", "first.last+tag@mail.example.co.uk", "a@b.io", "under_score-dash@ex-ample.org", "  x@example.org ")) {
            assertNull(good, email(good))
        }
    }

    @Test
    fun badEmailsAreRefusedWithAReason() {
        assertTrue(email("no-at-sign.example.org")!!.contains("one address"))
        assertTrue(email("a@b@example.org")!!.contains("one address"))
        assertTrue(email("a b@example.org")!!.contains("spaces"))
        assertTrue(email("a@exa\nmple.org")!!.contains("control"))
        assertTrue(email("a\u0007@example.org")!!.contains("control"))
        assertTrue(email("mailto:a@example.org")!!.contains("mailto"))
        for (c in listOf('?', '&', '#', '%', '/', ':', '<', '>', '"')) {
            assertTrue("$c before @", email("a${c}b@example.org")!!.contains("cannot hold"))
            assertTrue("$c after @", email("ab@exa${c}mple.org")!!.contains("cannot hold"))
        }
        for (c in listOf(' ', '\u00A0', '\u2003')) assertTrue("space $c", email("a${c}b@example.org")!!.contains("spaces"))
        for (c in listOf('\t', '\n', '\r', '\u0000', '\u007F', '\u200B')) assertTrue("control ${c.code}", email("a${c}b@example.org")!!.contains("control"))
        // Characters a looser mailbox rule would let through.
        for (c in listOf('!', '$', '\'', '*', '=', '^', '{', '|', '}', '~')) assertTrue("$c", email("a${c}b@example.org")!!.contains("before @"))
        assertTrue(email("@example.org")!!.contains("before @"))
        assertTrue(email(".a@example.org")!!.contains("before @"))
        assertTrue(email("a..b@example.org")!!.contains("before @"))
        assertTrue(email("a@localhost")!!.contains("after @"))
        assertTrue(email("a@-x.org")!!.contains("after @"))
        assertTrue(email("a@example.123")!!.contains("after @"))
        assertTrue(email("a@exämple.org")!!.contains("xn--"))
        assertTrue(email("a".repeat(250) + "@example.org")!!.contains("longer"))
    }

    @Test
    fun problemsNameTheirFieldAndOnlyTheFieldsThatAreWrong() {
        val c = Contacts(website = "http://example.org", contactEmail = "fine@example.org", feedbackEmail = "nope", youtubeUrl = "https://youtube.com/@x")
        val p = ContactCheck.problems(c)
        assertEquals(p.toString(), 2, p.size)
        assertTrue(p[0], p[0].startsWith("Website: "))
        assertTrue(p[1], p[1].startsWith("Feedback email: "))
    }

    @Test
    fun eachFieldIsCheckedAsALinkOrAnEmail() {
        for (f in ContactField.values()) {
            val value = if (f.isEmail) "a@example.org" else "https://example.org/"
            assertEquals(f.name, emptyList<String>(), ContactCheck.problems(Contacts().with(f, value)))
            assertEquals(f.name, 1, ContactCheck.problems(Contacts().with(f, if (f.isEmail) "https://example.org/" else "a@example.org")).size)
            assertEquals(value, Contacts().with(f, value)[f])
        }
        assertEquals(setOf(ContactField.CONTACT_EMAIL, ContactField.FEEDBACK_EMAIL), ContactField.values().filter { it.isEmail }.toSet())
    }

    @Test
    fun aFieldOfOnlySpacesIsBlankAndIsWrittenAsEmpty() {
        val c = Contacts(website = "   ", feedbackEmail = " \t ")
        assertEquals(emptyList<String>(), ContactCheck.problems(c))
        assertEquals(Contacts(), c.trimmed())
        assertEquals("", ContactBranding.values(c)["about_website_url"])
        assertEquals("", ContactBranding.values(c)["feedback_email"])
    }

    @Test
    fun trimmedRemovesTheSpacesAroundEveryField() {
        val c = Contacts(" https://a.org ", " a@b.org", "c@d.org ", "\thttps://s.org", "https://f.org\t", " https://t.me/x ", " https://y.com ")
        assertEquals(Contacts("https://a.org", "a@b.org", "c@d.org", "https://s.org", "https://f.org", "https://t.me/x", "https://y.com"), c.trimmed())
    }
}
