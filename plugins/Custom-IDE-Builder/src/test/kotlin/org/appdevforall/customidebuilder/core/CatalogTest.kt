package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class CatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(type: String, slug: String, min: String, sha: String = "a".repeat(64), size: Long = 10, url: String = "https://addons.appdevforall.org/dl/$slug.cgp") =
        """{"type":"$type","slug":"$slug","addonId":"id.$slug","name":"Name $slug","version":"1.0","summary":"s","minAppVersion":"$min","download":{"url":"$url","sha256":"$sha","size":$size}}"""

    private fun catalog(vararg entries: String) = """{"schemaVersion":2,"generated":"x","addons":[${entries.joinToString(",")}]}"""

    @Test
    fun filtersByMinAppVersionAndType() {
        val parsed = Catalog.parse(
            catalog(
                entry("plugin", "old", "26.17"),
                entry("plugin", "same", "26.45"),
                entry("plugin", "newer", "26.46"),
                entry("template", "tpl", "26.40"),
                entry("template", "tpl-new", "27.01"),
                entry("snippet", "snip", "26.01"),
                entry("code-action", "action", "26.01"),
                entry("plugin", "nomin", ""),
            ),
        )
        val offer = Catalog.offer(parsed.addons, "26.45")
        assertTrue(offer.hostVersionKnown)
        assertEquals(listOf("nomin", "old", "same", "tpl"), offer.addons.map { it.slug }.sorted())
        assertEquals(listOf(AddonKind.PLUGIN, AddonKind.PLUGIN, AddonKind.PLUGIN, AddonKind.TEMPLATE), offer.addons.map { it.kind })
    }

    @Test
    fun hostVersionNamesAreReadLeniently() {
        val all = Catalog.parse(catalog(entry("plugin", "p", "26.45"))).addons
        assertEquals(1, Catalog.offer(all, "v26.45-abc123-release").addons.size)
        assertEquals(0, Catalog.offer(all, "26.44").addons.size)
        assertEquals(1, Catalog.offer(all, "26.45.1").addons.size)
        assertEquals(0, Catalog.offer(all, "25.99").addons.size)
        assertEquals(1, Catalog.offer(all, "100.0").addons.size)
    }

    @Test
    fun anUnreadableHostVersionOffersEverythingAndSaysSo() {
        val all = Catalog.parse(catalog(entry("plugin", "p", "26.45"))).addons
        for (v in listOf(null, "", "unknown")) {
            val offer = Catalog.offer(all, v)
            assertFalse(offer.hostVersionKnown)
            assertEquals(1, offer.addons.size)
        }
    }

    @Test
    fun versionComparison() {
        assertTrue(AppVersion.compare(listOf(26, 9), listOf(26, 10)) < 0)
        assertEquals(0, AppVersion.compare(listOf(26, 45), listOf(26, 45, 0)))
        assertEquals(listOf(26, 45), AppVersion.parse("v26.45-abc"))
    }

    @Test
    fun entriesWithoutAVerifiableDownloadAreSkipped() {
        val parsed = Catalog.parse(
            catalog(
                entry("plugin", "good", ""),
                entry("plugin", "BadSlug", ""),
                entry("plugin", "shortsha", "", sha = "abc"),
                entry("plugin", "upper", "", sha = "A".repeat(64)),
                entry("plugin", "nosize", "", size = -1),
            ),
        )
        assertEquals(listOf("good"), parsed.addons.map { it.slug })
        assertEquals(4, parsed.skipped)
    }

    @Test
    fun aCatalogOfTheWrongShapeIsAnError() {
        for (bad in listOf("nope", """{"schemaVersion":1,"addons":[]}""", """{"schemaVersion":2}""")) {
            try {
                Catalog.parse(bad)
                throw AssertionError("accepted: $bad")
            } catch (e: CatalogException) {
                // expected
            }
        }
    }

    @Test
    fun fileNameFollowsTheKind() {
        val a = Catalog.parse(catalog(entry("plugin", "p", ""), entry("template", "t", ""))).addons
        assertEquals(listOf("p.cgp", "t.cgt"), a.map { it.fileName })
    }

    private fun addonFor(bytes: ByteArray, size: Long = bytes.size.toLong(), sha: String = sha(bytes)) =
        Catalog.parse(catalog(entry("plugin", "dl", "", sha = sha, size = size))).addons.single()

    @Test
    fun aMatchingDownloadIsSavedAndVerified() {
        val bytes = TestSupport.noise(100_000)
        val addon = addonFor(bytes)
        val file = AddonDownloader { ByteArrayInputStream(bytes) }.download(addon, tmp.newFolder())
        assertEquals("dl.cgp", file.name)
        assertEquals(bytes.toList(), file.readBytes().toList())
        assertNull(Catalog.verifyDownload(file, addon))
    }

    @Test
    fun aWrongShaIsRejectedAndNothingIsLeftBehind() {
        val bytes = TestSupport.noise(1000)
        val dir = tmp.newFolder()
        val addon = addonFor(bytes, sha = "b".repeat(64))
        try {
            AddonDownloader { ByteArrayInputStream(bytes) }.download(addon, dir)
            throw AssertionError("accepted")
        } catch (e: DownloadException) {
            assertTrue(e.message, e.message!!.contains("SHA-256"))
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun aWrongSizeIsRejected() {
        val bytes = TestSupport.noise(1000)
        val dir = tmp.newFolder()
        for (promised in listOf(999L, 1001L)) {
            val addon = addonFor(bytes, size = promised)
            try {
                AddonDownloader { ByteArrayInputStream(bytes) }.download(addon, dir)
                throw AssertionError("accepted a promise of $promised")
            } catch (e: DownloadException) {
                // expected
            }
            assertEquals(0, dir.listFiles()!!.size)
        }
    }

    @Test
    fun aServerThatSendsMoreThanPromisedIsCutOff() {
        var read = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        val addon = addonFor(ByteArray(10), size = 10, sha = "c".repeat(64))
        try {
            AddonDownloader { endless }.download(addon, tmp.newFolder())
            throw AssertionError("accepted")
        } catch (e: DownloadException) {
            assertTrue(read < 1_000_000)
        }
    }

    @Test
    fun aNetworkErrorPropagatesAndLeavesNothing() {
        val dir = tmp.newFolder()
        try {
            AddonDownloader { throw IOException("offline") }.download(addonFor(ByteArray(3)), dir)
            throw AssertionError("accepted")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun cancellingAtAnyCheckStopsTheDownloadAndLeavesNothing() {
        val bytes = TestSupport.noise(1000)
        val addon = addonFor(bytes)
        // Call 1 is after the only chunk is read, 2 before the verification, 3 before the rename.
        for (stopAt in 1..3) {
            val dir = tmp.newFolder()
            var calls = 0
            try {
                AddonDownloader { ByteArrayInputStream(bytes) }.download(addon, dir, checkCancelled = { if (++calls == stopAt) throw java.util.concurrent.CancellationException("stop") })
                throw AssertionError("finished although cancelled at check $stopAt")
            } catch (e: java.util.concurrent.CancellationException) {
                assertEquals(stopAt, calls)
            }
            assertEquals("check $stopAt", 0, dir.listFiles()!!.size)
        }
    }

    @Test
    fun cancellingStopsTheCatalogRead() {
        try {
            AddonDownloader { ByteArrayInputStream("hello".toByteArray()) }.fetchCatalog(checkCancelled = { throw java.util.concurrent.CancellationException("stop") })
            throw AssertionError("finished")
        } catch (e: java.util.concurrent.CancellationException) {
            // expected
        }
    }

    @Test
    fun aMalformedRedirectLocationIsADownloadExceptionWithItsCause() {
        try {
            resolveRedirect("https://addons.appdevforall.org/a", "/bad path")
            throw AssertionError("accepted")
        } catch (e: DownloadException) {
            assertTrue(e.cause is IllegalArgumentException || e.cause is java.net.URISyntaxException)
        }
        assertEquals("https://addons.appdevforall.org/b/c", resolveRedirect("https://addons.appdevforall.org/a", "/b/c"))
        assertEquals("https://addons.appdevforall.org/a/d", resolveRedirect("https://addons.appdevforall.org/a/x", "d"))
    }

    @Test
    fun verifyDownloadChecksSizeAndDigest() {
        val f = File(tmp.root, "x").apply { writeBytes(ByteArray(5) { 1 }) }
        assertTrue(Catalog.verifyDownload(f, addonFor(ByteArray(5) { 1 }, size = 6))!!.contains("bytes"))
        assertNull(Catalog.verifyDownload(f, addonFor(ByteArray(5) { 1 })))
    }

    @Test
    fun theCatalogTextIsCapped() {
        val big = object : java.io.InputStream() {
            override fun read(): Int = 'a'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); return len }
        }
        try {
            AddonDownloader { big }.fetchCatalog()
            throw AssertionError("accepted")
        } catch (e: DownloadException) {
            // expected
        }
        assertEquals("hello", AddonDownloader { ByteArrayInputStream("hello".toByteArray()) }.fetchCatalog())
    }

    @Test
    fun onlyHttpsToTheAddonsHostIsAllowed() {
        assertTrue(DownloadPolicy.isAllowed("https://addons.appdevforall.org/v2/catalog.json"))
        assertTrue(DownloadPolicy.isAllowed("https://ADDONS.appdevforall.org:443/dl/x.cgp"))
        for (bad in listOf(
            "http://addons.appdevforall.org/dl/x.cgp",
            "https://evil.example/dl/x.cgp",
            "https://addons.appdevforall.org.evil.example/x",
            "https://user@addons.appdevforall.org/x",
            "https://addons.appdevforall.org:8443/x",
            "file:///etc/passwd",
            "not a url",
        )) assertFalse(bad, DownloadPolicy.isAllowed(bad))
    }
}
