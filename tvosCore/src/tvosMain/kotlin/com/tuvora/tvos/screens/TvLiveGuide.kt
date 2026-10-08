package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamLiveRecent
import com.nuvio.app.features.iptv.XtreamLiveRecents
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.livetv.LiveTvData
import kotlinx.coroutines.flow.StateFlow
import com.nuvio.app.features.library.toLibraryItem

/**
 * The live guide's data, per playlist: every channel (with category, pin and catch-up flags and the
 * personalization overlay applied, via LiveTvData.guideChannels) and each row's schedule, fetched only
 * for rows on screen — one request per composed row, as on the other clients.
 */
object TvLiveGuide {
    suspend fun channels(accountId: String): List<LiveGuideChannel> =
        // guideChannels is keyed by a channel id only to find its playlist; any live id of it works.
        LiveTvData.guideChannels(XtreamItemRegistry.liveId(accountId, 0))

    suspend fun programmes(contentId: String): List<XtreamProgram> = LiveTvData.programmes(contentId, limit = 8)

    val recents: StateFlow<List<XtreamLiveRecent>>
        get() {
            XtreamLiveRecents.ensureLoaded()
            return XtreamLiveRecents.recents
        }

    /** NuvioTV's live Favorites are channels saved in the Library (XtreamLiveGuideViewModel.favoriteLiveIds). */
    val libraryChanges get() = com.nuvio.app.features.library.LibraryRepository.uiState

    fun isFavorite(contentId: String): Boolean = com.nuvio.app.features.library.LibraryRepository.isLocalSaved(contentId, "tv")

    suspend fun toggleFavorite(channel: LiveGuideChannel) {
        val preview = com.nuvio.app.features.home.MetaPreview(
            id = channel.contentId, type = "tv", name = channel.name, poster = channel.logo, logo = channel.logo,
            posterShape = com.nuvio.app.features.home.PosterShape.Landscape,
        )
        com.nuvio.app.features.library.LibraryRepository.toggleLocalSaved(
            preview.toLibraryItem(savedAtEpochMs = nowMs()),
        )
    }

    /** Capture the original local membership before either a provider-row or favourites-row toggle. */
    fun captureFavoriteUndo(contentId: String): com.nuvio.app.features.library.LibrarySavedUndo =
        com.nuvio.app.features.library.LibraryRepository.captureLocalSavedUndo(contentId, "tv")

    /** P5: a favourite's order stamp (its place in the favourites rows); 0 when it is not saved. */
    fun favoriteSavedAt(contentId: String): Long =
        com.nuvio.app.features.library.LibraryRepository.localItems.value
            .firstOrNull { it.id == contentId }?.savedAtEpochMs ?: 0L

    /**
     * P5: after Undo saved a removed favourite again ([toggleFavorite]), put it back AT ITS OLD PLACE
     * ([savedAtEpochMs], see TvFavouriteRows.PendingRemoval) instead of at the top, where a fresh save
     * lands. Call on the main thread, like [moveFavorite]: run from a background coroutine, the library
     * write (TieredUserDefaults posts its change notification to the main queue and waits) deadlocked
     * against the main thread reading the library.
     */
    fun restoreFavoriteOrder(contentId: String, savedAtEpochMs: Long) {
        if (savedAtEpochMs > 0) {
            com.nuvio.app.features.library.LibraryRepository.setSavedAt(mapOf(contentId to savedAtEpochMs))
        }
    }

    /**
     * F03: the live favourites, in the synced favourites order — of one playlist ([accountId]), or of
     * every playlist on this profile (null: the All favorites row). Built from the library entries, so a
     * favourite made on another device is listed too; playback resolves it by content id.
     */
    fun favoriteChannels(accountId: String?): List<LiveGuideChannel> {
        val accounts = com.nuvio.app.features.iptv.XtreamRepository.uiState.value.accounts.map { it.id }
        return com.nuvio.app.features.iptv.LiveFavouritesRows
            .allPlaylists(com.nuvio.app.features.library.LibraryRepository.localItems.value, accounts)
            .filter { accountId == null || it.id.startsWith(XtreamItemRegistry.accountPrefix(accountId)) }
            .map { LiveGuideChannel(contentId = it.id, name = it.name, logo = it.logo ?: it.poster, streamId = 0, categoryId = null) }
    }

    /** F03: true when [contentId] can move one step ([delta]) within the favourites row [rowIds]. */
    fun canMoveFavorite(rowIds: List<String>, contentId: String, delta: Int): Boolean =
        com.nuvio.app.features.iptv.LiveFavouritesRows
            .nudge(com.nuvio.app.features.library.LibraryRepository.localItems.value, rowIds, contentId, delta).isNotEmpty()

    /** F03: move a favourite one step within its row — rewrites the synced favourites order. */
    fun moveFavorite(rowIds: List<String>, contentId: String, delta: Int) {
        val changes = com.nuvio.app.features.iptv.LiveFavouritesRows
            .nudge(com.nuvio.app.features.library.LibraryRepository.localItems.value, rowIds, contentId, delta)
        com.nuvio.app.features.library.LibraryRepository.setSavedAt(changes)
    }

    /** F03: true when a pinned [channel] can move one step among the pinned ones of [shown]. */
    fun canMovePinned(shown: List<LiveGuideChannel>, channel: LiveGuideChannel, delta: Int): Boolean =
        com.nuvio.app.features.livetv.LiveGuidePinnedMoves.move(shown, overlay(), channel, delta) != null

    /** F03: move a pinned channel one step among the pinned ones (synced overlay positions). */
    fun movePinned(shown: List<LiveGuideChannel>, channel: LiveGuideChannel, delta: Int) {
        com.nuvio.app.features.livetv.LiveGuidePinnedMoves.move(shown, overlay(), channel, delta)?.invoke()
    }

    fun accountPrefix(accountId: String): String = XtreamItemRegistry.accountPrefix(accountId)

    private fun overlay() = com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.uiState.value.channels

    fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
