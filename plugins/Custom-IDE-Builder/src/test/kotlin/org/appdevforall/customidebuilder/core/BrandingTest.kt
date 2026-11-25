package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.TableBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrandingTest {
    private val name = "My IDE"

    private fun icons(vararg densities: Density) = BrandIcons(
        launcher = densities.associateWith { ArscSupport.png("launcher-${it.qualifier}") },
        foreground = densities.associateWith { ArscSupport.png("foreground-${it.qualifier}") },
        notification = densities.associateWith { ArscSupport.png("notification-${it.qualifier}") },
        mark = densities.associateWith { ArscSupport.png("mark-${it.qualifier}") },
    )

    private val all = Density.values()

    private fun strings(t: TableBlock) = ArscSupport.strings(t, "string")

    @Test
    fun appNameIsReplacedInEveryConfig() {
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(name, null))
        val s = strings(ArscSupport.load(r.table))
        for (cfg in listOf("", "de", "fr", "es-rMX")) assertEquals(name, s["string/app_name [$cfg]"])
        assertEquals(4, r.report.appNameEntries)
        assertTrue("a name alone adds no files", r.files.isEmpty())
    }

    @Test
    fun protectedStringsKeepTheirText() {
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(name, null))
        val s = strings(ArscSupport.load(r.table))
        assertEquals(ArscSupport.OLD, s["string/made_with_code_on_the_go []"])
        assertEquals(ArscSupport.OLD, s["string/made_with_code_on_the_go [de]"])
        assertEquals("Download Code On The Go from the website", s["string/download_codeonthego_message []"])
        assertEquals(3, r.report.protectedKept)
    }

    @Test
    fun aStringSharingItsPoolItemWithAppNameIsLeftAlone() {
        // made_with_code_on_the_go and app_name were written with the same text, so the table stores it once.
        val original = ArscSupport.load(ArscSupport.table())
        val pool = original.stringPool.toList().filter { it.get() == ArscSupport.OLD }
        assertEquals("one shared pool item", 1, pool.size)
        val after = ResourceBranding.apply(ArscSupport.table(), Branding(name, null))
        val s = strings(ArscSupport.load(after.table))
        assertEquals(name, s["string/app_name []"])
        assertEquals(ArscSupport.OLD, s["string/made_with_code_on_the_go []"])
    }

    @Test
    fun otherStringsThatNameTheAppAreRewrittenInAnySpacingAndCase() {
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(name, null))
        val s = strings(ArscSupport.load(r.table))
        assertEquals("Welcome to My IDE!", s["string/welcome []"])
        assertEquals("Willkommen bei My IDE, dem Editor", s["string/welcome [de]"])
        assertEquals("My IDE", s["string/nbsp []"])
        assertEquals("Use My IDE today", s["string/dollar []"])
        assertEquals(4, r.report.otherStringsReplaced)
    }

    @Test
    fun urlsAndOtherWordsAreNotMistakenForTheName() {
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(name, null))
        val s = strings(ArscSupport.load(r.table))
        assertEquals("https://github.com/appdevforall/CodeOnTheGo", s["string/url []"])
        assertEquals("Code on the Gone", s["string/gone []"])
        assertEquals("About %1\$s", s["string/formatted []"])
        assertEquals("Nothing to see", s["string/plain []"])
    }

    @Test
    fun aNameWithRegexSpecialCharactersIsWrittenLiterally() {
        val tricky = "A\$1 \\B &"
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(tricky, null))
        val s = strings(ArscSupport.load(r.table))
        assertEquals(tricky, s["string/app_name []"])
        assertEquals("Welcome to $tricky!", s["string/welcome []"])
    }

    private fun binary(utf8: Boolean) = BinaryArsc.build(
        listOf(
            BinaryArsc.Str("app_name", ArscSupport.OLD, ArscSupport.OLD),
            BinaryArsc.Str("styled", "Welcome to Code on the Go now", "Willkommen bei Code on the Go", boldFrom = 11, boldTo = 24),
            BinaryArsc.Str("plain", "Code on the Go rocks"),
        ),
        utf8,
    )

    @Test
    fun aStringWithMarkupIsSkippedAndCountedWhateverThePoolEncoding() {
        for (utf8 in listOf(true, false)) {
            val source = binary(utf8)
            val before = ArscSupport.load(source)
            val styledBefore = before.stringPool.toList().filter { it.hasStyle() }
            assertEquals("the fixture holds a styled string (utf8=$utf8)", 1, styledBefore.size)
            val spanned = styledBefore[0].styleDocument.styledString
            val r = ResourceBranding.apply(source, Branding(name, null))
            assertEquals(1, r.report.styledSkipped)
            assertEquals("the plain string and the unstyled German one", 2, r.report.otherStringsReplaced)
            val after = ArscSupport.load(r.table)
            val styled = after.stringPool.toList().filter { it.hasStyle() }
            assertEquals(1, styled.size)
            assertEquals("the span and its text come through untouched", spanned, styled[0].styleDocument.styledString)
            val s = ArscSupport.strings(after, "string")
            assertEquals(name, s["string/app_name []"])
            assertEquals(name, s["string/app_name [de]"])
            assertEquals("My IDE rocks", s["string/plain []"])
            assertEquals("Willkommen bei My IDE", s["string/styled [de]"])
        }
    }

    @Test
    fun aRealFrameworkTableSurvivesAnEditThatMatchesNothingInIt() {
        // ARSCLib ships Android's own framework tables (900 KB, seven packages, thousands of entries) for its decoder.
        val apk = javaClass.classLoader.getResourceAsStream("frameworks/android/android-36.apk")
        org.junit.Assume.assumeNotNull(apk)
        val arsc = java.util.zip.ZipInputStream(apk!!).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "resources.arsc" }
            z.readBytes()
        }
        val r = ResourceBranding.apply(arsc, Branding(null, icons(*all)))
        assertEquals(0, r.report.iconEntriesRepointed)
        assertEquals(ArscSupport.ids(ArscSupport.load(arsc)), ArscSupport.ids(ArscSupport.load(r.table)))
    }

    @Test
    fun aTableMadeByAnotherWriterSurvivesAnUneditedRoundTripOfItsPool() {
        // A name-only edit of a table that holds no name leaves every other string as it was.
        val source = BinaryArsc.build(listOf(BinaryArsc.Str("app_name", "X"), BinaryArsc.Str("styled", "a b c", boldFrom = 2, boldTo = 2)), true)
        val r = ResourceBranding.apply(source, Branding(name, null))
        val after = ArscSupport.load(r.table)
        assertEquals(name, ArscSupport.strings(after, "string")["string/app_name []"])
        assertEquals(1, after.stringPool.toList().count { it.hasStyle() })
    }

    @Test
    fun everythingElseInTheTableIsUnchanged() {
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(name, icons(*all)))
        val before = ArscSupport.load(ArscSupport.table())
        val after = ArscSupport.load(r.table)
        assertEquals(ArscSupport.ids(before), ArscSupport.ids(after))
        val b = strings(before)
        val a = strings(after)
        for (key in listOf("string/plain []", "string/gone []", "string/url []", "string/formatted []")) assertEquals(b[key], a[key])
        assertEquals("res/drawable/ic_other.xml", ArscSupport.strings(after, "drawable")["drawable/ic_other []"])
        val flag = after.iterator().next().getEntry("", "bool", "flag")
        assertEquals(true, flag.valueAsBoolean)
        assertEquals(42, after.iterator().next().getEntry("", "integer", "answer").valueAsInteger)
    }

    @Test
    fun theIconsAndTheLogoAreRepointedInEveryConfigWithTheirIdsKept() {
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(null, icons(*all)))
        val after = ArscSupport.load(r.table)
        val dr = ArscSupport.strings(after, "drawable")
        val mm = ArscSupport.strings(after, "mipmap")
        val d = "res/custom-ide-builder"
        assertEquals("$d/launcher-xxxhdpi.png", dr["drawable/ic_launcher []"])
        assertEquals("$d/foreground-xxxhdpi.png", dr["drawable/ic_launcher_foreground []"])
        assertEquals("$d/foreground-xxxhdpi.png", dr["drawable/ic_launcher_fg_vector []"])
        assertEquals("$d/notification-xxxhdpi.png", dr["drawable/ic_cogo_notification []"])
        assertEquals("res/drawable/ic_other.xml", dr["drawable/ic_other []"])
        assertEquals("$d/mark-xxxhdpi.png", dr["drawable/cogo_brand_mark []"])
        for (q in all.map { it.qualifier }) {
            assertEquals("$d/launcher-$q.png", mm["mipmap/ic_launcher [$q]"])
            assertEquals("$d/launcher-$q.png", mm["mipmap/ic_launcher_round [$q]"])
            assertEquals("$d/foreground-$q.png", mm["mipmap/ic_launcher_foreground [$q]"])
        }
        assertEquals("the adaptive config points straight at a PNG", "$d/launcher-xxxhdpi.png", mm["mipmap/ic_launcher [anydpi-v26]"])
        assertEquals("$d/launcher-xxxhdpi.png", mm["mipmap/ic_launcher_round [anydpi-v26]"])
        assertEquals(ArscSupport.ids(ArscSupport.load(ArscSupport.table())), ArscSupport.ids(after))
        assertEquals(1 + 1 + 1 + 1 + 1 + 5 * 3 + 2, r.report.iconEntriesRepointed)
        // Every file an entry names is among the files to add, and nothing else is.
        val named = (dr.values + mm.values).filterNotNull().filter { it.startsWith("$d/") }.toSet()
        assertEquals(named, r.files.keys)
        assertArrayEquals(ArscSupport.png("launcher-xxxhdpi"), r.files["$d/launcher-xxxhdpi.png"])
    }

    @Test
    fun aDensityThatWasNotGeneratedTakesTheNearest() {
        val r = ResourceBranding.apply(ArscSupport.table(), Branding(null, icons(Density.XHDPI, Density.XXXHDPI)))
        val mm = ArscSupport.strings(ArscSupport.load(r.table), "mipmap")
        val d = "res/custom-ide-builder"
        assertEquals("$d/launcher-xhdpi.png", mm["mipmap/ic_launcher [mdpi]"])
        assertEquals("$d/launcher-xhdpi.png", mm["mipmap/ic_launcher [hdpi]"])
        assertEquals("$d/launcher-xxxhdpi.png", mm["mipmap/ic_launcher [xxhdpi]"])
        assertEquals(
            "only the generated files an entry needs are added",
            setOf("$d/launcher-xhdpi.png", "$d/launcher-xxxhdpi.png", "$d/foreground-xhdpi.png", "$d/foreground-xxxhdpi.png", "$d/notification-xxxhdpi.png", "$d/mark-xxxhdpi.png"),
            r.files.keys,
        )
    }

    @Test
    fun anIconAloneLeavesTheNameAndANameAloneLeavesTheIcons() {
        val iconsOnly = ArscSupport.load(ResourceBranding.apply(ArscSupport.table(), Branding(null, icons(*all))).table)
        assertEquals(ArscSupport.OLD, strings(iconsOnly)["string/app_name []"])
        assertEquals("Welcome to Code On The Go!", strings(iconsOnly)["string/welcome []"])
        val nameOnly = ArscSupport.load(ResourceBranding.apply(ArscSupport.table(), Branding(name, null)).table)
        assertEquals("res/drawable/ic_launcher.xml", ArscSupport.strings(nameOnly, "drawable")["drawable/ic_launcher []"])
    }

    @Test
    fun applyRunsTheCheckOnTheBytesItReturns() {
        var checked: ByteArray? = null
        val result =
            ResourceBranding.apply(ArscSupport.table(), Branding(name, icons(*all))) { bytes, _, _, _ -> checked = bytes }
        assertTrue("apply() did not run the check after the edit", checked != null)
        assertTrue("the check saw other bytes than apply() returned", checked!!.contentEquals(result.table))
    }

    private fun failsVerification(edited: ByteArray, original: ByteArray, branding: Branding, files: Map<String, ByteArray>, expect: String) {
        try {
            ResourceBranding.verify(edited, ResourceBranding.fingerprint(ArscSupport.load(original)), branding, files)
            fail("verification accepted a bad edit ($expect)")
        } catch (e: BrandingException) {
            assertTrue(e.message, e.message!!.contains(expect))
        }
    }

    @Test
    fun theCheckAfterTheEditCatchesAChangedValueALostEntryAWrongNameAndAStrayIconPath() {
        val original = ArscSupport.table()
        // The edit itself is fine: nothing to complain about.
        val good = ResourceBranding.apply(original, Branding(name, icons(*all)))
        ResourceBranding.verify(good.table, ResourceBranding.fingerprint(ArscSupport.load(original)), Branding(name, icons(*all)), good.files)

        val changed = ArscSupport.load(original)
        changed.iterator().next().getEntry("", "integer", "answer").setValueAsRaw(com.reandroid.arsc.value.ValueType.DEC, 7)
        changed.refreshFull()
        failsVerification(changed.bytes, original, Branding(null, null), emptyMap(), "more than intended")

        val fewer = BinaryArsc.build(listOf(BinaryArsc.Str("app_name", "X")), true)
        failsVerification(fewer, original, Branding(null, null), emptyMap(), "more than intended")

        failsVerification(original, original, Branding(name, null), emptyMap(), "app_name")

        val stray = ArscSupport.load(original)
        stray.iterator().next().getEntry("xhdpi", "mipmap", "ic_launcher").setValueAsString("res/custom-ide-builder/zzz.png")
        stray.refreshFull()
        failsVerification(stray.bytes, original, Branding(null, null), emptyMap(), "zzz.png")
    }

    @Test
    fun theResultSurvivesAnotherRoundTripAndAnotherEdit() {
        val first = ResourceBranding.apply(ArscSupport.table(), Branding(name, icons(*all)))
        val second = ResourceBranding.apply(first.table, Branding("Second", icons(*all)))
        assertEquals("Second", strings(ArscSupport.load(second.table))["string/app_name [de]"])
    }

    @Test
    fun aTableWithoutAppNameCannotBeRenamed() {
        val t = TableBlock()
        t.newPackage(0x7f, "x").getOrCreate("", "string", "other").setValueAsString("x")
        t.refreshFull()
        try {
            ResourceBranding.apply(t.bytes, Branding(name, null))
            fail("renamed a table with no app_name")
        } catch (e: BrandingException) {
            assertTrue(e.message, e.message!!.contains("app_name"))
        }
    }

    @Test
    fun garbageIsRefusedWithAMessage() {
        try {
            ResourceBranding.apply(ByteArray(64) { 7 }, Branding(name, null))
            fail("accepted garbage")
        } catch (e: BrandingException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun nearestDensityRules() {
        assertEquals(Density.XXXHDPI, Density.nearest(0, all.toList()))
        assertEquals(Density.XXXHDPI, Density.nearest(0xFFFE, all.toList()))
        assertEquals(Density.XXXHDPI, Density.nearest(0xFFFF, all.toList()))
        assertEquals(Density.HDPI, Density.nearest(240, all.toList()))
        assertEquals(Density.HDPI, Density.nearest(213, all.toList()))
        assertEquals(Density.XXXHDPI, Density.nearest(560, all.toList()))
        assertEquals(null, Density.nearest(160, emptyList()))
        assertEquals(144, Density.XXHDPI.px(IconSizes.LAUNCHER_DP))
        assertEquals(432, Density.XXXHDPI.px(IconSizes.FOREGROUND_DP))
        assertEquals(24, Density.MDPI.px(IconSizes.NOTIFICATION_DP))
    }
}
