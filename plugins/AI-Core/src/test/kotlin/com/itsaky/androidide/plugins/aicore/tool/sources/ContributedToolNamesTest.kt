package com.itsaky.androidide.plugins.aicore.tool.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ContributedToolNames], which owns the agent's global tool namespace. */
class ContributedToolNamesTest {

    @Test
    fun givenAToolName_whenListingCandidates_thenItsOwnNameComesFirst() {
        // The plain name is what the tool's description talks about and what a user would type.
        val candidates = ContributedToolNames.candidates(
            "com.example.searchplugin",
            "code_search",
        )

        assertEquals(listOf("code_search", "searchplugin_code_search"), candidates)
    }

    @Test
    fun givenAToolNameWithPunctuation_whenListingCandidates_thenBothAreReducedToTheSafeAlphabet() {
        val candidates = ContributedToolNames.candidates("com.example.github", "GitHub: Create-Issue!")

        assertEquals(listOf("github_create_issue", "github_github_create_issue"), candidates)
    }

    @Test
    fun givenANameWithNothingUsable_whenListingCandidates_thenThereAreNone() {
        // Registering a broken name would leave a tool the model can name but never call.
        assertTrue(ContributedToolNames.candidates("com.example.plugin", "***").isEmpty())
        assertTrue(ContributedToolNames.candidates("com.example.plugin", "   ").isEmpty())
    }

    @Test
    fun givenAProviderWhoseAliasRepeatsTheToolName_whenListingCandidates_thenTheDuplicateIsDropped() {
        // "add" from a provider aliased "add" qualifies to "add_add"; the plain form must not repeat.
        val candidates = ContributedToolNames.candidates("com.example.add", "add")

        assertEquals(listOf("add", "add_add"), candidates)
        assertEquals(candidates.distinct(), candidates)
    }

    @Test
    fun givenAVeryLongName_whenQualifying_thenItIsCappedForTheModelsSake() {
        val name = ContributedToolNames.qualify("com.example.plugin", "a".repeat(120))

        assertTrue(name!!.length <= ContributedToolNames.MAX_TOOL_NAME_LENGTH)
        assertTrue(name.startsWith("plugin_"))
    }

    @Test
    fun givenAVeryLongName_whenListingCandidates_thenEveryCandidateIsCapped() {
        val candidates = ContributedToolNames.candidates("com.example.plugin", "a".repeat(120))

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.length <= ContributedToolNames.MAX_TOOL_NAME_LENGTH })
    }

    @Test
    fun givenAProviderIdWithNoUsableSegment_whenQualifying_thenTheToolNameStandsAlone() {
        // The bare name is then subject to the built-in reservation, which is where it is caught.
        assertEquals("edit_file", ContributedToolNames.qualify("***", "edit_file"))
    }

    @Test
    fun givenAProviderIdWithNoUsableSegment_whenListingCandidates_thenThereIsOnlyTheBareName() {
        assertEquals(listOf("edit_file"), ContributedToolNames.candidates("***", "edit_file"))
    }

    @Test
    fun givenAnUnusableToolName_whenQualifying_thenItIsRejected() {
        assertNull(ContributedToolNames.qualify("com.example.plugin", "***"))
    }
}
