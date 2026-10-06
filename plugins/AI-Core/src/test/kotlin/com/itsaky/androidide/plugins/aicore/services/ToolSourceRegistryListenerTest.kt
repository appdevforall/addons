package com.itsaky.androidide.plugins.aicore.services

import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore
import com.itsaky.androidide.plugins.services.CapabilityStatus
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests the registry's consumer listeners (contract 2): told on register, unregister, a tools
 * change and a status change, and only for a provider that is actually registered.
 */
class ToolSourceRegistryListenerTest {

    private companion object {
        const val MCP = "com.itsaky.androidide.plugins.aiagentmcp"
    }

    private lateinit var store: ToolSourceStore
    private lateinit var registry: ToolSourceRegistryImpl
    private val heard = mutableListOf<String>()
    private val listener = ToolSourceRegistry.ToolSourceListener { heard += it }

    @Before
    fun setUp() {
        store = ToolSourceStore()
        registry = ToolSourceRegistryImpl(store)
        registry.addToolSourceListener(listener)
    }

    @Test
    fun givenAListener_whenASourceRegistersAndUnregisters_thenItHearsBoth() {
        val source = source(MCP)

        registry.registerToolSource(source)
        registry.unregisterToolSource(source)

        assertEquals(listOf(MCP, MCP), heard)
    }

    @Test
    fun givenARegisteredSource_whenItsToolsOrStatusChange_thenTheListenerHearsEach() {
        registry.registerToolSource(source(MCP))
        heard.clear()

        registry.notifyToolsChanged(MCP)
        registry.notifyToolSourceStatusChanged(MCP)

        assertEquals(listOf(MCP, MCP), heard)
    }

    @Test
    fun givenAStatusChange_whenItArrives_thenTheAgentsToolSetIsNotRebuilt() {
        registry.registerToolSource(source(MCP))
        var rebuilds = 0
        store.addChangeListener { rebuilds++ }

        registry.notifyToolSourceStatusChanged(MCP)

        assertEquals(0, rebuilds)
    }

    @Test
    fun givenAnUnknownProvider_whenItReportsAChange_thenNothingIsHeard() {
        registry.notifyToolsChanged("com.example.nobody")
        registry.notifyToolSourceStatusChanged("com.example.nobody")

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun givenARemovedListener_whenASourceRegisters_thenItIsNotCalled() {
        registry.removeToolSourceListener(listener)

        registry.registerToolSource(source(MCP))

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun givenAListenerThatThrows_whenASourceRegisters_thenTheOthersStillHear() {
        registry.removeToolSourceListener(listener)
        registry.addToolSourceListener { throw IllegalStateException("boom") }
        registry.addToolSourceListener(listener)

        registry.registerToolSource(source(MCP))

        assertEquals(listOf(MCP), heard)
    }

    @Test
    fun givenARegisteredSource_whenTheRegistryListsIt_thenItsGroupsAndStatusPassThroughUnchanged() {
        val group = mockk<ToolSourceRegistry.ToolGroup>()
        val source = source(MCP).also {
            every { it.toolGroups } returns listOf(group)
            every { it.status } returns CapabilityStatus.DEGRADED
        }

        registry.registerToolSource(source)
        val listed = registry.toolSources.single() as FullSource

        assertEquals(listOf(group), listed.toolGroups)
        assertEquals(CapabilityStatus.DEGRADED, listed.status)
    }

    @Test
    fun givenAListenerThatTellsThemApart_whenTheStatusChanges_thenOnlyItsStatusCallbackRuns() {
        registry.removeToolSourceListener(listener)
        val tools = mutableListOf<String>()
        val status = mutableListOf<String>()
        registry.addToolSourceListener(object : ToolSourceRegistry.ToolSourceListener {
            override fun onToolSourcesChanged(providerId: String) {
                tools += providerId
            }

            override fun onToolSourceStatusChanged(providerId: String) {
                status += providerId
            }
        })
        registry.registerToolSource(source(MCP))
        tools.clear()

        registry.notifyToolSourceStatusChanged(MCP)
        registry.notifyToolsChanged(MCP)

        assertEquals(listOf(MCP), status)
        assertEquals(listOf(MCP), tools)
    }

    @Test
    fun givenAListener_whenTheSameOneIsAddedTwice_thenItHearsEachChangeOnce() {
        registry.addToolSourceListener(listener)

        registry.registerToolSource(source(MCP))

        assertEquals(listOf(MCP), heard)
    }

    /** A source that reports both status and groups, as the MCP plugin's does. */
    private interface FullSource :
        ToolSourceRegistry.StatusReportingToolSource,
        ToolSourceRegistry.GroupedToolSource

    private fun source(id: String) = mockk<FullSource>(relaxed = true) {
        every { providerId } returns id
        every { displayName } returns "MCP servers"
        every { listTools() } returns emptyList()
    }
}
