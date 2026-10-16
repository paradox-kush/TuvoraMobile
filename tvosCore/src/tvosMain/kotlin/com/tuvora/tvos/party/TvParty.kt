package com.tuvora.tvos.party

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.features.party.PartyAction
import com.nuvio.app.features.party.PartyError
import com.nuvio.app.features.party.PartyEvent
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.PartyServerTitles
import nuvio.composeapp.generated.resources.party_server_not_connected
import com.nuvio.app.features.party.partyInviteLink
import com.nuvio.app.features.player.PlayerLaunch
import com.tuvora.tvos.player.TvPlayerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_action_paused
import nuvio.composeapp.generated.resources.party_action_resumed
import nuvio.composeapp.generated.resources.party_action_seeked
import nuvio.composeapp.generated.resources.party_action_speed
import nuvio.composeapp.generated.resources.party_caught_up
import nuvio.composeapp.generated.resources.party_control_everyone
import nuvio.composeapp.generated.resources.party_control_host_only
import nuvio.composeapp.generated.resources.party_ended
import nuvio.composeapp.generated.resources.party_error_ended
import nuvio.composeapp.generated.resources.party_error_full
import nuvio.composeapp.generated.resources.party_error_network
import nuvio.composeapp.generated.resources.party_error_not_found
import nuvio.composeapp.generated.resources.party_error_rate_limited
import nuvio.composeapp.generated.resources.party_error_removed
import nuvio.composeapp.generated.resources.party_host_only_note
import nuvio.composeapp.generated.resources.party_media_changed
import nuvio.composeapp.generated.resources.party_member_joined
import nuvio.composeapp.generated.resources.party_member_left
import nuvio.composeapp.generated.resources.party_removed
import nuvio.composeapp.generated.resources.party_sign_in_required
import nuvio.composeapp.generated.resources.party_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.formatResource
import org.jetbrains.compose.resources.localizedText

/** The result of a join for SwiftUI: [message] is set when it failed. */
data class TvPartyJoinResult(val joined: Boolean, val message: String?)

/**
 * Apple TV's door to the shared watch party (features/party: PartyRepository, PartySync, PartyClock).
 * The network, realtime channel, clock sync and party rules are all the phone's shared code; this object
 * only shapes them for SwiftUI ([TvPartyViews]) and turns events into localized one-line messages.
 * Party playback sync in the player is [TvPartyPlayerLink], owned by each TvPlayerSession.
 */
object TvParty {
    private val log = Logger.withTag("TvParty")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The viewer's own "behind the party" gap, published by the player link while it is behind. */
    internal val selfBehindMs = MutableStateFlow<Long?>(null)

    val state: StateFlow<TvPartyView> = combine(
        PartyRepository.state,
        PartyRepository.available,
        AuthRepository.state,
        selfBehindMs,
    ) { party, available, _, behind ->
        build(party.party, party.busy, available, behind)
    }.stateIn(scope, SharingStarted.Eagerly, build(PartyRepository.state.value.party, false, PartyRepository.available.value, null))

    private fun build(party: com.nuvio.app.features.party.ActiveParty?, busy: Boolean, available: Boolean, behind: Long?) =
        TvPartyViews.view(
            party = party,
            busy = busy,
            available = available,
            canUse = PartyRepository.canUse,
            inviteLink = party?.code?.let(::partyInviteLink).orEmpty(),
            selfBehindMs = behind,
            resolution = party?.media?.let(PartyServerTitles::localize) ?: com.nuvio.app.features.party.PartyServerResolution.NotServer,
        )

    /**
     * One-line messages for the toast: members joining and leaving, the party ending, what someone did to
     * playback ("Ana paused"), and the party moving to another title.
     */
    val messages: Flow<String> = merge(
        PartyRepository.events.map { event ->
            when (event) {
                is PartyEvent.MemberJoined -> text(Res.string.party_member_joined, event.name)
                is PartyEvent.MemberLeft -> text(Res.string.party_member_left, event.name)
                PartyEvent.Removed -> text(Res.string.party_removed)
                PartyEvent.Ended -> text(Res.string.party_ended)
                is PartyEvent.MediaChanged -> text(Res.string.party_media_changed, event.media.title)
                is PartyEvent.ControlChanged ->
                    text(if (event.hostOnly) Res.string.party_control_host_only else Res.string.party_control_everyone)
            }
        },
        PartyRepository.notices.map { notice ->
            when (notice.action) {
                PartyAction.Paused -> text(Res.string.party_action_paused, notice.name)
                PartyAction.Resumed -> text(Res.string.party_action_resumed, notice.name)
                PartyAction.Seeked -> text(Res.string.party_action_seeked, notice.name, clock(notice.positionMs))
                PartyAction.Speed -> text(Res.string.party_action_speed, notice.name, "${notice.rate}x")
            }
        },
        TvPartyPlayerLink.lineMessages,
    )

    /** Keypad and boxes (pure, [TvPartyViews]). */
    fun keypadRows(): List<List<String>> = TvPartyViews.keypadRows()
    fun append(code: String, key: String): String = TvPartyViews.append(code, key)
    fun backspace(code: String): String = TvPartyViews.backspace(code)
    fun cleanCode(raw: String): String = TvPartyViews.cleanCode(raw)
    fun boxes(code: String): List<String> = TvPartyViews.boxes(code)
    fun canJoin(code: String): Boolean = TvPartyViews.canJoin(code)
    fun codeFromLink(url: String): String? = TvPartyViews.codeFromLink(url)

    /** The preview behind a typed code (one `watch_party_preview` call). */
    suspend fun lookup(code: String): TvPartyLookup = TvPartyViews.lookup(PartyRepository.preview(code))

    suspend fun join(code: String): TvPartyJoinResult {
        val error = PartyRepository.join(code)
        return TvPartyJoinResult(joined = error == null && PartyRepository.state.value.party != null, message = error?.let(::message))
    }

    /** Starts a party for what [session] plays (the player's "Start watch party"); null on success, else the message. */
    suspend fun host(session: TvPlayerSession): String? {
        if (!session.canWatchTogether) return message(PartyError.Unavailable)
        return PartyRepository.create(TvPartyViews.mediaOf(session.launch))?.let(::message)
    }

    /** The host moves the party to what [session] plays (the phone's "Switch the party to …"). */
    fun switchTo(session: TvPlayerSession) {
        if (!session.canWatchTogether) return
        PartyRepository.publishMedia(TvPartyViews.mediaOf(session.launch), session.state.value.positionMs)
    }

    /** Whether the party plays what [session] plays. */
    fun playsSessionTitle(session: TvPlayerSession): Boolean = session.party.playsPartyTitle

    fun leave() = PartyRepository.leave()
    fun end() = PartyRepository.end()
    fun setHostOnly(enabled: Boolean) = PartyRepository.setHostOnly(enabled)
    fun removeMember(memberId: String) = PartyRepository.removeMember(memberId)

    /** Whether a launch can be watched together (not live TV or catch-up). */
    internal fun canHost(launch: PlayerLaunch, isLive: Boolean): Boolean = TvPartyViews.canHost(launch, isLive)

    fun message(error: PartyError): String = when (error) {
        PartyError.NotFound -> text(Res.string.party_error_not_found)
        PartyError.Full -> text(Res.string.party_error_full)
        PartyError.Removed -> text(Res.string.party_error_removed)
        PartyError.Ended -> text(Res.string.party_error_ended)
        PartyError.RateLimited -> text(Res.string.party_error_rate_limited)
        PartyError.SignInRequired -> text(Res.string.party_sign_in_required)
        PartyError.Unavailable -> text(Res.string.party_unavailable)
        PartyError.Network -> text(Res.string.party_error_network)
    }

    /** "This title is on a media server you're not connected to." */
    fun serverNotConnected(): String = text(Res.string.party_server_not_connected)

    internal fun hostOnlyNote(): String = text(Res.string.party_host_only_note)
    internal fun caughtUp(): String = text(Res.string.party_caught_up)

    internal fun text(resource: StringResource, vararg args: Any): String =
        formatResource(localizedText(resource.key, resource.defaultText), args)

    /** `m:ss` / `h:mm:ss`, for "2:05 behind" and "skipped to 12:30". */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        fun two(n: Long) = n.toString().padStart(2, '0')
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    internal fun launchInScope(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { log.w(it) { "party call failed" } } }
    }
}
