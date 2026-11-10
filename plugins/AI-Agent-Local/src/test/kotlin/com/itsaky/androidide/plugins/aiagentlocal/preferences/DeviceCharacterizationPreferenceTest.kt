package com.itsaky.androidide.plugins.aiagentlocal.preferences

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.settings.LocalLlmSettingsViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCharacterizationPreferenceTest {

    @get:org.junit.Rule
    val instantTaskExecutorRule = androidx.arch.core.executor.testing.InstantTaskExecutorRule()

    /** A strict store: only the exact call `getBoolean("deviceCharacterization", false)` is answered, so a default of true would fail. */
    private fun strictContext(value: Boolean): Pair<PluginContext, SharedPreferences> {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean("deviceCharacterization", false) } returns value
        val ctx = mockk<PluginContext>()
        every { ctx.getPluginSharedPreferences(any()) } returns prefs
        return ctx to prefs
    }

    @Test
    fun givenTheKey_whenRead_thenItIsTheNamedPreferenceAndTheDefaultIsOff() {
        assertEquals("deviceCharacterization", LocalLlmPreferences.KEY_DEVICE_CHARACTERIZATION)
        val (ctx, _) = strictContext(false)
        assertFalse(LocalLlmPreferences.deviceCharacterization(ctx))
        val (on, _) = strictContext(true)
        assertTrue(LocalLlmPreferences.deviceCharacterization(on))
    }

    @Test
    fun givenTheSettingsViewModel_whenAskedAndSet_thenItReadsDefaultOffAndWritesTheSameKey() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        val ctx = mockk<PluginContext>(relaxed = true)
        every { ctx.getPluginSharedPreferences(any()) } returns prefs
        val vm = LocalLlmSettingsViewModel(getContext = { ctx }, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        assertFalse(vm.isDeviceCharacterizationEnabled())
        verify { prefs.getBoolean("deviceCharacterization", false) } // the default is off
        vm.setDeviceCharacterization(true)
        verify { editor.putBoolean("deviceCharacterization", true) }
    }

    @Test
    fun givenTheMigrationList_whenChecked_thenThePart1SwitchIsNotCopiedFromAnOlderStore() {
        // the switch is new: it has no legacy value, and a copy from another plugin's file must never turn Part 1 on
        val owned = LocalLlmPreferences::class.java.getDeclaredField("OWNED_KEYS").also { it.isAccessible = true }.get(null) as List<*>
        assertFalse(owned.contains(LocalLlmPreferences.KEY_DEVICE_CHARACTERIZATION))
    }
}
