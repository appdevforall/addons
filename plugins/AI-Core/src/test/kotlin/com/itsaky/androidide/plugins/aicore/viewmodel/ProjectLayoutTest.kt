package com.itsaky.androidide.plugins.aicore.viewmodel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ProjectLayout]. The run this exists for spent seven of its sixteen turns walking
 * `app` → `src` → `main` → `java` → `com` → `example` and hit the step limit with work outstanding;
 * every path it was looking for is one this collapses into the prompt.
 */
class ProjectLayoutTest {

    private lateinit var root: File

    @Before
    fun setup() {
        root = Files.createTempDirectory("project-layout").toFile().canonicalFile
    }

    private fun dir(path: String): File = File(root, path).apply { mkdirs() }

    private fun file(path: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    @Test
    fun givenASingleChildPackageChain_whenDescribed_thenItCollapsesToTheDeepestDirectory() {
        dir("app/src/main/java/com/example/myapplication27")

        val modules = ProjectLayout.describe(root)

        assertEquals(1, modules.size)
        assertEquals("app", modules[0].name)
        assertEquals("app/src/main/java/com/example/myapplication27", modules[0].sourceDir)
    }

    @Test
    fun givenAPackageDirectoryHoldingSources_whenDescribed_thenDescentStopsThere() {
        // A directory with files in it is the package, not another level to walk through.
        file("app/src/main/java/com/example/app/MainActivity.java")
        dir("app/src/main/java/com/example/app/ui")

        assertEquals("app/src/main/java/com/example/app", ProjectLayout.describe(root)[0].sourceDir)
    }

    @Test
    fun givenABranchingTree_whenDescribed_thenDescentStopsAtTheBranch() {
        dir("app/src/main/java/com/example")
        dir("app/src/main/java/org/other")

        // Two packages: there is no single directory a new class obviously belongs in.
        assertEquals("app/src/main/java", ProjectLayout.describe(root)[0].sourceDir)
    }

    @Test
    fun givenAKotlinSourceRoot_whenDescribed_thenItIsFoundToo() {
        dir("app/src/main/kotlin/com/example/app")

        assertEquals("app/src/main/kotlin/com/example/app", ProjectLayout.describe(root)[0].sourceDir)
    }

    @Test
    fun givenLayoutsAndAManifest_whenDescribed_thenBothArePointedAt() {
        dir("app/src/main/java/com/example/app")
        dir("app/src/main/res/layout")
        file("app/src/main/AndroidManifest.xml")

        val module = ProjectLayout.describe(root)[0]

        assertEquals("app/src/main/res/layout", module.layoutDir)
        assertEquals("app/src/main/AndroidManifest.xml", module.manifest)
    }

    @Test
    fun givenNoLayoutsOrManifest_whenDescribed_thenNothingIsInvented() {
        dir("app/src/main/java/com/example/app")

        val module = ProjectLayout.describe(root)[0]

        assertNull(module.layoutDir)
        assertNull(module.manifest)
    }

    @Test
    fun givenSeveralModules_whenDescribed_thenTheAppModuleComesFirst() {
        dir("core/src/main/java/com/example/core")
        dir("app/src/main/java/com/example/app")
        dir("zebra/src/main/java/com/example/zebra")

        assertEquals(listOf("app", "core", "zebra"), ProjectLayout.describe(root).map { it.name })
    }

    @Test
    fun givenADirectoryThatIsNotAModule_whenDescribed_thenItIsLeftOut() {
        dir("app/src/main/java/com/example/app")
        dir("build/intermediates")
        dir("gradle/wrapper")

        assertEquals(listOf("app"), ProjectLayout.describe(root).map { it.name })
    }

    @Test
    fun givenAnEmptyJavaRootBesideAPopulatedKotlinOne_whenDescribed_thenKotlinIsNamed() {
        // AGP keeps an empty `java` beside the `kotlin` tree, and naming the empty one tells the
        // agent to write new Kotlin classes where nothing else lives.
        dir("app/src/main/java")
        file("app/src/main/kotlin/com/example/app/MainActivity.kt")

        assertEquals(
            "app/src/main/kotlin/com/example/app",
            ProjectLayout.describe(root)[0].sourceDir,
        )
    }

    @Test
    fun givenTwoEmptySourceRoots_whenDescribed_thenThePreferredOneIsStillNamed() {
        dir("app/src/main/java")
        dir("app/src/main/kotlin")

        assertEquals("app/src/main/java", ProjectLayout.describe(root)[0].sourceDir)
    }

    @Test
    fun givenAnEmptyProject_whenDescribed_thenTheBlockHasNothingToSay() {
        assertTrue(ProjectLayout.describe(root).isEmpty())
    }

    @Test
    fun givenAMissingRoot_whenDescribed_thenItDegradesToSayingNothing() {
        assertTrue(ProjectLayout.describe(File(root, "gone")).isEmpty())
    }
}
