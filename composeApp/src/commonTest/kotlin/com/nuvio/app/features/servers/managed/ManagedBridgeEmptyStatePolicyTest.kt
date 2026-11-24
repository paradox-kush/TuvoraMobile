package com.nuvio.app.features.servers

import com.nuvio.app.features.streams.StreamsEmptyStateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedBridgeEmptyStatePolicyTest {
    @Test fun storeBuildExplainsAFailedOrPreparingBridge() {
        assertEquals(ManagedBridgeNotice.Preparing, ManagedBridgeEmptyStatePolicy.explanation(false, ManagedBridgeNotice.Preparing, null, false, false))
        val failed = ManagedBridgeNotice.Failed("no_supported_addons")
        assertEquals(failed, ManagedBridgeEmptyStatePolicy.explanation(false, failed, StreamsEmptyStateReason.NoStreamsFound, false, false))
    }

    /** B246: a Connect user whose add-ons cannot serve this title gets NoCompatibleAddons, not NoStreamsFound. */
    @Test fun noCompatibleAddonsIsExplainedToo() {
        assertNotNull(
            ManagedBridgeEmptyStatePolicy.explanation(false, ManagedBridgeNotice.Failed("no_supported_addons"), StreamsEmptyStateReason.NoCompatibleAddons, false, false),
            "the Connect explanation must show for NoCompatibleAddons",
        )
    }

    @Test fun reasonsThatAreNotAboutMissingSourcesKeepTheirOwnText() {
        val failed = ManagedBridgeNotice.Failed("unreachable")
        listOf(
            StreamsEmptyStateReason.SourcesStillLoading,
            StreamsEmptyStateReason.StreamFetchFailed,
            StreamsEmptyStateReason.ProviderSourceUnavailable,
        ).forEach { reason ->
            assertNull(ManagedBridgeEmptyStatePolicy.explanation(false, failed, reason, false, false), "$reason has its own honest text")
        }
        listOf(StreamsEmptyStateReason.NoAddonsInstalled, StreamsEmptyStateReason.NoStreamsFound, null).forEach { reason ->
            assertNotNull(ManagedBridgeEmptyStatePolicy.explanation(false, failed, reason, false, false), "$reason is explained")
        }
    }

    @Test fun fullBuildAndHealthyBridgeKeepTheGenericEmptyState() {
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(true, ManagedBridgeNotice.Preparing, StreamsEmptyStateReason.NoCompatibleAddons, true, false))
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(false, null, StreamsEmptyStateReason.NoCompatibleAddons, false, true))
    }

    /** B246 follow-up: the play gate lets a store user with synced add-ons in, so "never set up Connect" needs words. */
    @Test fun syncedAddonsWithoutAConnectionAreToldConnectIsNotSetUp() {
        assertEquals(
            ManagedBridgeNotice.NotConnected,
            ManagedBridgeEmptyStatePolicy.explanation(false, null, StreamsEmptyStateReason.NoCompatibleAddons, hasSyncedAddons = true, hasManagedConnection = false),
        )
    }

    @Test fun aReadyConnectionOrNoAddonsKeepsTheGenericText() {
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(false, null, StreamsEmptyStateReason.NoCompatibleAddons, hasSyncedAddons = true, hasManagedConnection = true))
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(false, null, StreamsEmptyStateReason.NoCompatibleAddons, hasSyncedAddons = false, hasManagedConnection = false))
    }

    @Test fun fullBuildNeverSaysNotConnected() {
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(true, null, StreamsEmptyStateReason.NoCompatibleAddons, hasSyncedAddons = true, hasManagedConnection = false))
    }

    @Test fun aBridgeNoticeWinsOverNotConnected() {
        assertEquals(
            ManagedBridgeNotice.Preparing,
            ManagedBridgeEmptyStatePolicy.explanation(false, ManagedBridgeNotice.Preparing, StreamsEmptyStateReason.NoStreamsFound, hasSyncedAddons = true, hasManagedConnection = false),
        )
    }

    @Test fun notConnectedHasNothingToRetryOrRefresh() {
        assertFalse(ManagedBridgeEmptyStatePolicy.canRetry(ManagedBridgeNotice.NotConnected))
        assertFalse(ManagedBridgeEmptyStatePolicy.canRetry(null))
        assertTrue(ManagedBridgeEmptyStatePolicy.canRetry(ManagedBridgeNotice.Preparing))
        assertFalse(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(false, ManagedBridgeNotice.NotConnected))
        assertEquals("none", ManagedBridgeTelemetryPolicy.stateOf(ManagedBridgeNotice.NotConnected), "the derived state is never reported")
    }

    @Test fun refreshOnEmptyOnlyWhenTheBridgeWasNotReady() {
        assertTrue(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(false, ManagedBridgeNotice.Failed("unreachable")))
        assertFalse(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(false, null))
        assertFalse(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(true, ManagedBridgeNotice.Preparing))
    }
}
