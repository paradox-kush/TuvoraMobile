package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManagedBridgeTelemetryPolicyTest {
    @Test fun storeBuildReportsStateReasonAndSurface() {
        val policy = ManagedBridgeTelemetryPolicy()
        assertEquals(
            mapOf("state" to "failed", "reason" to "no_supported_addons", "surface" to "streams_empty"),
            policy.nextReport(false, ManagedBridgeNotice.Failed("no_supported_addons")),
        )
        assertEquals(
            mapOf("state" to "preparing", "reason" to "", "surface" to "streams_empty"),
            policy.nextReport(false, ManagedBridgeNotice.Preparing),
        )
        assertEquals("none", policy.nextReport(false, null)?.get("state"))
    }

    @Test fun unreachableAndDisconnectedAreTheirOwnStates() {
        val policy = ManagedBridgeTelemetryPolicy()
        assertEquals("unreachable", policy.nextReport(false, ManagedBridgeNotice.Failed("unreachable"))?.get("state"))
        assertEquals("disconnected", policy.nextReport(false, ManagedBridgeNotice.Failed("disconnected"))?.get("state"))
    }

    @Test fun fullBuildNeverReports() {
        assertNull(ManagedBridgeTelemetryPolicy().nextReport(true, ManagedBridgeNotice.Preparing))
    }

    @Test fun eachStateAndReasonIsReportedOnceAPerSession() {
        val policy = ManagedBridgeTelemetryPolicy()
        val failed = ManagedBridgeNotice.Failed("addon_selection_changed")
        assertEquals("failed", policy.nextReport(false, failed)?.get("state"))
        assertNull(policy.nextReport(false, failed))
        assertEquals("failed", policy.nextReport(false, ManagedBridgeNotice.Failed("no_playback_addons"))?.get("state"))
        assertEquals("preparing", policy.nextReport(false, ManagedBridgeNotice.Preparing)?.get("state"))
        assertNull(policy.nextReport(false, ManagedBridgeNotice.Preparing))
    }

    @Test fun unknownServerCodesCollapseToABoundedVocabulary() {
        val policy = ManagedBridgeTelemetryPolicy()
        assertEquals("other", policy.nextReport(false, ManagedBridgeNotice.Failed("https://host.example/secret user@x.com"))?.get("reason"))
        assertEquals("http_502", policy.nextReport(false, ManagedBridgeNotice.Failed("http_502"))?.get("reason"))
        assertEquals("", policy.nextReport(false, ManagedBridgeNotice.Failed(null))?.get("reason"))
        // A second unknown code is the same (state, reason) pair, so it is not reported again.
        assertNull(policy.nextReport(false, ManagedBridgeNotice.Failed("another-unknown")))
    }
}
