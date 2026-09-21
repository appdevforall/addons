package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [EditTargetResolver] — the decision about *which* file an edit may touch. Both halves
 * are load-bearing: rejecting too much makes the tool unusable on a local model, whose paths are often
 * invented, and accepting too much lets a write land in the git database.
 */
class EditTargetResolverTest {

    private lateinit var projectRoot: File

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("edit-target").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    private fun createFile(relative: String, content: String = "x\n"): File =
        File(projectRoot, relative).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun resolved(path: String) =
        EditTargetResolver.resolve(path) as? EditTargetResolver.Target.Resolved
            ?: error("expected $path to resolve")

    private fun rejection(path: String) =
        (EditTargetResolver.resolve(path) as? EditTargetResolver.Target.Rejected)?.reason
            ?: error("expected $path to be rejected")

    @Test
    fun givenAnExistingFile_whenResolved_thenItIsUsedAsGiven() {
        val file = createFile("app/src/Main.kt")

        val target = resolved("app/src/Main.kt")

        assertEquals(file.canonicalFile, target.file.canonicalFile)
        assertEquals("app/src/Main.kt", target.displayPath)
        assertNull("nothing was corrected", target.correctedFrom)
    }

    @Test
    fun givenAGuessedPathWithOneRealCandidate_whenResolved_thenTheRealFileIsUsedAndReported() {
        // The commonest local-model error: right class, wrong language or folder.
        createFile("app/src/main/kotlin/Main.kt")

        val target = resolved("app/src/main/java/Main.java")

        assertEquals("Main.kt", target.file.name)
        assertEquals(
            "the corrected path is what the approval dialog must show",
            "app/src/main/kotlin/Main.kt",
            target.displayPath,
        )
        assertEquals("app/src/main/java/Main.java", target.correctedFrom)
    }

    @Test
    fun givenSeveralPlausibleCandidates_whenResolved_thenItAsksInsteadOfPickingOne() {
        createFile("a/Main.kt")
        createFile("b/Main.kt")

        val reason = rejection("c/Main.kt")

        assertTrue("must offer the candidates: $reason", reason.contains("did you mean"))
        assertTrue("both candidates belong in the message: $reason", reason.contains("b/Main.kt"))
    }

    @Test
    fun givenOneExactNameMatchBesideOtherLanguages_whenResolved_thenTheExactNameWins() {
        // The extension the model asked for settles it; bouncing this back costs a whole turn for
        // nothing. Only rivals *with the same name* make the choice genuinely ambiguous.
        createFile("app/src/main/kotlin/Main.kt")
        createFile("app/legacy/Main.java")

        val target = resolved("app/src/Main.kt")

        assertEquals("app/src/main/kotlin/Main.kt", target.displayPath)
        assertEquals("app/src/Main.kt", target.correctedFrom)
    }

    @Test
    fun givenTwoSameNameMatchesAndAStemMatch_whenResolved_thenItStillAsks() {
        createFile("a/Main.kt")
        createFile("b/Main.kt")
        createFile("c/Main.java")

        val reason = rejection("d/Main.kt")

        assertTrue("got: $reason", reason.contains("did you mean"))
    }

    @Test
    fun givenNoPlausibleCandidate_whenResolved_thenItPointsAtSearchProjectAndCreateFile() {
        val reason = rejection("com/nope/Ghost.java")

        assertTrue(reason.contains("search_project"))
        assertTrue(reason.contains("create_file"))
    }

    @Test
    fun givenAPathOutsideTheProject_whenResolved_thenItIsRejected() {
        val reason = rejection("../../etc/hosts")

        assertTrue(reason.contains("within project directory"))
    }

    @Test
    fun givenAFileInsideTheGitDatabase_whenResolved_thenItIsRefused() {
        createFile(".git/config", "[core]\n")

        val reason = rejection(".git/config")

        assertTrue("git internals are unrecoverable for a user with no other checkout: $reason",
            reason.contains(".git"))
    }

    @Test
    fun givenAFileInAGeneratedTree_whenResolved_thenItIsRefused() {
        createFile("app/build/generated/Out.kt")

        val reason = rejection("app/build/generated/Out.kt")

        assertTrue(reason.contains("build/"))
    }

    @Test
    fun givenSigningMaterial_whenResolved_thenItIsRefused() {
        createFile("release.keystore")

        val reason = rejection("release.keystore")

        assertTrue(reason.contains("signing"))
    }

    @Test
    fun givenBuildConfiguration_whenResolved_thenItIsRefused() {
        createFile("local.properties", "sdk.dir=/x\n")

        val reason = rejection("local.properties")

        assertTrue(reason.contains("build configuration"))
    }

    @Test
    fun givenADirectory_whenResolved_thenItIsRejectedBeforeAnyWriteIsAttempted() {
        // A directory resolves and is contained, so only the isFile check stops the write.
        File(projectRoot, "app/src").mkdirs()

        val reason = rejection("app/src")

        assertTrue("got: $reason", reason.contains("not a file"))
    }

    @Test
    fun givenADenylistedDirectoryItself_whenResolved_thenItIsRejectedRatherThanTreatedAsAFile() {
        // The denylist's dropLast(1) skips the basename, so the isFile check refuses a bare "build".
        File(projectRoot, "build").mkdirs()

        val reason = rejection("build")

        assertTrue("got: $reason", reason.contains("not a file"))
    }
}
