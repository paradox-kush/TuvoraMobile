package com.nuvio.app.core.deeplink

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * iOS Universal Links (applinks:tuvora.co) arrive through SwiftUI onOpenURL as plain https URLs and go to
 * handleAppUrl, the same entry as every custom-scheme link. Both claimed paths must land as pending app links.
 */
class UniversalLinkRoutingTest {
    @AfterTest
    fun tearDown() {
        AppDeepLinkRepository.pendingDeepLink.value?.let(AppDeepLinkRepository::markConsumed)
    }

    @Test
    fun `a Watch Party universal link becomes the party route`() {
        handleAppUrl("https://tuvora.co/party/K7M2QX")
        assertEquals(AppDeepLink.Party("K7M2QX"), AppDeepLinkRepository.pendingDeepLink.value)
    }

    @Test
    fun `a setup-code universal link becomes the setup route`() {
        handleAppUrl("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN")
        assertEquals(AppDeepLink.SetupCode("TUV-ABCD-EFGH-JKMN"), AppDeepLinkRepository.pendingDeepLink.value)
    }

    @Test
    fun `other tuvora dot co pages stay out of the app`() {
        handleAppUrl("https://tuvora.co/privacy")
        assertNull(AppDeepLinkRepository.pendingDeepLink.value)
    }
}
