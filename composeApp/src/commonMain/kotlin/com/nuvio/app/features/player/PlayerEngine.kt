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
    /** Whether [setAudioDelayMs] has an effect; the player hides the audio delay control otherwise. */
    val supportsAudioDelay: Boolean get() = false
    /** Plays the audio [delayMs] later than the video; negative values play it earlier. */
    fun setAudioDelayMs(delayMs: Int) {}
    /** Whether [setAudioAmplificationDb] has an effect; the player hides the amplification control otherwise. */
    val supportsAudioAmplification: Boolean get() = false
    /** Boosts the audio by [db] decibels, from 0 (off) to [AUDIO_AMPLIFICATION_MAX_DB]. */
    fun setAudioAmplificationDb(db: Int) {}
    /** Whether the audio playing now goes through a downmix whose center level [setCenterMixLevelDb] changes. */
    fun isCenterMixActive(): Boolean = false
    /** Center channel level in the downmix, from [CENTER_MIX_LEVEL_MIN_DB] to [CENTER_MIX_LEVEL_MAX_DB]. */
    fun setCenterMixLevelDb(db: Int) {}
    fun configureIosVideoOutput(settings: PlayerSettingsUiState) {}
    fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {}
    fun clearNowPlayingInfo() {}
    // Upstream's stream-info panel reads this; the fork serves it from getStreamInfo(), which every
    // engine already answers from observed state — no on-demand mpv reads.
    suspend fun getMediaInfo(): PlayerMediaInfo = getStreamInfo().toMediaInfo()
    /** Chapters of the loaded file; empty if not supported by the engine. */
    suspend fun getChapters(): List<PlayerChapter> = emptyList()

    /**
     * True when this engine can hold a live stream's past in memory ([setLiveCacheBudget] +
     * [liveSeekWindow] do something). Default false: an engine without the hooks keeps the plain
     * pause it always had.
     */
    val supportsLiveBackBuffer: Boolean get() = false

    /**
     * Sizes the engine's own demuxer cache for a live stream: [maxBytes] ahead of the playhead,
     * [maxBackBytes] behind it, [cacheSecs] of read-ahead. A pause of N seconds needs the forward cache
     * (and its seconds limit) raised, a rewind needs the back buffer. Applies to the CURRENT stream.
     * Must be safe to call from the UI thread (engines queue it off-thread). Default: no-op.
     */
    fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) {}

    /**
     * What the engine can seek within right now on a live stream, or null when it cannot say. Must not
     * block (read an observed shadow, never ask the engine). Default: null.
     */
    fun liveSeekWindow(): LiveSeekWindow? = null
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
     * The URL is spent after its first open (a live Stalker `create_link`). The engine then makes no
     * re-open of its own after a drop (mpv `stream-lavf-o reconnect=0`, no Media3 load retry), and the
     * app-level recovery mints a fresh link. See [LiveLinkReconnectPolicy].
     */
    singleUseLink: Boolean = false,
    /**
     * Subtitle AutoSync may read this source's URL itself (HTTP Range requests for the container's
     * subtitle index): [com.nuvio.app.features.autosync.AutoSyncSourcePolicy.mayProbe] for the playing
     * item. False (the default) attaches add-on subtitles unchanged and never touches the URL, so an
     * own-source, live or catch-up link is never spent by a side request.
     */
    autoSyncMayProbeSource: Boolean = false,
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
    /**
     * The source is a LOCAL growing HLS playlist (live pause buffer): prepare paused and park at the START
     * of its window, instead of joining the live edge as a live source normally does. Engines that cannot
     * honour it may ignore it (the screen then starts at the engine's own default).
     */
    startAtLiveWindowStart: Boolean = false,
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
