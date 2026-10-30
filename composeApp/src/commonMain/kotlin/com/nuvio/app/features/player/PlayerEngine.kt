package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface PlayerEngineController {
    val playbackEngine: AndroidPlaybackEngine? get() = null
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun retry()
    /**
     * Reopen the current source at 0:00 on THIS player ("Start from beginning"). A new player would
     * first wait for the old one to tear down, and with a slow provider the old one is stuck in the
     * very read the viewer is trying to escape. Engines that cannot seek before playback starts
     * (mpv) override this to reload with a start position.
     */
    fun restartFromBeginning() = seekTo(0L)

    /**
     * Reinitialise the video pipeline, leaving the connection alone — for a live channel whose
     * picture died while its audio kept arriving. Returns false when the engine has no such
     * primitive, so the caller escalates to a full re-resolve instead of spending a recovery
     * attempt on nothing.
     *
     * Deliberately not [retry]: a live link carries an expiring, sometimes single-use token, and
     * providers cap concurrent connections. Re-resolving a stream that is still delivering audio
     * can cost the viewer the half-working stream they still had.
     */
    fun resetVideoPipeline(): Boolean = false
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean) {}
    fun getAudioTracks(): List<AudioTrack>
    fun getSubtitleTracks(): List<SubtitleTrack>

    /**
     * Facts about the stream being decoded, for the stream info panel. Defaulted so an
     * engine that cannot report them degrades to an empty panel instead of failing to
     * build. Implementations must not throw — this is diagnostics, and it is called from
     * the UI thread when the user opens the panel.
     */
    fun getStreamInfo(): PlayerStreamInfo = PlayerStreamInfo()
    fun applyAudioLanguagePreferences(languages: List<String>)
    fun selectAudioTrack(index: Int)
    fun selectSubtitleTrack(index: Int)
    fun setSubtitleUri(url: String)
    fun clearExternalSubtitle()
    fun clearExternalSubtitleAndSelect(trackIndex: Int)
    fun applySubtitleStyle(style: SubtitleStyleState) {}
    fun applySubtitlePreferences(
        preferredLanguage: String,
        secondaryPreferredLanguage: String? = null,
        useForcedSubtitles: Boolean,
        autoSelectionApplied: Boolean,
        hasActiveSubtitle: Boolean,
        useCustomSubtitles: Boolean = false,
    ) {}
    fun setSubtitleDelayMs(delayMs: Int) {}
    fun configureIosVideoOutput(settings: PlayerSettingsUiState) {}
    fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {}
    fun clearNowPlayingInfo() {}
    // Upstream's stream-info panel reads this; the fork serves it from getStreamInfo(), which every
    // engine already answers from observed state — no on-demand mpv reads.
    suspend fun getMediaInfo(): PlayerMediaInfo = getStreamInfo().toMediaInfo()
    /** Chapters of the loaded file; empty if not supported by the engine. */
    suspend fun getChapters(): List<PlayerChapter> = emptyList()
}

internal fun sanitizePlaybackHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

internal fun sanitizePlaybackResponseHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

@Composable
expect fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String? = null,
    sourceHeaders: Map<String, String> = emptyMap(),
    sourceResponseHeaders: Map<String, String> = emptyMap(),
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    streamType: String? = null,
    /**
     * This source is a CATCH-UP recording arriving down the live pipe.
     *
     * Carried BESIDE [streamType] rather than replacing it: the archive is still delivered as a
     * live stream and the engine selection depends on that, so a new content type would ripple
     * through every comparison in the app while fixing nothing. Live-only behaviour reads
     * `live && !isCatchUpPlayback` — see CatchUpPlayback.
     */
    isCatchUpPlayback: Boolean = false,
    /**
     * Which screen is hosting this surface — one of [LIVE_FREEZE_SURFACE_DOCKED] /
     * [LIVE_FREEZE_SURFACE_PLAYER], the same vocabulary freeze reporting already uses.
     *
     * The engine cannot infer it: the docked Live TV screen and the fullscreen player hand over
     * identical sources. [LiveVideoOutputPolicy] needs the distinction because the docked screen can
     * safely use a renderer that draws no OSD and ignores panscan, and the fullscreen player cannot.
     * Null means "unknown host", which every policy here treats as the conservative branch.
     */
    playbackSurface: String? = null,
    useYoutubeChunkedPlayback: Boolean = false,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    initialPositionMs: Long? = null,
    initialPositionRequestKey: String? = null,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    /** F36 manual zoom on top of [resizeMode] — see [VideoZoomPolicy] for how each engine applies it. */
    videoZoom: VideoZoom = VideoZoom.IDENTITY,
    playbackEngine: AndroidPlaybackEngine? = null,
    useNativeController: Boolean = false,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit = { _, _ -> },
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
)
