package com.itsaky.androidide.plugins.aicore.managers

import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the per-project storage namespace (ADFA-5583). */
class ProjectKeyTest {

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
    }

    @Test
    fun givenSameRoot_whenDerivingKey_thenKeyIsStable() {
        val first = ProjectKey.forRoot("/projects/Alpha")
        val second = ProjectKey.forRoot("/projects/Alpha")

        assertEquals(first, second)
    }

    @Test
    fun givenDifferentRoots_whenDerivingKeys_thenKeysDiffer() {
        assertNotEquals(ProjectKey.forRoot("/projects/Alpha"), ProjectKey.forRoot("/projects/Beta"))
    }

    @Test
    fun givenTrailingSeparator_whenDerivingKey_thenKeyMatchesTheBareRoot() {
        assertEquals(ProjectKey.forRoot("/projects/Alpha"), ProjectKey.forRoot("/projects/Alpha/"))
    }

    @Test
    fun givenUnnormalizedRoot_whenDerivingKey_thenKeyMatchesTheCanonicalRoot() {
        assertEquals(
            ProjectKey.forRoot("/projects/Alpha"),
            ProjectKey.forRoot("/projects/Beta/../Alpha")
        )
    }

    @Test
    fun givenRoot_whenDerivingKey_thenKeyIsShortLowercaseHex() {
        val key = ProjectKey.forRoot("/projects/Alpha")

        assertEquals(16, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    @Test
    fun givenNullRoot_whenDerivingKey_thenNoProjectNamespace() {
        assertEquals(ProjectKey.NO_PROJECT, ProjectKey.forRoot(null))
    }

    @Test
    fun givenBlankRoot_whenDerivingKey_thenNoProjectNamespace() {
        assertEquals(ProjectKey.NO_PROJECT, ProjectKey.forRoot("   "))
    }

    @Test
    fun givenFilesystemRoot_whenDerivingKey_thenNoProjectNamespace() {
        assertEquals(ProjectKey.NO_PROJECT, ProjectKey.forRoot(File.separator))
    }

    @Test
    fun givenWorkspaceParent_whenDerivingKey_thenNoProjectNamespace() {
        // PathGuard's fallback means "nothing resolved"; hashing it would pool every rootless
        // state under one key that looks exactly like a real project's.
        assertEquals(ProjectKey.NO_PROJECT, ProjectKey.forRoot(PathGuard.DEFAULT_ROOT))
    }

    @Test
    fun givenAnUnnormalizedWorkspaceParent_whenDerivingKey_thenNoProjectNamespace() {
        // The fallback reaches this class through the filesystem, where it can arrive symlinked or
        // unnormalized. Both sides are canonicalized so it is still recognized as "nothing
        // resolved" rather than hashed into a key that looks like a real project's.
        val indirect = PathGuard.DEFAULT_ROOT + "/../" + File(PathGuard.DEFAULT_ROOT).name

        assertEquals(ProjectKey.NO_PROJECT, ProjectKey.forRoot(indirect))
    }

    @Test
    fun givenProjectRootOverride_whenAskingForCurrent_thenItMatchesThatRoot() {
        PathGuard.setProjectRootForTesting("/projects/Alpha")

        assertEquals(ProjectKey.forRoot("/projects/Alpha"), ProjectKey.current())
    }

    @Test
    fun givenHostThatThrows_whenAskingForCurrent_thenNullRatherThanAnException() {
        // Null tells the caller to keep the binding it has, instead of swapping the user onto an
        // empty history because the host answered badly once.
        PathGuard.setProjectRootProvider { throw IllegalStateException("no project service") }

        assertNull(ProjectKey.current())
    }
}
