package com.itsaky.androidide.plugins.aicore.tool.web

import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallingBackend.EXTRA_PARAM_REQUIRED_TOOL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VerificationPolicy]. The snippets are the three the agent approved as current
 * without searching (ADFA-6223); each has to trigger the search on its own.
 */
class VerificationPolicyTest {

    @Test
    fun givenAnUnfencedComposeSnippet_whenChecked_thenASearchIsRequired() {
        val message = """
            Review the following Kotlin snippet designed for an Android application using Jetpack Compose:

            @Composable
            fun CameraScreen() {
                val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
                if (permissionState.status.isGranted) {
                    Text("Camera access granted")
                }
            }
        """.trimIndent()

        assertTrue(VerificationPolicy.requiresWebCheck(message))
    }

    @Test
    fun givenAFencedSnippetLeftOpen_whenChecked_thenASearchIsRequired() {
        val message = "Analyze this Android networking code written with Ktor Client:\n\n```kotlin\n" +
            "val client = HttpClient(CIO) {\n    install(JsonFeature) {\n        serializer = KotlinxSerializer()\n" +
            "    }\n}"

        assertTrue(VerificationPolicy.requiresWebCheck(message))
    }

    @Test
    fun givenAnActivitySnippet_whenChecked_thenASearchIsRequired() {
        val message = """
            Examine the following legacy Android Activity configuration:
            override fun onCreate(savedInstanceState: Bundle?) {
                  super.onCreate(savedInstanceState)
                  setContentView(R.layout.activity_main)
            }
        """.trimIndent()

        assertTrue(VerificationPolicy.requiresWebCheck(message))
    }

    @Test
    fun givenAQuestionAboutWhatIsCurrent_whenChecked_thenASearchIsRequired() {
        assertTrue(VerificationPolicy.requiresWebCheck("What is the latest version of Ktor?"))
        assertTrue(VerificationPolicy.requiresWebCheck("Is Accompanist Permissions deprecated?"))
        assertTrue(VerificationPolicy.requiresWebCheck("¿Cuál es la última versión de Compose?"))
        assertTrue(VerificationPolicy.requiresWebCheck("ÚLTIMA VERSIÓN de Ktor"))
    }

    @Test
    fun givenAWordThatOnlyContainsAKeyword_whenChecked_thenItIsNotMatched() {
        // "remigrate" and "prereview" hold keywords but are not them; the boundary must see letters.
        assertFalse(VerificationPolicy.asksWhetherCurrent("remigrate the island"))
        assertFalse(VerificationPolicy.asksForReview("a prereview party"))
        assertFalse(VerificationPolicy.asksWhetherCurrent("sílegacy"))
    }

    @Test
    fun givenSmallTalkOrAPlainQuestion_whenChecked_thenNoSearchIsRequired() {
        assertFalse(VerificationPolicy.requiresWebCheck("hi"))
        assertFalse(VerificationPolicy.requiresWebCheck("What does a ViewModel do?"))
        assertFalse(VerificationPolicy.requiresWebCheck("Rename count to itemCount in MainActivity.kt"))
    }

    @Test
    fun givenProseNamingOneCall_whenChecked_thenItIsNotReadAsCode() {
        assertFalse(VerificationPolicy.containsCode("Why does\nlistOf() return an immutable list?"))
    }

    @Test
    fun givenAReviewRequest_whenFilesAreAttached_thenASearchIsRequiredOnlyThen() {
        assertTrue(VerificationPolicy.requiresWebCheck("Review this file", hasAttachedFiles = true))
        assertFalse(VerificationPolicy.requiresWebCheck("Review this file", hasAttachedFiles = false))
    }

    @Test
    fun givenAConfig_whenRequiringATool_thenOnlyTheCopyCarriesIt() {
        val config = LlmConfig("gemini").apply {
            temperature = 0.3f
            maxTokens = 100
            systemPrompt = "system"
            extraParams = mapOf("grammar" to "g")
        }

        val required = VerificationPolicy.requiring(config, WebAccess.WEB_SEARCH_TOOL)

        assertEquals("gemini", required.backendId)
        assertEquals(0.3f, required.temperature, 0f)
        assertEquals(100, required.maxTokens)
        assertEquals("system", required.systemPrompt)
        assertEquals(
            mapOf("grammar" to "g", EXTRA_PARAM_REQUIRED_TOOL to WebAccess.WEB_SEARCH_TOOL),
            required.extraParams,
        )
        assertNull(config.extraParams[EXTRA_PARAM_REQUIRED_TOOL])
    }
}
