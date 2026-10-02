package com.itsaky.androidide.plugins.aicore.capabilities

import com.itsaky.androidide.plugins.aicore.backends.BackendOption
import com.itsaky.androidide.plugins.aicore.backends.SelectedBackend
import com.itsaky.androidide.plugins.services.CapabilityStatus
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests how the host contracts become capability tags: one tag per MCP server rather than one
 * for MCP, broken sources kept and marked, and a misbehaving provider costing only its own tag.
 */
class CapabilityReadersTest {

    private companion object {
        const val MCP = "com.itsaky.androidide.plugins.aiagentmcp"
        const val OTHER = "com.example.searchplugin"
    }

    @Test
    fun givenASourceWithGroups_whenRead_thenEachGroupIsItsOwnTag() {
        val source = source(
            MCP,
            groups = listOf(
                group("a", "GitHub", tools = 3, status = CapabilityStatus.AVAILABLE),
                group("b", "Docs", tools = 0, status = CapabilityStatus.DEGRADED, message = "Docs is unreachable."),
            ),
        )

        val tags = CapabilityReaders.tools(listOf(source))

        assertEquals(listOf("GitHub", "Docs"), tags.map { it.name })
        assertEquals(listOf(3, 0), tags.map { it.toolCount })
        assertEquals(listOf(CapabilityStatus.AVAILABLE, CapabilityStatus.DEGRADED), tags.map { it.status })
        assertEquals("Docs is unreachable.", tags[1].statusMessage)
        assertTrue(tags.all { it.sourceName == "MCP servers" })
    }

    @Test
    fun givenASourceWithoutGroups_whenRead_thenItIsOneTagNamedForTheSource() {
        val source = source(OTHER, name = "Search", tools = 2)

        val tag = CapabilityReaders.tools(listOf(source)).single()

        assertEquals("Search", tag.name)
        assertEquals(2, tag.toolCount)
        assertNull(tag.groupId)
    }

    @Test
    fun givenAGrouplessSourceOfferingNothing_whenRead_thenItHasNoTag() {
        val source = source(MCP, tools = 0)

        assertEquals(emptyList<CapabilityTag.Tools>(), CapabilityReaders.tools(listOf(source)))
    }

    @Test
    fun givenABrokenSourceOfferingNothing_whenRead_thenItKeepsADegradedTag() {
        val source = source(OTHER, tools = 0, status = CapabilityStatus.DEGRADED)

        assertEquals(CapabilityStatus.DEGRADED, CapabilityReaders.tools(listOf(source)).single().status)
    }

    @Test
    fun givenASourceThatThrowsFromItsGroups_whenRead_thenTheOthersSurvive() {
        val broken = source(OTHER, tools = 1).also { every { it.toolGroups } throws IllegalStateException("boom") }
        val healthy = source(MCP, groups = listOf(group("a", "GitHub", tools = 1)))

        val tags = CapabilityReaders.tools(listOf(broken, healthy))

        assertEquals(listOf("Search", "GitHub"), tags.map { it.name })
    }

    @Test
    fun givenANullStatus_whenMapped_thenItReadsAsDegraded() {
        assertEquals(CapabilityStatus.DEGRADED, CapabilityReaders.status(null))
        assertEquals(CapabilityStatus.CONNECTING, CapabilityReaders.status(CapabilityStatus.CONNECTING))
    }

    @Test
    fun givenAReadyBackendReportingAModel_whenRead_thenTheTagCarriesIt() {
        val service = service(backend("gemini", available = true, model = "gemini-2.5-flash"))

        val tag = CapabilityReaders.backend(installed("gemini", "Gemini API"), service)

        assertEquals("Gemini API", tag.name)
        assertEquals("gemini-2.5-flash", tag.modelName)
        assertEquals(CapabilityStatus.AVAILABLE, tag.status)
    }

    @Test
    fun givenABackendThatIsNotConfigured_whenRead_thenItIsDegraded() {
        val service = service(backend("openai", available = false, model = null))

        val tag = CapabilityReaders.backend(installed("openai", "OpenAI"), service)

        assertEquals(BackendState.NOT_CONFIGURED, tag.state)
        assertEquals(CapabilityStatus.DEGRADED, tag.status)
        assertNull(tag.modelName)
    }

    @Test
    fun givenABackendThatThrowsFromItsModelName_whenRead_thenOnlyTheModelIsMissing() {
        val backend = backend("local", available = true, model = null).also {
            every { it.activeModelName } throws IllegalStateException("boom")
        }

        val tag = CapabilityReaders.backend(installed("local", "Local LLM"), service(backend))

        assertEquals(BackendState.READY, tag.state)
        assertNull(tag.modelName)
    }

    @Test
    fun givenAReadyBackendWhoseServerIsDown_whenRead_thenItIsDegradedWithItsReason() {
        val backend = backend(
            "openai", available = true, model = "qwen2.5-coder",
            status = CapabilityStatus.DEGRADED, message = "Nothing answered.",
        )

        val tag = CapabilityReaders.backend(installed("openai", "OpenAI"), service(backend))

        assertEquals(BackendState.READY, tag.state)
        assertEquals(CapabilityStatus.DEGRADED, tag.status)
        assertEquals("Nothing answered.", tag.statusMessage)
    }

    @Test
    fun givenAReadyBackendStillBeingChecked_whenRead_thenItIsConnecting() {
        val backend = backend(
            "openai", available = true, model = null, status = CapabilityStatus.CONNECTING,
        )

        val tag = CapabilityReaders.backend(installed("openai", "OpenAI"), service(backend))

        assertEquals(CapabilityStatus.CONNECTING, tag.status)
    }

    @Test
    fun givenABackendThatThrowsFromItsStatus_whenRead_thenItIsTakenAsAvailable() {
        val backend = backend("gemini", available = true, model = null).also {
            every { it.status } throws IllegalStateException("boom")
        }

        val tag = CapabilityReaders.backend(installed("gemini", "Gemini API"), service(backend))

        assertEquals(CapabilityStatus.AVAILABLE, tag.status)
        assertNull(tag.statusMessage)
    }

    @Test
    fun givenABackendThatReportsNeitherStatusNorModel_whenRead_thenItIsAvailableWithNoModel() {
        val backend = mockk<LlmInferenceService.LlmBackend> {
            every { getId() } returns "gemini"
            every { isAvailable() } returns true
        }

        val tag = CapabilityReaders.backend(installed("gemini", "Gemini API"), service(backend))

        assertEquals(CapabilityStatus.AVAILABLE, tag.status)
        assertNull(tag.statusMessage)
        assertNull(tag.modelName)
    }

    @Test
    fun givenASourceThatReportsNeitherStatusNorGroups_whenRead_thenItIsOneAvailableTag() {
        val plain = mockk<ToolSourceRegistry.ToolSource> {
            every { providerId } returns OTHER
            every { displayName } returns "Search"
            every { listTools() } returns List(2) { mockk() }
        }

        val tag = CapabilityReaders.tools(listOf(plain)).single()

        assertEquals(CapabilityStatus.AVAILABLE, tag.status)
        assertEquals(2, tag.toolCount)
        assertNull(tag.groupId)
    }

    @Test
    fun givenNoBackendInstalled_whenRead_thenTheTagSaysSo() {
        val tag = CapabilityReaders.backend(SelectedBackend.None, null)

        assertEquals(BackendState.NONE_INSTALLED, tag.state)
        assertEquals(CapabilityStatus.DEGRADED, tag.status)
    }

    private fun installed(id: String, name: String) =
        SelectedBackend.Installed(BackendOption(id, name, null, null))

    private fun service(backend: LlmInferenceService.LlmBackend): LlmInferenceService {
        // Read before recording: a call on another mock inside `every` is recorded as part of it.
        val id = backend.id
        return mockk { every { getBackend(id) } returns backend }
    }

    private fun backend(
        id: String,
        available: Boolean,
        model: String?,
        status: CapabilityStatus = CapabilityStatus.AVAILABLE,
        message: String? = null,
    ) = mockk<FullBackend> {
        every { getId() } returns id
        every { isAvailable() } returns available
        every { activeModelName } returns model
        every { getStatus() } returns status
        every { statusMessage } returns message
    }

    private fun source(
        id: String,
        name: String = if (id == MCP) "MCP servers" else "Search",
        tools: Int = 0,
        status: CapabilityStatus = CapabilityStatus.AVAILABLE,
        groups: List<ToolSourceRegistry.ToolGroup> = emptyList(),
    ) = mockk<FullSource> {
        every { providerId } returns id
        every { displayName } returns name
        every { listTools() } returns List(tools) { mockk() }
        every { this@mockk.status } returns status
        every { statusMessage } returns null
        every { toolGroups } returns groups
    }

    /** A backend that reports both status and model, as the OpenAI plugin's does. */
    private interface FullBackend :
        LlmInferenceService.StatusReportingBackend,
        LlmInferenceService.ActiveModelReportingBackend

    /** A source that reports both status and groups, as the MCP plugin's does. */
    private interface FullSource :
        ToolSourceRegistry.StatusReportingToolSource,
        ToolSourceRegistry.GroupedToolSource

    private fun group(
        id: String,
        name: String,
        tools: Int,
        status: CapabilityStatus = CapabilityStatus.AVAILABLE,
        message: String? = null,
    ) = mockk<ToolSourceRegistry.ToolGroup> {
        every { this@mockk.id } returns id
        every { displayName } returns name
        every { toolNames } returns List(tools) { "tool_$it" }
        every { this@mockk.status } returns status
        every { statusMessage } returns message
    }
}
