package com.itsaky.androidide.plugins.aicore.tool.handlers

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PathGuard] — the shared containment guard for filesystem tools;
 * covers the regression where anchoring to `user.dir` ("/" on Android) rejected
 * every relative path (`open_file .gitignore` → "outside the project directory").
 */
class PathGuardTest {

    private lateinit var projectRoot: File

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("pathguard-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
    }

    @After
    fun tearDown() {
        // Reset shared singleton state so tests don't leak into each other.
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    // --- The reported bug -----------------------------------------------------

    @Test
    fun givenARelativeDotfile_whenResolveWithinIsCalled_thenItResolvesInsideTheProjectRoot() {
        val resolved = PathGuard.resolveWithin(".gitignore")

        assertNotNull("'.gitignore' must resolve inside the project root", resolved)
        assertEquals(File(projectRoot, ".gitignore").canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun givenTheFilesystemRootAsProjectRoot_whenAnyPathIsResolved_thenItIsRejected() {
        // A root of "/" means no project is open; the guard must reject everything.
        PathGuard.setProjectRootForTesting("/")

        assertNull("a relative path under '/' must be rejected", PathGuard.resolveWithin(".gitignore"))
        assertNull("an absolute path under '/' must be rejected", PathGuard.resolveWithin("/etc/passwd"))
    }

    @Test
    fun givenANonExistentProjectRoot_whenAPathIsResolved_thenItIsRejected() {
        PathGuard.setProjectRootForTesting("/no/such/project/dir/anywhere")

        assertNull(PathGuard.resolveWithin("build.gradle.kts"))
    }

    // --- Normal containment ---------------------------------------------------

    @Test
    fun givenANestedRelativePath_whenResolveWithinIsCalled_thenItResolvesInsideTheProjectRoot() {
        val resolved = PathGuard.resolveWithin("app/src/main/AndroidManifest.xml")

        assertNotNull(resolved)
        assertEquals(
            File(projectRoot, "app/src/main/AndroidManifest.xml").canonicalPath,
            resolved!!.canonicalPath
        )
    }

    @Test
    fun givenTheProjectRootPath_whenResolveWithinIsCalled_thenItResolves() {
        val resolved = PathGuard.resolveWithin(".")

        assertNotNull(resolved)
        assertEquals(projectRoot.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun givenAnAbsolutePathInsideTheRoot_whenResolveWithinIsCalled_thenItResolves() {
        val inside = File(projectRoot, "build.gradle.kts").absolutePath

        val resolved = PathGuard.resolveWithin(inside)

        assertNotNull(resolved)
        assertEquals(File(inside).canonicalPath, resolved!!.canonicalPath)
    }

    // --- Escape attempts are rejected ----------------------------------------

    @Test
    fun givenARelativeTraversalEscapingTheRoot_whenResolveWithinIsCalled_thenItIsRejected() {
        assertNull(PathGuard.resolveWithin("../secrets.txt"))
    }

    @Test
    fun givenAnAbsolutePathOutsideTheRoot_whenResolveWithinIsCalled_thenItIsRejected() {
        assertNull(PathGuard.resolveWithin("/etc/passwd"))
    }

    @Test
    fun givenASiblingDirectorySharingANamePrefix_whenResolveWithinIsCalled_thenItIsRejected() {
        // e.g. root "/tmp/proj" must NOT accept "/tmp/proj-evil".
        assertNull(PathGuard.resolveWithin(projectRoot.absolutePath + "-evil/file.txt"))
    }

    // --- Root resolution precedence ------------------------------------------

    @Test
    fun givenNoTestOverride_whenTheProjectRootIsQueried_thenTheProviderSuppliesIt() {
        PathGuard.setProjectRootForTesting(null)
        val providerRoot = Files.createTempDirectory("pathguard-provider").toFile().canonicalFile
        try {
            PathGuard.setProjectRootProvider { providerRoot.absolutePath }

            assertEquals(providerRoot.canonicalPath, File(PathGuard.projectRoot()).canonicalPath)
            assertNotNull(PathGuard.resolveWithin("settings.gradle"))
        } finally {
            providerRoot.deleteRecursively()
        }
    }

    // --- findByName (basename resolution) ------------------------------------

    @Test
    fun givenANestedFile_whenFindByNameIsCalledWithItsBasename_thenItIsLocated() {
        File(projectRoot, "app/src/main/java/com/example").mkdirs()
        File(projectRoot, "app/src/main/java/com/example/MainActivity.java").writeText("x")

        val found = PathGuard.findByName("MainActivity.java")

        assertEquals(1, found.size)
        assertEquals(
            File(projectRoot, "app/src/main/java/com/example/MainActivity.java").canonicalPath,
            found[0].canonicalPath
        )
    }

    @Test
    fun givenFilesDifferingOnlyInCase_whenFindByNameIsCalled_thenItMatchesAllCaseInsensitively() {
        File(projectRoot, "a").mkdirs(); File(projectRoot, "a/Notes.txt").writeText("x")
        File(projectRoot, "b").mkdirs(); File(projectRoot, "b/notes.txt").writeText("x")

        assertEquals(2, PathGuard.findByName("notes.txt").size)
    }

    @Test
    fun givenFilesUnderBuildAndDotDirectories_whenFindByNameIsCalled_thenTheyAreSkipped() {
        File(projectRoot, "build/generated").mkdirs()
        File(projectRoot, "build/generated/R.java").writeText("x")
        File(projectRoot, ".git").mkdirs()
        File(projectRoot, ".git/R.java").writeText("x")

        assertTrue("must not match files under build/ or .git/", PathGuard.findByName("R.java").isEmpty())
    }

    @Test
    fun givenNoMatchingFile_whenFindByNameIsCalled_thenItReturnsEmpty() {
        assertTrue(PathGuard.findByName("DoesNotExist.kt").isEmpty())
    }

    @Test
    fun givenAFileReachableOnlyThroughASymlinkedDir_whenFindByNameIsCalled_thenItIsNotMatched() {
        // A symlinked project subdir must not let the walk escape the root.
        val outside = Files.createTempDirectory("pathguard-outside").toFile().canonicalFile
        try {
            File(outside, "Secret.kt").writeText("x")
            val link = File(projectRoot, "linked").toPath()
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (e: Exception) {
                // Filesystem/OS without symlink support — nothing to assert.
                return
            }
            assertTrue(
                "a match reachable only via a symlinked dir must be dropped",
                PathGuard.findByName("Secret.kt").isEmpty()
            )
        } finally {
            outside.deleteRecursively()
        }
    }

    // --- resolve() — the shared handler resolution policy --------------------

    @Test
    fun givenAnExistingRelativePath_whenResolveIsCalled_thenItReturnsResolved() {
        File(projectRoot, "app").mkdirs()
        val target = File(projectRoot, "app/build.gradle.kts").apply { writeText("x") }

        val resolution = PathGuard.resolve("app/build.gradle.kts")

        assertTrue(resolution is PathGuard.Resolution.Resolved)
        assertEquals(target.canonicalPath, (resolution as PathGuard.Resolution.Resolved).file.canonicalPath)
    }

    @Test
    fun givenASlashPrefixedPath_whenResolveIsCalled_thenItRetriesAsRelativeBeforeBasenameSearch() {
        // The slash-stripped relative retry must resolve before an ambiguous basename search.
        File(projectRoot, "app").mkdirs(); File(projectRoot, "app/.gitignore").writeText("x")
        File(projectRoot, ".gitignore").writeText("x")

        val resolution = PathGuard.resolve("/app/.gitignore")

        assertTrue(resolution is PathGuard.Resolution.Resolved)
        assertEquals(
            File(projectRoot, "app/.gitignore").canonicalPath,
            (resolution as PathGuard.Resolution.Resolved).file.canonicalPath
        )
    }

    @Test
    fun givenABareName_whenResolveIsCalled_thenItReturnsResolvedViaBasenameSearch() {
        File(projectRoot, "app/src/main").mkdirs()
        File(projectRoot, "app/src/main/MainActivity.java").writeText("x")

        val resolution = PathGuard.resolve("MainActivity.java")

        assertTrue(resolution is PathGuard.Resolution.Resolved)
    }

    @Test
    fun givenABasenameMatchingSeveralFiles_whenResolveIsCalled_thenItReturnsAmbiguous() {
        File(projectRoot, "a").mkdirs(); File(projectRoot, "a/Strings.kt").writeText("x")
        File(projectRoot, "b").mkdirs(); File(projectRoot, "b/Strings.kt").writeText("x")

        val resolution = PathGuard.resolve("Strings.kt")

        assertTrue(resolution is PathGuard.Resolution.Ambiguous)
        assertEquals("Strings.kt", (resolution as PathGuard.Resolution.Ambiguous).baseName)
        assertEquals(2, resolution.matches.size)
    }

    @Test
    fun givenAPathWithNoInRootInterpretation_whenResolveIsCalled_thenItReturnsEscaped() {
        assertEquals(PathGuard.Resolution.Escaped, PathGuard.resolve("../outside.txt"))
    }

    @Test
    fun givenASlashPrefixedInRootPathThatDoesNotExist_whenResolveIsCalled_thenItReturnsNotFound() {
        // Absolute miss but in-root as relative → NotFound, not Escaped.
        assertEquals(PathGuard.Resolution.NotFound, PathGuard.resolve("/does/exist.kt"))
    }

    @Test
    fun givenAnInRootPathThatDoesNotExist_whenResolveIsCalled_thenItReturnsNotFound() {
        assertEquals(PathGuard.Resolution.NotFound, PathGuard.resolve("does/not/exist.kt"))
    }

    @Test
    fun givenNoSourceAnswers_whenTheRawProjectRootIsQueried_thenItIsNullRatherThanTheFallback() {
        // Containment wants a path to fail closed against, so projectRoot() still answers; a caller
        // deriving a per-project identity has to see that nothing resolved.
        PathGuard.setProjectRootForTesting(null)
        System.clearProperty("project.dir")

        assertNull(PathGuard.rawProjectRoot())
        assertEquals(PathGuard.DEFAULT_ROOT, PathGuard.projectRoot())
    }

    @Test
    fun givenABlankProviderResult_whenTheProjectRootIsQueried_thenItFallsThroughToTheNextSource() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider { "  " }
        System.setProperty("project.dir", projectRoot.absolutePath)
        try {
            assertEquals(projectRoot.canonicalPath, File(PathGuard.projectRoot()).canonicalPath)
        } finally {
            System.clearProperty("project.dir")
        }
    }
}
