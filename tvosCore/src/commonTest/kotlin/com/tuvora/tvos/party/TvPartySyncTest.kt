package com.tuvora.tvos.party

import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyMember
import com.nuvio.app.features.party.PartyMemberStatus
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartyPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPartySyncTest {
    private val media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie", title = "Film")
    private val key = media.key

    private fun party(
        me: String = "me",
        host: String = "host",
        hostOnly: Boolean = false,
        presence: Map<String, PartyPresence> = emptyMap(),
    ) = ActiveParty(
        id = "p", code = "K7M2QX", memberId = me, hostMemberId = host,
        members = listOf(PartyMember("host", "Host"), PartyMember("me", "Me")),
        presence = presence, media = media, maxMembers = 4, connected = true, hostOnly = hostOnly,
    )

    private fun playback(seq: Long = 1, by: String = "host", paused: Boolean = false, positionMs: Long = 10_000, at: Long = 1_000_000) =
        PartyPlayback(seq = seq, memberId = by, paused = paused, positionMs = positionMs, rate = 1f, at = at, mediaKey = key)

    private fun tick(
        nowMs: Long = 10_000,
        partyNowMs: Long = 1_000_000,
        party: ActiveParty? = party(),
        playback: PartyPlayback? = playback(),
        localKey: String = key,
        positionMs: Long = 10_000,
        isPlaying: Boolean = true,
        isLoading: Boolean = false,
        started: Boolean = true,
        wantsToPlay: Boolean = true,
        durationMs: Long = 3_600_000,
    ) = TvPartyTick(
        nowMs = nowMs, partyNowMs = partyNowMs, party = party, playback = playback, localKey = localKey,
        positionMs = positionMs, durationMs = durationMs, isPlaying = isPlaying, isLoading = isLoading, isEnded = false,
        speed = 1f, started = started, hasError = false, wantsToPlay = wantsToPlay,
    )

    @Test
    fun `the first tick applies the party's playback - pause and seek to where the party is`() {
        val sync = TvPartySync()
        val out = sync.tick(tick(playback = playback(paused = true, positionMs = 60_000), positionMs = 5_000))
        assertTrue(TvPartyCommand.Pause in out, "$out")
        assertTrue(TvPartyCommand.SeekTo(60_000) in out, "$out")
        assertTrue(out.any { it is TvPartyCommand.Status && it.status == PartyMemberStatus.Ready && it.mediaKey == key }, "$out")
    }

    @Test
    fun `a playing party is joined at its extrapolated position`() {
        val sync = TvPartySync()
        // 3 s of party clock since the playback was stamped: expected 13 s.
        val out = sync.tick(tick(partyNowMs = 1_003_000, positionMs = 0))
        assertTrue(TvPartyCommand.Play in out, "$out")
        assertTrue(TvPartyCommand.SeekTo(13_000) in out, "$out")
    }

    @Test
    fun `a small drift is nudged with speed - not a seek`() {
        val sync = TvPartySync()
        sync.tick(tick(positionMs = 10_000))                     // applies seq 1
        val out = sync.tick(tick(nowMs = 20_000, positionMs = 10_600))   // 600 ms ahead, settled
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.SetSpeed(0.95f)), out)
        assertEquals(0.95f, sync.rateNudge)
    }

    @Test
    fun `a large drift seeks back into sync`() {
        val sync = TvPartySync()
        sync.tick(tick(positionMs = 10_000))
        val out = sync.tick(tick(nowMs = 20_000, positionMs = 4_000))
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.SeekTo(10_000)), out)
    }

    @Test
    fun `no correction until a seek has settled`() {
        val sync = TvPartySync()
        sync.tick(tick(nowMs = 10_000, positionMs = 10_000))
        assertEquals(emptyList(), sync.tick(tick(nowMs = 11_000, positionMs = 4_000)), "inside SEEK_SETTLE")
    }

    @Test
    fun `the host publishes its playback when the party has none yet`() {
        val sync = TvPartySync()
        val out = sync.tick(tick(party = party(me = "host"), playback = null, positionMs = 42_000))
        assertTrue(TvPartyCommand.Publish(paused = false, positionMs = 42_000, rate = 1f) in out, "$out")
        assertFalse(sync.tick(tick(nowMs = 10_250, party = party(me = "host"), playback = null)).any { it is TvPartyCommand.Publish }, "only once")
    }

    @Test
    fun `a member does not publish an initial playback`() {
        val out = TvPartySync().tick(tick(playback = null))
        assertFalse(out.any { it is TvPartyCommand.Publish }, "$out")
    }

    @Test
    fun `another title in the player only reports where this member is`() {
        val out = TvPartySync().tick(tick(localKey = "series|tt9|1|1"))
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.Status(PartyMemberStatus.Ready, "series|tt9|1|1", null)), out)
    }

    @Test
    fun `buffering is reported only after a second`() {
        val sync = TvPartySync()
        val first = sync.tick(tick(nowMs = 10_000, isLoading = true))
        assertTrue(first.any { it is TvPartyCommand.Status && it.status == PartyMemberStatus.Ready }, "$first")
        val later = sync.tick(tick(nowMs = 11_500, isLoading = true))
        assertTrue(later.any { it is TvPartyCommand.Status && it.status == PartyMemberStatus.Buffering }, "$later")
    }

    @Test
    fun `before the first frame a member is loading`() {
        val out = TvPartySync().tick(tick(started = false))
        assertTrue(out.any { it is TvPartyCommand.Status && it.status == PartyMemberStatus.Loading }, "$out")
    }

    @Test
    fun `play pause in an open party plays locally and tells the party`() {
        val out = TvPartySync().toggle(tick(isPlaying = true, positionMs = 30_000))
        assertEquals(listOf(TvPartyCommand.Pause, TvPartyCommand.Publish(paused = true, positionMs = 30_000, rate = 1f)), out)
    }

    @Test
    fun `in a host-only party a member's pause is for themselves only`() {
        val sync = TvPartySync()
        val t = tick(party = party(hostOnly = true))
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.Pause), sync.toggle(t), "no Publish")
        assertTrue(sync.behind)
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.Play), sync.toggle(t.copy(wantsToPlay = false)), "resume for themselves")
    }

    @Test
    fun `in a host-only party a member cannot skip ahead of the party`() {
        val sync = TvPartySync()
        val out = sync.seek(120_000, tick(party = party(hostOnly = true)))
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.HostOnly), out)
    }

    @Test
    fun `in a host-only party a member may rewind and falls behind`() {
        val sync = TvPartySync()
        val out = sync.seek(2_000, tick(party = party(hostOnly = true)))
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.SeekTo(2_000)), out)
        assertTrue(sync.behind)
        val next = sync.tick(tick(nowMs = 20_000, party = party(hostOnly = true), positionMs = 2_000))
        assertTrue(next.any { it is TvPartyCommand.Status && it.status == PartyMemberStatus.Behind }, "$next")
        assertEquals(8_000L, sync.behindMs)
    }

    @Test
    fun `a member who is behind and plays back up to the party rejoins it`() {
        val sync = TvPartySync()
        sync.seek(2_000, tick(party = party(hostOnly = true)))
        sync.tick(tick(nowMs = 20_000, party = party(hostOnly = true), positionMs = 2_000))
        assertTrue(sync.behind)
        val out = sync.tick(tick(nowMs = 20_250, party = party(hostOnly = true), positionMs = 9_000))
        assertFalse(sync.behind, "within REJOIN_GAP of the party")
        assertTrue(TvPartyCommand.CaughtUp in out, "$out")
    }

    @Test
    fun `a host-only party ignores a member's speed change`() {
        assertEquals(listOf<TvPartyCommand>(TvPartyCommand.HostOnly), TvPartySync().speed(1.5f, tick(party = party(hostOnly = true))))
    }

    @Test
    fun `the host's seek is told to the party`() {
        val out = TvPartySync().seek(90_000, tick(party = party(me = "host", hostOnly = true)))
        assertEquals(listOf(TvPartyCommand.SeekTo(90_000), TvPartyCommand.Publish(paused = false, positionMs = 90_000, rate = 1f)), out)
    }

    @Test
    fun `an engine that stopped by itself is adopted and told to the party when the member may control`() {
        val sync = TvPartySync()
        sync.tick(tick(nowMs = 10_000))
        sync.tick(tick(nowMs = 20_000, isPlaying = false))
        val out = sync.tick(tick(nowMs = 21_500, isPlaying = false, positionMs = 10_000))
        assertTrue(TvPartyCommand.AdoptPlaying(false) in out, "$out")
        assertTrue(out.any { it is TvPartyCommand.Publish && it.paused }, "$out")
    }

    @Test
    fun `leaving the player pauses the party only when nobody else is watching`() {
        val alone = TvPartySync().leaving(tick())
        assertTrue(alone.any { it is TvPartyCommand.Publish && it.paused }, "$alone")
        assertEquals(TvPartyCommand.Status(PartyMemberStatus.Browsing, null, null), alone.last())
        val watched = party(presence = mapOf("host" to PartyPresence("host", PartyMemberStatus.Ready, key)))
        val withOthers = TvPartySync().leaving(tick(party = watched))
        assertFalse(withOthers.any { it is TvPartyCommand.Publish }, "$withOthers")
    }

    @Test
    fun `no party - nothing to do`() {
        assertEquals(emptyList(), TvPartySync().tick(tick(party = null, playback = null)))
        assertEquals(emptyList(), TvPartySync().leaving(tick(party = null)))
    }
}
