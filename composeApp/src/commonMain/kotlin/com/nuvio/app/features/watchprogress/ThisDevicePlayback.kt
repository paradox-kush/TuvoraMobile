package com.nuvio.app.features.watchprogress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-local, never-synced memory of which titles were played on this device, feeding the small "played
 * here" boost in ResumeRankPolicy. WatchProgressEntry has no device field and progress rows sync across
 * devices, so this lives beside it in the device-local ContinueWatchingEnrichmentStorage (plain
 * SharedPreferences / NSUserDefaults, not profile-scoped, not part of any sync blob).
 */
internal object ThisDevicePlayback {
    private const val STORAGE_KEY = "resume_rank_played_here_v1"

    private var ids: List<String>? = null
    private val _playedIds = MutableStateFlow<Set<String>>(emptySet())
    val playedIds: StateFlow<Set<String>> = _playedIds.asStateFlow()

    fun ensureLoaded() {
        if (ids != null) return
        val loaded = ThisDevicePlaybackPolicy.decode(ContinueWatchingEnrichmentStorage.loadPayload(STORAGE_KEY))
        ids = loaded
        _playedIds.value = loaded.toSet()
    }

    /** Thin hook called by the player's progress save. Cheap when the title is already the latest one. */
    fun record(contentId: String) {
        ensureLoaded()
        val current = ids.orEmpty()
        val next = ThisDevicePlaybackPolicy.record(current, contentId)
        if (next === current || next == current) return
        ids = next
        _playedIds.value = next.toSet()
        ContinueWatchingEnrichmentStorage.savePayload(STORAGE_KEY, ThisDevicePlaybackPolicy.encode(next))
    }
}
