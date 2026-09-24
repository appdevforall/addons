package com.itsaky.androidide.plugins.vectorsearch

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun givenIdenticalVectors_whenComparing_thenSimilarityIsOne() {
        val vector = floatArrayOf(0.3f, 0.4f, 0.5f)

        assertEquals(1.0f, VectorMath.cosineSimilarity(vector, vector), TOLERANCE)
    }

    @Test
    fun givenOrthogonalVectors_whenComparing_thenSimilarityIsZero() {
        val similarity = VectorMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))

        assertEquals(0.0f, similarity, TOLERANCE)
    }

    @Test
    fun givenOppositeVectors_whenComparing_thenSimilarityIsMinusOne() {
        val similarity = VectorMath.cosineSimilarity(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f))

        assertEquals(-1.0f, similarity, TOLERANCE)
    }

    @Test
    fun givenVectorsOfDifferentMagnitude_whenComparing_thenOnlyDirectionCounts() {
        // Cosine is scale-invariant, which is what lets an L2-normalised index rank at all.
        val similarity = VectorMath.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(10f, 20f))

        assertEquals(1.0f, similarity, TOLERANCE)
    }

    @Test
    fun givenAZeroVector_whenComparing_thenSimilarityIsZeroRatherThanNaN() {
        val similarity = VectorMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))

        assertEquals(0.0f, similarity, TOLERANCE)
    }

    @Test
    fun givenDifferentWidths_whenComparing_thenSimilarityIsZeroRatherThanAnException() {
        // Widths only differ across embedders. This answering 0.0f rather than throwing is exactly
        // why mixed vectors never surfaced as a failure, and why provenance is filtered in SQL.
        val similarity = VectorMath.cosineSimilarity(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f, 1f))

        assertEquals(0.0f, similarity, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-6f
    }
}
