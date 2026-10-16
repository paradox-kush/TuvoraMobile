package com.tuvora.tvos.party

import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.PartyError
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyMember
import com.nuvio.app.features.party.PartyMemberStatus
import com.nuvio.app.features.party.PartyPresence
import com.nuvio.app.features.party.PartyPreview
import com.nuvio.app.features.party.normalizePartyCode
import com.nuvio.app.features.player.PlayerLaunch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPartyViewsTest {
    private val episode = PartyMedia(
        type = "series", metaId = "tt9", metaType = "series", season = 1, episode = 2,
        title = "Show", episodeTitle = "Pilot", poster = "https://img/p.jpg",
    )

    private fun party(me: String, members: List<PartyMember>, presence: Map<String, PartyPresence> = emptyMap(), hostOnly: Boolean = false) =
        ActiveParty(
            id = "p", code = "K7M2QX", memberId = me, hostMemberId = "h", members = members, presence = presence,
            media = episode, maxMembers = 4, connected = true, hostOnly = hostOnly,
        )

    @Test
    fun `the keypad is exactly the party code alphabet`() {
        val keys = TvPartyViews.keypadRows().flatten()
        assertEquals(32, keys.size)
        assertTrue(keys.all { normalizePartyCode(it) == it }, "every key survives normalization")
        assertFalse(keys.any { it in setOf("I", "O", "0", "1") })
    }

    @Test
    fun `typing builds a clean six character code`() {
        var code = ""
        "k7m-2qxZ".forEach { code = TvPartyViews.append(code, it.toString()) }
        assertEquals("K7M2QX", code, "lowercase upper-cased, dash dropped, seventh ignored")
        assertTrue(TvPartyViews.canJoin(code))
        assertEquals("K7M2Q", TvPartyViews.backspace(code))
        assertFalse(TvPartyViews.canJoin("K7M2Q"))
        assertEquals(listOf("K", "7", "", "", "", ""), TvPartyViews.boxes("k7"))
        assertEquals("K7M2QX", TvPartyViews.cleanCode("https://tuvora.co/party/K7M2QX"), "a link pasted from the iPhone keyboard")
    }

    @Test
    fun `party links open the join screen on Apple TV`() {
        assertEquals("K7M2QX", TvPartyViews.codeFromLink("tuvora://party/k7m2qx"))
        assertEquals("K7M2QX", TvPartyViews.codeFromLink("https://tuvora.co/party/K7M2QX"))
        assertEquals("K7M2QX", TvPartyViews.codeFromLink("nuvio://party/K7M2QX"))
        assertNull(TvPartyViews.codeFromLink("tuvora://title?type=movie&id=tt1"))
        assertNull(TvPartyViews.codeFromLink("tuvora://party/K7M"))
        assertNull(TvPartyViews.codeFromLink("https://nuvio.tv/party/K7M2QX"))
    }

    @Test
    fun `a member sees the roster with host - self and status - and cannot end or lock`() {
        val members = listOf(PartyMember("h", "Host"), PartyMember("me", "ana"), PartyMember("x", "Xi"))
        val presence = mapOf(
            "h" to PartyPresence("h", PartyMemberStatus.Ready, episode.key),
            "me" to PartyPresence("me", PartyMemberStatus.Buffering, episode.key),
            "x" to PartyPresence("x", PartyMemberStatus.Browsing, "movie|tt1||"),
        )
        val view = TvPartyViews.view(party("me", members, presence, hostOnly = true), busy = false, available = true, canUse = true, inviteLink = "https://tuvora.co/party/K7M2QX")
        assertTrue(view.inParty)
        assertEquals("K7M 2QX", view.code)
        assertEquals("Host", view.hostName)
        assertFalse(view.isHost)
        assertFalse(view.canEnd)
        assertFalse(view.canToggleHostOnly)
        assertTrue(view.controlLocked, "host-only party, not the host")
        assertEquals(listOf(TvPartyMemberState.Watching, TvPartyMemberState.Buffering, TvPartyMemberState.Choosing), view.members.map { it.state })
        assertEquals(listOf(true, false, false), view.members.map { it.isHost })
        assertEquals(listOf(false, true, false), view.members.map { it.isYou })
        assertEquals("A", view.members[1].initial)
        assertEquals("S1 E2 · Pilot", view.mediaDetail)
        assertEquals(3, view.seatsUsed)
        assertEquals(4, view.seatsMax)
    }

    @Test
    fun `a member nobody has heard from is offline`() {
        val view = TvPartyViews.view(party("me", listOf(PartyMember("h", "Host"), PartyMember("me", "Me"))), false, true, true, "")
        assertEquals(TvPartyMemberState.Offline, view.members[0].state)
        assertEquals(TvPartyMemberState.Choosing, view.members[1].state, "self is always present (browsing)")
    }

    @Test
    fun `the host may end only with someone to end it for`() {
        val alone = TvPartyViews.view(party("h", listOf(PartyMember("h", "Host"))), false, true, true, "")
        assertTrue(alone.isHost)
        assertTrue(alone.canToggleHostOnly)
        assertFalse(alone.canEnd)
        val withGuest = TvPartyViews.view(party("h", listOf(PartyMember("h", "Host"), PartyMember("g", "Guest"))), false, true, true, "")
        assertTrue(withGuest.canEnd)
        assertFalse(withGuest.controlLocked)
    }

    @Test
    fun `no party - an empty join view`() {
        val view = TvPartyViews.view(null, busy = false, available = true, canUse = false, inviteLink = "x")
        assertFalse(view.inParty)
        assertEquals("", view.inviteLink)
        assertFalse(view.canUse)
    }

    @Test
    fun `a usable preview shows its card - a full one also says so`() {
        val ok = TvPartyViews.lookup(PartyPreview(media = episode, host = PartyMember("h", "Host"), members = 2, maxMembers = 4))
        assertEquals("Show", ok.card?.title)
        assertEquals("Host", ok.card?.hostName)
        assertNull(ok.error)
        val full = TvPartyViews.lookup(PartyPreview(media = episode, members = 4, maxMembers = 4))
        assertEquals(PartyError.Full, full.error)
        assertTrue(full.card?.full == true)
    }

    @Test
    fun `a refused preview says why - a network blip stays quiet`() {
        assertEquals(TvPartyLookup(null, PartyError.NotFound), TvPartyViews.lookup(PartyPreview(error = "party_not_found")))
        assertEquals(TvPartyLookup(null, PartyError.Ended), TvPartyViews.lookup(PartyPreview(error = "party_ended")))
        assertEquals(TvPartyLookup(null, null), TvPartyViews.lookup(PartyPreview(error = "network")))
    }

    private fun launch(streamType: String? = null, metaId: String = "tt9") = PlayerLaunch(
        profileId = 1, title = "Show", sourceUrl = "https://cdn/x.mkv", streamTitle = "1080p", providerName = "Addon",
        parentMetaId = metaId, parentMetaType = "series", seasonNumber = 1, episodeNumber = 2, episodeTitle = "Pilot",
        videoId = "tt9:1:2", streamType = streamType,
    )

    @Test
    fun `a title played on Apple TV becomes the party's media with the phone's key`() {
        val media = TvPartyViews.mediaOf(launch(), description = "x".repeat(900))
        assertEquals("series|tt9|1|2", media.key)
        assertEquals(TvPartyViews.keyOf(launch()), media.key)
        assertEquals("tt9:1:2", media.videoId)
        assertEquals(600, media.description?.length)
        assertEquals("1080p", media.source?.name)
    }

    @Test
    fun `live TV cannot be watched together`() {
        assertTrue(TvPartyViews.canHost(launch(), isLive = false))
        assertFalse(TvPartyViews.canHost(launch(streamType = "live"), isLive = true))
        assertFalse(TvPartyViews.canHost(launch(metaId = ""), isLive = false))
    }
}

class TvPartyLineTest {
    private val media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie", title = "Film")

    private fun party(connected: Boolean = true, presence: Map<String, PartyPresence> = emptyMap()) = ActiveParty(
        id = "p", code = "K7M2QX", memberId = "me", hostMemberId = "h",
        members = listOf(PartyMember("h", "Host"), PartyMember("me", "Me"), PartyMember("a", "Ana")),
        presence = presence, media = media, maxMembers = 4, connected = connected,
    )

    private val waiting = com.nuvio.app.features.party.PartyPlayback(seq = 2, memberId = "h", paused = true, positionMs = 0, at = 0, mediaKey = media.key, waiting = true)

    @Test
    fun `reconnecting wins over everything`() {
        assertEquals(TvPartyLineKind.Reconnecting, TvPartyViews.lineOf(party(connected = false), waiting, true, 5_000, false).kind)
    }

    @Test
    fun `a waiting party names who it waits for - never yourself`() {
        val presence = mapOf(
            "a" to PartyPresence("a", PartyMemberStatus.Buffering, media.key),
            "me" to PartyPresence("me", PartyMemberStatus.Loading, media.key),
        )
        val line = TvPartyViews.lineOf(party(presence = presence), waiting, false, null, true)
        assertEquals(TvPartyLine(TvPartyLineKind.WaitingFor, names = listOf("Ana")), line)
        assertEquals(TvPartyLineKind.Waiting, TvPartyViews.lineOf(party(), waiting, false, null, true).kind)
    }

    @Test
    fun `behind - paused for you - or how far behind`() {
        assertEquals(TvPartyLineKind.PausedForYou, TvPartyViews.lineOf(party(), null, true, 4_000, wantsToPlay = false).kind)
        assertEquals(TvPartyLine(TvPartyLineKind.Behind, behindMs = 4_000), TvPartyViews.lineOf(party(), null, true, 4_000, wantsToPlay = true))
        assertEquals(TvPartyLineKind.None, TvPartyViews.lineOf(party(), null, false, null, true).kind)
        assertEquals(TvPartyLineKind.None, TvPartyViews.lineOf(null, null, true, 4_000, true).kind)
    }
}
