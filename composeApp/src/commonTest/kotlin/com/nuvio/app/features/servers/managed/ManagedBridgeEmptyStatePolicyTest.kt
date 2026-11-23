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
        assertEquals(ManagedBridgeNotice.Preparing, ManagedBridgeEmptyStatePolicy.explanation(false, ManagedBridgeNotice.Preparing, null))
        val failed = ManagedBridgeNotice.Failed("no_supported_addons")
        assertEquals(failed, ManagedBridgeEmptyStatePolicy.explanation(false, failed, StreamsEmptyStateReason.NoStreamsFound))
    }

    /** B246: a Connect user whose add-ons cannot serve this title gets NoCompatibleAddons, not NoStreamsFound. */
    @Test fun noCompatibleAddonsIsExplainedToo() {
        assertNotNull(
            ManagedBridgeEmptyStatePolicy.explanation(false, ManagedBridgeNotice.Failed("no_supported_addons"), StreamsEmptyStateReason.NoCompatibleAddons),
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
            assertNull(ManagedBridgeEmptyStatePolicy.explanation(false, failed, reason), "$reason has its own honest text")
        }
        listOf(StreamsEmptyStateReason.NoAddonsInstalled, StreamsEmptyStateReason.NoStreamsFound, null).forEach { reason ->
            assertNotNull(ManagedBridgeEmptyStatePolicy.explanation(false, failed, reason), "$reason is explained")
        }
    }

    @Test fun fullBuildAndHealthyBridgeKeepTheGenericEmptyState() {
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(true, ManagedBridgeNotice.Preparing, StreamsEmptyStateReason.NoCompatibleAddons))
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(false, null, StreamsEmptyStateReason.NoCompatibleAddons))
    }

    @Test fun refreshOnEmptyOnlyWhenTheBridgeWasNotReady() {
        assertTrue(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(false, ManagedBridgeNotice.Failed("unreachable")))
        assertFalse(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(false, null))
        assertFalse(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(true, ManagedBridgeNotice.Preparing))
    }
}
