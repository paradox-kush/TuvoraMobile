package com.nuvio.app.features.iptv

import kotlinx.atomicfu.atomic

/**
 * A tiny "is a player currently on screen" flag, set while the app is in the player and read by the
 * P3 auto-refresh worker so a heavy M3U re-ingest doesn't fire mid-playback (the worker reschedules
 * instead). Set from the PlayerRoute composable's DisposableEffect in App.kt.
 *
 * atomicfu-backed so the worker thread and the UI thread see a consistent value without locking.
 */
object IptvPlaybackGate {
    private val active = atomic(false)

    fun setPlaybackActive(isActive: Boolean) {
        active.value = isActive
    }

    val isPlaybackActive: Boolean get() = active.value
}
