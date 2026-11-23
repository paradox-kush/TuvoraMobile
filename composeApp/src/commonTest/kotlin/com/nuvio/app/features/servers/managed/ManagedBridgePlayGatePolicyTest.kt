package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B246: Play must lead to the sources list (where the explanation and Retry live) whenever Tuvora Connect could be the way in. */
class ManagedBridgePlayGatePolicyTest {
    @Test fun storeBuildWithSyncedAddonsAndAPreparingBridgeMayOpenSources() {
        assertTrue(ManagedBridgePlayGatePolicy.allowsOpeningSources(false, hasSyncedAddons = true, notice = ManagedBridgeNotice.Preparing))
    }

    @Test fun storeBuildWithSyncedAddonsMayOpenSourcesBeforeTheBridgeHasSpoken() {
        assertTrue(ManagedBridgePlayGatePolicy.allowsOpeningSources(false, hasSyncedAddons = true, notice = null))
    }

    @Test fun storeBuildWithAFailedBridgeMayOpenSourcesEvenWithoutSyncedAddons() {
        assertTrue(ManagedBridgePlayGatePolicy.allowsOpeningSources(false, hasSyncedAddons = false, notice = ManagedBridgeNotice.Failed("no_supported_addons")))
    }

    @Test fun storeBuildWithNothingSyncedAndNoNoticeKeepsTheOldGate() {
        assertFalse(ManagedBridgePlayGatePolicy.allowsOpeningSources(false, hasSyncedAddons = false, notice = null))
    }

    @Test fun fullBuildIsUnaffected() {
        assertFalse(ManagedBridgePlayGatePolicy.allowsOpeningSources(true, hasSyncedAddons = true, notice = ManagedBridgeNotice.Preparing))
        assertFalse(ManagedBridgePlayGatePolicy.allowsOpeningSources(true, hasSyncedAddons = false, notice = null))
    }
}

/** B246: no early return in the bridge may leave the user without a connection and without a reason. */
class ManagedBridgeSkipNoticeTest {
    @Test fun everySkipThatLeavesNoConnectionHasANotice() {
        listOf(ManagedBridgeSkip.InvalidStatus, ManagedBridgeSkip.UnexpectedRedirect, ManagedBridgeSkip.InvalidResponse).forEach { skip ->
            val notice = ManagedBridgePolicy.noticeFor(skip)
            assertNotNull(notice, "$skip leaves the user with no connection")
            assertTrue(notice is ManagedBridgeNotice.Failed, "$skip is a failure")
        }
    }

    @Test fun skipsThatAreNotFailuresStaySilentOnPurpose() {
        listOf(
            ManagedBridgeSkip.NativeRoute, ManagedBridgeSkip.SignedOut, ManagedBridgeSkip.Superseded,
            ManagedBridgeSkip.DeviceOptedOut, ManagedBridgeSkip.ConnectionDisabled, ManagedBridgeSkip.UpToDate,
        ).forEach { skip -> assertNull(ManagedBridgePolicy.noticeFor(skip), "$skip is not a problem to explain") }
    }

    @Test fun everySkipIsClassified() {
        // Adding a ManagedBridgeSkip forces a decision here: either it explains itself or it is listed as benign above.
        assertEquals(9, ManagedBridgeSkip.entries.size)
    }

    @Test fun noticeCodesStayInTheTelemetryVocabulary() {
        // Telemetry carries codes only; the new codes must not collapse into "other".
        listOf(ManagedBridgeSkip.InvalidStatus, ManagedBridgeSkip.UnexpectedRedirect, ManagedBridgeSkip.InvalidResponse).forEach { skip ->
            val notice = ManagedBridgePolicy.noticeFor(skip)
            assertEquals(
                (notice as ManagedBridgeNotice.Failed).code,
                ManagedBridgeTelemetryPolicy.reasonOf(notice),
                "$skip code must be a known reason",
            )
        }
    }
}
