package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.iptv.overlay.IptvOverlayRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Where one profile's [PinSuggestionState] is kept (device-local storage; a fake in tests). */
internal interface PinSuggestionStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, json: String)
}

/**
 * The state holder behind "pin the channels you keep coming back to": play counts, the switch and the snooze for the
 * active profile, persisted on the device only. It decides nothing itself ([PinSuggestionPolicy] does) and touches no
 * network; the screens read [state] and call the verbs below.
 *
 * Privacy: the counts are written to local storage and read back, and that is all. They are not part of any sync
 * payload, and nothing here reports to analytics.
 */
internal class PinSuggestionStore(
    private val storage: PinSuggestionStorage,
    private val nowMs: () -> Long,
    private val activeProfileId: () -> Int,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(PinSuggestionState())
    val state: StateFlow<PinSuggestionState> = _state.asStateFlow()

    private var loaded = false
    private var profileId = 1

    fun ensureLoaded() = synchronized(lock) {
        if (loaded) return@synchronized
        loaded = true
        profileId = activeProfileId()
        _state.value = parse(storage.load(profileId))
    }

    /** Reload for another profile: counts and choices are per profile. */
    fun onProfileChanged(newProfileId: Int) = synchronized(lock) {
        loaded = true
        profileId = newProfileId
        _state.value = parse(storage.load(newProfileId))
    }

    /** Account wipe: the in-memory state goes; the stored copy goes with the rest of the profile's local storage. */
    fun clearLocalState() = synchronized(lock) {
        loaded = false
        profileId = 1
        _state.value = PinSuggestionState()
    }

    /** A successful live play of [contentId]. */
    fun recordPlay(contentId: String, name: String, logo: String?) = change { current ->
        current.copy(channels = LivePlayCountsPolicy.recorded(current.channels, contentId, name, logo, nowMs()))
    }

    /** "Not now". */
    fun snooze() = change { PinSuggestionPolicy.snoozed(it, nowMs()) }

    /** "Stop suggesting". */
    fun stop() = change(PinSuggestionPolicy::stopped)

    /** The Settings switch. */
    fun setEnabled(enabled: Boolean) = change { PinSuggestionPolicy.withEnabled(it, enabled) }

    /** "Clear play counts". */
    fun clearPlayCounts() = change(LivePlayCountsPolicy::cleared)

    /** IPTV playlist edit: re-points counts under an old id prefix to the new one, or drops them when [newPrefix] is null. */
    fun migrateIdPrefix(oldPrefix: String, newPrefix: String?) = change { current ->
        current.copy(
            channels = current.channels.mapNotNull { ch ->
                when {
                    !ch.contentId.startsWith(oldPrefix) -> ch
                    newPrefix == null -> null
                    else -> ch.copy(contentId = newPrefix + ch.contentId.removePrefix(oldPrefix))
                }
            },
        )
    }

    /** B64: re-keys counts in place ([rewrite] = the new content id, or null to keep). Returns how many moved. */
    fun rekeyIds(rewrite: (String) -> String?): Int {
        var moved = 0
        change { current ->
            moved = 0
            val merged = LinkedHashMap<String, PlayedChannel>()
            for (ch in current.channels) {
                val row = rewrite(ch.contentId)?.let { moved++; ch.copy(contentId = it) } ?: ch
                val first = merged[row.contentId]
                merged[row.contentId] = if (first == null) row else first.copy(
                    plays = (first.plays + row.plays).sorted().takeLast(LivePlayCountsPolicy.MAX_PLAYS_PER_CHANNEL),
                    logo = first.logo ?: row.logo,
                )
            }
            if (moved == 0) current else current.copy(channels = merged.values.toList())
        }
        return moved
    }

    private fun change(transform: (PinSuggestionState) -> PinSuggestionState) {
        ensureLoaded()
        synchronized(lock) {
            val before = _state.value
            val after = transform(before)
            if (after == before) return@synchronized
            _state.value = after
            storage.save(profileId, json.encodeToString(after))
        }
    }

    private fun parse(stored: String?): PinSuggestionState {
        if (stored.isNullOrBlank()) return PinSuggestionState()
        return runCatching { json.decodeFromString<PinSuggestionState>(stored) }.getOrDefault(PinSuggestionState())
    }
}

/**
 * The live pin port: a favourite is a local library entry of type "tv" (the same entry the long-press menu writes), and
 * a channel pinned in the personalization overlay counts as pinned too, so it is never suggested again.
 */
internal object LibraryLiveChannelPinPort : LiveChannelPinPort {
    override fun isPinned(contentId: String): Boolean {
        if (LibraryRepository.isLocalSaved(contentId, "tv")) return true
        val entity = XtreamHubRepository.hideTargetFor(contentId)?.entityId ?: return false
        return IptvOverlayRepository.uiState.value.channels[entity]?.pinned == true
    }

    override fun pin(channel: PlayedChannel) {
        // Live favourites belong to Tuvora's own synced library, like the long-press toggle (see toggleLiveChannelFavorite).
        LibraryRepository.save(
            LibraryItem(
                id = channel.contentId,
                type = "tv",
                name = channel.name,
                poster = channel.logo,
                logo = channel.logo,
                posterShape = PosterShape.Landscape,
                savedAtEpochMs = 0L, // stamped by LibraryRepository.save()
            ),
        )
    }
}

/** The app's pin-suggestion state, wired to this device's storage. */
internal object PinSuggestions {
    val store = PinSuggestionStore(
        storage = object : PinSuggestionStorage {
            override fun load(profileId: Int) = XtreamAccountStorage.loadPinSuggestionsJson(profileId)
            override fun save(profileId: Int, json: String) = XtreamAccountStorage.savePinSuggestionsJson(profileId, json)
        },
        nowMs = { TraktPlatformClock.nowEpochMs() },
        activeProfileId = { ProfileRepository.activeProfileId },
    )

    val state: StateFlow<PinSuggestionState> get() = store.state

    /** Suggestions for the channels of one playlist ([accountPrefix]), or every playlist when null. */
    fun suggestionsFor(state: PinSuggestionState, accountPrefix: String?): List<PlayedChannel> =
        PinSuggestionPolicy.suggest(state, TraktPlatformClock.nowEpochMs()) { id ->
            (accountPrefix != null && !id.startsWith(accountPrefix)) || LibraryLiveChannelPinPort.isPinned(id)
        }

    fun pin(channel: PlayedChannel): Boolean = PinSuggestionActions.pin(LibraryLiveChannelPinPort, channel)

    fun pinAll(suggested: List<PlayedChannel>): Int = PinSuggestionActions.pinAll(LibraryLiveChannelPinPort, suggested)
}
