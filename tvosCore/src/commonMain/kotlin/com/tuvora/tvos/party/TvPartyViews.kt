package com.tuvora.tvos.party

import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.parseAppDeepLink
import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.PARTY_CODE_LENGTH
import com.nuvio.app.features.party.PartyCode
import com.nuvio.app.features.party.PartyError
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyMember
import com.nuvio.app.features.party.PartyMemberStatus
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartySync
import com.nuvio.app.features.party.PartyPreview
import com.nuvio.app.features.party.PartySource
import com.nuvio.app.features.party.formatPartyCode
import com.nuvio.app.features.party.isValidPartyCode
import com.nuvio.app.features.party.normalizePartyCode
import com.nuvio.app.features.party.partyErrorOf
import com.nuvio.app.features.party.partyMediaKey
import com.nuvio.app.features.player.PlayerLaunch

/** The player's one-line party status (the phone's PartyStatusPill message), as data. */
enum class TvPartyLineKind { None, Reconnecting, WaitingFor, Waiting, PausedForYou, Behind }

data class TvPartyLine(val kind: TvPartyLineKind, val names: List<String> = emptyList(), val behindMs: Long? = null)

/** A member's line on the Apple TV party screen (the phone's PartyUi.statusRes, as data). */
enum class TvPartyMemberState { Watching, Buffering, Loading, Choosing, Elsewhere, Offline }

data class TvPartyMemberRow(
    val id: String,
    val name: String,
    val initial: String,
    val avatarColor: String?,
    val isHost: Boolean,
    val isYou: Boolean,
    val state: TvPartyMemberState,
    /** Only for a member watching behind the party (host-only mode). */
    val behindMs: Long?,
)

/** What the party screen and the player's party line show. Built by [TvPartyViews.view]; SwiftUI only draws it. */
data class TvPartyView(
    val available: Boolean,
    /** Signed in with a full (non-anonymous) account: the only kind that can join. */
    val canUse: Boolean,
    val busy: Boolean,
    val inParty: Boolean,
    /** `ABC DEF`. */
    val code: String,
    val inviteLink: String,
    val isHost: Boolean,
    val hostName: String?,
    val hostOnly: Boolean,
    /** The host may switch "Only I can control playback" (hosts only). */
    val canToggleHostOnly: Boolean,
    /** "End for everyone" (the host, with someone to end it for); everyone else only leaves. */
    val canEnd: Boolean,
    /** A non-host in a host-only party: play, pause and seek act for this viewer only. */
    val controlLocked: Boolean,
    val connected: Boolean,
    val seatsUsed: Int,
    val seatsMax: Int,
    val members: List<TvPartyMemberRow>,
    val mediaTitle: String?,
    /** `S1 E2 · Episode title` for an episode, else null. */
    val mediaDetail: String?,
    val mediaPoster: String?,
    val mediaType: String?,
    val mediaId: String?,
    val mediaVideoId: String?,
    val mediaKey: String?,
)

/** The join screen's preview card: shown only for a usable answer (the phone's rule). */
data class TvPartyPreviewCard(
    val title: String,
    val detail: String?,
    val poster: String?,
    val hostName: String?,
    val members: Int,
    val maxMembers: Int,
    val full: Boolean,
)

/** What a party lookup or join came back with, for the screen: [card] and/or [error]. */
data class TvPartyLookup(val card: TvPartyPreviewCard?, val error: PartyError?)

object TvPartyViews {
    const val CODE_LENGTH = PARTY_CODE_LENGTH

    /** The keypad: the 32 code characters (no I, O, 0 or 1), eight to a row. */
    fun keypadRows(): List<List<String>> = CODE_ALPHABET.map { it.toString() }.chunked(8)

    /** Typed or pasted text (an invite link gives its code) as code characters, at most six ([PartyCode.fromInput]). */
    fun cleanCode(raw: String): String = PartyCode.fromInput(raw)

    fun append(code: String, key: String): String = cleanCode(code + key)

    fun backspace(code: String): String = cleanCode(code).dropLast(1)

    /** Six slots for the boxes; an empty string is an empty box. */
    fun boxes(code: String): List<String> {
        val clean = cleanCode(code)
        return List(PARTY_CODE_LENGTH) { i -> clean.getOrNull(i)?.toString() ?: "" }
    }

    fun canJoin(code: String): Boolean = isValidPartyCode(code)

    /**
     * A party code from a link that opens the app: `tuvora://party/<code>` (Apple TV's own scheme), or any link
     * the phone accepts (`https://tuvora.co/party/<code>`, `nuvio://party/<code>`). Null for anything else.
     */
    fun codeFromLink(url: String): String? {
        val trimmed = url.trim()
        val prefix = "tuvora://party"
        if (trimmed.startsWith(prefix, ignoreCase = true)) {
            return parseAppDeepLink("nuvio://party" + trimmed.substring(prefix.length)).partyCode()
        }
        return parseAppDeepLink(trimmed).partyCode()
    }

    private fun AppDeepLink?.partyCode(): String? = (this as? AppDeepLink.Party)?.code

    /** The phone's join-screen lookup rule (PartyScreen.lookUp), as data. */
    fun lookup(preview: PartyPreview): TvPartyLookup {
        val failure = partyErrorOf(preview.error)
        val media = preview.media
        return when {
            failure == null && media != null -> TvPartyLookup(
                card = TvPartyPreviewCard(
                    title = media.title,
                    detail = detail(media),
                    poster = media.poster ?: media.background,
                    hostName = preview.host?.displayName,
                    members = preview.members,
                    maxMembers = preview.maxMembers,
                    full = preview.isFull,
                ),
                error = if (preview.isFull) PartyError.Full else null,
            )
            // A network failure on the preview says nothing about the code: stay quiet, Join still works.
            failure != null && failure != PartyError.Network -> TvPartyLookup(card = null, error = failure)
            else -> TvPartyLookup(card = null, error = null)
        }
    }

    fun view(
        party: ActiveParty?,
        busy: Boolean,
        available: Boolean,
        canUse: Boolean,
        inviteLink: String,
        selfBehindMs: Long? = null,
    ): TvPartyView {
        val media = party?.media
        val members = party?.let { p -> p.members.map { row(p, it, selfBehindMs) } }.orEmpty()
        return TvPartyView(
            available = available,
            canUse = canUse,
            busy = busy,
            inParty = party != null,
            code = party?.code?.let(::formatPartyCode).orEmpty(),
            inviteLink = if (party != null) inviteLink else "",
            isHost = party?.isHost == true,
            hostName = party?.member(party.hostMemberId)?.displayName,
            hostOnly = party?.hostOnly == true,
            canToggleHostOnly = party?.isHost == true,
            canEnd = party?.let { it.isHost && it.members.size > 1 } == true,
            controlLocked = party?.canControl == false,
            connected = party?.connected == true,
            seatsUsed = party?.members?.size ?: 0,
            seatsMax = party?.maxMembers ?: 0,
            members = members,
            mediaTitle = media?.title?.takeIf { it.isNotBlank() },
            mediaDetail = media?.let(::detail),
            mediaPoster = media?.let { it.poster ?: it.background },
            mediaType = media?.metaType,
            mediaId = media?.metaId,
            mediaVideoId = media?.videoId,
            mediaKey = media?.key,
        )
    }

    /** The party's view of a title being played on Apple TV, for starting or switching a party from the player. */
    fun mediaOf(launch: PlayerLaunch, description: String? = launch.pauseDescription): PartyMedia = PartyMedia(
        type = launch.contentType ?: launch.parentMetaType,
        metaId = launch.parentMetaId,
        metaType = launch.parentMetaType,
        videoId = launch.videoId,
        season = launch.seasonNumber,
        episode = launch.episodeNumber,
        title = launch.title,
        episodeTitle = launch.episodeTitle,
        description = description?.take(DESCRIPTION_LIMIT),
        poster = launch.poster,
        background = launch.background,
        logo = launch.logo,
        source = PartySource(
            infoHash = launch.torrentInfoHash,
            fileIdx = launch.torrentFileIdx,
            filename = launch.sourceFilename ?: launch.torrentFilename,
            bingeGroup = launch.bingeGroup,
            addonId = launch.providerAddonId,
            name = launch.streamTitle,
        ),
    )

    /** The party key of what a launch plays (`partyMediaKey`). */
    fun keyOf(launch: PlayerLaunch): String =
        partyMediaKey(launch.parentMetaType, launch.parentMetaId, launch.seasonNumber, launch.episodeNumber)

    /** Live TV and catch-up have no shared title to watch together. */
    fun canHost(launch: PlayerLaunch, isLive: Boolean): Boolean =
        !isLive && launch.liveReplay == null && launch.parentMetaId.isNotBlank() && launch.parentMetaType.isNotBlank()

    /** The phone's PartyStatusPill message rule (PlayerScreenRuntimeParty.RenderParty), first match wins. */
    fun lineOf(party: ActiveParty?, playback: PartyPlayback?, behind: Boolean, behindMs: Long?, wantsToPlay: Boolean): TvPartyLine {
        if (party == null) return TvPartyLine(TvPartyLineKind.None)
        if (!party.connected) return TvPartyLine(TvPartyLineKind.Reconnecting)
        val waitingNames = if (playback?.paused == true && playback.waiting) {
            PartySync.waitingMemberIds(party, emptySet()).filter { it != party.memberId }.mapNotNull { party.member(it)?.displayName }
        } else {
            emptyList()
        }
        return when {
            waitingNames.isNotEmpty() -> TvPartyLine(TvPartyLineKind.WaitingFor, names = waitingNames)
            playback?.waiting == true -> TvPartyLine(TvPartyLineKind.Waiting)
            behind && !wantsToPlay -> TvPartyLine(TvPartyLineKind.PausedForYou)
            behind && behindMs != null -> TvPartyLine(TvPartyLineKind.Behind, behindMs = behindMs)
            else -> TvPartyLine(TvPartyLineKind.None)
        }
    }

    private fun row(party: ActiveParty, member: PartyMember, selfBehindMs: Long?): TvPartyMemberRow {
        val isYou = member.id == party.memberId
        val presence = party.presence[member.id] ?: if (isYou) com.nuvio.app.features.party.PartyPresence(memberId = member.id) else null
        val state = when {
            presence == null -> TvPartyMemberState.Offline
            presence.mediaKey != party.media.key ->
                if (presence.status == PartyMemberStatus.Browsing) TvPartyMemberState.Choosing else TvPartyMemberState.Elsewhere
            else -> when (presence.status) {
                PartyMemberStatus.Ready, PartyMemberStatus.Behind -> TvPartyMemberState.Watching
                PartyMemberStatus.Buffering -> TvPartyMemberState.Buffering
                PartyMemberStatus.Loading -> TvPartyMemberState.Loading
                PartyMemberStatus.Browsing -> TvPartyMemberState.Choosing
            }
        }
        val behind = if (isYou) {
            selfBehindMs
        } else {
            presence?.takeIf { it.status == PartyMemberStatus.Behind && it.mediaKey == party.media.key }?.let { it.behindMs ?: 0L }
        }
        return TvPartyMemberRow(
            id = member.id,
            name = member.displayName,
            initial = member.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            avatarColor = member.avatarColor,
            isHost = member.id == party.hostMemberId,
            isYou = isYou,
            state = state,
            behindMs = behind,
        )
    }

    private fun detail(media: PartyMedia): String? {
        val season = media.season ?: return null
        val episode = media.episode ?: return null
        val label = "S$season E$episode"
        return media.episodeTitle?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
    }

    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val DESCRIPTION_LIMIT = 600
}
