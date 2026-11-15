package com.nuvio.app.features.livetv

import com.nuvio.app.features.sportsdetail.api.NowWatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live channel the Live TV screen is playing right now, or null. Published by the screen while it is composed;
 * read by the game screen's Watch block through a port wired in FeatureWiring (consumers never name this object).
 */
object LiveNowWatching {
    private val _channel = MutableStateFlow<NowWatching?>(null)
    val channel: StateFlow<NowWatching?> = _channel.asStateFlow()

    fun set(contentId: String, name: String) { _channel.value = NowWatching(contentId, name) }

    /** Clears only if [contentId] is still the published one (a fast channel switch must not clear the new one). */
    fun clear(contentId: String) { if (_channel.value?.channelId == contentId) _channel.value = null }
}
