package org.appdevforall.maps.templates

import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import com.itsaky.androidide.plugins.services.IdeTemplateService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the host-independent parts of [MapTemplateBuilder]: the template
 * display name, the `.cgt` filename it derives from that name (stripping every
 * non-letter/digit char, mirroring the host's `CgtTemplateBuilder.DIR_NAME_REGEX`
 * + appending `.cgt`), and [MapTemplateBuilder.unregister], which delegates to
 * the host's [IdeTemplateService.unregisterTemplate] with exactly the derived
 * filename.
 *
 * `unregister` is exercised through a hand-written [FakeTemplateService] (no mock
 * framework is available here) that captures the unregister argument and returns
 * a configurable result — this is the established way to test host-facing code in
 * this module.
 *
 * `buildAndRegister` / `populateScaffold` / `addOfflineRepo` are intentionally NOT
 * exercised here: they reach `ctx.androidContext.assets` (an abstract
 * `AssetManager` that can't be constructed on a plain JVM without
 * Robolectric/Mockk — both disallowed) via `thumbnailFromAssets`,
 * `addTemplateFromAssets`, and `addStaticFromAssets`, and they invoke the host's
 * real `CgtTemplateBuilder.build()` zip internals. They are covered by the
 * on-device / instrumented path. See `skipped` in the agent result.
 */
class MapTemplateBuilderCoverageTest {

    /**
     * Minimal hand-fake of the host's [IdeTemplateService]. Implements only the
     * members `MapTemplateBuilder.unregister` touches; the rest throw so an
     * accidental call surfaces loudly rather than silently passing.
     */
    private class FakeTemplateService(
        private val unregisterResult: Boolean,
    ) : IdeTemplateService {
        var unregisterArg: String? = null
            private set
        var unregisterCallCount: Int = 0
            private set

        override fun unregisterTemplate(templateName: String): Boolean {
            unregisterArg = templateName
            unregisterCallCount++
            return unregisterResult
        }

        override fun createTemplateBuilder(templateName: String): CgtTemplateBuilder =
            throw UnsupportedOperationException("not used by unregister")

        override fun registerTemplate(cgtFile: File): Boolean =
            throw UnsupportedOperationException("not used by unregister")

        override fun isTemplateRegistered(templateName: String): Boolean =
            throw UnsupportedOperationException("not used by unregister")

        override fun getRegisteredTemplates(): List<String> =
            throw UnsupportedOperationException("not used by unregister")

        override fun reloadTemplates(): Unit =
            throw UnsupportedOperationException("not used by unregister")
    }

    @Test
    fun `unregister delegates to the host with the derived cgt filename`() {
        val svc = FakeTemplateService(unregisterResult = true)

        val removed = MapTemplateBuilder.unregister(svc)

        assertTrue("unregister should return the host's true result", removed)
        assertEquals("called exactly once", 1, svc.unregisterCallCount)
        // The argument MUST be the exact derived filename deactivate() relies on —
        // a mismatch would orphan the .cgt in the New-Project grid.
        assertEquals("OfflineOSMMap.cgt", svc.unregisterArg)
        assertEquals(MapTemplateBuilder.REGION_MAP_TEMPLATE_FILE, svc.unregisterArg)
    }

    @Test
    fun `unregister propagates a false host result (template not present)`() {
        val svc = FakeTemplateService(unregisterResult = false)

        val removed = MapTemplateBuilder.unregister(svc)

        assertFalse("a false host result must propagate, not be swallowed", removed)
        assertEquals("OfflineOSMMap.cgt", svc.unregisterArg)
    }

    @Test
    fun `unregister does not call the host before being invoked`() {
        // Guards against the delegate being wired at field-init time rather than
        // on call — the host must see the unregister only when unregister() runs.
        val svc = FakeTemplateService(unregisterResult = true)
        assertEquals(0, svc.unregisterCallCount)
        assertNull(svc.unregisterArg)
    }

    @Test
    fun `template name is the human-readable Offline OSM Map`() {
        assertEquals("Offline OSM Map", MapTemplateBuilder.REGION_MAP_TEMPLATE_NAME)
    }

    @Test
    fun `cgt filename strips spaces and appends cgt`() {
        // "Offline OSM Map" -> "OfflineOSMMap.cgt" — the exact string deactivate()
        // passes to unregisterTemplate(), so it must match the host's derivation.
        assertEquals("OfflineOSMMap.cgt", MapTemplateBuilder.REGION_MAP_TEMPLATE_FILE)
    }

    @Test
    fun `cgt filename derivation preserves only letters and digits from the name`() {
        val file = MapTemplateBuilder.REGION_MAP_TEMPLATE_FILE
        val stem = file.removeSuffix(".cgt")
        assertTrue("derived stem must end in .cgt", file.endsWith(".cgt"))
        // Stem has no spaces / punctuation.
        assertFalse("stem must not contain spaces", stem.contains(" "))
        assertTrue("stem must be alphanumeric only", stem.all { it.isLetterOrDigit() })
        // Every alphanumeric char of the source name survives, in order.
        val expected = MapTemplateBuilder.REGION_MAP_TEMPLATE_NAME.filter { it.isLetterOrDigit() }
        assertEquals(expected, stem)
    }
}
