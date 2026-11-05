package com.nuvio.app.features.player

/**
 * A seekable stretch of a live stream held by the engine; [positionMs] is the playhead in the same clock.
 * Its own file, free of Compose imports, so Apple TV's shared-code build (which leaves PlayerEngine.kt out) has it.
 */
data class LiveSeekWindow(val startMs: Long, val endMs: Long, val positionMs: Long) {
    val behindLiveMs: Long get() = (endMs - positionMs).coerceAtLeast(0L)
}
