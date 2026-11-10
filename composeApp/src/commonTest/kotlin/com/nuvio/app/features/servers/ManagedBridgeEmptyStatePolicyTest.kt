package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ManagedBridgeEmptyStatePolicyTest {
    @Test fun storeBuildExplainsAFailedOrPreparingBridge() {
        assertEquals(ManagedBridgeNotice.Preparing, ManagedBridgeEmptyStatePolicy.explanation(false, ManagedBridgeNotice.Preparing))
        val failed = ManagedBridgeNotice.Failed("no_supported_addons")
        assertEquals(failed, ManagedBridgeEmptyStatePolicy.explanation(false, failed))
    }
    @Test fun fullBuildAndHealthyBridgeKeepTheGenericEmptyState() {
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(true, ManagedBridgeNotice.Preparing))
        assertNull(ManagedBridgeEmptyStatePolicy.explanation(false, null))
    }
    @Test fun refreshOnEmptyOnlyWhenTheBridgeWasNotReady() {
        assertTrue(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(false, ManagedBridgeNotice.Failed("unreachable")))
        assertFalse(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(false, null))
        assertFalse(ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(true, ManagedBridgeNotice.Preparing))
    }
}
