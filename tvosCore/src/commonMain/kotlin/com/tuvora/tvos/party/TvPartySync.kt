package com.tuvora.tvos.party

import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.OwnSeek
import com.nuvio.app.features.party.PartyCorrection
import com.nuvio.app.features.party.PartyMemberStatus
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartySync
import kotlin.math.abs

/** One look at the Apple TV player and the party, taken by the party tick (every [TvPartySync.TICK_MS]). */
data class TvPartyTick(
    /** Monotonic milliseconds, for this policy's own timers (never compared with [partyNowMs]). */
    val nowMs: Long,
    /** The party clock (server-synced epoch ms, `PartyRepository.nowMs()`). */
    val partyNowMs: Long,
    val party: ActiveParty?,
    val playback: PartyPlayback?,
    /** The title this player is playing, as a party media key (`partyMediaKey`). */
    val localKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val isEnded: Boolean,
    val speed: Float,
    /** The first frame has played (the phone's `initialLoadCompleted`). */
    val started: Boolean,
    val hasError: Boolean,
    /** What the viewer (or the party) asked the player to do; the engine may lag behind it. */
    val wantsToPlay: Boolean,
)

/** What the player must do; [TvPartySync] never touches the engine or the network itself. */
sealed interface TvPartyCommand {
    data object Play : TvPartyCommand
    data object Pause : TvPartyCommand

    /** The engine changed play state by itself (or the viewer did, outside the controls): adopt it, no engine call. */
    data class AdoptPlaying(val playing: Boolean) : TvPartyCommand
    data class SeekTo(val positionMs: Long) : TvPartyCommand
    data class SetSpeed(val rate: Float) : TvPartyCommand

    /** Tell the party (PartyRepository.publishPlayback). */
    data class Publish(val paused: Boolean, val positionMs: Long, val rate: Float) : TvPartyCommand

    /** This member's presence (PartyRepository.setLocalStatus). */
    data class Status(val status: PartyMemberStatus, val mediaKey: String?, val behindMs: Long?) : TvPartyCommand

    /** Back in sync after watching behind the party (host-only mode). */
    data object CaughtUp : TvPartyCommand

    /** "Only the host can control playback". */
    data object HostOnly : TvPartyCommand
}

/**
 * The Apple TV player's half of a watch party: a faithful port of the phone's party loop
 * (PlayerScreenRuntimeParty.kt BindPartyEffects + togglePlayback/seek/speed), which is Compose-hosted and
 * excluded from :tvosCore. The decisions themselves are the shared [PartySync]; this class only keeps the
 * loop's memory (last applied playback, seek lead, nudge, "behind" mode) and turns each tick or remote
 * press into [TvPartyCommand]s. Pure: time comes in on [TvPartyTick], so it tests without a player.
 *
 * One instance per player session; it is driven from that session's own coroutine, never concurrently.
 */
class TvPartySync {
    private var bufferingSince: Long? = null
    private var mismatchSince: Long? = null
    private var lastSeek: Long? = null
    private var lastCorrection: Long? = null
    private var lastSeq: Pair<Long, String?>? = null
    private var lastStatus: Triple<PartyMemberStatus, String, Long?>? = null
    private var publishedInitial = false
    private var seekLeadMs = 0L
    private var measureSeekLead = false
    private var wasBehind = false

    /** Host-only party, and this viewer paused or rewound for themselves (the phone's `partyBehind`). */
    var behind = false
        private set

    /** How far behind the party, for the "x behind" line; null while in sync. */
    var behindMs: Long? = null
        private set

    /** The speed currently nudged to close a small drift; null when playing at the party's rate. */
    var rateNudge: Float? = null
        private set

    /** The player came back on screen: re-apply the party's playback from scratch on the next tick. */
    fun reattached() {
        lastSeq = null
        mismatchSince = null
    }

    /** The viewer asked to catch up from the "behind" line. */
    fun catchUp() {
        behind = false
    }

    fun tick(t: TvPartyTick): List<TvPartyCommand> {
        val out = mutableListOf<TvPartyCommand>()
        val party = t.party
        if (party == null) {
            clearNudge(t.playback, out)
            behind = false
            behindMs = null
            return out
        }
        var shouldPlay = t.wantsToPlay
        val synced = party.media.key == t.localKey
        val behindGap = t.playback
            ?.takeIf { behind && synced }
            ?.let { PartySync.expectedPositionMs(it, t.partyNowMs, t.durationMs) - t.positionMs }
        val status = when {
            t.hasError -> PartyMemberStatus.Browsing
            behindGap != null -> PartyMemberStatus.Behind
            !t.started -> PartyMemberStatus.Loading
            t.isLoading && !t.isEnded -> {
                val since = bufferingSince ?: t.nowMs.also { bufferingSince = it }
                if (t.nowMs - since > BUFFERING_REPORT_MS) PartyMemberStatus.Buffering else PartyMemberStatus.Ready
            }
            else -> {
                bufferingSince = null
                PartyMemberStatus.Ready
            }
        }
        val reported = Triple(
            status,
            t.localKey,
            behindGap?.takeIf { status == PartyMemberStatus.Behind }?.let(PartySync::reportedGapMs),
        )
        if (reported != lastStatus) {
            lastStatus = reported
            out += TvPartyCommand.Status(status, t.localKey, reported.third)
        }

        if (!synced || t.hasError) {
            clearNudge(t.playback, out)
            return out
        }
        val playback = t.playback
        if (playback == null) {
            if (t.started && !publishedInitial && party.isHost) {
                publishedInitial = true
                out += TvPartyCommand.Publish(paused = !shouldPlay, positionMs = t.positionMs, rate = t.speed)
            }
            return out
        }
        publishedInitial = true
        if (!t.started) return out
        if (behind && party.canControl) behind = false
        if (behind) {
            clearNudge(playback, out)
            mismatchSince = null
            val gap = PartySync.expectedPositionMs(playback, t.partyNowMs, t.durationMs) - t.positionMs
            if (PartySync.shouldRejoin(gap, playing = shouldPlay && !t.isLoading)) {
                behind = false
            } else {
                val shown = behindMs
                if (shown == null || abs(gap - shown) >= BEHIND_DISPLAY_STEP_MS) behindMs = gap.coerceAtLeast(0L)
                wasBehind = true
                return out
            }
        }
        if (wasBehind) {
            wasBehind = false
            lastSeq = null
            behindMs = null
            if (!party.canControl) out += TvPartyCommand.CaughtUp
        }
        val expected = PartySync.expectedPositionMs(playback, t.partyNowMs, t.durationMs)
        val identity = playback.seq to playback.memberId
        if (identity != lastSeq) {
            val forced = lastSeq == null
            lastSeq = identity
            if (forced || shouldPlay == playback.paused) {
                shouldPlay = !playback.paused
                out += if (playback.paused) TvPartyCommand.Pause else TvPartyCommand.Play
            }
            val own = playback.memberId == party.memberId && !forced
            if ((!own || playback.paused) && abs(t.positionMs - expected) > PartySync.PAUSED_TOLERANCE_MS) {
                out += TvPartyCommand.SeekTo(clamp(if (playback.paused) expected else expected + seekLeadMs, t.durationMs))
                measureSeekLead = !playback.paused
            }
            lastSeek = t.nowMs
            if (rateNudge == null && abs(t.speed - playback.rate) > 0.01f) out += TvPartyCommand.SetSpeed(playback.rate)
            mismatchSince = null
            return out
        }

        if (t.isLoading || t.isEnded) {
            mismatchSince = null
            return out
        }

        if (t.isPlaying != shouldPlay) {
            val since = mismatchSince ?: t.nowMs.also { mismatchSince = it }
            if (t.nowMs - since > OUTSIDE_TOGGLE_MS) {
                mismatchSince = null
                when {
                    party.canControl -> {
                        out += TvPartyCommand.AdoptPlaying(t.isPlaying)
                        out += TvPartyCommand.Publish(paused = !t.isPlaying, positionMs = t.positionMs, rate = playback.rate)
                    }
                    t.isPlaying -> out += if (playback.paused) TvPartyCommand.Pause else TvPartyCommand.Play
                    else -> {
                        behind = true
                        out += TvPartyCommand.AdoptPlaying(false)
                    }
                }
            }
            return out
        }
        mismatchSince = null

        val settled = lastSeek?.let { t.nowMs - it > SEEK_SETTLE_MS } ?: true
        val due = lastCorrection?.let { t.nowMs - it >= CORRECTION_INTERVAL_MS } ?: true
        if (!settled || !due) return out
        lastCorrection = t.nowMs
        val drift = t.positionMs - expected
        if (measureSeekLead && !playback.paused) {
            measureSeekLead = false
            if (abs(drift) < MAX_SEEK_LEAD_MS) seekLeadMs = (seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS)
        }
        when (val correction = PartySync.correction(t.positionMs, expected, playback, rateNudge != null)) {
            is PartyCorrection.Seek -> {
                clearNudge(playback, out)
                val target = if (playback.paused) correction.positionMs else correction.positionMs + seekLeadMs
                out += TvPartyCommand.SeekTo(clamp(target, t.durationMs))
                lastSeek = t.nowMs
                measureSeekLead = !playback.paused
            }
            is PartyCorrection.Nudge -> if (rateNudge != correction.rate) {
                rateNudge = correction.rate
                out += TvPartyCommand.SetSpeed(correction.rate)
            }
            PartyCorrection.InSync -> clearNudge(playback, out)
        }
        return out
    }

    /** Play/Pause on the remote (the phone's togglePlayback + toggleOwnPlayback). */
    fun toggle(t: TvPartyTick): List<TvPartyCommand> {
        if (locked(t)) {
            return when {
                t.wantsToPlay -> {
                    behind = true
                    listOf(TvPartyCommand.Pause)
                }
                behind -> listOf(TvPartyCommand.Play)
                else -> listOf(TvPartyCommand.HostOnly)
            }
        }
        val out = mutableListOf<TvPartyCommand>()
        val play = !t.isPlaying
        if (play && t.isEnded) out += TvPartyCommand.SeekTo(0L)
        out += if (play) TvPartyCommand.Play else TvPartyCommand.Pause
        publishIfSynced(t, out, paused = !play, positionMs = if (t.isEnded) 0L else t.positionMs)
        return out
    }

    /** An explicit play or pause (a scrub pauses, its commit plays). */
    fun setPlaying(playing: Boolean, t: TvPartyTick): List<TvPartyCommand> {
        if (locked(t)) {
            if (!playing && t.wantsToPlay) behind = true
            return listOf(if (playing) TvPartyCommand.Play else TvPartyCommand.Pause)
        }
        val out = mutableListOf<TvPartyCommand>(if (playing) TvPartyCommand.Play else TvPartyCommand.Pause)
        publishIfSynced(t, out, paused = !playing, positionMs = t.positionMs)
        return out
    }

    /**
     * A seek on the remote to [targetMs]. In a host-only party a non-host can only fall behind (rewind) or
     * catch up again (the phone's ownSeekTarget); skipping ahead of the party is refused.
     */
    fun seek(targetMs: Long, t: TvPartyTick): List<TvPartyCommand> {
        val target = clamp(targetMs.coerceAtLeast(0L), t.durationMs)
        if (locked(t)) {
            val playback = t.playback ?: return listOf(TvPartyCommand.SeekTo(target))
            val partyPositionMs = PartySync.expectedPositionMs(playback, t.partyNowMs, t.durationMs)
            return when (val own = PartySync.ownSeek(target, partyPositionMs, behind)) {
                is OwnSeek.Behind -> {
                    behind = true
                    listOf(TvPartyCommand.SeekTo(own.positionMs))
                }
                OwnSeek.CatchUp -> {
                    behind = false
                    emptyList()
                }
                OwnSeek.Blocked -> listOf(TvPartyCommand.HostOnly)
            }
        }
        val out = mutableListOf<TvPartyCommand>(TvPartyCommand.SeekTo(target))
        publishIfSynced(t, out, paused = !t.wantsToPlay, positionMs = target)
        return out
    }

    /** A speed picked in the player's Speed dialog. */
    fun speed(rate: Float, t: TvPartyTick): List<TvPartyCommand> {
        if (locked(t)) return listOf(TvPartyCommand.HostOnly)
        rateNudge = null
        val out = mutableListOf<TvPartyCommand>(TvPartyCommand.SetSpeed(rate))
        if (synced(t)) out += TvPartyCommand.Publish(paused = !t.wantsToPlay, positionMs = t.positionMs, rate = rate)
        return out
    }

    /**
     * The viewer leaves the player (the phone's DisposableEffect): if nobody else is watching the party's
     * title, pause it for the party so nobody is left playing alone; then report "browsing".
     */
    fun leaving(t: TvPartyTick): List<TvPartyCommand> {
        val party = t.party ?: return emptyList()
        val out = mutableListOf<TvPartyCommand>()
        val playback = t.playback
        val othersWatching = party.presence.values.any {
            it.memberId != party.memberId && it.mediaKey == party.media.key && it.status != PartyMemberStatus.Browsing
        }
        if (party.media.key == t.localKey && playback != null && !playback.paused && !othersWatching && party.canControl) {
            out += TvPartyCommand.Publish(paused = true, positionMs = t.positionMs, rate = playback.rate)
        }
        out += TvPartyCommand.Status(PartyMemberStatus.Browsing, null, null)
        clearNudge(playback, out)
        return out
    }

    private fun synced(t: TvPartyTick): Boolean = t.party?.media?.key == t.localKey

    private fun locked(t: TvPartyTick): Boolean = t.party?.let { !it.canControl && it.media.key == t.localKey } == true

    private fun publishIfSynced(t: TvPartyTick, out: MutableList<TvPartyCommand>, paused: Boolean, positionMs: Long) {
        if (!synced(t)) return
        out += TvPartyCommand.Publish(paused = paused, positionMs = positionMs, rate = t.playback?.rate ?: t.speed)
    }

    private fun clearNudge(playback: PartyPlayback?, out: MutableList<TvPartyCommand>) {
        if (rateNudge == null) return
        rateNudge = null
        out += TvPartyCommand.SetSpeed(playback?.rate ?: 1f)
    }

    private fun clamp(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)

    companion object {
        /** The phone's PARTY_TICK and timings (PlayerScreenRuntimeParty.kt), unchanged. */
        const val TICK_MS = 250L
        const val BUFFERING_REPORT_MS = 1_000L
        const val OUTSIDE_TOGGLE_MS = 1_200L
        const val SEEK_SETTLE_MS = 2_500L
        const val CORRECTION_INTERVAL_MS = 500L
        const val MAX_SEEK_LEAD_MS = 3_000L
        const val BEHIND_DISPLAY_STEP_MS = 1_000L
    }
}
