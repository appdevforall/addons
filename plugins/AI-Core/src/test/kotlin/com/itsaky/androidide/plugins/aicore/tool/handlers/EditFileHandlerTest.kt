package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.SelectionRange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [EditFileHandler] — surgical find/replace editing. Every rejection path asserts the
 * file is **byte-for-byte unchanged**, not merely that a failure was returned: a guard that reports
 * failure after truncating the file is the exact defect these tests exist to catch.
 */
class EditFileHandlerTest {

    private lateinit var projectRoot: File
    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var editorService: IdeEditorService
    private lateinit var handler: EditFileHandler

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("editfile-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)

        editorService = mockk(relaxed = true)
        // Default: nothing is open in the editor, so the disk path is exercised.
        every { editorService.getFileContent(any()) } returns null
        // Default: the host persists the buffer; the false case has its own test below.
        coEvery { editorService.saveFile(any()) } returns true
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeEditorService::class.java) } returns editorService

        handler = EditFileHandler(context, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    private fun createFile(relative: String, content: String): File =
        File(projectRoot, relative).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun edit(vararg pairs: Pair<String, Any?>) = runBlocking {
        handler.execute(mapOf(*pairs))
    }

    // --- Happy paths --------------------------------------------------------

    @Test
    fun givenAUniqueSnippet_whenEdited_thenOnlyThatSnippetChanges() {
        val file = createFile("Main.kt", "val a = 1\nval b = 2\nval c = 3\n")

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "val b = 2",
            "new_string" to "val b = 20",
        )

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("val a = 1\nval b = 20\nval c = 3\n", file.readText())
    }

    @Test
    fun givenAnEmptyNewString_whenEdited_thenTheSnippetIsDeleted() {
        val file = createFile("Main.kt", "keep\nremove me\nkeep\n")

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "remove me\n",
            "new_string" to "",
        )

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("keep\nkeep\n", file.readText())
    }

    @Test
    fun givenReplaceAll_whenTheSnippetRepeats_thenEveryOccurrenceChanges() {
        val file = createFile("Main.kt", "x = 1\ny = 1\nz = 1\n")

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "1",
            "new_string" to "2",
            "replace_all" to "true",
        )

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("x = 2\ny = 2\nz = 2\n", file.readText())
    }

    @Test
    fun givenAMultiLineSnippet_whenEdited_thenItIsReplacedWholesale() {
        val file = createFile("Main.kt", "fun a() {\n    old()\n    old2()\n}\n")

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "    old()\n    old2()",
            "new_string" to "    fresh()",
        )

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("fun a() {\n    fresh()\n}\n", file.readText())
    }

    @Test
    fun givenNoEditorService_whenEdited_thenTheDiskPathStillWorks() {
        val file = createFile("Main.kt", "old\n")
        every { services.get(IdeEditorService::class.java) } returns null

        val result = edit("file_path" to "Main.kt", "old_string" to "old", "new_string" to "new")

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("new\n", file.readText())
    }

    // --- Match failures -----------------------------------------------------

    @Test
    fun givenASnippetThatIsAbsent_whenEdited_thenItFailsAndTheFileIsUntouched() {
        val original = "val a = 1\n"
        val file = createFile("Main.kt", original)

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "val zzz = 9",
            "new_string" to "whatever",
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("not found"))
        assertEquals(original, file.readText())
    }

    @Test
    fun givenAnAmbiguousSnippet_whenEditedWithoutReplaceAll_thenItFailsWithTheMatchCount() {
        val original = "dup\ndup\ndup\n"
        val file = createFile("Main.kt", original)

        val result = edit("file_path" to "Main.kt", "old_string" to "dup", "new_string" to "x")

        assertFalse(result.success)
        assertTrue("Expected the count in: ${result.message}", result.message.contains("3 times"))
        assertEquals("nothing may be replaced when the match is ambiguous", original, file.readText())
    }

    @Test
    fun givenAnAmbiguousBareName_whenEditedWithoutReplaceAll_thenReplaceAllIsWhatItIsToldToDo() {
        createFile("Main.kt", "count\ncount\n")

        val result = edit("file_path" to "Main.kt", "old_string" to "count", "new_string" to "total")

        assertFalse(result.success)
        // The model must be steered to one replace_all edit, not one call per occurrence.
        assertTrue(
            "Expected a replace_all instruction in: ${result.message}",
            result.message.contains("replace_all") && result.message.contains("ONE edit"),
        )
        val replaceAllFirst = result.message.indexOf("replace_all")
        val surroundingLater = result.message.indexOf("surrounding lines")
        assertTrue(
            "replace_all must be offered before adding surrounding lines: ${result.message}",
            replaceAllFirst in 0 until surroundingLater,
        )
    }

    @Test
    fun givenAnAmbiguousCodeRegion_whenEditedWithoutReplaceAll_thenUniquenessIsSuggestedFirst() {
        createFile("Main.kt", "if (a) {\n    x()\n}\nif (a) {\n    x()\n}\n")

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "if (a) {\n    x()\n}",
            "new_string" to "if (a) {\n    y()\n}",
        )

        assertFalse(result.success)
        assertTrue(
            "A multi-line region is not a rename: ${result.message}",
            result.message.contains("add surrounding lines"),
        )
    }

    @Test
    fun givenAnIdenticalReplacement_whenEdited_thenItIsRejectedRatherThanRewritingTheFile() {
        val original = "same\n"
        val file = createFile("Main.kt", original)

        val result = edit("file_path" to "Main.kt", "old_string" to "same", "new_string" to "same")

        assertFalse(result.success)
        assertEquals(original, file.readText())
    }

    @Test
    fun givenTheUsersInstructionPastedIntoBothArgs_whenEdited_thenTheSplitPairIsSuggested() {
        // The exact local-model failure this hint exists for: "change _bind with _binding" echoed
        // into old_string and new_string, then repeated verbatim until the agent loop gave up.
        val original = "val _bind = 1\n"
        val file = createFile("Main.kt", original)

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "_bind with _binding",
            "new_string" to "_bind with _binding",
        )

        assertFalse(result.success)
        assertTrue(
            "Expected the corrected pair in: ${result.message}",
            result.message.contains("old_string=\"_bind\"") &&
                result.message.contains("new_string=\"_binding\""),
        )
        assertEquals(original, file.readText())
    }

    @Test
    fun givenIdenticalArgsThatAreRealCode_whenEdited_thenNoSplitIsInvented() {
        createFile("Main.kt", "val a = 1\n")

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "val a = 1",
            "new_string" to "val a = 1",
        )

        assertFalse(result.success)
        assertFalse(
            "A code snippet must not be re-read as an instruction: ${result.message}",
            result.message.contains("retry with"),
        )
    }

    // --- Argument validation ------------------------------------------------

    @Test
    fun givenNoNewString_whenEdited_thenItFailsRatherThanGuessingADeletion() {
        val original = "content\n"
        val file = createFile("Main.kt", original)

        val result = edit("file_path" to "Main.kt", "old_string" to "content")

        assertFalse(result.success)
        assertTrue(result.message.contains("new_string is required"))
        assertEquals(original, file.readText())
    }

    @Test
    fun givenNoOldString_whenEdited_thenItFails() {
        val result = edit("file_path" to "Main.kt", "new_string" to "x")

        assertFalse(result.success)
        assertTrue(result.message.contains("old_string is required"))
    }

    @Test
    fun givenABlankFilePath_whenEdited_thenItFails() {
        val result = edit("file_path" to "  ", "old_string" to "a", "new_string" to "b")

        assertFalse(result.success)
        assertTrue(result.message.contains("file_path is required"))
    }

    @Test
    fun givenAnOversizedNewString_whenEdited_thenItIsRejectedAndTheFileIsUntouched() {
        val original = "seed\n"
        val file = createFile("Main.kt", original)

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "seed",
            "new_string" to "x".repeat(EditFileHandler.MAX_ARG_CHARS + 1),
        )

        assertFalse(result.success)
        assertEquals(original, file.readText())
    }

    // --- Path and target guards --------------------------------------------

    @Test
    fun givenAPathEscapingTheProjectRoot_whenEdited_thenItIsRejected() {
        val result = edit("file_path" to "../outside.txt", "old_string" to "a", "new_string" to "b")

        assertFalse(result.success)
        assertTrue(result.message.contains("within project directory"))
    }

    @Test
    fun givenANonexistentFileWithNoLookalike_whenEdited_thenItPointsAtSearchProject() {
        val result = edit("file_path" to "Nope.kt", "old_string" to "a", "new_string" to "b")

        assertFalse(result.success)
        assertTrue(result.message.contains("search_project"))
    }

    @Test
    fun givenAGuessedPathWithTheWrongExtension_whenEdited_thenTheOneRealCandidateIsUsed() {
        // Bouncing back an invented .java path cost a turn and the model re-emitted the guess.
        val real = createFile("app/src/main/java/com/example/myapp/MainActivity.kt", "val a = 1\n")

        val result = edit(
            "file_path" to "app/src/main/java/com/example/MainActivity.java",
            "old_string" to "val a = 1",
            "new_string" to "val a = 2",
        )

        assertTrue("Expected the guess to resolve, got: ${result.message}", result.success)
        assertEquals("val a = 2\n", real.readText())
    }

    @Test
    fun givenAGuessedPath_whenValidated_thenTheCorrectedPathIsWhatGetsApproved() {
        // The user must review the file that will really change, not the model's guess.
        createFile("app/src/main/java/com/example/myapp/MainActivity.kt", "val a = 1\n")

        val validation = runBlocking {
            handler.validate(
                mapOf(
                    "file_path" to "app/src/main/java/com/example/MainActivity.java",
                    "old_string" to "val a = 1",
                    "new_string" to "val a = 2",
                )
            )
        }

        val accepted = validation as Validation.Accepted
        assertEquals(
            "app/src/main/java/com/example/myapp/MainActivity.kt",
            accepted.args["file_path"],
        )
    }

    @Test
    fun givenSeveralPlausibleFiles_whenEdited_thenItAsksInsteadOfPickingOne() {
        // With more than one candidate there is nothing safe to guess.
        createFile("app/src/main/java/a/MainActivity.kt", "val a = 1\n")
        createFile("app/src/main/java/b/MainActivity.kt", "val a = 1\n")

        val result = edit(
            "file_path" to "app/src/main/java/com/example/MainActivity.java",
            "old_string" to "val a = 1",
            "new_string" to "val a = 2",
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("did you mean"))
        assertEquals("val a = 1\n", File(projectRoot, "app/src/main/java/a/MainActivity.kt").readText())
        assertEquals("val a = 1\n", File(projectRoot, "app/src/main/java/b/MainActivity.kt").readText())
    }

    @Test
    fun givenAGuessedFolderButTheRightName_whenEdited_thenTheRealFileIsUsed() {
        val real = createFile("app/src/main/java/com/example/myapp/Settings.kt", "x\n")

        val result = edit("file_path" to "app/Settings.kt", "old_string" to "x", "new_string" to "y")

        assertTrue("Expected the guess to resolve, got: ${result.message}", result.success)
        assertEquals("y\n", real.readText())
    }

    // --- Content guards -----------------------------------------------------

    @Test
    fun givenAFileLargerThanTheCap_whenEdited_thenItIsRejectedBeforeBeingRead() {
        val file = File(projectRoot, "big.bin").apply {
            writeBytes(ByteArray((EditFileHandler.MAX_EDIT_BYTES + 1024).toInt()) { 'a'.code.toByte() })
        }
        val sizeBefore = file.length()

        val result = edit("file_path" to "big.bin", "old_string" to "aaa", "new_string" to "bbb")

        assertFalse(result.success)
        assertTrue(result.message.contains("too large"))
        assertEquals(sizeBefore, file.length())
    }

    @Test
    fun givenAFileWithNulBytes_whenEdited_thenItIsRefusedAsBinary() {
        val bytes = byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())
        val file = File(projectRoot, "blob.bin").apply { writeBytes(bytes) }

        val result = edit("file_path" to "blob.bin", "old_string" to "a", "new_string" to "z")

        assertFalse(result.success)
        assertTrue(result.message.contains("not a UTF-8 text file"))
        assertArrayEqualsBytes(bytes, file.readBytes())
    }

    @Test
    fun givenInvalidUtf8_whenEdited_thenItIsRefusedRatherThanRoundTrippedThroughReplacementChars() {
        // 0xFF is not valid UTF-8; a lenient read writes back U+FFFD and corrupts the file.
        val bytes = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())
        val file = File(projectRoot, "latin.txt").apply { writeBytes(bytes) }

        val result = edit("file_path" to "latin.txt", "old_string" to "a", "new_string" to "z")

        assertFalse(result.success)
        assertArrayEqualsBytes(bytes, file.readBytes())
    }

    // --- Line endings -------------------------------------------------------

    @Test
    fun givenACrlfFileAndAnLfSnippet_whenEdited_thenTheEditAppliesAndCrlfIsPreserved() {
        // A model emits \n, so a literal match finds nothing in a CRLF file and can never succeed.
        val file = createFile("Main.kt", "fun a() {\r\n    old()\r\n}\r\n")

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "fun a() {\n    old()",
            "new_string" to "fun a() {\n    fresh()",
        )

        assertTrue("Expected success, got: ${result.message}", result.success)
        // The file keeps its own convention: adapting the snippet must not rewrite every line.
        assertEquals("fun a() {\r\n    fresh()\r\n}\r\n", file.readText())
    }

    @Test
    fun givenACrlfFile_whenASingleLineSnippetIsEdited_thenOnlyThatLineChanges() {
        val file = createFile("Main.kt", "val a = 1\r\nval b = 2\r\n")

        val result = edit("file_path" to "Main.kt", "old_string" to "val b = 2", "new_string" to "val b = 3")

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("val a = 1\r\nval b = 3\r\n", file.readText())
    }

    @Test
    fun givenAMixedLineEndingFile_whenEditedAcrossLines_thenItRefusesRatherThanGuessing() {
        // Converting either way would rewrite lines the user never approved.
        val file = createFile("Main.kt", "one\r\ntwo\nthree\r\n")
        val bytes = file.readBytes()

        val result = edit("file_path" to "Main.kt", "old_string" to "one\ntwo", "new_string" to "x\ny")

        assertFalse(result.success)
        assertArrayEqualsBytes(bytes, file.readBytes())
    }

    // --- Open-editor path ---------------------------------------------------

    @Test
    fun givenAFileOpenInTheEditor_whenEdited_thenTheChangeGoesThroughTheBufferAtTheRightRange() {
        createFile("Main.kt", "line0\nline1\nline2\n")
        val target = File(projectRoot, "Main.kt")
        every { editorService.getFileContent(target) } returns "line0\nline1\nline2\n"
        every { editorService.replaceRange(any(), any(), any()) } returns true
        coEvery { editorService.saveFile(any()) } returns true

        val range = slot<SelectionRange>()
        val replacement = slot<String>()

        val result = edit("file_path" to "Main.kt", "old_string" to "line1", "new_string" to "LINE1")

        assertTrue("Expected success, got: ${result.message}", result.success)
        verify { editorService.replaceRange(eq(target), capture(range), capture(replacement)) }
        // 0-based line/column, matching the host editor's coordinate space.
        assertEquals(1, range.captured.startLine)
        assertEquals(0, range.captured.startColumn)
        assertEquals(1, range.captured.endLine)
        assertEquals(5, range.captured.endColumn)
        assertEquals("LINE1", replacement.captured)
        coVerify { editorService.saveFile(target) }
        // The save is file-targeted: the user's tab focus must not be read or moved (ADFA-5215).
        verify(exactly = 0) { editorService.openFile(any()) }
        verify(exactly = 0) { editorService.saveCurrentFile() }
    }

    @Test
    fun givenUnsavedEditorChanges_whenEdited_thenTheBufferIsTheTextMatchedAndNothingIsLost() {
        // Disk is stale; the user's unsaved buffer is what the model must edit.
        val file = createFile("Main.kt", "val a = 1\n")
        every { editorService.getFileContent(file) } returns "val a = 1\nval userTyped = 2\n"
        every { editorService.replaceRange(any(), any(), any()) } returns true
        coEvery { editorService.saveFile(any()) } returns true

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "val userTyped = 2",
            "new_string" to "val userTyped = 3",
        )

        assertTrue(
            "The snippet exists only in the unsaved buffer; got: ${result.message}",
            result.success
        )
        verify { editorService.replaceRange(eq(file), any(), eq("val userTyped = 3")) }
        // The handler must not have written the stale disk copy behind the editor's back.
        assertEquals("val a = 1\n", file.readText())
    }

    @Test
    fun givenReplaceAllInAnOpenBuffer_whenEdited_thenTheWholeBufferIsSwappedInOneEdit() {
        val file = createFile("Main.kt", "a\na\n")
        every { editorService.getFileContent(file) } returns "a\na\n"
        every { editorService.replaceRange(any(), any(), any()) } returns true
        coEvery { editorService.saveFile(any()) } returns true

        val range = slot<SelectionRange>()
        val replacement = slot<String>()

        val result = edit(
            "file_path" to "Main.kt",
            "old_string" to "a",
            "new_string" to "b",
            "replace_all" to true,
        )

        assertTrue("Expected success, got: ${result.message}", result.success)
        verify(exactly = 1) { editorService.replaceRange(any(), capture(range), capture(replacement)) }
        assertEquals(0, range.captured.startLine)
        assertEquals(0, range.captured.startColumn)
        assertEquals("b\nb\n", replacement.captured)
    }

    @Test
    fun givenTheHostCannotSaveTheFile_whenEdited_thenTheResultSaysUnsaved() {
        val file = createFile("Main.kt", "old\n")
        every { editorService.getFileContent(file) } returns "old\n"
        every { editorService.replaceRange(any(), any(), any()) } returns true
        coEvery { editorService.saveFile(any()) } returns false

        val result = edit("file_path" to "Main.kt", "old_string" to "old", "new_string" to "new")

        assertTrue("The buffer edit applied, so this is a success: ${result.message}", result.success)
        assertTrue(
            "Must not claim the file was saved; got: ${result.message}",
            result.message.contains("left unsaved")
        )
        // A failed save must not be retried by focusing the tab and saving that instead.
        verify(exactly = 0) { editorService.openFile(any()) }
        verify(exactly = 0) { editorService.saveCurrentFile() }
        assertEquals("old\n", file.readText())
    }

    @Test
    fun givenTheEditorRejectingTheEdit_whenEdited_thenItFailsAndTheDiskCopyIsUntouched() {
        val file = createFile("Main.kt", "old\n")
        every { editorService.getFileContent(file) } returns "old\n"
        every { editorService.replaceRange(any(), any(), any()) } returns false

        val result = edit("file_path" to "Main.kt", "old_string" to "old", "new_string" to "new")

        assertFalse(result.success)
        assertEquals("old\n", file.readText())
        coVerify(exactly = 0) { editorService.saveFile(any()) }
    }

    @Test
    fun givenTheUserTypingWhileTheEditWaitedForApproval_whenApplied_thenTheStaleOffsetsAreNotUsed() {
        // Offsets from the analysed buffer would replace the wrong span in a since-edited one.
        val file = createFile("Main.kt", "line0\nline1\nline2\n")
        val analysed = "line0\nline1\nline2\n"
        every { editorService.getFileContent(file) } returnsMany listOf(
            analysed,
            // What the user typed while the dialog was up: every offset past line 0 has moved.
            "inserted\nline0\nline1\nline2\n",
        )
        every { editorService.replaceRange(any(), any(), any()) } returns true

        val result = edit("file_path" to "Main.kt", "old_string" to "line1", "new_string" to "LINE1")

        assertFalse("a stale range must not be applied: ${result.message}", result.success)
        assertTrue(
            "the model needs to know why, so it can re-read: ${result.message}",
            result.message.contains("changed")
        )
        verify(exactly = 0) { editorService.replaceRange(any(), any(), any()) }
        coVerify(exactly = 0) { editorService.saveFile(any()) }
        assertEquals("the disk copy must not be touched either", analysed, file.readText())
    }

    @Test
    fun givenAnUnchangedBuffer_whenApplied_thenTheReReadDoesNotBlockTheEdit() {
        // The guard above must not fire when nothing changed between analysis and application.
        val file = createFile("Main.kt", "line0\nline1\n")
        every { editorService.getFileContent(file) } returnsMany listOf(
            "line0\nline1\n",
            "line0\nline1\n",
        )
        every { editorService.replaceRange(any(), any(), any()) } returns true
        coEvery { editorService.saveFile(any()) } returns true

        val result = edit("file_path" to "Main.kt", "old_string" to "line1", "new_string" to "LINE1")

        assertTrue("Expected success, got: ${result.message}", result.success)
        verify(exactly = 1) { editorService.replaceRange(any(), any(), any()) }
    }

    // --- Pre-approval validation -------------------------------------------

    @Test
    fun givenAnApplicableEdit_whenValidated_thenItPasses() = runBlocking {
        createFile("Main.kt", "val a = 1\n")

        val validation = handler.validate(
            mapOf("file_path" to "Main.kt", "old_string" to "val a = 1", "new_string" to "val a = 2")
        )

        assertTrue("expected acceptance, got $validation", validation is Validation.Accepted)
    }

    @Test
    fun givenIdenticalStrings_whenValidated_thenItIsRejectedWithoutPromptingTheUser() = runBlocking {
        // The commonest malformed edit: approving it can only fail, so never show the dialog.
        createFile("Main.kt", "_binding\n")

        val result = rejectionOf(
            mapOf("file_path" to "Main.kt", "old_string" to "_binding", "new_string" to "_binding")
        )

        assertTrue(result?.success == false)
        assertTrue(result!!.message.contains("identical"))
    }

    @Test
    fun givenAHallucinatedPath_whenValidated_thenItIsRejectedWithoutPromptingTheUser() = runBlocking {
        val result = rejectionOf(
            mapOf("file_path" to "app/src/main/java/com/nope/Ghost.java", "old_string" to "a", "new_string" to "b")
        )

        assertTrue(result?.success == false)
        assertTrue(result!!.message.contains("does not exist"))
    }

    @Test
    fun givenAnAbsentSnippet_whenValidated_thenItIsRejectedWithoutPromptingTheUser() = runBlocking {
        createFile("Main.kt", "val a = 1\n")

        val result = rejectionOf(
            mapOf("file_path" to "Main.kt", "old_string" to "not in the file", "new_string" to "x")
        )

        assertTrue(result?.success == false)
        assertTrue(result!!.message.contains("not found"))
    }

    @Test
    fun givenAProtectedPath_whenValidated_thenItIsRejectedWithoutPromptingTheUser() = runBlocking {
        createFile(".git/config", "[core]\n")

        val result = rejectionOf(
            mapOf("file_path" to ".git/config", "old_string" to "[core]", "new_string" to "[x]")
        )

        assertTrue(result?.success == false)
    }

    @Test
    fun givenValidation_whenItRuns_thenTheFileIsNotTouched() = runBlocking {
        // Running before approval, validate() must be side-effect free even when the edit is good.
        val file = createFile("Main.kt", "val a = 1\n")

        handler.validate(
            mapOf("file_path" to "Main.kt", "old_string" to "val a = 1", "new_string" to "val a = 2")
        )

        assertEquals("val a = 1\n", file.readText())
        verify(exactly = 0) { editorService.replaceRange(any(), any(), any()) }
        coVerify(exactly = 0) { editorService.saveFile(any()) }
    }

    // --- Content changing while the approval dialog is open -----------------

    @Test
    fun givenTheFileChangedAfterApproval_whenApplied_thenItRefusesRatherThanEditUnreviewedText() =
        runBlocking {
            // Rewritten under the dialog, old_string can still match once where nobody reviewed.
            val file = createFile("Main.kt", "val a = 1\nval keep = 0\n")
            val approved = acceptedArgsOf(
                mapOf("file_path" to "Main.kt", "old_string" to "val a = 1", "new_string" to "val a = 2")
            )

            file.writeText("val other = 9\nval a = 1\n")
            val result = handler.execute(approved)

            assertFalse("expected a refusal, got: ${result.message}", result.success)
            assertTrue(result.message.contains("changed after"))
            assertEquals("the file must be left exactly as it was", "val other = 9\nval a = 1\n", file.readText())
        }

    @Test
    fun givenTheFileIsUntouchedAfterApproval_whenApplied_thenTheEditStillGoesThrough() = runBlocking {
        // The staleness check must not cost the ordinary validate-then-execute path.
        val file = createFile("Main.kt", "val a = 1\n")
        val approved = acceptedArgsOf(
            mapOf("file_path" to "Main.kt", "old_string" to "val a = 1", "new_string" to "val a = 2")
        )

        val result = handler.execute(approved)

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("val a = 2\n", file.readText())
    }

    @Test
    fun givenNoFingerprint_whenApplied_thenTheEditIsNotBlocked() = runBlocking {
        // A caller that doesn't pre-validate gets the old behaviour rather than a hard failure.
        val file = createFile("Main.kt", "val a = 1\n")

        val result = handler.execute(
            mapOf("file_path" to "Main.kt", "old_string" to "val a = 1", "new_string" to "val a = 2")
        )

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("val a = 2\n", file.readText())
    }

    /** The arguments [EditFileHandler.validate] hands on for approval, fingerprint included. */
    private suspend fun acceptedArgsOf(args: Map<String, Any?>): Map<String, Any?> =
        (handler.validate(args) as Validation.Accepted).args

    /** The failure from a rejected validation, or null when it was accepted. */
    private suspend fun rejectionOf(args: Map<String, Any?>) =
        (handler.validate(args) as? Validation.Rejected)?.result

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "file bytes must be unchanged",
            expected.joinToString(",") { it.toString() },
            actual.joinToString(",") { it.toString() },
        )
    }
}
