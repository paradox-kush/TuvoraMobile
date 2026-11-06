package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.DetailsCounts
import com.nuvio.app.features.iptv.ExpiryDisplay
import com.nuvio.app.features.iptv.ManagedDetailsModel
import com.nuvio.app.features.iptv.ManagedInfo
import com.nuvio.app.features.iptv.ManagedInfoRepository
import com.nuvio.app.features.iptv.ManagedPlaylistActions
import com.nuvio.app.features.iptv.PlaylistAccountInfoStore
import com.nuvio.app.features.iptv.PlaylistAddress
import com.nuvio.app.features.iptv.PlaylistDetailsController
import com.nuvio.app.features.iptv.PlaylistDetailsLive
import com.nuvio.app.features.iptv.ServerFailoverPolicy
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamUiState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.nuvio.app.features.iptv.IptvManualRefreshController
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Apple TV playlist details page draws, as plain values (see [TvPlaylistDetailsPolicy]). */
data class TvDetailsState(
    /** False once the playlist is gone (removed on another device, or just now). */
    val found: Boolean = true,
    val name: String = "",
    /** The host (never a login) or "Using backup server N". */
    val addressLine: String? = null,
    /** "Managed by X" (and "updated <date>") for a managed playlist, null otherwise: no ribbon. */
    val ribbon: String? = null,
    val managedBy: String? = null,
    val providerName: String? = null,
    /** What the header says about expiry; null says nothing (the panel is still being asked). */
    val expiryLine: String? = null,
    /** The thin bar's fill (0..1); null when there is no count of days to show. */
    val expiryBar: Float? = null,
    val connectionsLine: String? = null,
    val statusLine: String? = null,
    val counts: List<TvDetailsCount> = emptyList(),
    /** The lock note for server and login ("Managed by X. Detach to manage them yourself."), managed only. */
    val lockedNote: String? = null,
    val enabled: Boolean = true,
    val contacts: List<TvSetupContact> = emptyList(),
    val shelves: List<TvDetailShelf> = emptyList(),
)

/**
 * One opening of the playlist details page. Builds [TvDetailsState] from the shared [ManagedDetailsModel]
 * and loads the slow half (the panel's account answer, cached; local catalog counts) once. Nothing runs on
 * a timer; [close] ends it with the page.
 */
class TvPlaylistDetailsSession(private val accountId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = PlaylistDetailsController()
    private val refreshController = IptvManualRefreshController(scope)

    val state: StateFlow<TvDetailsState> = combine(
        XtreamRepository.uiState, ManagedInfoRepository.state, controller.live, XtreamRepository.activeServers,
    ) { ui, managed, live, active ->
        build(ui, managed[ProfileRepository.activeProfileId]?.get(accountId), live, active)
    }.stateIn(
        scope, SharingStarted.Eagerly,
        build(
            XtreamRepository.uiState.value, ManagedInfoRepository.infoFor(ProfileRepository.activeProfileId, accountId),
            controller.live.value, XtreamRepository.activeServers.value,
        ),
    )

    /** UX179: the "Refresh now" card's words (policy-built, so they are unit-tested). */
    val refreshHint: StateFlow<String> = refreshController.state
        .map { TvPlaylistDetailsPolicy.refreshHint(it) }
        .stateIn(scope, SharingStarted.Eagerly, TvPlaylistDetailsPolicy.refreshHint(refreshController.state.value))

    init {
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }?.let {
            controller.load(it)
            refreshController.load(it)
        }
    }

    /** UX179: refresh this playlist's catalog now. The engine holds the cooldown; a press while one runs is ignored. */
    fun refreshNow() {
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }?.let { refreshController.start(it) }
    }

    private fun build(ui: XtreamUiState, info: ManagedInfo?, live: PlaylistDetailsLive, active: Map<String, Int>): TvDetailsState {
        val account = ui.accounts.firstOrNull { it.id == accountId } ?: return TvDetailsState(found = false)
        val model = ManagedDetailsModel.build(
            account = account, info = info, accountInfo = live.info, counts = live.counts,
            nowEpochSec = TraktPlatformClock.nowEpochMs() / 1000,
            addressLine = ServerFailoverPolicy.backupLabel(active[account.id] ?: 0) ?: PlaylistAddress.hostOnly(account.baseUrl),
            allowEdit = false,
            panelCheckFailed = live.hasPanel && !live.loading && !live.inspectionSkipped && live.info == null,
        )
        return TvDetailsState(
            name = model.name,
            addressLine = model.addressLine,
            ribbon = TvPlaylistDetailsPolicy.ribbon(model.managedBy, PlaylistAddress.isoDate(model.serviceUpdatedAt)),
            managedBy = model.managedBy,
            providerName = model.providerName,
            expiryLine = TvPlaylistDetailsPolicy.expiryLine(model.expiry, loading = live.loading, inspectionSkipped = live.inspectionSkipped),
            expiryBar = TvPlaylistDetailsPolicy.expiryBar(model.expiry),
            connectionsLine = TvPlaylistDetailsPolicy.connectionsLine(model.connections),
            statusLine = TvPlaylistDetailsPolicy.statusLine(model.statusText, live.loading, live.hasPanel, live.inspectionSkipped),
            counts = TvPlaylistDetailsPolicy.countLines(model.counts),
            lockedNote = if (model.lockedServerLogin) "Managed by ${model.providerName ?: "your provider"}. Detach to manage them yourself." else null,
            enabled = model.enabled,
            contacts = model.contacts.map(TvSetupStateBuilder::contact),
            shelves = TvPlaylistDetailsPolicy.shelves(model, account),
        )
    }

    /** Re-match: stale "not on this provider" verdicts are reset (the phone's Re-match catalog). */
    fun rematch() {
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }?.let { controller.rematch(it) }
    }

    /** Detach from the provider: the playlist stays, the provider stops updating it. True when it worked. */
    suspend fun detach(): Boolean = ManagedPlaylistActions.shared.detach(accountId)

    fun close() = scope.cancel()
}

/** The settings list's "Managed by X · N days left" line per playlist (Step 2 task: settings rows). */
object TvPlaylistRows {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Playlist id -> the managed line; only managed playlists have an entry. */
    val managedLines: StateFlow<Map<String, String>> = combine(
        XtreamRepository.uiState, ManagedInfoRepository.state, PlaylistAccountInfoStore.shared.known,
    ) { ui, managed, known ->
        val map = managed[ProfileRepository.activeProfileId].orEmpty()
        val nowSec = TraktPlatformClock.nowEpochMs() / 1000
        ui.accounts.mapNotNull { account ->
            val info = map[account.id] ?: return@mapNotNull null
            val expiry: ExpiryDisplay? = known[account.id]?.let { ManagedDetailsModel.expiryOf(it, nowSec) }
            account.id to TvPlaylistDetailsPolicy.managedRowLine(info.providerName, expiry)
        }.toMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Asks each managed playlist's panel once (the store keeps an answer for six hours), as the phone's list does. */
    fun refresh() {
        val map = ManagedInfoRepository.forProfile(ProfileRepository.activeProfileId)
        val managed = XtreamRepository.uiState.value.accounts.filter { it.id in map }
        if (managed.isEmpty()) return
        scope.launch { PlaylistAccountInfoStore.shared.refreshManaged(managed, map.keys) }
    }
}
