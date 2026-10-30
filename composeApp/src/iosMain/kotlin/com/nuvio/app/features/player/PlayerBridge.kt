package com.nuvio.app.features.player

import platform.UIKit.UIViewController

/**
 * Bridge interface for the MPV player.
 * Swift side implements this and registers a factory at app startup.
 */
interface NuvioPlayerBridge {
    fun createPlayerViewController(): UIViewController
    fun loadFile(url: String)
    // startOption: mpv per-file `start=` (see MpvStartPosition) or null. Hand-mirrored in Swift.
    fun loadFileWithAudio(
        videoUrl: String,
        audioUrl: String?,
        headersJson: String?,
        subtitlesJson: String?,
        startOption: String?,
    )
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun setSystemControlHandler(handler: PlayerSystemControlHandler?)
    fun getKeepsPlaybackInBackground(): Boolean
    fun retry()
    /** Reopen the current source at 0:00 on the same player ("Start from beginning"). */
    fun restartFromBeginning()
    // Live streams must rejoin the live edge (reload) on foreground instead of unpausing.
    fun setIsLiveStream(isLive: Boolean)
    // Picture-in-Picture. Hand-mirrored against MPVPlayerBridgeImpl in Swift — Gradle cannot check
    // these signatures, so a rename on either side fails at runtime, not build time.
    fun isPictureInPictureSupported(): Boolean
    fun isPictureInPictureActive(): Boolean
    fun startPictureInPicture()
    fun stopPictureInPicture()
    fun setPictureInPictureEnabled(enabled: Boolean)
    fun updateNowPlayingMetadata(
        title: String,
        subtitle: String?,
        artworkUrl: String?,
    )
    fun clearNowPlayingInfo()
    fun configureVideoOutput(
        hardwareDecoder: String,
        targetColorspaceHint: Boolean,
        toneMapping: String,
        hdrComputePeak: Boolean,
        targetPrimaries: String,
        targetTransfer: String,
        extendedDynamicRange: Boolean,
        deband: Boolean,
        interpolation: Boolean,
        brightness: Int,
        contrast: Int,
        saturation: Int,
        gamma: Int,
    )
    fun configureAudioOutput(audioOutput: String)
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean)
    fun setResizeMode(mode: Int) // 0=Fit, 1=Fill, 2=Zoom
    fun syncVideoSurfaceLayout(width: Double, height: Double)
    fun getAudioTrackCount(): Int
    fun getAudioTrackIndex(at: Int): Int
    fun getAudioTrackId(at: Int): String
    fun getAudioTrackLabel(at: Int): String
    fun getAudioTrackLang(at: Int): String
    fun isAudioTrackSelected(at: Int): Boolean
    fun getSubtitleTrackCount(): Int
    fun getSubtitleTrackIndex(at: Int): Int
    fun getSubtitleTrackId(at: Int): String
    fun getSubtitleTrackLabel(at: Int): String
    fun getSubtitleTrackLang(at: Int): String
    fun isSubtitleTrackSelected(at: Int): Boolean
    fun selectAudioTrack(trackId: Int)
    fun applyAudioLanguagePreferences(languages: List<String>)
    fun selectSubtitleTrack(trackId: Int)
    fun setSubtitleUrl(url: String)
    fun clearExternalSubtitle()
    fun clearExternalSubtitleAndSelect(trackId: Int)
    fun setSubtitleDelayMs(delayMs: Int)
    fun setSubtitleTextHandler(handler: SubtitleTextHandler?)
    fun applySubtitleStyle(
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Boolean,
        fontSize: Float,
        subPos: Int,
        stripSdh: Boolean,
        styledOverlay: Boolean,
    )
    fun getIsLoading(): Boolean
    fun getIsPlaying(): Boolean
    fun getIsEnded(): Boolean
    fun getDurationMs(): Long
    fun getPositionMs(): Long
    fun getBufferedMs(): Long
    fun getPlaybackSpeed(): Float
    fun getErrorMessage(): String

    /**
     * Evidence that the video output is alive, for live-freeze detection: a counter that only
     * moves while mpv reports frames coming out of the filter chain. Audio cannot advance it,
     * which is the whole point — a frozen picture with playing audio keeps the playhead moving
     * and is otherwise indistinguishable from healthy playback.
     *
     * Returns **-1 when the track has no video at all** (IPTV radio stations), so the caller can
     * tell "no picture expected" from "picture stopped". One method rather than a counter plus a
     * has-video flag, because every method here is a hand-mirrored Swift signature Gradle cannot
     * check — see the note on [getStreamInfoJson].
     */
    fun getVideoFrameTicks(): Long

    /**
     * mpv's VO-level counters, packed into one call because every method here is a
     * hand-mirrored Swift signature Gradle cannot check — the [getVideoFrameTicks] precedent.
     * `estimated-vf-fps` (which feeds the ticks) measures the filter chain, i.e. decoding;
     * mpv has no true presented-frames property, so these are the closest VO-level signals
     * to "the picture reached the screen".
     *
     * High 32 bits: `frame-drop-count` (frames the VO dropped). Low 32 bits:
     * `vo-delayed-frame-count` (mpv's delayed-vsync estimate). Both clamped non-negative on
     * the Swift side, so the packed value is never negative.
     */
    fun getVoFrameStats(): Long

    /**
     * Stream facts for the info overlay, as one JSON object matching
     * `PlayerStreamInfoPayload`. A single call rather than a getter per field: this bridge
     * is hand-mirrored in Swift and Gradle only compiles the Kotlin side, so every extra
     * method is a mismatch Gradle cannot catch. Returns `""` when nothing is playing.
     */
    fun getStreamInfoJson(): String
    fun destroy()
}

fun interface SubtitleTextHandler {
    fun onTextChanged(text: String)
}

/**
 * Optional capability of an mpv-backed [NuvioPlayerBridge]: set mpv properties by name, as strings
 * (`names[i]` = `values[i]`). One generic call instead of a method per feature, because bridges are
 * hand-mirrored in Swift and Gradle cannot check them: the shared policies ([VideoZoomPolicy],
 * [SubtitleStyleMpvMapping]) choose names and values, the bridge only applies them, and a name an
 * older mpv lacks fails alone.
 *
 * A separate interface (checked with `as?`), not a [NuvioPlayerBridge] member, so a bridge that has
 * not adopted it yet — the Apple TV app's MPV and AVPlayer bridges — keeps compiling and simply goes
 * without manual zoom and the box fix until it conforms.
 */
interface NuvioPlayerPropertyBridge {
    fun setMpvStringProperties(names: List<String>, values: List<String>)
}

internal fun NuvioPlayerBridge.setMpvProperties(properties: List<Pair<String, String>>) {
    (this as? NuvioPlayerPropertyBridge)
        ?.setMpvStringProperties(properties.map { it.first }, properties.map { it.second })
}

/**
 * Registry for the player bridge factory.
 * Swift calls [registerFactory] during app startup before Compose is initialized.
 */
object NuvioPlayerBridgeFactory {
    private var factoryRef: NuvioPlayerBridgeCreator? = null

    fun registerFactory(creator: NuvioPlayerBridgeCreator) {
        this.factoryRef = creator
    }

    fun create(): NuvioPlayerBridge? = factoryRef?.createBridge()

    val isRegistered: Boolean get() = factoryRef != null
}

/**
 * Interface for creating bridge instances.
 * Swift implements this to provide the factory.
 */
interface NuvioPlayerBridgeCreator {
    fun createBridge(): NuvioPlayerBridge
}
