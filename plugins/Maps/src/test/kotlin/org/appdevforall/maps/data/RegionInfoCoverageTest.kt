package org.appdevforall.maps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Branch-coverage tests for [RegionInfo]'s hand-written `equals` / `hashCode`.
 *
 * The data class overrides equality so the `bbox` [DoubleArray] is compared
 * structurally (`contentEquals`) rather than by reference. This walks every
 * field-mismatch branch, the self / other-type short-circuits, and the four
 * bbox null-vs-set permutations.
 */
class RegionInfoCoverageTest {

    private val dir = File("/tmp/region-info-coverage")

    /** A fully-populated canonical instance every test mutates one field of. */
    private fun base(): RegionInfo = RegionInfo(
        regionId = "addis-ababa",
        displayName = "Addis Ababa",
        sizeBytes = 4096L,
        downloadedAt = 1735000000000L,
        lastUsedAt = 1735100000000L,
        source = "internet",
        sourceHost = "box.adfa.lan",
        layers = listOf("vector", "basemap"),
        directory = dir,
        bbox = doubleArrayOf(1.0, 2.0, 3.0, 4.0),
    )

    // ----- self / other-type short-circuits -----

    @Test
    fun equalsIsReflexive() {
        val a = base()
        assertTrue(a == a)
    }

    @Test
    fun equalsRejectsOtherType() {
        assertFalse(base().equals("not-a-region-info"))
        assertFalse(base().equals(null))
    }

    @Test
    fun equalIdenticalInstances() {
        // Two independently-constructed-but-equal instances compare equal and
        // share a hashCode (the contract the override exists to satisfy).
        val a = base()
        val b = base()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ----- one branch per scalar field differing -----

    @Test
    fun differsByRegionId() {
        assertNotEquals(base(), base().copy(regionId = "kathmandu"))
    }

    @Test
    fun differsByDisplayName() {
        assertNotEquals(base(), base().copy(displayName = "Other"))
    }

    @Test
    fun differsBySizeBytes() {
        assertNotEquals(base(), base().copy(sizeBytes = 1L))
    }

    @Test
    fun differsByDownloadedAt() {
        assertNotEquals(base(), base().copy(downloadedAt = 999L))
        // null-vs-set on the same nullable field.
        assertNotEquals(base(), base().copy(downloadedAt = null))
    }

    @Test
    fun differsByLastUsedAt() {
        assertNotEquals(base(), base().copy(lastUsedAt = 999L))
        assertNotEquals(base(), base().copy(lastUsedAt = null))
    }

    @Test
    fun differsBySource() {
        assertNotEquals(base(), base().copy(source = "iiab-lan"))
    }

    @Test
    fun differsBySourceHost() {
        assertNotEquals(base(), base().copy(sourceHost = "other.lan"))
        assertNotEquals(base(), base().copy(sourceHost = null))
    }

    @Test
    fun differsByLayers() {
        assertNotEquals(base(), base().copy(layers = listOf("vector")))
        assertNotEquals(base(), base().copy(layers = emptyList()))
    }

    @Test
    fun differsByDirectory() {
        assertNotEquals(base(), base().copy(directory = File("/tmp/other-dir")))
    }

    // ----- the four bbox null-vs-set permutations -----

    @Test
    fun bbothBboxNull_areEqual() {
        val a = base().copy(bbox = null)
        val b = base().copy(bbox = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun bboxNull_vs_set_areNotEqual() {
        // this.bbox == null, other.bbox != null.
        assertNotEquals(base().copy(bbox = null), base())
    }

    @Test
    fun bboxSet_vs_null_areNotEqual() {
        // this.bbox != null, other.bbox == null.
        assertNotEquals(base(), base().copy(bbox = null))
    }

    @Test
    fun bothBboxSet_sameContents_areEqual() {
        // Different array instances, identical contents → contentEquals true.
        val a = base().copy(bbox = doubleArrayOf(5.0, 6.0, 7.0, 8.0))
        val b = base().copy(bbox = doubleArrayOf(5.0, 6.0, 7.0, 8.0))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun bothBboxSet_differentContents_areNotEqual() {
        val a = base().copy(bbox = doubleArrayOf(5.0, 6.0, 7.0, 8.0))
        val b = base().copy(bbox = doubleArrayOf(5.0, 6.0, 7.0, 9.0))
        assertNotEquals(a, b)
    }

    // ----- hashCode null-coalescing branches -----

    @Test
    fun hashCodeHandlesAllNullableFieldsNull() {
        // Exercises the `?: 0` fallbacks for downloadedAt/lastUsedAt/sourceHost/bbox.
        val sparse = RegionInfo(
            regionId = "x",
            displayName = "x",
            sizeBytes = 0L,
            downloadedAt = null,
            lastUsedAt = null,
            source = "unknown",
            sourceHost = null,
            layers = emptyList(),
            directory = dir,
            bbox = null,
        )
        // Stable + equal to an identical sparse instance.
        assertEquals(sparse.hashCode(), sparse.copy().hashCode())
        assertEquals(sparse, sparse.copy())
    }
}
