package com.tuvora.tvos.screens

import com.nuvio.app.core.sources.*
import kotlin.test.*

class TvDeviceSourcePresentationTest {
    private val server = DeviceSourceDescriptor("server", "media_server", "private-reference", "Home server", true, 1)
    private fun view(pending: PendingDeviceChoice? = null, enabled: Boolean? = null) = DeviceSourceView(
        disk = DevicePreferenceDisk(sources = mapOf(1 to listOf(server), 2 to listOf(server.copy(sourceId = "other-profile"))),
            pending = listOfNotNull(pending), values = listOf(DevicePreferenceValue(1, "server", enabled, 4))), mode = "ready")

    @Test fun onlyActiveProfilesSourcesArePresentedWithoutReferences() {
        val state = TvDeviceSourcePresentation.state(view(), 1, false)
        assertEquals(listOf("server"), state.sources.map { it.id })
        assertFalse(state.sources.toString().contains("private-reference"))
    }
    @Test fun pendingResetInheritsAccountDefaultInsteadOfOldSavedOff() {
        val pending = PendingDeviceChoice(mutation = "test", profile = 1, source = "server", desired = null, expectedRevision = 4)
        assertTrue(TvDeviceSourcePresentation.state(view(pending, false), 1, false).sources.single().enabled)
    }
    @Test fun conflictedEnableCannotOverrideNewerSavedOff() {
        val pending = PendingDeviceChoice(mutation = "test", profile = 1, source = "server", desired = true, expectedRevision = 3, conflict = true)
        val row = TvDeviceSourcePresentation.state(view(pending, false), 1, false).sources.single()
        assertTrue(row.conflict)
        assertFalse(row.enabled)
    }
    @Test fun accountDisabledSourceRemainsOffDespiteLocalEnable() {
        val view = view(enabled = true).copy(disk = view().disk.copy(sources = mapOf(1 to listOf(server.copy(accountEnabled = false)))))
        assertFalse(TvDeviceSourcePresentation.state(view, 1, false).sources.single().enabled)
    }
}
