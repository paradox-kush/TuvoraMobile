package com.nuvio.app

import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.contracts.IptvSearchAccess
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.RecTrackingAccess
import com.nuvio.app.core.memory.MemoryPortImpl
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Apple TV registers its ports through the same list as every other platform (FeatureContributions.kt),
// because FeatureWiring.kt (Compose UI slots) is not compiled for tvOS. The ports below throw when read
// unregistered, which would crash Apple TV at the first IPTV screen.
class LogicFeatureContributionsTest {

    @Test
    fun `the shared logic ports resolve on apple tv`() {
        registerLogicFeatureContributions()
        IptvCatalogAccess.catalog
        IptvSearchAccess.provider
        RecTrackingAccess.reporter
        RecTrackingAccess.settings
        assertSame(MemoryPortImpl, MemoryPortAccess.current())
        // The Calendar's followed-title and account ports come from the same shared list.
        assertTrue(com.nuvio.app.features.calendar.api.CalendarFeature.isRegistered)
    }
}
