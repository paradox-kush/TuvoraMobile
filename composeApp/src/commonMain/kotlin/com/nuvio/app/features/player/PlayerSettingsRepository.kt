package com.nuvio.app.features.player

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

val STREAM_AUTO_PLAY_TIMEOUT_VALUES: List<Int> = listOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, Int.MAX_VALUE
)

/**
 * Snaps [value] to the nearest allowed timeout value in [STREAM_AUTO_PLAY_TIMEOUT_VALUES].
 * Ties break to the lower value. Negative values snap to 0.
 */
fun snapToAllowedTimeout(value: Int): Int {
    if (value <= 0) return 0
    var bestValue = STREAM_AUTO_PLAY_TIMEOUT_VALUES[0]
    var bestDistance = Long.MAX_VALUE
    for (allowed in STREAM_AUTO_PLAY_TIMEOUT_VALUES) {
        val distance = abs(value.toLong() - allowed.toLong())
        if (distance < bestDistance || (distance == bestDistance && allowed < bestValue)) {
            bestDistance = distance
            bestValue = allowed
        }
    }
    return bestValue
}

data class PlayerSettingsUiState(
    val useLegacyPlayerLayout: Boolean = false,
    val showLoadingOverlay: Boolean = true,
    val showPlayerLoadingStatus: Boolean = true,
    val pauseOverlayEnabled: Boolean = true,
    val showParentalGuide: Boolean = true,
    val showStreamInfo: Boolean = true,
    val resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    /**
     * F37 "Remember my player preferences": per-series memory of tracks, subtitles on/off, aspect and
     * manual zoom ([PlayerPreferencePolicy]). Device-local for now — NOT in the settings-sync payload
     * (adding it is a sync payload change, held for an owner decision).
     */
    val rememberPlayerPreferences: Boolean = PlayerPreferencePolicy.DEFAULT_REMEMBER,
    /** F13: live IPTV buffer in seconds, [LiveBufferPolicy.AUTO] = engine default. Device-local. */
    val liveBufferSeconds: Int = LiveBufferPolicy.AUTO,
    val holdToSpeedEnabled: Boolean = true,
    val holdToSpeedValue: Float = 2f,
    val touchGesturesEnabled: Boolean = true,
    /**
     * Picture-in-Picture. Deliberately NOT part of the settings-sync payload: PiP exists only on
     * the mobile platforms, and syncing a platform-specific playback preference is exactly how the
     * external-player id once dead-ended playback on a device that could not honour it.
     */
    val pictureInPictureEnabled: Boolean = true,
    val externalPlayerEnabled: Boolean = false,
    val externalPlayerForwardSubtitles: Boolean = false,
    val externalPlayerSendSkipSegments: Boolean = false,
    val externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId(),
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String = SubtitleLanguageOption.NONE,
    val secondaryPreferredSubtitleLanguage: String? = null,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    /**
     * Default is libmpv on Android, NOT Auto (which starts on ExoPlayer).
     *
     * ExoPlayer macroblocks 4K / high-bitrate H.264 on Qualcomm hardware decoders — the "green
     * blocks on live" cohort — while libmpv decodes the same streams clean (device-confirmed on a
     * Galaxy S24 Ultra; see research/tuvora-4k-avc-macroblocking). Defaulting to libmpv is safe
     * now that [androidLibmpvVideoOutput] defaults to `gpu` (not `gpu-next`), which sidesteps the
     * libplacebo v7.360.0 sync_file fd leak that once made a libmpv default untenable.
     *
     * Android-only: iOS always renders through the Swift MPV bridge and desktop uses its own
     * native player, so both persist this field but ignore it for engine selection. This moves
     * only the DEFAULT — a user who explicitly picked Auto or ExoPlayer keeps that choice.
     */
    val androidPlaybackEngine: AndroidPlaybackEngine = AndroidPlaybackEngine.Libmpv,
    val autoSwitchPlaybackEngineOnError: Boolean = true,
    /**
     * `gpu`, NOT `gpu-next`, until our libmpv bundles libplacebo >= v7.360.1.
     *
     * `gpu-next` is libplacebo, and the libplacebo we ship (**v7.360.0**, read out of the shipped
     * `libmpv.so`) has a per-frame file-descriptor leak on its OpenGL backend: `gl_poll_callbacks()`
     * in `opengl/util` creates a GL sync object per frame and never calls `gl->DeleteSync`. On
     * Android every GL sync object is backed by a `sync_file` fd, so the process leaks one
     * descriptor per rendered frame and dies on EMFILE at the 32768 ceiling.
     *
     * Measured on a Galaxy S24 Ultra (2026-08-16): **~25 fences/second on a 25 fps channel** —
     * the leak rate is the frame rate — with `anon_inode:sync_file` at 93% of all open descriptors
     * and dma-buf flat. That is ~22 minutes of continuous playback to a crash, ~9 at 60 fps, and it
     * matches the whole `native_crash` cohort in telemetry.
     *
     * Upstream fixed it in **libplacebo v7.360.1** — one patch release ahead of ours — in a commit
     * that explicitly closes mpv-android#977 (gpu-next + gpu-api=opengl failing after 10-12 min on
     * Android) and mpv#12708 (GL_OUT_OF_MEMORY on Android, Qualcomm GPUs). Every device in our
     * crash table is a Qualcomm Android phone.
     *
     * `gpu` is mpv's own GL renderer and does not go through libplacebo at all, which is why
     * NuvioTV — which has always used `gpu` — shows healthy fence counts (3-35) in the same
     * telemetry window while mobile hits 32,000. It is a full-featured renderer already shipped as
     * a user-selectable option, so this costs some of gpu-next's higher-quality processing and
     * nothing else. A user who has explicitly chosen gpu-next keeps it: this only moves the DEFAULT.
     *
     * Revert to [AndroidLibmpvVideoOutput.GpuNext] once libmpv is rebuilt with libplacebo
     * >= v7.360.1, and confirm with the fd census in research/mpv-fence-fd-leak.md.
     */
    val androidLibmpvVideoOutput: AndroidLibmpvVideoOutput = AndroidLibmpvVideoOutput.Gpu,
    val androidLibmpvHardwareDecodingEnabled: Boolean = true,
    val androidLibmpvYuv420pEnabled: Boolean = false,
    val androidExoChaptersEnabled: Boolean = false,
    val decoderPriority: Int = 1,
    val mapDV7ToHevc: Boolean = false,
    val tunnelingEnabled: Boolean = false,
    val exoNativeMemoryEnabled: Boolean = false,
    val bufferEngineEnabled: Boolean = false,
    val minBufferMs: Int = PlaybackBufferSettings.DEFAULT_MIN_BUFFER_MS,
    val maxBufferMs: Int = PlaybackBufferSettings.DEFAULT_MAX_BUFFER_MS,
    val bufferForPlaybackMs: Int = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    val bufferForPlaybackAfterRebufferMs: Int = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val backBufferDurationMs: Int = PlaybackBufferSettings.DEFAULT_BACK_BUFFER_MS,
    val targetBufferSizeMb: Int = PlaybackBufferSettings.DEFAULT_TARGET_BUFFER_MB,
    val vodCacheEnabled: Boolean = false,
    val vodCacheSizeMode: VodCacheSizeMode = VodCacheSizeMode.AUTO,
    val vodCacheSizeMb: Int = VodCacheSizing.DEFAULT_SIZE_MB,
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlaySource: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
    val streamAutoPlaySelectedAddons: Set<String> = emptySet(),
    val streamAutoPlaySelectedPlugins: Set<String> = emptySet(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayTimeoutSeconds: Int = 3,
    val skipIntroEnabled: Boolean = true,
    val autoSkipSegmentTypes: Set<AutoSkipSegmentType> = emptySet(),
    val animeSkipEnabled: Boolean = false,
    val animeSkipClientId: String = "",
    val introDbApiKey: String = "",
    val introSubmitEnabled: Boolean = false,
    val streamAutoPlayNextEpisodeEnabled: Boolean = true,
    val streamAutoPlayNextEpisodeFallbackEnabled: Boolean = true,
    val streamAutoPlayPreferBingeGroup: Boolean = true,
    val streamAutoPlayReuseBingeGroup: Boolean = false,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val preloadNextEpisodeSources: Boolean = false,
    val useLibass: Boolean = true,
    val libassRenderType: String = "CUES",
    val libassRenderScale: String = "FULL",
    val libassRenderMode: String = "OVERLAY",
    val iosVideoOutputPreset: IosVideoOutputPreset = IosVideoOutputPreset.NativeEdr,
    val iosToneMappingMode: IosToneMappingMode = IosToneMappingMode.Auto,
    val iosTargetPrimaries: IosTargetPrimaries = IosTargetPrimaries.Auto,
    val iosTargetTransfer: IosTargetTransfer = IosTargetTransfer.Auto,
    val iosHardwareDecoderMode: IosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox,
    val iosAudioOutputMode: IosAudioOutputMode = IosAudioOutputMode.Auto,
    val iosExtendedDynamicRangeEnabled: Boolean = true,
    val iosTargetColorspaceHintEnabled: Boolean = true,
    val iosHdrComputePeakEnabled: Boolean = true,
    val iosDebandEnabled: Boolean = false,
    val iosInterpolationEnabled: Boolean = false,
    val iosBrightness: Int = 0,
    val iosContrast: Int = 0,
    val iosSaturation: Int = 0,
    val iosGamma: Int = 0,
)

object PlayerSettingsRepository {
    private val _uiState = MutableStateFlow(PlayerSettingsUiState())
    val uiState: StateFlow<PlayerSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var useLegacyPlayerLayout = false
    private var showLoadingOverlay = true
    private var showPlayerLoadingStatus = true
    private var pauseOverlayEnabled = true
    private var showParentalGuide = true
    private var showStreamInfo = true
    private var resizeMode = PlayerResizeMode.Fit
    private var rememberPlayerPreferences = PlayerPreferencePolicy.DEFAULT_REMEMBER
    private var liveBufferSeconds = LiveBufferPolicy.AUTO
    private var holdToSpeedEnabled = true
    private var holdToSpeedValue = 2f
    private var touchGesturesEnabled = true
    private var pictureInPictureEnabled = true
    private var externalPlayerEnabled = false
    private var externalPlayerForwardSubtitles = false
    private var externalPlayerSendSkipSegments = false
    private var externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId()
    private var preferredAudioLanguage = AudioLanguageOption.DEVICE
    private var secondaryPreferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage = SubtitleLanguageOption.NONE
    private var secondaryPreferredSubtitleLanguage: String? = null
    private var subtitleStyle = SubtitleStyleState.DEFAULT
    private var streamReuseLastLinkEnabled = false
    private var streamReuseLastLinkCacheHours = 24
    // libmpv, not Auto — ExoPlayer macroblocks 4K/high-bitrate H.264 on Qualcomm; see the
    // KDoc on the UI-state default.
    private var androidPlaybackEngine = AndroidPlaybackEngine.Libmpv
    private var autoSwitchPlaybackEngineOnError = true
    // gpu, not gpu-next — see the KDoc on the UI-state default: our libplacebo (v7.360.0) leaks
    // one sync_file fd per frame on its OpenGL backend, fixed upstream in v7.360.1.
    private var androidLibmpvVideoOutput = AndroidLibmpvVideoOutput.Gpu
    private var androidLibmpvHardwareDecodingEnabled = true
    private var androidLibmpvYuv420pEnabled = false
    private var androidExoChaptersEnabled = false
    private var decoderPriority = 1
    private var mapDV7ToHevc = false
    private var tunnelingEnabled = false
    private var exoNativeMemoryEnabled = false
    private var bufferEngineEnabled = false
    private var minBufferMs = PlaybackBufferSettings.DEFAULT_MIN_BUFFER_MS
    private var maxBufferMs = PlaybackBufferSettings.DEFAULT_MAX_BUFFER_MS
    private var bufferForPlaybackMs = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
    private var bufferForPlaybackAfterRebufferMs = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
    private var backBufferDurationMs = PlaybackBufferSettings.DEFAULT_BACK_BUFFER_MS
    private var targetBufferSizeMb = PlaybackBufferSettings.DEFAULT_TARGET_BUFFER_MB
    private var vodCacheEnabled = false
    private var vodCacheSizeMode = VodCacheSizeMode.AUTO
    private var vodCacheSizeMb = VodCacheSizing.DEFAULT_SIZE_MB
    private var streamAutoPlayMode = StreamAutoPlayMode.MANUAL
    private var streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
    private var streamAutoPlaySelectedAddons: Set<String> = emptySet()
    private var streamAutoPlaySelectedPlugins: Set<String> = emptySet()
    private var streamAutoPlayRegex = ""
    private var streamAutoPlayTimeoutSeconds = 3
    private var skipIntroEnabled = true
    private var autoSkipSegmentTypes: Set<AutoSkipSegmentType> = emptySet()
    private var animeSkipEnabled = false
    private var animeSkipClientId = ""
    private var introDbApiKey = ""
    private var introSubmitEnabled = false
    private var streamAutoPlayNextEpisodeEnabled = true
    private var streamAutoPlayNextEpisodeFallbackEnabled = true
    private var streamAutoPlayPreferBingeGroup = true
    private var streamAutoPlayReuseBingeGroup = false
    private var nextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    private var nextEpisodeThresholdPercent = 99f
    private var nextEpisodeThresholdMinutesBeforeEnd = 2f
    private var preloadNextEpisodeSources = false
    private var useLibass = true
    private var libassRenderType = "OVERLAY_OPEN_GL"
    private var libassRenderScale = "FULL"
    private var libassRenderMode = "OVERLAY"
    private var iosVideoOutputPreset = IosVideoOutputPreset.NativeEdr
    private var iosToneMappingMode = IosToneMappingMode.Auto
    private var iosTargetPrimaries = IosTargetPrimaries.Auto
    private var iosTargetTransfer = IosTargetTransfer.Auto
    private var iosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox
    private var iosAudioOutputMode = IosAudioOutputMode.Auto
    private var iosExtendedDynamicRangeEnabled = true
    private var iosTargetColorspaceHintEnabled = true
    private var iosHdrComputePeakEnabled = true
    private var iosDebandEnabled = false
    private var iosInterpolationEnabled = false
    private var iosBrightness = 0
    private var iosContrast = 0
    private var iosSaturation = 0
    private var iosGamma = 0

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        useLegacyPlayerLayout = false
        showLoadingOverlay = true
        showPlayerLoadingStatus = true
        pauseOverlayEnabled = true
        showParentalGuide = true
        showStreamInfo = true
        resizeMode = PlayerResizeMode.Fit
        rememberPlayerPreferences = PlayerPreferencePolicy.DEFAULT_REMEMBER
        liveBufferSeconds = LiveBufferPolicy.AUTO
        holdToSpeedEnabled = true
        holdToSpeedValue = 2f
        touchGesturesEnabled = true
        pictureInPictureEnabled = true
        externalPlayerEnabled = false
        externalPlayerForwardSubtitles = false
        externalPlayerSendSkipSegments = false
        externalPlayerId = ExternalPlayerPlatform.defaultPlayerId()
        preferredAudioLanguage = AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage = null
        preferredSubtitleLanguage = SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage = null
        subtitleStyle = SubtitleStyleState.DEFAULT
        streamReuseLastLinkEnabled = false
        streamReuseLastLinkCacheHours = 24
        androidPlaybackEngine = AndroidPlaybackEngine.Libmpv
        autoSwitchPlaybackEngineOnError = true
        androidLibmpvVideoOutput = AndroidLibmpvVideoOutput.Gpu
        androidLibmpvHardwareDecodingEnabled = true
        androidLibmpvYuv420pEnabled = false
        androidExoChaptersEnabled = false
        decoderPriority = 1
        mapDV7ToHevc = false
        tunnelingEnabled = false
        exoNativeMemoryEnabled = false
        bufferEngineEnabled = false
        minBufferMs = PlaybackBufferSettings.DEFAULT_MIN_BUFFER_MS
        maxBufferMs = PlaybackBufferSettings.DEFAULT_MAX_BUFFER_MS
        bufferForPlaybackMs = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        bufferForPlaybackAfterRebufferMs = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        backBufferDurationMs = PlaybackBufferSettings.DEFAULT_BACK_BUFFER_MS
        targetBufferSizeMb = PlaybackBufferSettings.DEFAULT_TARGET_BUFFER_MB
        vodCacheEnabled = false
        vodCacheSizeMode = VodCacheSizeMode.AUTO
        vodCacheSizeMb = VodCacheSizing.DEFAULT_SIZE_MB
        applyExoPlayerNativeMemory(false)
        streamAutoPlayMode = StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = emptySet()
        streamAutoPlaySelectedPlugins = emptySet()
        streamAutoPlayRegex = ""
        streamAutoPlayTimeoutSeconds = 3
        skipIntroEnabled = true
        autoSkipSegmentTypes = emptySet()
        animeSkipEnabled = false
        animeSkipClientId = ""
        introDbApiKey = ""
        introSubmitEnabled = false
        streamAutoPlayNextEpisodeEnabled = true
        streamAutoPlayNextEpisodeFallbackEnabled = true
        streamAutoPlayPreferBingeGroup = true
        streamAutoPlayReuseBingeGroup = false
        nextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
        nextEpisodeThresholdPercent = 99f
        nextEpisodeThresholdMinutesBeforeEnd = 2f
        preloadNextEpisodeSources = false
        useLibass = true
        libassRenderType = "OVERLAY_OPEN_GL"
        libassRenderScale = "FULL"
        libassRenderMode = "OVERLAY"
        iosVideoOutputPreset = IosVideoOutputPreset.NativeEdr
        iosToneMappingMode = IosToneMappingMode.Auto
        iosTargetPrimaries = IosTargetPrimaries.Auto
        iosTargetTransfer = IosTargetTransfer.Auto
        iosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox
        iosAudioOutputMode = IosAudioOutputMode.Auto
        iosExtendedDynamicRangeEnabled = true
        iosTargetColorspaceHintEnabled = true
        iosHdrComputePeakEnabled = true
        iosDebandEnabled = false
        iosInterpolationEnabled = false
        iosBrightness = 0
        iosContrast = 0
        iosSaturation = 0
        iosGamma = 0
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        useLegacyPlayerLayout = PlayerSettingsStorage.loadUseLegacyPlayerLayout() ?: false
        showLoadingOverlay = PlayerSettingsStorage.loadShowLoadingOverlay() ?: true
        showPlayerLoadingStatus = PlayerSettingsStorage.loadShowPlayerLoadingStatus() ?: true
        pauseOverlayEnabled = PlayerSettingsStorage.loadPauseOverlayEnabled() ?: true
        showParentalGuide = PlayerSettingsStorage.loadShowParentalGuide() ?: true
        showStreamInfo = PlayerSettingsStorage.loadShowStreamInfo() ?: true
        resizeMode = PlayerSettingsStorage.loadResizeMode()
            ?.let { runCatching { PlayerResizeMode.valueOf(it) }.getOrNull() }
            ?: PlayerResizeMode.Fit
        rememberPlayerPreferences = PlayerSettingsStorage.loadRememberPlayerPreferences()
            ?: PlayerPreferencePolicy.DEFAULT_REMEMBER
        liveBufferSeconds = LiveBufferPolicy.normalize(PlayerSettingsStorage.loadLiveBufferSeconds())
        holdToSpeedEnabled = PlayerSettingsStorage.loadHoldToSpeedEnabled() ?: true
        holdToSpeedValue = PlayerSettingsStorage.loadHoldToSpeedValue() ?: 2f
        touchGesturesEnabled = PlayerSettingsStorage.loadTouchGesturesEnabled() ?: true
        pictureInPictureEnabled = PlayerSettingsStorage.loadPictureInPictureEnabled() ?: true
        externalPlayerEnabled = PlayerSettingsStorage.loadExternalPlayerEnabled() ?: false
        externalPlayerForwardSubtitles = PlayerSettingsStorage.loadExternalPlayerForwardSubtitles() ?: false
        externalPlayerSendSkipSegments = PlayerSettingsStorage.loadExternalPlayerSendSkipSegments() ?: false
        externalPlayerId = PlayerSettingsStorage.loadExternalPlayerId()
            ?: ExternalPlayerPlatform.defaultPlayerId()
        preferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredAudioLanguage())
                ?: AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredAudioLanguage())
        preferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredSubtitleLanguage())
                ?: SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredSubtitleLanguage())
        // F47: new users get the new default look; anyone with a stored field keeps the old base.
        val storedTextColor = PlayerSettingsStorage.loadSubtitleTextColor()
        val storedBackgroundColor = PlayerSettingsStorage.loadSubtitleBackgroundColor()
        val storedOutlineColor = PlayerSettingsStorage.loadSubtitleOutlineColor()
        val storedOutlineEnabled = PlayerSettingsStorage.loadSubtitleOutlineEnabled()
        val base = SubtitleStyleDefaults.baseFor(
            anyFieldStored = storedTextColor != null || storedBackgroundColor != null ||
                storedOutlineColor != null || storedOutlineEnabled != null ||
                PlayerSettingsStorage.loadSubtitleOutlineWidth() != null ||
                PlayerSettingsStorage.loadSubtitleBold() != null ||
                PlayerSettingsStorage.loadSubtitleFontSizeSp() != null ||
                PlayerSettingsStorage.loadSubtitleBottomOffset() != null,
        )
        subtitleStyle = SubtitleStyleState(
            textColor = subtitleColorFromStorage(storedTextColor)
                ?: base.textColor,
            backgroundColor = subtitleColorFromStorage(storedBackgroundColor)
                ?: base.backgroundColor,
            outlineColor = subtitleColorFromStorage(storedOutlineColor)
                ?: base.outlineColor,
            outlineEnabled = storedOutlineEnabled
                ?: base.outlineEnabled,
            outlineWidth = PlayerSettingsStorage.loadSubtitleOutlineWidth()
                ?: base.outlineWidth,
            bold = PlayerSettingsStorage.loadSubtitleBold()
                ?: base.bold,
            fontSizeSp = (PlayerSettingsStorage.loadSubtitleFontSizeSp()
                ?: base.fontSizeSp).coerceIn(subtitleFontSizeRangeSp),
            bottomOffset = PlayerSettingsStorage.loadSubtitleBottomOffset()
                ?: base.bottomOffset,
            stripSdh = PlayerSettingsStorage.loadSubtitleStripSdh()
                ?: base.stripSdh,
            useForcedSubtitles = PlayerSettingsStorage.loadSubtitleUseForcedSubtitles()
                ?: base.useForcedSubtitles,
            showOnlyPreferredLanguages = PlayerSettingsStorage.loadSubtitleShowOnlyPreferredLanguages()
                ?: base.showOnlyPreferredLanguages,
            sideMarginPercent = (PlayerSettingsStorage.loadSubtitleSideMarginPercent()
                ?: base.sideMarginPercent).coerceIn(0, SubtitleSideMargin.MAX_PERCENT),
            appearance = decodeSubtitleAppearance(PlayerSettingsStorage.loadSubtitleAppearance()),
        )
        streamReuseLastLinkEnabled = PlayerSettingsStorage.loadStreamReuseLastLinkEnabled() ?: false
        streamReuseLastLinkCacheHours = PlayerSettingsStorage.loadStreamReuseLastLinkCacheHours() ?: 24
        androidPlaybackEngine = PlayerSettingsStorage.loadAndroidPlaybackEngine()
            ?.let { runCatching { AndroidPlaybackEngine.valueOf(it) }.getOrNull() }
            // Nothing stored -> libmpv. A user who explicitly picked Auto/ExoPlayer keeps it.
            ?: AndroidPlaybackEngine.Libmpv
        autoSwitchPlaybackEngineOnError = PlayerSettingsStorage.loadAutoSwitchPlaybackEngineOnError() ?: true
        androidLibmpvVideoOutput = PlayerSettingsStorage.loadAndroidLibmpvVideoOutput()
            ?.let { runCatching { AndroidLibmpvVideoOutput.valueOf(it) }.getOrNull() }
            // An install with NOTHING stored takes gpu. A user who explicitly picked gpu-next
            // keeps it — this only changes the default, never an expressed choice.
            ?: AndroidLibmpvVideoOutput.Gpu
        androidLibmpvHardwareDecodingEnabled = PlayerSettingsStorage.loadAndroidLibmpvHardwareDecodingEnabled() ?: true
        androidLibmpvYuv420pEnabled = PlayerSettingsStorage.loadAndroidLibmpvYuv420pEnabled() ?: false
        androidExoChaptersEnabled = PlayerSettingsStorage.loadAndroidExoChaptersEnabled() ?: false
        decoderPriority = PlayerSettingsStorage.loadDecoderPriority() ?: 1
        mapDV7ToHevc = PlayerSettingsStorage.loadMapDV7ToHevc() ?: false
        tunnelingEnabled = PlayerSettingsStorage.loadTunnelingEnabled() ?: false
        exoNativeMemoryEnabled = (PlayerSettingsStorage.loadExoNativeMemoryEnabled() ?: false) &&
            isExoNativeMemorySupported()
        vodCacheEnabled = PlayerSettingsStorage.loadVodCacheEnabled() ?: false
        vodCacheSizeMode = PlayerSettingsStorage.loadVodCacheSizeMode()
            ?.let { runCatching { VodCacheSizeMode.valueOf(it) }.getOrNull() }
            ?: VodCacheSizeMode.AUTO
        vodCacheSizeMb = (PlayerSettingsStorage.loadVodCacheSizeMb() ?: VodCacheSizing.DEFAULT_SIZE_MB)
            .coerceIn(VodCacheSizing.MIN_SIZE_MB, VodCacheSizing.MAX_SIZE_MB)
        applyExoPlayerNativeMemory(exoNativeMemoryEnabled)
        bufferEngineEnabled = PlayerSettingsStorage.loadBufferEngineEnabled() ?: false
        minBufferMs = PlayerSettingsStorage.loadMinBufferMs() ?: PlaybackBufferSettings.DEFAULT_MIN_BUFFER_MS
        maxBufferMs = (PlayerSettingsStorage.loadMaxBufferMs() ?: PlaybackBufferSettings.DEFAULT_MAX_BUFFER_MS)
            .coerceAtLeast(minBufferMs)
        bufferForPlaybackMs = PlayerSettingsStorage.loadBufferForPlaybackMs()
            ?: PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        bufferForPlaybackAfterRebufferMs = PlayerSettingsStorage.loadBufferForPlaybackAfterRebufferMs()
            ?: PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        backBufferDurationMs = PlayerSettingsStorage.loadBackBufferDurationMs()
            ?: PlaybackBufferSettings.DEFAULT_BACK_BUFFER_MS
        targetBufferSizeMb = PlayerSettingsStorage.loadTargetBufferSizeMb()
            ?: PlaybackBufferSettings.DEFAULT_TARGET_BUFFER_MB
        streamAutoPlayMode = PlayerSettingsStorage.loadStreamAutoPlayMode()
            ?.let { runCatching { StreamAutoPlayMode.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = PlayerSettingsStorage.loadStreamAutoPlaySource()
            ?.let { runCatching { StreamAutoPlaySource.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = PlayerSettingsStorage.loadStreamAutoPlaySelectedAddons() ?: emptySet()
        streamAutoPlaySelectedPlugins = PlayerSettingsStorage.loadStreamAutoPlaySelectedPlugins() ?: emptySet()
        if (!AppFeaturePolicy.pluginsEnabled) {
            val normalizedSource = normalizeStreamAutoPlaySource(streamAutoPlaySource)
            if (normalizedSource != streamAutoPlaySource) {
                streamAutoPlaySource = normalizedSource
                PlayerSettingsStorage.saveStreamAutoPlaySource(normalizedSource.name)
            }
            if (streamAutoPlaySelectedPlugins.isNotEmpty()) {
                streamAutoPlaySelectedPlugins = emptySet()
                PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(emptySet())
            }
        }
        streamAutoPlayRegex = PlayerSettingsStorage.loadStreamAutoPlayRegex() ?: ""
        streamAutoPlayTimeoutSeconds = PlayerSettingsStorage.loadStreamAutoPlayTimeoutSeconds() ?: 3
        // Legacy migration: 11 was the old sentinel for "unlimited"
        if (streamAutoPlayTimeoutSeconds == 11) {
            streamAutoPlayTimeoutSeconds = Int.MAX_VALUE
            PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(streamAutoPlayTimeoutSeconds)
        } else if (streamAutoPlayTimeoutSeconds !in STREAM_AUTO_PLAY_TIMEOUT_VALUES) {
            streamAutoPlayTimeoutSeconds = snapToAllowedTimeout(streamAutoPlayTimeoutSeconds)
            PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(streamAutoPlayTimeoutSeconds)
        }
        skipIntroEnabled = PlayerSettingsStorage.loadSkipIntroEnabled() ?: true
        autoSkipSegmentTypes = PlayerSettingsStorage.loadAutoSkipSegmentTypes()
            ?.mapNotNull(AutoSkipSegmentType::fromStoredValue)?.toSet() ?: buildSet {
                if (PlayerSettingsStorage.loadAutoSkipMovieCredits() == true) add(AutoSkipSegmentType.MOVIE_CREDITS)
            }
        animeSkipEnabled = PlayerSettingsStorage.loadAnimeSkipEnabled() ?: false
        animeSkipClientId = PlayerSettingsStorage.loadAnimeSkipClientId() ?: ""
        introDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey() ?: ""
        introSubmitEnabled = PlayerSettingsStorage.loadIntroSubmitEnabled() ?: false
        streamAutoPlayNextEpisodeEnabled = PlayerSettingsStorage.loadStreamAutoPlayNextEpisodeEnabled() ?: true
        streamAutoPlayNextEpisodeFallbackEnabled = PlayerSettingsStorage.loadStreamAutoPlayNextEpisodeFallbackEnabled() ?: true
        streamAutoPlayPreferBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayPreferBingeGroup() ?: true
        streamAutoPlayReuseBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayReuseBingeGroup() ?: false
        nextEpisodeThresholdMode = PlayerSettingsStorage.loadNextEpisodeThresholdMode()
            ?.let { runCatching { NextEpisodeThresholdMode.valueOf(it) }.getOrNull() }
            ?: NextEpisodeThresholdMode.PERCENTAGE
        nextEpisodeThresholdPercent = PlayerSettingsStorage.loadNextEpisodeThresholdPercent() ?: 99f
        nextEpisodeThresholdMinutesBeforeEnd = PlayerSettingsStorage.loadNextEpisodeThresholdMinutesBeforeEnd() ?: 2f
        preloadNextEpisodeSources = PlayerSettingsStorage.loadPreloadNextEpisodeSources() ?: false
        useLibass = PlayerSettingsStorage.loadUseLibass() ?: true
        libassRenderType = PlayerSettingsStorage.loadLibassRenderType() ?: "CUES"
        libassRenderScale = PlayerSettingsStorage.loadLibassRenderScale() ?: "FULL"
        libassRenderMode = PlayerSettingsStorage.loadLibassRenderMode() ?: "OVERLAY"
        iosVideoOutputPreset = PlayerSettingsStorage.loadIosVideoOutputPreset()
            ?.let { runCatching { IosVideoOutputPreset.valueOf(it) }.getOrNull() }
            ?: IosVideoOutputPreset.NativeEdr
        iosToneMappingMode = PlayerSettingsStorage.loadIosToneMappingMode()
            ?.let { runCatching { IosToneMappingMode.valueOf(it) }.getOrNull() }
            ?: IosToneMappingMode.Auto
        iosTargetPrimaries = PlayerSettingsStorage.loadIosTargetPrimaries()
            ?.let { runCatching { IosTargetPrimaries.valueOf(it) }.getOrNull() }
            ?: IosTargetPrimaries.Auto
        iosTargetTransfer = PlayerSettingsStorage.loadIosTargetTransfer()
            ?.let { runCatching { IosTargetTransfer.valueOf(it) }.getOrNull() }
            ?: IosTargetTransfer.Auto
        iosHardwareDecoderMode = PlayerSettingsStorage.loadIosHardwareDecoderMode()
            ?.let { runCatching { IosHardwareDecoderMode.valueOf(it) }.getOrNull() }
            ?: IosHardwareDecoderMode.VideoToolbox
        iosAudioOutputMode = IosAudioOutputMode.fromStoredName(PlayerSettingsStorage.loadIosAudioOutputMode())
        iosExtendedDynamicRangeEnabled = PlayerSettingsStorage.loadIosExtendedDynamicRangeEnabled() ?: true
        iosTargetColorspaceHintEnabled = PlayerSettingsStorage.loadIosTargetColorspaceHintEnabled() ?: true
        iosHdrComputePeakEnabled = PlayerSettingsStorage.loadIosHdrComputePeakEnabled() ?: true
        iosDebandEnabled = PlayerSettingsStorage.loadIosDebandEnabled() ?: false
        iosInterpolationEnabled = PlayerSettingsStorage.loadIosInterpolationEnabled() ?: false
        iosBrightness = PlayerSettingsStorage.loadIosBrightness() ?: 0
        iosContrast = PlayerSettingsStorage.loadIosContrast() ?: 0
        iosSaturation = PlayerSettingsStorage.loadIosSaturation() ?: 0
        iosGamma = PlayerSettingsStorage.loadIosGamma() ?: 0
        publish()
    }

    fun setUseLegacyPlayerLayout(enabled: Boolean) {
        ensureLoaded()
        if (useLegacyPlayerLayout == enabled) return
        useLegacyPlayerLayout = enabled
        publish()
        PlayerSettingsStorage.saveUseLegacyPlayerLayout(enabled)
    }

    fun setShowLoadingOverlay(enabled: Boolean) {
        ensureLoaded()
        if (showLoadingOverlay == enabled) return
        showLoadingOverlay = enabled
        publish()
        PlayerSettingsStorage.saveShowLoadingOverlay(enabled)
    }

    fun setShowPlayerLoadingStatus(enabled: Boolean) {
        ensureLoaded()
        if (showPlayerLoadingStatus == enabled) return
        showPlayerLoadingStatus = enabled
        publish()
        PlayerSettingsStorage.saveShowPlayerLoadingStatus(enabled)
    }

    fun setPauseOverlayEnabled(enabled: Boolean) {
        ensureLoaded()
        if (pauseOverlayEnabled == enabled) return
        pauseOverlayEnabled = enabled
        publish()
        PlayerSettingsStorage.savePauseOverlayEnabled(enabled)
    }

    fun setShowParentalGuide(enabled: Boolean) {
        ensureLoaded()
        if (showParentalGuide == enabled) return
        showParentalGuide = enabled
        publish()
        PlayerSettingsStorage.saveShowParentalGuide(enabled)
    }

    fun setShowStreamInfo(enabled: Boolean) {
        ensureLoaded()
        if (showStreamInfo == enabled) return
        showStreamInfo = enabled
        publish()
        PlayerSettingsStorage.saveShowStreamInfo(enabled)
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        ensureLoaded()
        if (resizeMode == mode) return
        resizeMode = mode
        publish()
        PlayerSettingsStorage.saveResizeMode(mode.name)
    }

    fun setRememberPlayerPreferences(enabled: Boolean) {
        ensureLoaded()
        if (rememberPlayerPreferences == enabled) return
        rememberPlayerPreferences = enabled
        publish()
        PlayerSettingsStorage.saveRememberPlayerPreferences(enabled)
    }

    fun setLiveBufferSeconds(seconds: Int) {
        ensureLoaded()
        val normalized = LiveBufferPolicy.normalize(seconds)
        if (liveBufferSeconds == normalized) return
        liveBufferSeconds = normalized
        publish()
        PlayerSettingsStorage.saveLiveBufferSeconds(normalized)
    }

    fun setHoldToSpeedEnabled(enabled: Boolean) {
        ensureLoaded()
        if (holdToSpeedEnabled == enabled) return
        holdToSpeedEnabled = enabled
        publish()
        PlayerSettingsStorage.saveHoldToSpeedEnabled(enabled)
    }

    fun setHoldToSpeedValue(speed: Float) {
        ensureLoaded()
        val normalized = speed.coerceIn(1f, 4f)
        if (holdToSpeedValue == normalized) return
        holdToSpeedValue = normalized
        publish()
        PlayerSettingsStorage.saveHoldToSpeedValue(normalized)
    }

    fun setTouchGesturesEnabled(enabled: Boolean) {
        ensureLoaded()
        if (touchGesturesEnabled == enabled) return
        touchGesturesEnabled = enabled
        publish()
        PlayerSettingsStorage.saveTouchGesturesEnabled(enabled)
    }

    fun setPictureInPictureEnabled(enabled: Boolean) {
        ensureLoaded()
        if (pictureInPictureEnabled == enabled) return
        pictureInPictureEnabled = enabled
        publish()
        PlayerSettingsStorage.savePictureInPictureEnabled(enabled)
    }

    fun setExternalPlayerEnabled(enabled: Boolean) {
        ensureLoaded()
        if (enabled && externalPlayerId.isNullOrBlank()) {
            externalPlayerId = ExternalPlayerPlatform.defaultPlayerId()
                ?: ExternalPlayerPlatform.availablePlayers().firstOrNull()?.id
            PlayerSettingsStorage.saveExternalPlayerId(externalPlayerId)
        }
        if (externalPlayerEnabled == enabled) {
            publish()
            return
        }
        externalPlayerEnabled = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerEnabled(enabled)
    }

    fun setExternalPlayerId(playerId: String?) {
        ensureLoaded()
        val normalized = playerId?.takeIf { it.isNotBlank() }
        if (externalPlayerId == normalized) return
        externalPlayerId = normalized
        publish()
        PlayerSettingsStorage.saveExternalPlayerId(normalized)
    }

    fun setExternalPlayerForwardSubtitles(enabled: Boolean) {
        ensureLoaded()
        if (externalPlayerForwardSubtitles == enabled) return
        externalPlayerForwardSubtitles = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerForwardSubtitles(enabled)
    }

    fun setExternalPlayerSendSkipSegments(enabled: Boolean) {
        ensureLoaded()
        if (externalPlayerSendSkipSegments == enabled) return
        externalPlayerSendSkipSegments = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerSendSkipSegments(enabled)
    }

    fun setPreferredAudioLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: AudioLanguageOption.DEVICE
        if (preferredAudioLanguage == normalized) return
        preferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredAudioLanguage(normalized)
    }

    fun setSecondaryPreferredAudioLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredAudioLanguage == normalized) return
        secondaryPreferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredAudioLanguage(normalized)
    }

    fun setPreferredSubtitleLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: SubtitleLanguageOption.NONE
        if (preferredSubtitleLanguage == normalized) return
        preferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredSubtitleLanguage(normalized)
    }

    fun setSecondaryPreferredSubtitleLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredSubtitleLanguage == normalized) return
        secondaryPreferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredSubtitleLanguage(normalized)
    }

    fun setSubtitleStyle(style: SubtitleStyleState) {
        ensureLoaded()
        val normalized = style.copy(
            fontSizeSp = style.fontSizeSp.coerceIn(subtitleFontSizeRangeSp),
            sideMarginPercent = style.sideMarginPercent.coerceIn(0, SubtitleSideMargin.MAX_PERCENT),
            bottomOffset = style.bottomOffset.coerceIn(0, 200),
            appearance = style.appearance.normalized(),
        )
        if (subtitleStyle == normalized) return
        subtitleStyle = normalized
        publish()
        PlayerSettingsStorage.saveSubtitleTextColor(normalized.textColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleBackgroundColor(normalized.backgroundColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleAppearance(normalized.appearance.encode())
        PlayerSettingsStorage.saveSubtitleOutlineColor(normalized.outlineColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleOutlineEnabled(normalized.outlineEnabled)
        PlayerSettingsStorage.saveSubtitleOutlineWidth(normalized.outlineWidth)
        PlayerSettingsStorage.saveSubtitleBold(normalized.bold)
        PlayerSettingsStorage.saveSubtitleFontSizeSp(normalized.fontSizeSp)
        PlayerSettingsStorage.saveSubtitleBottomOffset(normalized.bottomOffset)
        PlayerSettingsStorage.saveSubtitleStripSdh(normalized.stripSdh)
        PlayerSettingsStorage.saveSubtitleUseForcedSubtitles(normalized.useForcedSubtitles)
        PlayerSettingsStorage.saveSubtitleShowOnlyPreferredLanguages(normalized.showOnlyPreferredLanguages)
        PlayerSettingsStorage.saveSubtitleSideMarginPercent(normalized.sideMarginPercent)
    }

    fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamReuseLastLinkEnabled == enabled) return
        streamReuseLastLinkEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkEnabled(enabled)
    }

    fun setStreamReuseLastLinkCacheHours(hours: Int) {
        ensureLoaded()
        if (streamReuseLastLinkCacheHours == hours) return
        streamReuseLastLinkCacheHours = hours
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkCacheHours(hours)
    }

    fun setAndroidPlaybackEngine(engine: AndroidPlaybackEngine) {
        ensureLoaded()
        if (androidPlaybackEngine == engine) return
        androidPlaybackEngine = engine
        publish()
        PlayerSettingsStorage.saveAndroidPlaybackEngine(engine.name)
    }

    fun setAutoSwitchPlaybackEngineOnError(enabled: Boolean) {
        ensureLoaded()
        if (autoSwitchPlaybackEngineOnError == enabled) return
        autoSwitchPlaybackEngineOnError = enabled
        publish()
        PlayerSettingsStorage.saveAutoSwitchPlaybackEngineOnError(enabled)
    }

    fun setAndroidLibmpvVideoOutput(output: AndroidLibmpvVideoOutput) {
        ensureLoaded()
        if (androidLibmpvVideoOutput == output) return
        androidLibmpvVideoOutput = output
        publish()
        PlayerSettingsStorage.saveAndroidLibmpvVideoOutput(output.name)
    }

    fun setAndroidLibmpvHardwareDecodingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (androidLibmpvHardwareDecodingEnabled == enabled) return
        androidLibmpvHardwareDecodingEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAndroidLibmpvHardwareDecodingEnabled(enabled)
    }

    fun setAndroidLibmpvYuv420pEnabled(enabled: Boolean) {
        ensureLoaded()
        if (androidLibmpvYuv420pEnabled == enabled) return
        androidLibmpvYuv420pEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAndroidLibmpvYuv420pEnabled(enabled)
    }

    fun setAndroidExoChaptersEnabled(enabled: Boolean) {
        ensureLoaded()
        if (androidExoChaptersEnabled == enabled) return
        androidExoChaptersEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAndroidExoChaptersEnabled(enabled)
    }

    fun setDecoderPriority(priority: Int) {
        ensureLoaded()
        if (decoderPriority == priority) return
        decoderPriority = priority
        publish()
        PlayerSettingsStorage.saveDecoderPriority(priority)
    }

    fun setMapDV7ToHevc(enabled: Boolean) {
        ensureLoaded()
        if (mapDV7ToHevc == enabled) return
        mapDV7ToHevc = enabled
        publish()
        PlayerSettingsStorage.saveMapDV7ToHevc(enabled)
    }

    fun setTunnelingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (tunnelingEnabled == enabled) return
        tunnelingEnabled = enabled
        publish()
        PlayerSettingsStorage.saveTunnelingEnabled(enabled)
    }

    fun setExoNativeMemoryEnabled(enabled: Boolean) {
        ensureLoaded()
        val supported = enabled && isExoNativeMemorySupported()
        if (exoNativeMemoryEnabled == supported) return
        exoNativeMemoryEnabled = supported
        resetBufferSettingsToDefaults()
        applyExoPlayerNativeMemory(supported)
        publish()
        PlayerSettingsStorage.saveExoNativeMemoryEnabled(supported)
    }

    private fun resetBufferSettingsToDefaults() {
        minBufferMs = PlaybackBufferSettings.DEFAULT_MIN_BUFFER_MS
        maxBufferMs = PlaybackBufferSettings.DEFAULT_MAX_BUFFER_MS
        bufferForPlaybackMs = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        bufferForPlaybackAfterRebufferMs = PlaybackBufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        backBufferDurationMs = PlaybackBufferSettings.DEFAULT_BACK_BUFFER_MS
        targetBufferSizeMb = PlaybackBufferSettings.DEFAULT_TARGET_BUFFER_MB
        PlayerSettingsStorage.saveMinBufferMs(minBufferMs)
        PlayerSettingsStorage.saveMaxBufferMs(maxBufferMs)
        PlayerSettingsStorage.saveBufferForPlaybackMs(bufferForPlaybackMs)
        PlayerSettingsStorage.saveBufferForPlaybackAfterRebufferMs(bufferForPlaybackAfterRebufferMs)
        PlayerSettingsStorage.saveBackBufferDurationMs(backBufferDurationMs)
        PlayerSettingsStorage.saveTargetBufferSizeMb(targetBufferSizeMb)
    }

    fun setBufferEngineEnabled(enabled: Boolean) {
        ensureLoaded()
        if (bufferEngineEnabled == enabled) return
        bufferEngineEnabled = enabled
        publish()
        PlayerSettingsStorage.saveBufferEngineEnabled(enabled)
    }

    fun setMinBufferMs(value: Int) {
        ensureLoaded()
        val coerced = value.coerceAtLeast(PlaybackBufferSettings.MIN_DURATION_SEC * 1_000)
        if (minBufferMs == coerced && maxBufferMs >= coerced) return
        minBufferMs = coerced
        if (maxBufferMs < coerced) {
            maxBufferMs = coerced
            PlayerSettingsStorage.saveMaxBufferMs(coerced)
        }
        publish()
        PlayerSettingsStorage.saveMinBufferMs(coerced)
    }

    fun setMaxBufferMs(value: Int) {
        ensureLoaded()
        val coerced = value.coerceAtLeast(minBufferMs)
        if (maxBufferMs == coerced) return
        maxBufferMs = coerced
        publish()
        PlayerSettingsStorage.saveMaxBufferMs(coerced)
    }

    fun setBufferForPlaybackMs(value: Int) {
        ensureLoaded()
        val coerced = value.coerceIn(
            PlaybackBufferSettings.MIN_START_SEC * 1_000,
            PlaybackBufferSettings.MAX_START_SEC * 1_000,
        )
        if (bufferForPlaybackMs == coerced) return
        bufferForPlaybackMs = coerced
        publish()
        PlayerSettingsStorage.saveBufferForPlaybackMs(coerced)
    }

    fun setBufferForPlaybackAfterRebufferMs(value: Int) {
        ensureLoaded()
        val coerced = value.coerceIn(
            PlaybackBufferSettings.MIN_START_SEC * 1_000,
            PlaybackBufferSettings.MAX_REBUFFER_SEC * 1_000,
        )
        if (bufferForPlaybackAfterRebufferMs == coerced) return
        bufferForPlaybackAfterRebufferMs = coerced
        publish()
        PlayerSettingsStorage.saveBufferForPlaybackAfterRebufferMs(coerced)
    }

    fun setBackBufferDurationMs(value: Int) {
        ensureLoaded()
        val coerced = value.coerceIn(0, PlaybackBufferSettings.MAX_BACK_SEC * 1_000)
        if (backBufferDurationMs == coerced) return
        backBufferDurationMs = coerced
        publish()
        PlayerSettingsStorage.saveBackBufferDurationMs(coerced)
    }

    fun setTargetBufferSizeMb(value: Int) {
        ensureLoaded()
        val coerced = value.coerceAtLeast(PlaybackBufferSettings.MIN_TARGET_MB)
        if (targetBufferSizeMb == coerced) return
        targetBufferSizeMb = coerced
        publish()
        PlayerSettingsStorage.saveTargetBufferSizeMb(coerced)
    }

    fun setVodCacheEnabled(enabled: Boolean) {
        ensureLoaded()
        if (vodCacheEnabled == enabled) return
        vodCacheEnabled = enabled
        publish()
        PlayerSettingsStorage.saveVodCacheEnabled(enabled)
    }

    fun setVodCacheSizeMode(mode: VodCacheSizeMode) {
        ensureLoaded()
        if (vodCacheSizeMode == mode) return
        vodCacheSizeMode = mode
        publish()
        PlayerSettingsStorage.saveVodCacheSizeMode(mode.name)
    }

    fun setVodCacheSizeMb(sizeMb: Int) {
        ensureLoaded()
        val coerced = sizeMb.coerceIn(VodCacheSizing.MIN_SIZE_MB, VodCacheSizing.MAX_SIZE_MB)
        if (vodCacheSizeMb == coerced) return
        vodCacheSizeMb = coerced
        publish()
        PlayerSettingsStorage.saveVodCacheSizeMb(coerced)
    }

    fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        ensureLoaded()
        if (streamAutoPlayMode == mode) return
        streamAutoPlayMode = mode
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayMode(mode.name)
    }

    fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        ensureLoaded()
        val normalizedSource = normalizeStreamAutoPlaySource(source)
        if (streamAutoPlaySource == normalizedSource) return
        streamAutoPlaySource = normalizedSource
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySource(normalizedSource.name)
    }

    fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        ensureLoaded()
        if (streamAutoPlaySelectedAddons == addons) return
        streamAutoPlaySelectedAddons = addons
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedAddons(addons)
    }

    fun setStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        ensureLoaded()
        val normalizedPlugins = if (AppFeaturePolicy.pluginsEnabled) plugins else emptySet()
        if (streamAutoPlaySelectedPlugins == normalizedPlugins) return
        streamAutoPlaySelectedPlugins = normalizedPlugins
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(normalizedPlugins)
    }

    fun setStreamAutoPlayRegex(regex: String) {
        ensureLoaded()
        if (streamAutoPlayRegex == regex) return
        streamAutoPlayRegex = regex
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayRegex(regex)
    }

    fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        ensureLoaded()
        if (streamAutoPlayTimeoutSeconds == seconds) return
        streamAutoPlayTimeoutSeconds = seconds
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(seconds)
    }

    fun setSkipIntroEnabled(enabled: Boolean) {
        ensureLoaded()
        if (skipIntroEnabled == enabled) return
        skipIntroEnabled = enabled
        publish()
        PlayerSettingsStorage.saveSkipIntroEnabled(enabled)
    }

    fun setAutoSkipSegmentTypeEnabled(segmentType: AutoSkipSegmentType, enabled: Boolean) {
        ensureLoaded()
        val updated = if (enabled) autoSkipSegmentTypes + segmentType else autoSkipSegmentTypes - segmentType
        if (autoSkipSegmentTypes == updated) return
        autoSkipSegmentTypes = updated
        publish()
        PlayerSettingsStorage.saveAutoSkipSegmentTypes(updated.mapTo(linkedSetOf()) { it.storedValue })
    }

    fun setAnimeSkipEnabled(enabled: Boolean) {
        ensureLoaded()
        if (animeSkipEnabled == enabled) return
        animeSkipEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAnimeSkipEnabled(enabled)
    }

    fun setAnimeSkipClientId(clientId: String) {
        ensureLoaded()
        if (animeSkipClientId == clientId) return
        animeSkipClientId = clientId
        publish()
        PlayerSettingsStorage.saveAnimeSkipClientId(clientId)
    }

    fun setIntroDbApiKey(apiKey: String) {
        ensureLoaded()
        if (introDbApiKey == apiKey) return
        introDbApiKey = apiKey
        publish()
        PlayerSettingsStorage.saveIntroDbApiKey(apiKey)
    }

    fun setIntroSubmitEnabled(enabled: Boolean) {
        ensureLoaded()
        if (introSubmitEnabled == enabled) return
        introSubmitEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIntroSubmitEnabled(enabled)
    }

    fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayNextEpisodeEnabled == enabled) return
        streamAutoPlayNextEpisodeEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayNextEpisodeEnabled(enabled)
    }

    fun setStreamAutoPlayNextEpisodeFallbackEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayNextEpisodeFallbackEnabled == enabled) return
        streamAutoPlayNextEpisodeFallbackEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayNextEpisodeFallbackEnabled(enabled)
    }

    fun setStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayPreferBingeGroup == enabled) return
        streamAutoPlayPreferBingeGroup = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayPreferBingeGroup(enabled)
    }

    fun setStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayReuseBingeGroup == enabled) return
        streamAutoPlayReuseBingeGroup = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayReuseBingeGroup(enabled)
    }

    fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        ensureLoaded()
        if (nextEpisodeThresholdMode == mode) return
        nextEpisodeThresholdMode = mode
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdMode(mode.name)
    }

    fun setNextEpisodeThresholdPercent(percent: Float) {
        ensureLoaded()
        if (nextEpisodeThresholdPercent == percent) return
        nextEpisodeThresholdPercent = percent
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdPercent(percent)
    }

    fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        ensureLoaded()
        if (nextEpisodeThresholdMinutesBeforeEnd == minutes) return
        nextEpisodeThresholdMinutesBeforeEnd = minutes
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdMinutesBeforeEnd(minutes)
    }

    fun setPreloadNextEpisodeSources(enabled: Boolean) {
        ensureLoaded()
        if (preloadNextEpisodeSources == enabled) return
        preloadNextEpisodeSources = enabled
        publish()
        PlayerSettingsStorage.savePreloadNextEpisodeSources(enabled)
    }

    fun setUseLibass(enabled: Boolean) {
        ensureLoaded()
        if (useLibass == enabled) return
        useLibass = enabled
        publish()
        PlayerSettingsStorage.saveUseLibass(enabled)
    }

    fun setLibassRenderType(renderType: String) {
        ensureLoaded()
        if (libassRenderType == renderType) return
        libassRenderType = renderType
        publish()
        PlayerSettingsStorage.saveLibassRenderType(renderType)
    }

    fun setLibassRenderScale(scale: String) {
        ensureLoaded()
        if (libassRenderScale == scale) return
        libassRenderScale = scale
        publish()
        PlayerSettingsStorage.saveLibassRenderScale(scale)
    }

    fun setLibassRenderMode(mode: String) {
        ensureLoaded()
        if (libassRenderMode == mode) return
        libassRenderMode = mode
        publish()
        PlayerSettingsStorage.saveLibassRenderMode(mode)
    }

    fun setIosVideoOutputPreset(preset: IosVideoOutputPreset) {
        ensureLoaded()
        iosVideoOutputPreset = preset
        when (preset) {
            IosVideoOutputPreset.NativeEdr -> {
                iosExtendedDynamicRangeEnabled = true
                iosTargetColorspaceHintEnabled = true
                iosHdrComputePeakEnabled = true
                iosToneMappingMode = IosToneMappingMode.Auto
                iosTargetPrimaries = IosTargetPrimaries.Auto
                iosTargetTransfer = IosTargetTransfer.Auto
            }
            IosVideoOutputPreset.SdrToneMapped -> {
                iosExtendedDynamicRangeEnabled = false
                iosTargetColorspaceHintEnabled = false
                iosHdrComputePeakEnabled = true
                iosToneMappingMode = IosToneMappingMode.Bt2390
                iosTargetPrimaries = IosTargetPrimaries.Bt709
                iosTargetTransfer = IosTargetTransfer.Srgb
            }
            IosVideoOutputPreset.Compatibility -> {
                iosExtendedDynamicRangeEnabled = false
                iosTargetColorspaceHintEnabled = true
                iosHdrComputePeakEnabled = false
                iosToneMappingMode = IosToneMappingMode.Auto
                iosTargetPrimaries = IosTargetPrimaries.Auto
                iosTargetTransfer = IosTargetTransfer.Auto
            }
            IosVideoOutputPreset.Custom -> Unit
        }
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosToneMappingMode(mode: IosToneMappingMode) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosToneMappingMode = mode
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetPrimaries(primaries: IosTargetPrimaries) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetPrimaries = primaries
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetTransfer(transfer: IosTargetTransfer) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetTransfer = transfer
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosHardwareDecoderMode(mode: IosHardwareDecoderMode) {
        ensureLoaded()
        iosHardwareDecoderMode = mode
        publish()
        PlayerSettingsStorage.saveIosHardwareDecoderMode(mode.name)
    }

    fun setIosAudioOutputMode(mode: IosAudioOutputMode) {
        ensureLoaded()
        iosAudioOutputMode = mode.takeUnless { it == IosAudioOutputMode.AvFoundation } ?: IosAudioOutputMode.Auto
        publish()
        PlayerSettingsStorage.saveIosAudioOutputMode(iosAudioOutputMode.name)
    }

    fun setIosExtendedDynamicRangeEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosExtendedDynamicRangeEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetColorspaceHintEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetColorspaceHintEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosHdrComputePeakEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosHdrComputePeakEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosDebandEnabled(enabled: Boolean) {
        ensureLoaded()
        iosDebandEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIosDebandEnabled(enabled)
    }

    fun setIosInterpolationEnabled(enabled: Boolean) {
        ensureLoaded()
        iosInterpolationEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIosInterpolationEnabled(enabled)
    }

    fun setIosBrightness(value: Int) {
        ensureLoaded()
        iosBrightness = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosBrightness(iosBrightness)
    }

    fun setIosContrast(value: Int) {
        ensureLoaded()
        iosContrast = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosContrast(iosContrast)
    }

    fun setIosSaturation(value: Int) {
        ensureLoaded()
        iosSaturation = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosSaturation(iosSaturation)
    }

    fun setIosGamma(value: Int) {
        ensureLoaded()
        iosGamma = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosGamma(iosGamma)
    }

    fun resetIosVideoOutputTuning() {
        ensureLoaded()
        iosBrightness = 0
        iosContrast = 0
        iosSaturation = 0
        iosGamma = 0
        iosDebandEnabled = false
        iosInterpolationEnabled = false
        publish()
        PlayerSettingsStorage.saveIosBrightness(0)
        PlayerSettingsStorage.saveIosContrast(0)
        PlayerSettingsStorage.saveIosSaturation(0)
        PlayerSettingsStorage.saveIosGamma(0)
        PlayerSettingsStorage.saveIosDebandEnabled(false)
        PlayerSettingsStorage.saveIosInterpolationEnabled(false)
    }

    private fun saveIosVideoOutputSettings() {
        PlayerSettingsStorage.saveIosVideoOutputPreset(iosVideoOutputPreset.name)
        PlayerSettingsStorage.saveIosToneMappingMode(iosToneMappingMode.name)
        PlayerSettingsStorage.saveIosTargetPrimaries(iosTargetPrimaries.name)
        PlayerSettingsStorage.saveIosTargetTransfer(iosTargetTransfer.name)
        PlayerSettingsStorage.saveIosExtendedDynamicRangeEnabled(iosExtendedDynamicRangeEnabled)
        PlayerSettingsStorage.saveIosTargetColorspaceHintEnabled(iosTargetColorspaceHintEnabled)
        PlayerSettingsStorage.saveIosHdrComputePeakEnabled(iosHdrComputePeakEnabled)
    }

    private fun publish() {
        _uiState.value = PlayerSettingsUiState(
            useLegacyPlayerLayout = useLegacyPlayerLayout,
            showLoadingOverlay = showLoadingOverlay,
            showPlayerLoadingStatus = showPlayerLoadingStatus,
            pauseOverlayEnabled = pauseOverlayEnabled,
            showParentalGuide = showParentalGuide,
            showStreamInfo = showStreamInfo,
            resizeMode = resizeMode,
            rememberPlayerPreferences = rememberPlayerPreferences,
            liveBufferSeconds = liveBufferSeconds,
            holdToSpeedEnabled = holdToSpeedEnabled,
            holdToSpeedValue = holdToSpeedValue,
            touchGesturesEnabled = touchGesturesEnabled,
            pictureInPictureEnabled = pictureInPictureEnabled,
            externalPlayerEnabled = externalPlayerEnabled,
            externalPlayerForwardSubtitles = externalPlayerForwardSubtitles,
            externalPlayerSendSkipSegments = externalPlayerSendSkipSegments,
            externalPlayerId = externalPlayerId,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            subtitleStyle = subtitleStyle,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
            androidPlaybackEngine = androidPlaybackEngine,
            autoSwitchPlaybackEngineOnError = autoSwitchPlaybackEngineOnError,
            androidLibmpvVideoOutput = androidLibmpvVideoOutput,
            androidLibmpvHardwareDecodingEnabled = androidLibmpvHardwareDecodingEnabled,
            androidLibmpvYuv420pEnabled = androidLibmpvYuv420pEnabled,
            androidExoChaptersEnabled = androidExoChaptersEnabled,
            decoderPriority = decoderPriority,
            mapDV7ToHevc = mapDV7ToHevc,
            tunnelingEnabled = tunnelingEnabled,
            exoNativeMemoryEnabled = exoNativeMemoryEnabled,
            bufferEngineEnabled = bufferEngineEnabled,
            minBufferMs = minBufferMs,
            maxBufferMs = maxBufferMs,
            bufferForPlaybackMs = bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs,
            backBufferDurationMs = backBufferDurationMs,
            targetBufferSizeMb = targetBufferSizeMb,
            vodCacheEnabled = vodCacheEnabled,
            vodCacheSizeMode = vodCacheSizeMode,
            vodCacheSizeMb = vodCacheSizeMb,
            streamAutoPlayMode = streamAutoPlayMode,
            streamAutoPlaySource = streamAutoPlaySource,
            streamAutoPlaySelectedAddons = streamAutoPlaySelectedAddons,
            streamAutoPlaySelectedPlugins = streamAutoPlaySelectedPlugins,
            streamAutoPlayRegex = streamAutoPlayRegex,
            streamAutoPlayTimeoutSeconds = streamAutoPlayTimeoutSeconds,
            skipIntroEnabled = skipIntroEnabled,
            autoSkipSegmentTypes = autoSkipSegmentTypes,
            animeSkipEnabled = animeSkipEnabled,
            animeSkipClientId = animeSkipClientId,
            introDbApiKey = introDbApiKey,
            introSubmitEnabled = introSubmitEnabled,
            streamAutoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayNextEpisodeFallbackEnabled = streamAutoPlayNextEpisodeFallbackEnabled,
            streamAutoPlayPreferBingeGroup = streamAutoPlayPreferBingeGroup,
            streamAutoPlayReuseBingeGroup = streamAutoPlayReuseBingeGroup,
            nextEpisodeThresholdMode = nextEpisodeThresholdMode,
            nextEpisodeThresholdPercent = nextEpisodeThresholdPercent,
            nextEpisodeThresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEnd,
            preloadNextEpisodeSources = preloadNextEpisodeSources,
            useLibass = useLibass,
            libassRenderType = libassRenderType,
            libassRenderScale = libassRenderScale,
            libassRenderMode = libassRenderMode,
            iosVideoOutputPreset = iosVideoOutputPreset,
            iosToneMappingMode = iosToneMappingMode,
            iosTargetPrimaries = iosTargetPrimaries,
            iosTargetTransfer = iosTargetTransfer,
            iosHardwareDecoderMode = iosHardwareDecoderMode,
            iosAudioOutputMode = iosAudioOutputMode,
            iosExtendedDynamicRangeEnabled = iosExtendedDynamicRangeEnabled,
            iosTargetColorspaceHintEnabled = iosTargetColorspaceHintEnabled,
            iosHdrComputePeakEnabled = iosHdrComputePeakEnabled,
            iosDebandEnabled = iosDebandEnabled,
            iosInterpolationEnabled = iosInterpolationEnabled,
            iosBrightness = iosBrightness,
            iosContrast = iosContrast,
            iosSaturation = iosSaturation,
            iosGamma = iosGamma,
        )
    }

    private fun normalizeStreamAutoPlaySource(source: StreamAutoPlaySource): StreamAutoPlaySource {
        return if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.ALL_SOURCES
        } else {
            source
        }
    }
}
