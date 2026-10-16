package com.tuvora.tvos.party

import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.player.PlayerLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_behind
import nuvio.composeapp.generated.resources.party_paused_for_you
import nuvio.composeapp.generated.resources.party_reconnecting
import nuvio.composeapp.generated.resources.party_waiting
import nuvio.composeapp.generated.resources.party_waiting_for

/** What the party needs from the player engine. TvPlayerSession implements it; nothing else does. */
internal interface TvPartyPlayerControls {
    val partyPositionMs: Long
    val partyDurationMs: Long
    val partyIsPlaying: Boolean
    val partyIsLoading: Boolean
    val partyIsEnded: Boolean
    val partySpeed: Float
    val partyStarted: Boolean
    val partyHasError: Boolean
    val partyWantsToPlay: Boolean
    fun partyPlay()
    fun partyPause()
    fun partyAdoptPlaying(playing: Boolean)
    fun partySeek(positionMs: Long)
    fun partySpeed(rate: Float)
}

/**
 * One Apple TV player session's link to the watch party. Runs [TvPartySync] every 250 ms while the player
 * is on screen (attached), and only while a party is active: the loop is bound to the session's own scope,
 * so it can never outlive the player or run twice. All network goes through the shared PartyRepository
 * (realtime broadcast + debounced `watch_party_update`), exactly as on the phone.
 */
class TvPartyPlayerLink internal constructor(
    private val launch: PlayerLaunch,
    private val isLive: Boolean,
    private val controls: TvPartyPlayerControls,
) {
    private val sync = TvPartySync()
    private val key = TvPartyViews.keyOf(launch)
    private val clock = TimeSource.Monotonic.markNow()
    private var job: Job? = null

    /** A party is running and this player takes part in it (live TV never does). */
    val inParty: Boolean get() = !isLive && PartyRepository.state.value.party != null

    /** The party plays this very title. */
    val playsPartyTitle: Boolean get() = !isLive && PartyRepository.state.value.party?.media?.key == key

    internal fun attach(scope: CoroutineScope) {
        if (isLive) return
        sync.reattached()
        autoSwitchEpisode()
        lastPlayedKey = key
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (PartyRepository.state.value.party != null) apply(sync.tick(tick()))
                else if (sync.behind || sync.rateNudge != null) apply(sync.tick(tick()))
                TvParty.selfBehindMs.value = sync.behindMs.takeIf { sync.behind }
                delay(TvPartySync.TICK_MS)
            }
        }
    }

    internal fun detach() {
        job?.cancel()
        job = null
        if (inParty) apply(sync.leaving(tick()))
        TvParty.selfBehindMs.value = null
    }

    /** Remote Play/Pause. False: not in a party, the session handles it alone. */
    internal fun toggle(): Boolean = route { sync.toggle(it) }

    internal fun setPlaying(playing: Boolean): Boolean = route { sync.setPlaying(playing, it) }

    internal fun seek(targetMs: Long): Boolean = route { sync.seek(targetMs, it) }

    internal fun speed(rate: Float): Boolean = route { sync.speed(rate, it) }

    /** This viewer paused or rewound for themselves in a host-only party. */
    val isBehind: Boolean get() = sync.behind

    /**
     * The player's party line ("Reconnecting", "Waiting for Ana", "2:05 behind"…), or null when there is
     * nothing to say. Cheap and local: the player reads it on each state update.
     */
    fun line(): String? {
        val line = TvPartyViews.lineOf(
            party = PartyRepository.state.value.party?.takeIf { !isLive },
            playback = PartyRepository.playback.value,
            behind = sync.behind,
            behindMs = sync.behindMs,
            wantsToPlay = controls.partyWantsToPlay,
        )
        return when (line.kind) {
            TvPartyLineKind.None -> null
            TvPartyLineKind.Reconnecting -> TvParty.text(Res.string.party_reconnecting)
            TvPartyLineKind.WaitingFor -> TvParty.text(Res.string.party_waiting_for, line.names.joinToString())
            TvPartyLineKind.Waiting -> TvParty.text(Res.string.party_waiting)
            TvPartyLineKind.PausedForYou -> TvParty.text(Res.string.party_paused_for_you)
            TvPartyLineKind.Behind -> TvParty.text(Res.string.party_behind, TvParty.clock(line.behindMs ?: 0L))
        }
    }

    /** "Catch up" from the behind line. */
    fun catchUp() {
        sync.catchUp()
    }

    private inline fun route(decide: (TvPartyTick) -> List<TvPartyCommand>): Boolean {
        if (!inParty) return false
        apply(decide(tick()))
        return true
    }

    /**
     * The phone's LaunchedEffect(partyKey): the host who moves on to another episode of the party's title
     * takes the party along. A different title is the host's explicit choice (the player's party panel).
     */
    private fun autoSwitchEpisode() {
        val party = PartyRepository.state.value.party ?: return
        val previous = lastPlayedKey
        if (!party.isHost || previous == null || previous == key || party.media.key != previous) return
        if (party.media.metaId != launch.parentMetaId) return
        PartyRepository.publishMedia(TvPartyViews.mediaOf(launch), positionMs = 0L)
    }

    private fun tick() = TvPartyTick(
        nowMs = clock.elapsedNow().inWholeMilliseconds,
        partyNowMs = PartyRepository.nowMs(),
        party = PartyRepository.state.value.party,
        playback = PartyRepository.playback.value,
        localKey = key,
        positionMs = controls.partyPositionMs,
        durationMs = controls.partyDurationMs,
        isPlaying = controls.partyIsPlaying,
        isLoading = controls.partyIsLoading,
        isEnded = controls.partyIsEnded,
        speed = controls.partySpeed,
        started = controls.partyStarted,
        hasError = controls.partyHasError,
        wantsToPlay = controls.partyWantsToPlay,
    )

    private fun apply(commands: List<TvPartyCommand>) {
        commands.forEach { command ->
            when (command) {
                TvPartyCommand.Play -> controls.partyPlay()
                TvPartyCommand.Pause -> controls.partyPause()
                is TvPartyCommand.AdoptPlaying -> controls.partyAdoptPlaying(command.playing)
                is TvPartyCommand.SeekTo -> controls.partySeek(command.positionMs)
                is TvPartyCommand.SetSpeed -> controls.partySpeed(command.rate)
                is TvPartyCommand.Publish -> PartyRepository.publishPlayback(command.paused, command.positionMs, command.rate)
                is TvPartyCommand.Status -> PartyRepository.setLocalStatus(command.status, command.mediaKey, command.behindMs)
                TvPartyCommand.CaughtUp -> _lineMessages.tryEmit(TvParty.caughtUp())
                TvPartyCommand.HostOnly -> _lineMessages.tryEmit(TvParty.hostOnlyNote())
            }
        }
    }

    companion object {
        /** The last title a player played while in the party (survives the per-episode session). */
        private var lastPlayedKey: String? = null

        private val _lineMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        internal val lineMessages: SharedFlow<String> = _lineMessages
    }
}
