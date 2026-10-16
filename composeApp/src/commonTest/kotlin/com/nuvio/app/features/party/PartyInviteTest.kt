package com.nuvio.app.features.party

import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.parseAppDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals

class PartyInviteTest {

    @Test
    fun `the official server invites with a tuvora dot co link`() {
        assertEquals("https://tuvora.co/party/K7M2QX", partyInviteLink("K7M2QX", isCustomServer = false))
    }

    @Test
    fun `a custom server invites with the app scheme`() {
        assertEquals("tuvora://party/K7M2QX", partyInviteLink("K7M2QX", isCustomServer = true))
    }

    @Test
    fun `every invite link the app shares opens the app again`() {
        listOf(false, true).forEach { custom ->
            val link = partyInviteLink("K7M2QX", isCustomServer = custom)
            assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink(link), link)
        }
    }

    @Test
    fun `a profile without a name joins as Tuvora`() {
        assertEquals("Tuvora", partyDisplayName(null))
        assertEquals("Tuvora", partyDisplayName("   "))
        assertEquals("Ana", partyDisplayName("  Ana "))
        assertEquals(40, partyDisplayName("x".repeat(60)).length)
    }
}
