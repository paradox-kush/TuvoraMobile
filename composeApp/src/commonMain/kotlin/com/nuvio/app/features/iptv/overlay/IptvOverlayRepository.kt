package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the active profile's IPTV personalization overlay. Loads the sparse snapshot the
 * read layer applies, and exposes the edit intents the native one-tap toggles + (later) the sync
 * adapter call. No ViewModel layer (house pattern); the UI observes [uiState] and calls intents.
 *
 * Edits are local-first (written to [IptvOverlayStore]); the delta-sync adapter pushes them and pulls
 * the website's edits into the same store, then calls [reload]. All ids are canon-v1, so they match.
 */
internal object IptvOverlayRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(OverlaySnapshot.EMPTY)
    val uiState: StateFlow<OverlaySnapshot> = _uiState.asStateFlow()

    private var loadedProfile: Int = -1

    private fun now() = TraktPlatformClock.nowEpochMs()
    private fun profile() = ProfileRepository.activeProfileId

    // The overlay is an OPTIONAL layer: a DB/sync/apply failure must degrade to the unfiltered guide,
    // never crash. Every fire-and-forget op runs through here so a throw can't reach the scope's
    // uncaught handler (parity with the TuvoraTV v1.6.0 IPTV-open crash fix).
    private fun launchSafely(block: suspend () -> Unit) =
        scope.launch { runCatching { block() }.onFailure { Logger.w("IptvOverlay") { "overlay op failed: ${it.message}" } } }

    fun ensureLoaded() {
        val p = profile()
        if (loadedProfile == p) return
        loadedProfile = p
        reload()
    }

    fun resetForProfile() {
        loadedProfile = profile()
        reload()
    }

    fun onProfileChanged(profileIndex: Int) {
        loadedProfile = profileIndex
        reload()
    }

    private fun reload() {
        val p = profile()
        launchSafely { _uiState.value = IptvOverlayStore.snapshot(p) }
    }

    /** Ordered-sync entry point: pull one profile's overlay edits (called from the SyncManager loop). */
    suspend fun pullForProfile(profileId: Int) {
        if (IptvOverlaySyncAdapter.pullInto(profileId) && profileId == profile()) {
            _uiState.value = IptvOverlayStore.snapshot(profileId)
        }
    }

    /** Pull the website's (and other devices') overlay edits, apply, and refresh the snapshot. Cheap delta. */
    fun pullFromServer() {
        val p = profile()
        launchSafely {
            runCatching { if (IptvOverlaySyncAdapter.pullInto(p)) _uiState.value = IptvOverlayStore.snapshot(p) }
        }
    }

    /** Push this device's edits to the server so the web + other devices see them. */
    private fun pushToServer() {
        val p = profile()
        launchSafely { IptvOverlaySyncAdapter.push(p) }
    }

    // ---- channel intents -------------------------------------------------------------------------

    private fun editChannel(entityId: String, playlistId: String?, transform: (ChannelOverlay) -> ChannelOverlay) {
        val p = profile()
        val current = _uiState.value.channels[entityId] ?: ChannelOverlay()
        val next = transform(current)
        launchSafely {
            IptvOverlayStore.setChannel(p, entityId, playlistId, next, now())
            _uiState.value = IptvOverlayStore.snapshot(p)
            pushToServer()
        }
    }

    fun setChannelHidden(entityId: String, playlistId: String?, hidden: Boolean) =
        editChannel(entityId, playlistId) { it.copy(hidden = hidden) }

    fun toggleChannelHidden(entityId: String, playlistId: String?) =
        editChannel(entityId, playlistId) { it.copy(hidden = !it.hidden) }

    fun setChannelPinned(entityId: String, playlistId: String?, pinned: Boolean) =
        editChannel(entityId, playlistId) { it.copy(pinned = pinned) }

    fun toggleChannelPinned(entityId: String, playlistId: String?) =
        editChannel(entityId, playlistId) { it.copy(pinned = !it.pinned) }

    fun renameChannel(entityId: String, playlistId: String?, name: String?) =
        editChannel(entityId, playlistId) { it.copy(rename = name?.trim()?.takeIf { n -> n.isNotEmpty() }) }

    /** Assign explicit positions to a whole ordered list of channels (a drag-reorder captures the order). */
    fun reorderChannels(playlistId: String?, orderedEntityIds: List<String>) {
        val p = profile()
        launchSafely {
            val t = now()
            orderedEntityIds.forEachIndexed { i, entity ->
                val cur = _uiState.value.channels[entity] ?: ChannelOverlay()
                IptvOverlayStore.setChannel(p, entity, playlistId, cur.copy(position = i), t)
            }
            _uiState.value = IptvOverlayStore.snapshot(p)
        }
    }

    // ---- category intents ------------------------------------------------------------------------

    private fun editCategory(playlistId: String, contentType: String, categoryKey: String, transform: (CategoryOverlay) -> CategoryOverlay) {
        val p = profile()
        val current = _uiState.value.categories[categoryKey] ?: CategoryOverlay()
        val next = transform(current)
        launchSafely {
            IptvOverlayStore.setCategory(p, playlistId, contentType, categoryKey, next, now())
            _uiState.value = IptvOverlayStore.snapshot(p)
            pushToServer()
        }
    }

    fun toggleCategoryHidden(playlistId: String, contentType: String, categoryKey: String) =
        editCategory(playlistId, contentType, categoryKey) { it.copy(hidden = !it.hidden) }

    fun setCategoryPinned(playlistId: String, contentType: String, categoryKey: String, pinned: Boolean) =
        editCategory(playlistId, contentType, categoryKey) { it.copy(pinned = pinned) }

    fun renameCategory(playlistId: String, contentType: String, categoryKey: String, name: String?) =
        editCategory(playlistId, contentType, categoryKey) { it.copy(rename = name?.trim()?.takeIf { n -> n.isNotEmpty() }) }

    // ---- custom group intents --------------------------------------------------------------------

    fun putGroup(group: CustomGroup) {
        val p = profile()
        launchSafely {
            IptvOverlayStore.putGroup(p, group, now())
            _uiState.value = IptvOverlayStore.snapshot(p)
        }
    }

    fun deleteGroup(groupId: String) {
        val p = profile()
        launchSafely {
            IptvOverlayStore.deleteGroup(p, groupId, now())
            _uiState.value = IptvOverlayStore.snapshot(p)
        }
    }

    /** After a playlist is removed, drop its channel/category overlay (custom groups may span playlists). */
    fun onPlaylistRemoved(playlistId: String) {
        val p = profile()
        launchSafely {
            IptvOverlayStore.purgePlaylist(p, playlistId)
            _uiState.value = IptvOverlayStore.snapshot(p)
        }
    }
}
