package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.SpannableString
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.app.R
import com.nuvio.app.AppExitReporter
import com.nuvio.app.core.analytics.MpvVideoOutputSignal
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTier
import com.nuvio.app.features.streams.normalizeStreamType
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private const val TAG = "NuvioPlayer"


/**
 * Codec label for the stream info panel. ExoPlayer describes a track by MIME type; strip
 * it back to the short name and run it through the shared table so ExoPlayer and libmpv
 * agree on how a codec is spelled.
 */
private fun Format.displayCodecName(): String? {
    val fromMime = sampleMimeType
        ?.substringAfter('/', "")
        ?.takeIf { it.isNotBlank() }
        ?.removePrefix("x-")
    // RFC 6381 strings ("avc1.640028", "mp4a.40.2") carry the codec before the first dot.
    val fromCodecs = codecs?.substringBefore('.')?.trim()?.takeIf { it.isNotBlank() }
    return StreamCodecNames.display(fromMime ?: fromCodecs)
}
private const val PLAYER_DIAGNOSTIC_TAG = "NuvioPlayerDiag"

private class PlaybackDiagnostics {
    var prepareStartedAtMs: Long = 0L
    var attempt: Int = 0
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    isCatchUpPlayback: Boolean,
    playbackSurface: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val playerSettings = remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState.value
    }
    val playerSourceKey = listOf(
        sourceUrl,
        sourceAudioUrl.orEmpty(),
        sanitizePlaybackHeaders(sourceHeaders),
        sanitizePlaybackResponseHeaders(sourceResponseHeaders),
        normalizeStreamType(streamType).orEmpty(),
        useYoutubeChunkedPlayback,
        initialPositionRequestKey.orEmpty(),
    )
    // Live no longer force-selects libmpv. The old reason ("raw continuous MPEG-TS, which ExoPlayer
    // can't sustain — buffers forever") is handled now that the extractor sets
    // FLAG_DETECT_ACCESS_UNITS | FLAG_ALLOW_NON_IDR_KEYFRAMES (see LIVE_TS_EXTRACTOR_FLAGS); the
    // premise was never actually tested, because those flags had never been set on mobile.
    //
    // Forcing libmpv dragged every live stream through libplacebo — one leaked sync-file fd per
    // rendered frame, measured at ~25/sec on a Galaxy S24 Ultra, EMFILE at 32768 in ~22 minutes —
    // and through a flat 96MB mpv demuxer cache gated on API level rather than device memory, which
    // is a large slice of a budget phone. ExoPlayer's buffer is already memory-tier aware
    // (playerTargetBufferBytes), so live inherits correct sizing for free.
    //
    // NuvioTV made this same migration already. Live now respects the engine setting
    // (Auto -> ExoPlayer); the startup failover below still switches a stream to libmpv if it
    // genuinely cannot sustain on ExoPlayer.
    // ponytail: if a codec class regresses on ExoPlayer, narrow the force back BY CODEC, not by "live".
    var activeEngine by remember(playerSourceKey, playerSettings.androidPlaybackEngine) {
        val base = playerSettings.androidPlaybackEngine.initialAndroidEngine()
        // Fix 2 (telemetry-derived, 2026-08-25): open live on libmpv on the hardware decoders that
        // video-stall on live TS far above the fleet baseline (MediaTek MT8696, Amlogic Onn 4K
        // Streaming Box), even when the resolved engine is ExoPlayer. Live only; device-gated
        // (LiveHardwareDecoderPolicy, narrow + tunable). Android already defaults to libmpv, so this
        // only bites the Auto/ExoPlayer users. NuvioTV made the same change.
        val gated = if (base == ResolvedAndroidPlaybackEngine.ExoPlayer &&
            normalizeStreamType(streamType) == "live" &&
            LiveHardwareDecoderProbe.preferLibmpvForLive()
        ) {
            ResolvedAndroidPlaybackEngine.Libmpv
        } else {
            base
        }
        mutableStateOf(gated)
    }

    when (activeEngine) {
        ResolvedAndroidPlaybackEngine.ExoPlayer -> ExoPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            sourceResponseHeaders = sourceResponseHeaders,
            externalSubtitles = externalSubtitles,
            streamType = streamType,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            modifier = modifier,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            initialPositionRequestKey = initialPositionRequestKey,
            resizeMode = resizeMode,
            useNativeController = useNativeController,
            onInitialPositionHandled = onInitialPositionHandled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = { message, linkAuthFailure ->
                // A 401/403/410 is the LINK being refused, not a decoding problem — libmpv would be
                // handed the same dead URL and fail identically. Worse, swallowing it as `null` here
                // hid it from the screen's expired-link recovery (which only runs for a non-null
                // message), so an IPTV token/stream-id failure could never self-heal on Android.
                // Pass those straight through; keep the engine failover for real playback failures.
                if (message != null && !linkAuthFailure &&
                    playerSettings.androidPlaybackEngine == AndroidPlaybackEngine.Auto
                ) {
                    Log.w(TAG, "ExoPlayer failed; falling back to libmpv: $message")
                    initialPositionRequestKey?.let { key ->
                        onInitialPositionHandled(key, false)
                    }
                    activeEngine = ResolvedAndroidPlaybackEngine.Libmpv
                    onError(null)
                } else {
                    onError(message)
                }
            },
        )
        ResolvedAndroidPlaybackEngine.Libmpv -> {
            LaunchedEffect(initialPositionRequestKey) {
                initialPositionRequestKey?.let { key ->
                    onInitialPositionHandled(key, false)
                }
            }
            LibmpvPlayerSurface(
                sourceUrl = sourceUrl,
                sourceAudioUrl = sourceAudioUrl,
                sourceHeaders = sourceHeaders,
                externalSubtitles = externalSubtitles,
                // The ENGINE choice stays live (a replay arrives down the same pipe, so it
                // needs libmpv exactly as much), but this flag drives rejoin-the-live-edge on
                // surface return and zeroes the reported duration. Both are wrong for a
                // recording: the first throws away the viewer's position, the second hides the
                // timeline the replay actually has.
                isLiveStream = LivePlaybackRejoinPolicy.rejoinsLiveEdge(streamType, isCatchUpPlayback),
                modifier = modifier,
                playWhenReady = playWhenReady,
                resizeMode = resizeMode,
                // Routed through the policy: on the live path it downgrades a leaking gpu-next to
                // gpu (the fence-fd leak fix — see LiveVideoOutputPolicy); off the live path it
                // passes the user's renderer through unchanged.
                videoOutput = LiveVideoOutputPolicy.videoOutputFor(
                    isLive = normalizeStreamType(streamType) == "live",
                    isCatchUpPlayback = isCatchUpPlayback,
                    surface = playbackSurface,
                    userPreference = playerSettings.androidLibmpvVideoOutput.mpvValue,
                ),
                hardwareDecodingEnabled = playerSettings.androidLibmpvHardwareDecodingEnabled,
                yuv420pEnabled = playerSettings.androidLibmpvYuv420pEnabled,
                onControllerReady = onControllerReady,
                onSnapshot = onSnapshot,
                onError = onError,
            )
        }
    }
}

private enum class ResolvedAndroidPlaybackEngine {
    ExoPlayer,
    Libmpv,
}

private fun AndroidPlaybackEngine.initialAndroidEngine(): ResolvedAndroidPlaybackEngine =
    when (this) {
        AndroidPlaybackEngine.Auto,
        AndroidPlaybackEngine.ExoPlayer -> ResolvedAndroidPlaybackEngine.ExoPlayer
        AndroidPlaybackEngine.Libmpv -> ResolvedAndroidPlaybackEngine.Libmpv
    }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    /** [linkAuthFailure] = the server refused the URL itself (401/403/410), not a decode problem. */
    onError: (message: String?, linkAuthFailure: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val coroutineScope = rememberCoroutineScope()

    val playerSettings = remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState.value
    }

    val sanitizedSourceHeaders = remember(sourceHeaders) {
        sanitizePlaybackHeaders(sourceHeaders)
    }
    val sanitizedSourceResponseHeaders = remember(sourceResponseHeaders) {
        sanitizePlaybackResponseHeaders(sourceResponseHeaders)
    }
    val normalizedStreamType = remember(streamType) {
        normalizeStreamType(streamType)
    }
    val useLibass = playerSettings.useLibass
    val libassRenderType = runCatching {
        LibassRenderType.valueOf(playerSettings.libassRenderType)
    }.getOrDefault(LibassRenderType.CUES)
    val playerSourceKey = listOf(
        sourceUrl,
        sourceAudioUrl.orEmpty(),
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        normalizedStreamType.orEmpty(),
        useYoutubeChunkedPlayback,
        initialPositionRequestKey.orEmpty(),
    )
    val playbackDiagnostics = remember(playerSourceKey) { PlaybackDiagnostics() }
    var subtitleDelayMs by remember(playerSourceKey) { mutableStateOf(0) }
    var selectedExternalSubtitleMimeType by remember(playerSourceKey) { mutableStateOf<String?>(null) }
    val latestSubtitleDelayMs = rememberUpdatedState(subtitleDelayMs)
    val latestExternalSubtitleMimeType = rememberUpdatedState(selectedExternalSubtitleMimeType)
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var videoAspectRatio by remember(playerSourceKey) { mutableStateOf(0f) }
    val latestVideoAspectRatio = rememberUpdatedState(videoAspectRatio)
    var currentSubtitleStyle by remember { mutableStateOf(SubtitleStyleState.DEFAULT) }
    var decoderPriorityOverride by remember(playerSourceKey) { mutableStateOf<Int?>(null) }
    var fallbackStartPositionMs by remember(playerSourceKey) { mutableStateOf<Long?>(null) }
    val effectiveDecoderPriority = decoderPriorityOverride ?: playerSettings.decoderPriority

    val initialMediaItem = remember(playerSourceKey, externalSubtitles) {
        val subtitleConfigs = externalSubtitles.mapNotNull { subtitle ->
            val mimeType = resolveSubtitleMimeType(subtitle.url, subtitle.headers)
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                .setMimeType(mimeType)
                .setLanguage(subtitle.language)
                .setLabel(subtitle.name ?: subtitle.language)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build()
        }
        playbackMediaItemFromUrl(
            url = sourceUrl,
            responseHeaders = sanitizedSourceResponseHeaders,
            streamType = normalizedStreamType,
        ).buildUpon()
            .setMediaId(sourceUrl)
            .apply {
                if (subtitleConfigs.isNotEmpty()) {
                    setSubtitleConfigurations(subtitleConfigs)
                }
            }
            .build()
    }

    var resolvedMediaItem by remember(playerSourceKey) { mutableStateOf(initialMediaItem) }
    var probeAttempted by remember(playerSourceKey) { mutableStateOf(false) }

    val extractorsFactory = remember {
        DefaultExtractorsFactory()
            // A direct live `.ts` is a single-program transport stream. The default multi-PMT mode
            // scans for multiple programs and can mis-select PIDs / mis-frame the ES on some panels,
            // feeding the decoder malformed access units (macroblocking). StreamVault + TiviMate both
            // pin SINGLE_PMT for live .ts. (Phase 0, research/iptv-playback-engine-design.md)
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            .setTsExtractorFlags(LIVE_TS_EXTRACTOR_FLAGS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }
    val dataSourceFactory = remember(
        context,
        sourceUrl,
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        useYoutubeChunkedPlayback,
        externalSubtitles,
    ) {
        PlatformPlaybackDataSourceFactory.create(
            context = context,
            defaultRequestHeaders = sanitizedSourceHeaders,
            defaultResponseHeaders = sanitizedSourceResponseHeaders,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            useLongReadTimeout = isLoopbackPlaybackSource(sourceUrl),
            externalSubtitles = externalSubtitles,
        )
    }

    fun ExoPlayer.setPlaybackMediaItem(videoMediaItem: MediaItem, startPositionMs: Long? = null) {
        if (!sourceAudioUrl.isNullOrBlank()) {
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            val videoSource = mediaSourceFactory.createMediaSource(videoMediaItem)
            val audioSource = mediaSourceFactory.createMediaSource(playbackMediaItemFromUrl(sourceAudioUrl))
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            if (startPositionMs != null) {
                setMediaSource(mergedSource, startPositionMs.coerceAtLeast(0L))
            } else {
                setMediaSource(mergedSource)
            }
        } else if (startPositionMs != null) {
            setMediaItem(videoMediaItem, startPositionMs.coerceAtLeast(0L))
        } else {
            setMediaItem(videoMediaItem)
        }
    }

    val exoPlayer = remember(
        sourceUrl,
        sourceAudioUrl,
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        normalizedStreamType,
        useYoutubeChunkedPlayback,
        effectiveDecoderPriority,
        initialPositionRequestKey,
    ) {
        val renderersFactory = SubtitleOffsetRenderersFactory(
            context = context,
            subtitleDelayUsProvider = { latestSubtitleDelayMs.value.toLong() * 1_000L },
            shouldNormalizeCuePositionProvider = {
                latestExternalSubtitleMimeType.value == MimeTypes.TEXT_VTT
            },
            shouldStripSdhProvider = { currentSubtitleStyle.stripSdh },
            videoBoundsFractionProvider = {
                playerViewRef?.videoBoundsFraction(latestVideoAspectRatio.value)
            },
        )
            .setExtensionRendererMode(effectiveDecoderPriority)
            .setEnableDecoderFallback(true)
            .setMapDV7ToHevc(playerSettings.mapDV7ToHevc)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            )
            if (playerSettings.tunnelingEnabled) {
                setParameters(buildUponParameters().setTunnelingEnabled(true))
            }
        }

        val minBufferMs = 15_000
        val maxBufferMs = 70_000
        val bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        val bufferForPlaybackAfterRebufferMs = 5_000
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(playerTargetBufferBytes())
            // Pinned to media3 1.8.0's default, because the heap/4 byte cap above only bounds
            // memory while it holds: shouldContinueLoading keeps loading below minBufferMs
            // whenever `prioritizeTimeOverSizeThresholds || !targetBufferSizeReached`, so
            // flipping this to true would let a high-bitrate stream buffer straight past the
            // cap that field OOMs forced us onto.
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            .build()
        // Published so a reported live freeze carries the thresholds that were actually in
        // effect; a stall that never clears is only explained by these if the player needed
        // more buffered media to resume than a realtime source could produce.
        com.nuvio.app.core.analytics.LivePlaybackBufferProfile.current =
            com.nuvio.app.core.analytics.LivePlaybackBufferProfile.Values(
                minBufferMs = minBufferMs,
                maxBufferMs = maxBufferMs,
                bufferForPlaybackMs = bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs,
            )

        val player = if (useLibass) {
            ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .buildWithAssSupportCompat(
                    context = context,
                    renderType = libassRenderType.toAssRenderType(),
                    dataSourceFactory = dataSourceFactory,
                    extractorsFactory = extractorsFactory,
                    renderersFactory = renderersFactory
                )
        } else {
            val mediaSourceFactory = DefaultMediaSourceFactory(
                dataSourceFactory,
                extractorsFactory,
            )

            ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        }

        player
    }

    val nowPlayingController = remember(context, exoPlayer) {
        AndroidPlayerNowPlayingController(
            context = context,
            controls = AndroidPlayerNowPlayingController.PlaybackControls(
                play = {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                },
                pause = exoPlayer::pause,
                seekTo = { positionMs -> exoPlayer.seekTo(positionMs.coerceAtLeast(0L)) },
                seekBy = { offsetMs ->
                    exoPlayer.seekTo((exoPlayer.currentPosition + offsetMs).coerceAtLeast(0L))
                },
            ),
        )
    }

    fun dispatchExoPlayerSnapshot() {
        val snapshot = exoPlayer.snapshot()
        latestOnSnapshot.value(snapshot)
        nowPlayingController.syncPlayback(snapshot)
    }

    DisposableEffect(nowPlayingController) {
        onDispose { nowPlayingController.release() }
    }

    LaunchedEffect(exoPlayer, resolvedMediaItem, initialPositionRequestKey) {
        val mediaItem = resolvedMediaItem ?: return@LaunchedEffect
        val requestedStartPositionMs = fallbackStartPositionMs
            ?: initialPositionMs?.takeIf { it > 0L }
        playbackDiagnostics.attempt += 1
        playbackDiagnostics.prepareStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            PLAYER_DIAGNOSTIC_TAG,
            "prepare begin attempt=${playbackDiagnostics.attempt} " +
                "source=${diagnosticPlaybackSource(sourceUrl)} audioSource=${!sourceAudioUrl.isNullOrBlank()} " +
                "mime=${mediaItem.localConfiguration?.mimeType ?: "auto"} " +
                "startPositionMs=${requestedStartPositionMs ?: 0L}",
        )
        exoPlayer.setPlaybackMediaItem(mediaItem, requestedStartPositionMs)
        if (fallbackStartPositionMs == null) {
            initialPositionRequestKey?.let { key ->
                latestOnInitialPositionHandled.value(
                    key,
                    requestedStartPositionMs != null,
                )
            }
        }
        exoPlayer.prepare()
    }

    val pendingSubtitleTrackIndex = remember { mutableListOf<Int>() }
    val pendingAudioTrackSelection = remember { mutableListOf<TrackSelectionSnapshot>() }
    var subtitleSelectionJob by remember { mutableStateOf<Job?>(null) }
    val isInPip = rememberIsInPictureInPicture()
    val pipSubtitleScale by rememberUpdatedState(if (isInPip) 0.4f else 1.0f)

    fun syncPlayerViewKeepScreenOn() {
        playerViewRef?.keepScreenOn = exoPlayer.shouldKeepPlayerScreenOn()
    }

    fun preserveAudioSelectionForReload(reason: String) {
        pendingAudioTrackSelection.clear()
        val selection = exoPlayer.captureSelectedTrack(C.TRACK_TYPE_AUDIO) ?: return
        pendingAudioTrackSelection.add(selection)
        Log.d(TAG, "$reason: preserving audio track index=${selection.index} id=${selection.id}")
    }

    DisposableEffect(exoPlayer) {
        PlayerPictureInPictureManager.registerPausePlaybackCallback {
            exoPlayer.pause()
        }
        PlayerPictureInPictureManager.registerTogglePlaybackCallback {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                }
                exoPlayer.play()
            }
        }

        fun reportPlayerError(error: PlaybackException) {
            if (
                playerSettings.decoderPriority == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON &&
                effectiveDecoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER &&
                error.isDecoderFailure()
            ) {
                Log.w(
                    TAG,
                    "Decoder failure (${error.errorCodeName}); retrying with app decoders",
                    error,
                )
                fallbackStartPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                decoderPriorityOverride = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                latestOnError.value(null, false)
                return
            }
            latestOnError.value(
                error.localizedMessage ?: runBlocking { getString(Res.string.player_unable_to_play_stream) },
                error.isLinkAuthFailure(),
            )
        }

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                syncPlayerViewKeepScreenOn()
                Log.e(
                    PLAYER_DIAGNOSTIC_TAG,
                    "error attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "code=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName ?: "none"} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)} " +
                        "bufferedMs=${exoPlayer.bufferedPosition.coerceAtLeast(0L)} " +
                        "durationMs=${exoPlayer.duration.coerceAtLeast(0L)} " +
                        "message=${diagnosticPlayerMessage(error.message)} " +
                        "causeChain=${diagnosticThrowableChain(error)}",
                    error,
                )

                val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

                if (isSourceError && !probeAttempted) {
                    probeAttempted = true
                    coroutineScope.launch {
                        val probedMime = withContext(Dispatchers.IO) {
                            probeMimeType(sourceUrl, sanitizedSourceHeaders)
                        }
                        // The probe can come back empty — the panel refuses a second request, or on
                        // Stalker the single-use create_link token was already spent by the attempt
                        // that just failed. Retry as HLS anyway: a source that doesn't hand back
                        // real MPEG-TS on a `.ts` path is redirecting to an m3u8.
                        val retryMime = probedMime ?: MimeTypes.APPLICATION_M3U8
                        // Only a probed type is a fact; the blind guess doesn't earn a memory.
                        if (probedMime != null) rememberContainerMimeType(sourceUrl, probedMime)
                        Log.d(
                            TAG,
                            "Playback failed with source error. Retrying as $retryMime " +
                                "(probed=${probedMime ?: "none"})...",
                        )
                        resolvedMediaItem = MediaItem.Builder()
                            .setUri(sourceUrl)
                            .setMimeType(retryMime)
                            .setMediaId(sourceUrl)
                            .apply {
                                val subtitleConfigs = externalSubtitles.mapNotNull { subtitle ->
                                    val mimeType = resolveSubtitleMimeType(subtitle.url, subtitle.headers)
                                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                                        .setMimeType(mimeType)
                                        .setLanguage(subtitle.language)
                                        .setLabel(subtitle.name ?: subtitle.language)
                                        .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                                        .build()
                                }
                                if (subtitleConfigs.isNotEmpty()) {
                                    setSubtitleConfigurations(subtitleConfigs)
                                }
                            }
                            .build()
                        // A retry that also fails re-enters here with probeAttempted set, and the
                        // error is reported then.
                        latestOnError.value(null, false)
                    }
                    return
                }

                reportPlayerError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d(TAG, "onPlaybackStateChanged: $stateName")
                Log.i(
                    PLAYER_DIAGNOSTIC_TAG,
                    "state=$stateName attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)} " +
                        "bufferedMs=${exoPlayer.bufferedPosition.coerceAtLeast(0L)} " +
                        "durationMs=${exoPlayer.duration.coerceAtLeast(0L)} " +
                        "bufferedPercent=${BufferedPercent.of(exoPlayer.bufferedPosition, exoPlayer.duration)} playWhenReady=${exoPlayer.playWhenReady} " +
                        "terminalError=${exoPlayer.playerError?.errorCodeName ?: "none"}",
                )
                if (playbackState == Player.STATE_READY) {
                    fallbackStartPositionMs = null
                    latestOnError.value(null, false)
                    exoPlayer.logCurrentTracks("STATE_READY")
                }
                syncPlayerViewKeepScreenOn()
                dispatchExoPlayerSnapshot()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(
                    PLAYER_DIAGNOSTIC_TAG,
                    "isPlaying=$isPlaying attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)}",
                )
                syncPlayerViewKeepScreenOn()
                dispatchExoPlayerSnapshot()
            }

            override fun onRenderedFirstFrame() {
                Log.i(
                    PLAYER_DIAGNOSTIC_TAG,
                    "firstFrame attempt=${playbackDiagnostics.attempt} " +
                        "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                        "positionMs=${exoPlayer.currentPosition.coerceAtLeast(0L)}",
                )
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                dispatchExoPlayerSnapshot()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                latestOnSnapshot.value(exoPlayer.snapshot())
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                Log.d(TAG, "onTracksChanged: ${tracks.groups.size} groups total")
                exoPlayer.logCurrentTracks("onTracksChanged")
                pendingAudioTrackSelection.firstOrNull()?.let { selection ->
                    if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                        pendingAudioTrackSelection.clear()
                        val restored = exoPlayer.restoreTrackSelection(selection)
                        Log.d(TAG, "onTracksChanged: restored pending audio selection=$restored")
                    }
                }
                if (pendingSubtitleTrackIndex.isNotEmpty() && tracks.groups.isNotEmpty()) {
                    val idx = pendingSubtitleTrackIndex.removeAt(0)
                    Log.d(TAG, "onTracksChanged: applying pending subtitle selection index=$idx")
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, idx < 0)
                        .build()
                    if (idx >= 0) {
                        exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, idx)
                    }
                }
                dispatchExoPlayerSnapshot()
            }

        }
        exoPlayer.addListener(listener)
        onDispose {
            PlayerPictureInPictureManager.registerPausePlaybackCallback(null)
            PlayerPictureInPictureManager.registerTogglePlaybackCallback(null)
            exoPlayer.removeListener(listener)
            playerViewRef?.keepScreenOn = false
            subtitleSelectionJob?.cancel()
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> exoPlayer.playWhenReady = latestPlayWhenReady.value
                Lifecycle.Event.ON_STOP -> {
                    val isInPictureInPicture =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true
                    val isFinishing = activity?.isFinishing == true
                    val hasActiveNowPlayingSession = nowPlayingController.isActive
                    if ((!isInPictureInPicture && !hasActiveNowPlayingSession) || isFinishing) {
                        exoPlayer.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, playWhenReady) {
        exoPlayer.playWhenReady = latestPlayWhenReady.value
        syncPlayerViewKeepScreenOn()
        dispatchExoPlayerSnapshot()
    }

    LaunchedEffect(exoPlayer) {
        onControllerReady(
            object : PlayerEngineController {
                override fun play() {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }

                override fun pause() {
                    exoPlayer.pause()
                }

                override fun seekTo(positionMs: Long) {
                    exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
                }

                override fun seekBy(offsetMs: Long) {
                    exoPlayer.seekTo((exoPlayer.currentPosition + offsetMs).coerceAtLeast(0L))
                }

                override fun retry() {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }

                override fun getStreamInfo(): PlayerStreamInfo = runCatching {
                    val video = exoPlayer.videoFormat
                    val audio = exoPlayer.audioFormat
                    PlayerStreamInfo(
                        videoCodec = video?.displayCodecName(),
                        videoWidth = video?.width?.takeIf { it > 0 },
                        videoHeight = video?.height?.takeIf { it > 0 },
                        videoFrameRate = video?.frameRate?.takeIf { it > 0f },
                        videoBitrate = video?.bitrate?.takeIf { it > 0 },
                        audioCodec = audio?.displayCodecName(),
                        audioChannelCount = audio?.channelCount?.takeIf { it > 0 },
                        audioSampleRate = audio?.sampleRate?.takeIf { it > 0 },
                        audioBitrate = audio?.bitrate?.takeIf { it > 0 },
                        playerEngine = ENGINE_LABEL_EXOPLAYER,
                    )
                }.getOrElse {
                    Log.w(TAG, "Failed to read ExoPlayer stream info: ${it.message}")
                    PlayerStreamInfo(playerEngine = ENGINE_LABEL_EXOPLAYER)
                }

                override fun setPlaybackSpeed(speed: Float) {
                    exoPlayer.setPlaybackSpeed(speed)
                }

                override fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {
                    nowPlayingController.updateMetadata(info)
                }

                override fun clearNowPlayingInfo() {
                    nowPlayingController.clear()
                }

                override fun getAudioTracks(): List<AudioTrack> =
                    exoPlayer.extractAudioTracks(context)

                override fun getSubtitleTracks(): List<SubtitleTrack> {
                    val tracks = exoPlayer.extractSubtitleTracks(context)
                    Log.d(TAG, "getSubtitleTracks: found ${tracks.size} tracks")
                    tracks.forEach { t ->
                        Log.d(TAG, "  track idx=${t.index} id=${t.id} label='${t.label}' lang=${t.language} selected=${t.isSelected}")
                    }
                    return tracks
                }

                override fun selectAudioTrack(index: Int) {
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_AUDIO, index)
                }

                override fun selectSubtitleTrack(index: Int) {
                    Log.d(TAG, "selectSubtitleTrack: index=$index")
                    if (index < 0) {
                        Log.d(TAG, "selectSubtitleTrack: disabling text tracks")
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        return
                    }
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, index)
                    Log.d(TAG, "selectSubtitleTrack: after selection, textDisabled=${exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
                    exoPlayer.logCurrentTracks("after selectSubtitleTrack")
                }

                override fun setSubtitleUri(url: String) {
                    Log.d(TAG, "setSubtitleUri: url=$url")
                    subtitleSelectionJob?.cancel()
                    subtitleSelectionJob = coroutineScope.launch {
                        val currentPosition = exoPlayer.currentPosition
                        val wasPlaying = exoPlayer.isPlaying
                        val currentMediaItem = exoPlayer.currentMediaItem ?: run {
                            Log.e(TAG, "setSubtitleUri: currentMediaItem is null, aborting")
                            return@launch
                        }
                        preserveAudioSelectionForReload("setSubtitleUri")
                        val resolvedMime = withContext(Dispatchers.IO) {
                            resolveSubtitleMimeType(url)
                        }
                        selectedExternalSubtitleMimeType = resolvedMime
                        Log.d(TAG, "setSubtitleUri: currentPosition=$currentPosition, wasPlaying=$wasPlaying")
                        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                            .setMimeType(resolvedMime)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                            .build()
                        Log.d(
                            TAG,
                            "setSubtitleUri: subtitleConfig built, uri=${subtitleConfig.uri}, mime=${subtitleConfig.mimeType}, selectionFlags=${subtitleConfig.selectionFlags}"
                        )
                        val newMediaItem = currentMediaItem.buildUpon()
                            .setSubtitleConfigurations(listOf(subtitleConfig))
                            .build()
                        Log.d(TAG, "setSubtitleUri: newMediaItem subtitleConfigs count=${newMediaItem.localConfiguration?.subtitleConfigurations?.size}")
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                            .build()
                        Log.d(TAG, "setSubtitleUri: track params set before prepare, textDisabled=${exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
                        exoPlayer.setPlaybackMediaItem(newMediaItem, currentPosition)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = wasPlaying
                        Log.d(TAG, "setSubtitleUri: prepare() called, waiting for STATE_READY")
                    }
                }

                override fun clearExternalSubtitle() {
                    Log.d(TAG, "clearExternalSubtitle called")
                    subtitleSelectionJob?.cancel()
                    selectedExternalSubtitleMimeType = null
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying
                    val currentMediaItem = exoPlayer.currentMediaItem ?: return
                    preserveAudioSelectionForReload("clearExternalSubtitle")
                    val newMediaItem = currentMediaItem.buildUpon()
                        .setSubtitleConfigurations(emptyList())
                        .build()
                    exoPlayer.setPlaybackMediaItem(newMediaItem, currentPosition)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = wasPlaying
                    Log.d(TAG, "clearExternalSubtitle: done, position=$currentPosition")
                }

                override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                    Log.d(TAG, "clearExternalSubtitleAndSelect: trackIndex=$trackIndex")
                    subtitleSelectionJob?.cancel()
                    selectedExternalSubtitleMimeType = null
                    pendingSubtitleTrackIndex.clear()
                    pendingSubtitleTrackIndex.add(trackIndex)
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying
                    val currentMediaItem = exoPlayer.currentMediaItem ?: return
                    preserveAudioSelectionForReload("clearExternalSubtitleAndSelect")
                    val newMediaItem = currentMediaItem.buildUpon()
                        .setSubtitleConfigurations(emptyList())
                        .build()
                    exoPlayer.setPlaybackMediaItem(newMediaItem, currentPosition)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = wasPlaying
                    Log.d(TAG, "clearExternalSubtitleAndSelect: done, pending=$trackIndex position=$currentPosition")
                }

                override fun applySubtitleStyle(style: SubtitleStyleState) {
                    currentSubtitleStyle = style
                    playerViewRef?.applySubtitleStyle(style, pipSubtitleScale)
                }

                override fun setSubtitleDelayMs(delayMs: Int) {
                    subtitleDelayMs = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
                }
            }
        )
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            dispatchExoPlayerSnapshot()
            delay(250L)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = useNativeController
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                player = exoPlayer
                keepScreenOn = exoPlayer.shouldKeepPlayerScreenOn()
                this.resizeMode = resizeMode.toExoResizeMode()
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                playerViewRef = this
                syncLibassOverlay(
                    player = exoPlayer,
                    enabled = useLibass,
                    renderType = libassRenderType,
                )
                applySubtitleStyle(currentSubtitleStyle, pipSubtitleScale)
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
            playerView.useController = useNativeController
            playerView.resizeMode = resizeMode.toExoResizeMode()
            playerViewRef = playerView
            syncPlayerViewKeepScreenOn()
            playerView.syncLibassOverlay(
                player = exoPlayer,
                enabled = useLibass,
                renderType = libassRenderType,
            )
            playerView.applySubtitleStyle(currentSubtitleStyle, pipSubtitleScale)
        },
    )
}

@Composable
private fun LibmpvPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    isLiveStream: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    /** Already resolved by [LiveVideoOutputPolicy]: gpu on the live path, else the user's renderer. */
    videoOutput: String,
    hardwareDecodingEnabled: Boolean,
    yuv420pEnabled: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val isLocalFileSource = sourceUrl.startsWith("file:", ignoreCase = true)
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val coroutineScope = rememberCoroutineScope()
    val playbackDiagnostics = remember { PlaybackDiagnostics() }
    val sanitizedSourceHeaders = remember(sourceHeaders) {
        sanitizePlaybackHeaders(sourceHeaders)
    }
    var playerViewRef by remember { mutableStateOf<NuvioLibmpvView?>(null) }
    val nowPlayingController = remember(context, playerViewRef) {
        playerViewRef?.let { view ->
            AndroidPlayerNowPlayingController(
                context = context,
                controls = AndroidPlayerNowPlayingController.PlaybackControls(
                    play = { view.setPaused(false) },
                    pause = { view.setPaused(true) },
                    seekTo = { positionMs -> view.seekToMs(positionMs) },
                    seekBy = { offsetMs -> view.seekByMs(offsetMs) },
                ),
            )
        }
    }

    DisposableEffect(nowPlayingController) {
        onDispose { nowPlayingController?.release() }
    }

    DisposableEffect(lifecycleOwner, nowPlayingController) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            val view = playerViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.setPaused(!latestPlayWhenReady.value)
                Lifecycle.Event.ON_STOP -> {
                    val isInPictureInPicture =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true
                    val isFinishing = activity?.isFinishing == true
                    val hasActiveNowPlayingSession = nowPlayingController?.isActive == true
                    if ((!isInPictureInPicture && !hasActiveNowPlayingSession) || isFinishing) {
                        view.setPaused(true)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(playerViewRef, nowPlayingController) {
        val view = playerViewRef ?: return@DisposableEffect onDispose {}
        fun dispatchSnapshot(updateKeepScreenOn: Boolean = false) {
            coroutineScope.launch(Dispatchers.Main.immediate) {
                val snapshot = view.snapshot()
                latestOnSnapshot.value(snapshot)
                nowPlayingController?.syncPlayback(snapshot)
                if (updateKeepScreenOn) {
                    view.keepScreenOn = view.shouldKeepScreenOn()
                }
            }
        }
        val observer = object : MPV.EventObserver {
            override fun eventProperty(property: String) = Unit
            override fun eventProperty(property: String, value: Long) {
                if (property == "cache-buffering-state") {
                    dispatchSnapshot(updateKeepScreenOn = true)
                }
            }
            override fun eventProperty(property: String, value: Boolean) {
                if (property == "eof-reached" && value) {
                    Log.w(
                        PLAYER_DIAGNOSTIC_TAG,
                        "mpv_property=eof-reached value=true attempt=${playbackDiagnostics.attempt} " +
                            "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)}",
                    )
                }
                if (property == "eof-reached" || property == "pause" || property == "paused-for-cache" || property == "seeking") {
                    dispatchSnapshot(updateKeepScreenOn = true)
                }
            }
            override fun eventProperty(property: String, value: String) = Unit
            override fun eventProperty(property: String, value: Double) {
                if (property == "duration" || property == "time-pos" || property == "speed") {
                    dispatchSnapshot()
                }
            }
            override fun eventProperty(property: String, value: MPVNode) {
                if (property == "track-list") dispatchSnapshot()
            }
            override fun event(eventId: Int, data: MPVNode) {
                when (eventId) {
                    MPV.mpvEvent.MPV_EVENT_START_FILE -> {
                        Log.i(
                            PLAYER_DIAGNOSTIC_TAG,
                            "mpv_event=START_FILE attempt=${playbackDiagnostics.attempt} " +
                                "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)}",
                        )
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            latestOnError.value(null)
                            val snapshot = PlayerPlaybackSnapshot()
                            latestOnSnapshot.value(snapshot)
                            nowPlayingController?.syncPlayback(snapshot)
                        }
                    }
                    MPV.mpvEvent.MPV_EVENT_FILE_LOADED,
                    MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            latestOnError.value(null)
                            val snapshot = view.snapshot()
                            Log.i(
                                PLAYER_DIAGNOSTIC_TAG,
                                "mpv_event=${if (eventId == MPV.mpvEvent.MPV_EVENT_FILE_LOADED) "FILE_LOADED" else "PLAYBACK_RESTART"} " +
                                    "attempt=${playbackDiagnostics.attempt} " +
                                    "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                                    "positionMs=${snapshot.positionMs} bufferedMs=${snapshot.bufferedPositionMs} " +
                                    "durationMs=${snapshot.durationMs}",
                            )
                            latestOnSnapshot.value(snapshot)
                            nowPlayingController?.syncPlayback(snapshot)
                        }
                    }
                    MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            val snapshot = view.snapshot()
                            Log.w(
                                PLAYER_DIAGNOSTIC_TAG,
                                "mpv_event=END_FILE attempt=${playbackDiagnostics.attempt} " +
                                    "elapsedMs=${diagnosticElapsedSince(playbackDiagnostics.prepareStartedAtMs)} " +
                                    "positionMs=${snapshot.positionMs} bufferedMs=${snapshot.bufferedPositionMs} " +
                                    "durationMs=${snapshot.durationMs} eof=${snapshot.isEnded} " +
                                    "data=${diagnosticPlayerMessage(data.toJson())}",
                            )
                            latestOnSnapshot.value(snapshot)
                            nowPlayingController?.syncPlayback(snapshot)
                            view.keepScreenOn = view.shouldKeepScreenOn()
                        }
                    }
                }
            }
        }
        val logObserver = object : MPV.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                Log.w(
                    PLAYER_DIAGNOSTIC_TAG,
                    "mpv_log level=$level prefix=${diagnosticPlayerMessage(prefix)} " +
                        "message=${diagnosticPlayerMessage(text)}",
                )
            }
        }
        view.mpv.addObserver(observer)
        view.mpv.addLogObserver(logObserver)
        onDispose {
            view.mpv.removeObserver(observer)
            view.mpv.removeLogObserver(logObserver)
        }
    }

    DisposableEffect(playerViewRef) {
        val view = playerViewRef ?: return@DisposableEffect onDispose {}
        PlayerPictureInPictureManager.registerPausePlaybackCallback {
            view.setPaused(true)
        }
        PlayerPictureInPictureManager.registerTogglePlaybackCallback {
            val snapshot = view.snapshot()
            if (snapshot.isPlaying) {
                view.setPaused(true)
            } else {
                if (snapshot.isEnded) {
                    view.seekToMs(0L)
                }
                view.setPaused(false)
            }
        }
        onDispose {
            PlayerPictureInPictureManager.registerPausePlaybackCallback(null)
            PlayerPictureInPictureManager.registerTogglePlaybackCallback(null)
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(playerViewRef, sourceUrl, sourceAudioUrl, sanitizedSourceHeaders, externalSubtitles) {
        val view = playerViewRef ?: return@LaunchedEffect
        playbackDiagnostics.attempt += 1
        playbackDiagnostics.prepareStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            PLAYER_DIAGNOSTIC_TAG,
            "mpv_prepare_begin attempt=${playbackDiagnostics.attempt} " +
                "source=${diagnosticPlaybackSource(sourceUrl)} audioSource=${!sourceAudioUrl.isNullOrBlank()}",
        )
        val snapshot = PlayerPlaybackSnapshot()
        latestOnSnapshot.value(snapshot)
        nowPlayingController?.syncPlayback(snapshot)
        view.loadSource(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            requestHeaders = sanitizedSourceHeaders,
            externalSubtitles = externalSubtitles,
            playWhenReady = latestPlayWhenReady.value,
        )
    }

    LaunchedEffect(playerViewRef, playWhenReady) {
        val view = playerViewRef ?: return@LaunchedEffect
        view.setPaused(!latestPlayWhenReady.value)
        view.keepScreenOn = view.shouldKeepScreenOn()
        val snapshot = view.snapshot()
        latestOnSnapshot.value(snapshot)
        nowPlayingController?.syncPlayback(snapshot)
    }

    LaunchedEffect(playerViewRef, resizeMode) {
        playerViewRef?.applyResizeMode(resizeMode)
    }

    LaunchedEffect(playerViewRef, sourceUrl, sourceAudioUrl, sanitizedSourceHeaders, externalSubtitles) {
        val view = playerViewRef ?: return@LaunchedEffect
        onControllerReady(view.controller(context, nowPlayingController))
    }

    LaunchedEffect(playerViewRef) {
        val view = playerViewRef ?: return@LaunchedEffect
        while (isActive) {
            val snapshot = view.snapshot()
            latestOnSnapshot.value(snapshot)
            nowPlayingController?.syncPlayback(snapshot)
            view.keepScreenOn = view.shouldKeepScreenOn()
            delay(250L)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            NuvioLibmpvView(
                context = viewContext,
                videoOutput = if (isLocalFileSource) AndroidLibmpvVideoOutput.Gpu.mpvValue else videoOutput,
                hardwareDecodingEnabled = if (isLocalFileSource) false else hardwareDecodingEnabled,
                yuv420pEnabled = yuv420pEnabled,
            ).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                keepScreenOn = false
                initializeWhenPredecessorReleased(
                    configDir = viewContext.filesDir.path,
                    cacheDir = viewContext.cacheDir.path,
                ) { error ->
                    Log.e(TAG, "Failed to initialize libmpv", error)
                    latestOnError.value(error.localizedMessage ?: "libmpv unavailable")
                }
                playerViewRef = this
            }
        },
        update = { view ->
            playerViewRef = view
            view.isLiveStream = isLiveStream
            view.playWhenReadyIntent = playWhenReady
            view.applyResizeMode(resizeMode)
        },
        onRelease = { view ->
            if (playerViewRef === view) playerViewRef = null
            // Teardown on the control thread: mpv_terminate_destroy joins the demuxer,
            // which can hang on a dead network read — the BACK-during-stall ANR path.
            view.release()
        },
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private class NuvioLibmpvView(
    context: Context,
    private val videoOutput: String,
    private val hardwareDecodingEnabled: Boolean,
    private val yuv420pEnabled: Boolean,
    attrs: AttributeSet? = null,
) : BaseMPVView(context, attrs) {
    private var currentSourceUrl: String? = null
    private var currentSourceAudioUrl: String? = null
    private var currentRequestHeaders: Map<String, String> = emptyMap()
    private var currentExternalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList()

    // Set from the composable. Live streams must rejoin the live edge when the surface returns:
    // a live stream paused in the background goes stale (the server keeps sending real time and
    // eventually drops the socket), so unpausing plays out the old buffer, stalls, and lags live.
    var isLiveStream: Boolean = false
    var playWhenReadyIntent: Boolean = true
    private var pendingLiveRejoin = false

    private val lifecycleLease = AndroidMpvInstanceGate.gate.register()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coreInitialized = AtomicBoolean(false)
    private val controlLock = Any()
    private val pendingControls = ArrayDeque<() -> Unit>()
    private var controlsReady = false
    // Read and written only by mpv-ctl.
    private var attachedSurface: Surface? = null

    // All mpv control calls (property writes, loadfile, seeks, teardown) run here,
    // serialized in submission order. mpv_set_property/mpv_command take the same core
    // lock as reads: with a wedged live demuxer, ON_START's setPaused(false) blocked the
    // main thread >5s (reproduced ANR: pthread_cond_wait ← mpv_set_property ← setPaused
    // ← lifecycle onStateChanged). Reads are lock-free via the shadow; writes queue here.
    private val mpvCtl = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-ctl-${lifecycleLease.id}")
    }
    private val released = AtomicBoolean(false)

    private fun ctl(block: () -> Unit) {
        if (released.get()) return
        synchronized(controlLock) {
            if (released.get()) return
            if (!controlsReady) {
                pendingControls.addLast(block)
                return
            }
            executeControl(block = block)
        }
    }

    private fun executeControl(allowAfterRelease: Boolean = false, block: () -> Unit) {
        runCatching {
            mpvCtl.execute {
                if (!allowAfterRelease && released.get()) return@execute
                runCatching(block).onFailure { error ->
                    Log.w(TAG, "MPV control operation failed: ${error.message}")
                }
            }
        }
    }

    fun initializeWhenPredecessorReleased(
        configDir: String,
        cacheDir: String,
        onFailure: (Throwable) -> Unit,
    ) {
        recordMpvStage("waiting_for_predecessor")
        lifecycleLease.whenReady {
            mainHandler.post {
                if (released.get()) {
                    finishWithoutNativeCore("released_before_initialization")
                    return@post
                }
                runCatching {
                    Utils.copyAssets(context)
                    initialize(configDir, cacheDir)
                    // BaseMPVView.initialize() OVERWRITES idle with "once" AFTER initOptions()
                    // runs (mpv-android-lib BaseMPVView.kt:38-39) — under idle=once a FAILED load
                    // (dead IPTV channel) that emptied the playlist QUITS the core, silently
                    // bricking every later load on this instance (device-traced on Android TV;
                    // same wrapper here). idle is runtime-settable, last write wins: re-assert
                    // after the library's overwrite and verify.
                    mpv.setOptionString("idle", "yes")
                    val idleNow = runCatching { mpv.getPropertyString("idle") }.getOrNull()
                    if (idleNow != "yes") {
                        Log.e(TAG, "mpv idle mode is '" + idleNow + "' after re-assert; core will die on a dead channel")
                    }
                }.onSuccess {
                    coreInitialized.set(true)
                    lifecycleLease.markInitialized()
                    recordMpvStage("initialized")

                    // BaseMPVView does not replay surfaceCreated when initialize() runs after the
                    // Surface already exists. Put an idempotent attach first, ahead of every
                    // load/pause command that accumulated while this lease waited.
                    val initialSurface = holder.surface?.takeIf { it.isValid }
                    synchronized(controlLock) {
                        controlsReady = true
                        initialSurface?.let { surface ->
                            executeControl { attachSurfaceInternal(surface) }
                        }
                        while (pendingControls.isNotEmpty()) {
                            executeControl(block = pendingControls.removeFirst())
                        }
                    }
                }.onFailure { error ->
                    Log.e(TAG, "MPV initialization failed for instance ${lifecycleLease.id}", error)
                    if (mpv.isInitialized) {
                        coreInitialized.set(true)
                        lifecycleLease.markInitialized()
                    }
                    recordMpvStage("initialization_failed")
                    onFailure(error)
                    release()
                }
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val releaseStartedAtMs = SystemClock.elapsedRealtime()
        runCatching { holder.removeCallback(this) }
        synchronized(controlLock) { pendingControls.clear() }
        recordMpvStage("release_started")

        if (!coreInitialized.get()) {
            finishWithoutNativeCore("released_without_native_core")
            return
        }

        executeControl(
            allowAfterRelease = true,
            block = {
                var destroyed = false
                try {
                    // This may wait for one already-running network operation, but it never runs
                    // on Main. network-timeout bounds that wait; keeping it on this queue avoids
                    // racing stop/detach/destroy from independent raw threads.
                    runCatching { mpv.command("stop") }
                    detachSurfaceInternal()
                    runCatching { mpv.removeObserver(propertyShadow) }
                    runCatching { mpv.destroy() }
                        .onSuccess { destroyed = true }
                        .onFailure { Log.w(TAG, "Failed to destroy libmpv cleanly", it) }
                } finally {
                    val waitMs = SystemClock.elapsedRealtime() - releaseStartedAtMs
                    if (destroyed) {
                        coreInitialized.set(false)
                        lifecycleLease.complete()
                        recordMpvStage("destroyed", destroyWaitMs = waitMs)
                    } else {
                        // Keep the lease closed: initializing a replacement after a failed native
                        // destroy recreates the exact overlapping-core resource leak we prevent.
                        recordMpvStage("destroy_failed", destroyWaitMs = waitMs)
                    }
                    mpvCtl.shutdown()
                }
            },
        )

        mainHandler.postDelayed({
            if (!lifecycleLease.isComplete()) {
                recordMpvStage(
                    stage = "destroy_timeout",
                    destroyWaitMs = SystemClock.elapsedRealtime() - releaseStartedAtMs,
                )
            }
        }, MPV_DESTROY_WATCHDOG_MS)
    }

    private fun finishWithoutNativeCore(stage: String) {
        synchronized(controlLock) {
            controlsReady = false
            pendingControls.clear()
        }
        lifecycleLease.complete()
        recordMpvStage(stage)
        mpvCtl.shutdownNow()
    }

    private fun recordMpvStage(stage: String, destroyWaitMs: Long? = null) {
        val snapshot = AndroidMpvInstanceGate.gate.snapshot()
        AppExitReporter.recordMpvLifecycle(
            context = context,
            instanceId = lifecycleLease.id,
            stage = stage,
            activeInstances = snapshot.initializedInstances,
            waitingInstances = snapshot.waitingInstances,
            peakActiveInstances = snapshot.peakInitializedInstances,
            destroyWaitMs = destroyWaitMs,
        )
    }

    // Shadow of every property observeProperties() registers, updated from mpv's event
    // thread. snapshot()/extractLibmpvTracks() read these instead of mpv_get_property:
    // a synchronous read takes the mpv core lock, which stalls for seconds while a live
    // demuxer is busy or tearing down — on the main thread that's an ANR (Play vitals:
    // getPropertyBoolean → pthread_cond_wait while exiting a live stream).
    @Volatile private var obsPaused = true
    @Volatile private var obsPausedForCache = false
    @Volatile private var obsCoreIdle = false
    @Volatile private var obsEofReached = false
    @Volatile private var obsSeeking = false
    @Volatile private var obsCacheBufferingState: Int? = null
    @Volatile private var obsDurationMs = 0L
    @Volatile private var obsPositionMs = 0L
    @Volatile private var obsCachePositionMs = 0L
    @Volatile private var obsSpeed = 1.0
    @Volatile private var obsTrackList: MPVNode? = null
    @Volatile private var obsVideoParams: MPVNode? = null
    // Rolling estimates. Live MPEG-TS rarely declares a bitrate in the container, so
    // mpv's running measurement is the only number the stream info panel can show.
    @Volatile private var obsVideoBitrate: Double? = null
    @Volatile private var obsAudioBitrate: Double? = null

    /**
     * The last mirrored `estimated-vf-fps` value (a rate). The live-freeze tick [obsVideoFrameTicks]
     * is derived from this at READ time (in [snapshot], off the main thread's mpv core), NOT by
     * incrementing on the property-change callback: that count plateaus during healthy steady-state
     * playback exactly as on a real freeze (device-proven, review pass 3 F1/F2) and shipped a false
     * VIDEO_STALLED / spurious live reconnect. See [MpvVideoOutputSignal].
     */
    @Volatile private var obsEstimatedVfFps = 0.0

    /**
     * Monotonic "the picture is alive" counter fed to [LivePlaybackFreezePolicy] as
     * videoProgressTicks — advanced once per [snapshot] while [obsEstimatedVfFps] proves frames are
     * flowing, held when they stop. Read off the shadow on the main thread, never through mpv (the
     * ANR fix).
     *
     * CAVEAT: `estimated-vf-fps` measures the *filter chain* output, so it proves decoding rather
     * than that the VO presented the frame; a VO that stops presenting frames a healthy decoder
     * keeps producing would slip past it. The state signals (core-idle / paused-for-cache) and the
     * END_FILE error path backstop that residual case.
     */
    @Volatile private var obsVideoFrameTicks = 0L

    // VO-level counters, straight from mpv. `estimated-vf-fps` above proves decoding, not
    // presentation; these are the closest signals mpv has to "the picture reached the screen"
    // (no true presented-frames property exists). Snapshot diagnostics only for now.
    @Volatile private var obsVoDroppedFrames = 0L
    @Volatile private var obsVoDelayedFrames = 0L

    private val propertyShadow = object : MPV.EventObserver {
        override fun eventProperty(property: String) {
            // MPV_FORMAT_NONE: property became unavailable — fall back to the same
            // defaults a failed synchronous read used to produce.
            when (property) {
                "pause" -> obsPaused = true
                "paused-for-cache" -> obsPausedForCache = false
                "core-idle" -> obsCoreIdle = false
                "eof-reached" -> obsEofReached = false
                "seeking" -> obsSeeking = false
                "cache-buffering-state" -> obsCacheBufferingState = null
                "duration" -> obsDurationMs = 0L
                "time-pos" -> obsPositionMs = 0L
                "demuxer-cache-time" -> obsCachePositionMs = 0L
                "speed" -> obsSpeed = 1.0
                "track-list" -> obsTrackList = null
                "video-params" -> obsVideoParams = null
                "video-bitrate" -> obsVideoBitrate = null
                "audio-bitrate" -> obsAudioBitrate = null
                // Unavailable means no active video output — mirror it as zero fps so the read-time
                // liveness tick (see [snapshot]) holds, i.e. a freeze the policy can see, rather than
                // reading the last healthy rate forever.
                "estimated-vf-fps" -> obsEstimatedVfFps = 0.0
                // Unavailable means no active VO — the same 0 a fresh core reports.
                "frame-drop-count" -> obsVoDroppedFrames = 0L
                "vo-delayed-frame-count" -> obsVoDelayedFrames = 0L
            }
        }

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "cache-buffering-state" -> obsCacheBufferingState = value.toInt()
                "frame-drop-count" -> obsVoDroppedFrames = value
                "vo-delayed-frame-count" -> obsVoDelayedFrames = value
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> obsPaused = value
                "paused-for-cache" -> obsPausedForCache = value
                "core-idle" -> obsCoreIdle = value
                "eof-reached" -> obsEofReached = value
                "seeking" -> obsSeeking = value
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "duration" -> obsDurationMs = value.toMillis()
                "time-pos" -> obsPositionMs = value.toMillis()
                "demuxer-cache-time" -> obsCachePositionMs = value.toMillis()
                "speed" -> obsSpeed = value
                "video-bitrate" -> obsVideoBitrate = value.takeIf { it > 0.0 }
                "audio-bitrate" -> obsAudioBitrate = value.takeIf { it > 0.0 }
                // Mirror the value only; the liveness tick is advanced at read time in [snapshot].
                "estimated-vf-fps" -> obsEstimatedVfFps = value
            }
        }

        override fun eventProperty(property: String, value: String) = Unit

        override fun eventProperty(property: String, value: MPVNode) {
            when (property) {
                "track-list" -> obsTrackList = value
                "video-params" -> obsVideoParams = value
            }
        }

        override fun event(eventId: Int, data: MPVNode) = Unit
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // BaseMPVView writes android-surface-size straight from this callback, and
        // mpv_set_property takes the core lock — which a live demuxer holds for seconds at a
        // time. Because SurfaceView resizes synchronously inside View.layout, that turns
        // every docked <-> fullscreen transition into a main-thread stall:
        //
        //   main  pthread_cond_wait <- mpv_set_property <- MPV.setPropertyString
        //         <- SurfaceView.updateSurface <- SurfaceView.setFrame <- View.layout
        //
        // (reproduced as an ANR on the fullscreen toggle). Queue the write instead, like
        // every other mpv write in this class. mpv applies it a beat later and resizes its
        // output then; blocking layout on it bought nothing and could also strand mpv at the
        // pre-resize size, leaving the video drawn small in the corner of the new surface.
        //
        // Deliberately does NOT call super: the whole of BaseMPVView.surfaceChanged is that
        // one property write, which is what we are re-issuing off the main thread.
        ctl { mpv.setPropertyString("android-surface-size", "${width}x$height") }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val shouldStopLive = isLiveStream && currentSourceUrl != null
        if (shouldStopLive) pendingLiveRejoin = true
        // Do not call BaseMPVView: it performs blocking mpv calls synchronously on Main.
        ctl {
            if (shouldStopLive) mpv.command("stop")
            detachSurfaceInternal()
            recordMpvStage("surface_detached")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface ?: return
        val shouldRejoinLive = pendingLiveRejoin
        pendingLiveRejoin = false
        // Attach and optional live reload are one ordered transition. A fast background/return
        // can no longer attach a new Surface before the previous detach has completed.
        ctl {
            attachSurfaceInternal(surface)
            if (shouldRejoinLive) {
                Log.i(TAG, "Rejoining live edge after background")
                loadCurrentSource(playWhenReady = playWhenReadyIntent)
            }
        }
    }

    private fun attachSurfaceInternal(surface: Surface) {
        if (!surface.isValid) return
        if (attachedSurface === surface) return
        detachSurfaceInternal()
        mpv.attachSurface(surface)
        attachedSurface = surface
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", videoOutput)
        // Logged so the applied renderer (gpu on live, else the user's choice) is visible in logcat
        // when a black screen or fence-fd leak has to be diagnosed. setPropertyString returns Unit.
        Log.i(TAG, "mpv vo applied: '$videoOutput'")
        recordMpvStage("surface_attached")
    }

    private fun detachSurfaceInternal() {
        if (attachedSurface == null) return
        runCatching { mpv.setPropertyString("vo", "null") }
        runCatching { mpv.setPropertyString("force-window", "no") }
        runCatching { mpv.detachSurface() }
        attachedSurface = null
    }

    override fun initOptions() {
        setVo(videoOutput)
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("hwdec", if (hardwareDecodingEnabled) "auto" else "no")
        if (yuv420pEnabled) {
            mpv.setOptionString("vf", "format=yuv420p")
        }
        mpv.setOptionString("msg-level", "all=warn")
        // The app supplies its own controls; avoid loading mpv's built-in Lua console and its
        // extra interpreter state (also present in the native tombstones seen in production).
        mpv.setOptionString("load-console", "no")
        // No youtube-dl/yt-dlp exists on-device, and mpv's ytdl_hook is FATAL without one: for an
        // EXTENSIONLESS live URL (common in M3U playlists — /live/play/<token>/<id>) the hook takes
        // over the load, fails to spawn the missing binary, and ends the file instead of falling
        // through to ffmpeg (device-reproduced on Android TV, 1.5.8). .ts URLs bypassed the hook,
        // which masked this on Xtream. Every URL goes straight to the ffmpeg demuxer.
        mpv.setOptionString("ytdl", "no")
        // NEVER let the core self-quit: without idle=yes a FAILED load (dead IPTV channel) empties
        // the playlist and the core exits (event: shutdown) while the app still holds it — every
        // later load into that core is silently ignored (device-traced on Android TV, 2026-08-26;
        // bites any surface that reuses one core for sequential loads, e.g. the docked Live TV
        // guide's zapping). Canonical embedded-mpv setting (mpv-android ships it).
        mpv.setOptionString("idle", "yes")
        // Bound blocking network reads (ffmpeg rw_timeout): a half-dead live socket
        // otherwise wedges the demuxer — and with it any thread waiting on the core.
        mpv.setOptionString("network-timeout", "15")
        // keep-open (set below) parks the core on the last frame at EOF, which is right for a
        // file and wrong for a live channel: an IPTV panel closing the socket mid-stream reads
        // as a clean EOF. Letting ffmpeg re-open the URL heals a transient drop before the
        // app-level reconnect (LivePlaybackFreezeTracking) has to rebuild playback.
        mpv.setOptionString(
            "stream-lavf-o",
            "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5"
        )
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        val demuxerBytes = demuxerBytesFor(MemoryPortAccess.current().baseTier())
        mpv.setOptionString("demuxer-max-bytes", "${demuxerBytes.maxBytes}").logIfMpvError("demuxer-max-bytes")
        mpv.setOptionString("demuxer-max-back-bytes", "${demuxerBytes.maxBackBytes}").logIfMpvError("demuxer-max-back-bytes")
        Log.i(TAG, "mpv demuxer budget: fwd=${demuxerBytes.maxBytes} back=${demuxerBytes.maxBackBytes}")
        mpv.setOptionString("vd-lavc-film-grain", "cpu")
        mpv.setPropertyBoolean("keep-open", true)
        mpv.setPropertyBoolean("input-default-bindings", true)
        mpv.setPropertyBoolean("audio-fallback-to-null", true)
    }

    override fun postInitOptions() = Unit

    override fun observeProperties() {
        // Registered before the composable's observer so shadows are current when a
        // snapshot dispatch fires for the same event.
        mpv.addObserver(propertyShadow)
        val props = mapOf(
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "speed" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "video-params" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "video-bitrate" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "audio-bitrate" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            // Fires roughly per frame, like time-pos above; the shadow handler is a volatile
            // increment, so the cost is comparable to what this observer already carries.
            "estimated-vf-fps" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            // VO-level counters: change only when a frame is dropped or a vsync runs long, so
            // they are near-silent during healthy playback.
            "frame-drop-count" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "vo-delayed-frame-count" to MPV.mpvFormat.MPV_FORMAT_INT64,
        )
        props.forEach { (name, format) -> mpv.observeProperty(name, format) }
    }

    fun loadSource(
        sourceUrl: String,
        sourceAudioUrl: String?,
        requestHeaders: Map<String, String>,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
        playWhenReady: Boolean,
    ) {
        val sameSource =
            currentSourceUrl == sourceUrl &&
                currentSourceAudioUrl == sourceAudioUrl &&
                currentRequestHeaders == requestHeaders &&
                currentExternalSubtitles == externalSubtitles
        currentSourceUrl = sourceUrl
        currentSourceAudioUrl = sourceAudioUrl
        currentRequestHeaders = requestHeaders
        currentExternalSubtitles = externalSubtitles
        if (!sameSource) {
            ctl { loadCurrentSource(playWhenReady = playWhenReady) }
        } else {
            obsPaused = !playWhenReady
            ctl {
                applyRequestHeaders(requestHeaders)
                mpv.setPropertyBoolean("pause", !playWhenReady)
            }
        }
    }

    // Runs on the mpv-ctl thread only.
    private fun loadCurrentSource(playWhenReady: Boolean) {
        val sourceUrl = currentSourceUrl ?: return
        recordMpvStage("load_source")
        applyRequestHeaders(currentRequestHeaders)
        obsPaused = !playWhenReady
        mpv.setPropertyBoolean("pause", !playWhenReady)
        mpv.command("loadfile", sourceUrl.toMpvSource(), "replace")
        currentSourceAudioUrl?.takeIf { it.isNotBlank() }?.let { sourceAudioUrl ->
            mpv.command("audio-add", sourceAudioUrl.toMpvSource(), "auto")
        }
        currentExternalSubtitles.forEachIndexed { index, subtitle ->
            val flag = if (index == 0) "auto" else "cached"
            mpv.command("sub-add", subtitle.url, flag)
        }
        mpv.setPropertyBoolean("pause", !playWhenReady)
    }

    private fun String.toMpvSource(): String =
        if (!startsWith("file:", ignoreCase = true)) {
            this
        } else {
            runCatching { File(URI(this)).absolutePath }.getOrDefault(this)
        }

    fun setPaused(paused: Boolean) {
        // Optimistic shadow echo so a snapshot() issued right after reflects the intent;
        // mpv's own pause event confirms (or corrects) it moments later.
        obsPaused = paused
        ctl { mpv.setPropertyBoolean("pause", paused) }
    }

    // Route through ctl {} (mpv-ctl queue) so seeks never touch mpv on the main thread — see the ANR fix.
    fun seekToMs(positionMs: Long) {
        ctl { mpv.command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute") }
    }

    // Computed purely from the observed-property shadow — must stay free of mpv calls
    // (runs on the main thread from the poll loop and event dispatches).
    fun snapshot(): PlayerPlaybackSnapshot {
        val paused = obsPaused
        val pausedForCache = obsPausedForCache
        val idle = obsCoreIdle
        val ended = obsEofReached
        val seeking = obsSeeking
        val cacheBufferingState = obsCacheBufferingState
        // Live streams: libmpv reports a finite demuxer-cache extent as "duration", which the
        // shared controls would draw as a bounded VOD scrubber (capped at the cache window).
        // Zero it to match ExoPlayer's TIME_UNSET->0 for live, so no finite timeline is built.
        val durationMs = if (isLiveStream) 0L else obsDurationMs
        val positionMs = obsPositionMs
        val cachePositionMs = obsCachePositionMs
        val isCacheBuffering = cacheBufferingState != null && cacheBufferingState in 0 until 100
        val isLoading = pausedForCache ||
            (!paused && !ended && (seeking || isCacheBuffering || (idle && durationMs <= 0L)))
        // Advance the video-liveness tick at READ time from the mirrored fps (see MpvVideoOutputSignal):
        // estimated-vf-fps stops emitting once steady, so a callback-driven count would plateau on
        // healthy playback and read as a freeze. This matches iOS/desktop, which already poll it.
        obsVideoFrameTicks = MpvVideoOutputSignal.advance(obsVideoFrameTicks, obsEstimatedVfFps)
        return PlayerPlaybackSnapshot(
            isLoading = isLoading,
            isPlaying = !paused && !isLoading && !idle && !ended,
            isEnded = ended,
            durationMs = durationMs,
            positionMs = positionMs,
            bufferedPositionMs = maxOf(positionMs, cachePositionMs),
            playbackSpeed = obsSpeed.toFloat(),
            videoProgressTicks = obsVideoFrameTicks,
            // video-params is present exactly when a video track is decoding, and it is already
            // in the shadow — so "has a picture" costs nothing extra and stays off the main thread.
            hasVideoTrack = obsVideoParams != null,
            voDroppedFrameCount = obsVoDroppedFrames,
            voDelayedFrameCount = obsVoDelayedFrames,
            // ponytail: videoWidth/videoHeight left at their 0 defaults. Upstream read them via
            // mpv.getPropertyInt here, but snapshot() runs on the main thread and must stay mpv-free
            // (the ANR fix). To restore PiP aspect ratio, observe video-params/dw,dh in the property
            // shadow (obs*) and read them off-main like the other fields.
        )
    }

    fun shouldKeepScreenOn(): Boolean {
        val snapshot = snapshot()
        return snapshot.isPlaying || snapshot.isLoading
    }

    fun applyResizeMode(resizeMode: PlayerResizeMode) = ctl {
        when (resizeMode) {
            PlayerResizeMode.Fit -> {
                mpv.setPropertyDouble("panscan", 0.0)
                mpv.setPropertyString("video-aspect-override", "no")
            }
            PlayerResizeMode.Fill -> {
                mpv.setPropertyDouble("panscan", 1.0)
                mpv.setPropertyString("video-aspect-override", "no")
            }
            PlayerResizeMode.Zoom -> {
                mpv.setPropertyDouble("panscan", 0.5)
                mpv.setPropertyString("video-aspect-override", "no")
            }
        }
    }

    fun seekByMs(offsetMs: Long) {
        ctl { mpv.command("seek", (offsetMs / 1000.0).toString(), "relative") }
    }

    /**
     * Stream facts for the info panel, read off the observed-property shadow only — never
     * the mpv core, so this is safe on the main thread (a synchronous mpv_get_property
     * takes the core lock, which a wedged live demuxer holds for seconds).
     *
     * Total by contract: any node access can throw if mpv publishes an unexpected shape,
     * and this is called straight from the UI event that opens the panel.
     */
    fun readStreamInfo(): PlayerStreamInfo = runCatching {
        val tracks = obsTrackList?.asArray()?.toList().orEmpty()
        fun selected(type: String) = tracks.firstOrNull {
            it.nodeString("type") == type && it.nodeBoolean("selected") == true
        }
        val video = selected("video")
        val audio = selected("audio")
        PlayerStreamInfo(
            videoCodec = StreamCodecNames.display(video?.nodeString("codec")),
            videoWidth = obsVideoParams?.nodeInt("w") ?: video?.nodeInt("demux-w"),
            videoHeight = obsVideoParams?.nodeInt("h") ?: video?.nodeInt("demux-h"),
            videoFrameRate = video?.nodeDouble("demux-fps")?.toFloat()?.takeIf { it > 0f },
            // Bits per second, same unit as ExoPlayer's Format.bitrate. Measured first,
            // then the container's average, then the HLS variant's declared rate — the
            // only one many Xtream live channels expose.
            videoBitrate = (
                obsVideoBitrate
                    ?: video?.nodeDouble("demux-bitrate")
                    ?: video?.nodeDouble("hls-bitrate")
                )?.takeIf { it > 0.0 }?.toInt(),
            audioCodec = StreamCodecNames.display(audio?.nodeString("codec")),
            audioChannelCount = audio?.nodeInt("demux-channel-count")
                ?: audio?.nodeInt("audio-channels"),
            audioSampleRate = audio?.nodeInt("demux-samplerate"),
            audioBitrate = (obsAudioBitrate ?: audio?.nodeDouble("demux-bitrate"))
                ?.takeIf { it > 0.0 }?.toInt(),
            playerEngine = ENGINE_LABEL_LIBMPV,
        )
    }.getOrElse {
        Log.w(TAG, "Failed to read libmpv stream info: ${it.message}")
        PlayerStreamInfo(playerEngine = ENGINE_LABEL_LIBMPV)
    }

    fun controller(
        context: Context,
        nowPlayingController: AndroidPlayerNowPlayingController?,
    ): PlayerEngineController =
        object : PlayerEngineController {
            override fun play() = setPaused(false)

            override fun pause() = setPaused(true)

            override fun seekTo(positionMs: Long) = this@NuvioLibmpvView.seekToMs(positionMs)

            override fun seekBy(offsetMs: Long) = this@NuvioLibmpvView.seekByMs(offsetMs)

            override fun retry() {
                ctl { loadCurrentSource(playWhenReady = true) }
            }

            /**
             * `video-reload` reinitialises the video track off the demuxer that is already
             * connected, so a wedged decoder is reset without asking the provider for a new
             * link. Queued through [ctl] like every other mpv write — never on Main.
             */
            override fun resetVideoPipeline(): Boolean {
                ctl { mpv.command("video-reload") }
                return true
            }

            override fun getStreamInfo(): PlayerStreamInfo = readStreamInfo()

            override fun setPlaybackSpeed(speed: Float) {
                ctl { mpv.setPropertyDouble("speed", speed.coerceIn(0.25f, 4f).toDouble()) }
            }

            override fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {
                nowPlayingController?.updateMetadata(info)
            }

            override fun clearNowPlayingInfo() {
                nowPlayingController?.clear()
            }

            override fun setMuted(muted: Boolean) {
                ctl { mpv.setPropertyBoolean("mute", muted) }
            }

            override fun getAudioTracks(): List<AudioTrack> =
                extractLibmpvTracks(context, type = "audio").mapIndexed { index, track ->
                    AudioTrack(
                        index = index,
                        id = track.id.toString(),
                        label = track.label,
                        language = track.language,
                        isSelected = track.isSelected,
                    )
                }

            override fun getSubtitleTracks(): List<SubtitleTrack> =
                extractLibmpvTracks(context, type = "sub").mapIndexed { index, track ->
                    SubtitleTrack(
                        index = index,
                        id = track.id.toString(),
                        label = track.label,
                        language = track.language,
                        isSelected = track.isSelected,
                        isForced = track.isForced,
                    )
                }

            override fun selectAudioTrack(index: Int) {
                if (index < 0) {
                    ctl { mpv.setPropertyString("aid", "no") }
                } else {
                    extractLibmpvTracks(context, type = "audio").getOrNull(index)?.let { track ->
                        ctl { mpv.setPropertyInt("aid", track.id) }
                    }
                }
            }

            override fun selectSubtitleTrack(index: Int) {
                if (index < 0) {
                    ctl { mpv.setPropertyString("sid", "no") }
                } else {
                    extractLibmpvTracks(context, type = "sub").getOrNull(index)?.let { track ->
                        ctl { mpv.setPropertyInt("sid", track.id) }
                    }
                }
            }

            override fun setSubtitleUri(url: String) {
                ctl { mpv.command("sub-add", url, "select") }
            }

            override fun clearExternalSubtitle() {
                ctl { mpv.setPropertyString("sid", "no") }
            }

            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                selectSubtitleTrack(trackIndex)
            }

            override fun applySubtitleStyle(style: SubtitleStyleState) = ctl {
                mpv.setPropertyString("sub-ass-override", "no")
                mpv.setPropertyString("sub-color", style.textColor.toMpvColor())
                mpv.setPropertyString("sub-back-color", style.backgroundColor.toMpvColor())
                mpv.setPropertyString("sub-outline-color", style.outlineColor.toMpvColor())
                mpv.setPropertyString("sub-border-color", style.outlineColor.toMpvColor())
                mpv.setPropertyString("sub-border-style", style.toMpvSubtitleBorderStyle())
                mpv.setPropertyString("sub-bold", if (style.bold) "yes" else "no")
                mpv.setPropertyInt("sub-font-size", style.toMpvSubtitleFontSize())
                mpv.setPropertyInt("sub-outline-size", style.toMpvSubtitleOutlineSize())
                mpv.setPropertyInt("sub-border-size", style.toMpvSubtitleOutlineSize())
                mpv.setPropertyInt("sub-pos", (100 - style.bottomOffset / 10).coerceIn(0, 100))
                mpv.setPropertyBoolean("sub-filter-sdh", style.stripSdh)
                mpv.setPropertyBoolean("sub-filter-sdh-harder", style.stripSdh)
            }

            override fun setSubtitleDelayMs(delayMs: Int) {
                ctl {
                    mpv.setPropertyDouble(
                        "sub-delay",
                        delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS) / 1000.0,
                    )
                }
            }
        }

    private fun applyRequestHeaders(headers: Map<String, String>) {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (!userAgent.isNullOrBlank()) {
            mpv.setPropertyString("user-agent", userAgent)
        }
        val serialized = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .map { (key, value) -> "${key}: ${value.replace(",", "\\,")}" }
            .joinToString(",")
        mpv.setPropertyString("http-header-fields", serialized)
    }

    private fun extractLibmpvTracks(context: Context, type: String): List<LibmpvTrack> {
        val nodes = obsTrackList?.asArray()?.toList().orEmpty()
        return nodes
            .filter { node -> node.nodeString("type") == type }
            .mapIndexedNotNull { index, node ->
                val id = node.nodeInt("id") ?: return@mapIndexedNotNull null
                val rawLabel = node.nodeString("title")
                    ?: node.nodeString("external-filename")?.substringAfterLast('/')
                    ?: node.nodeString("codec")
                val language = node.nodeString("lang") ?: normalizeLanguageCode(rawLabel)
                val label = rawLabel?.takeIf { it.isNotBlank() }
                    ?: runBlocking { getString(Res.string.compose_player_track_number, index + 1) }
                LibmpvTrack(
                    id = id,
                    label = label,
                    language = language,
                    isSelected = node.nodeBoolean("selected") ?: false,
                    isForced = inferForcedSubtitleTrack(
                        label = label,
                        language = language,
                        trackId = id.toString(),
                        hasForcedSelectionFlag = node.nodeBoolean("forced") ?: false,
                    ),
                )
            }
    }
}

private const val MPV_DESTROY_WATCHDOG_MS = 20_000L

private data class LibmpvTrack(
    val id: Int,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    val isForced: Boolean,
)

/**
 * TS extractor flags for IPTV streams, including raw live MPEG-TS.
 *
 * IPTV live `.ts` streams frequently lack Access Unit Delimiters and IDR keyframes at the join
 * point — you connect mid-GOP, because the stream has been running for hours. Left to its defaults
 * ExoPlayer waits for a keyframe that never arrives and buffers forever without reaching READY.
 * That symptom is the entire reason mobile used to force libmpv for live, and forcing libmpv is
 * what dragged live through libplacebo (one leaked sync-file fd per frame, EMFILE in ~22 minutes)
 * and a flat 96MB mpv demuxer cache sized on API level rather than device memory.
 *
 * [DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS] tells the extractor to find frame
 * boundaries itself; [DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES] lets it start on
 * a non-IDR frame. NuvioTV sets exactly these two and dropped its own live force-libmpv as a
 * result; StreamVault (media3-only, no mpv at all) sets them plus MODE_SINGLE_PMT.
 *
 * The pre-existing HDMV DTS flag is retained — some providers' audio depends on it.
 * Pinned by LiveTsExtractorFlagsTest, because removing a flag here breaks live SILENTLY: no crash,
 * no error, just an infinite spinner.
 */
internal const val LIVE_TS_EXTRACTOR_FLAGS: Int =
    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
        DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM

/** Demuxer cache budget: the forward window plus the seek-back window, in bytes. */
internal data class MpvDemuxerBytes(val maxBytes: Long, val maxBackBytes: Long)

/**
 * mpv demuxer cache per memory tier. Pure so the numbers are testable.
 *
 * Replaces an API-level gate inherited from mpv-android (64MiB ≥ O_MR1, else 32) that gave a
 * 2GB phone the same 96MiB native allocation as an S24 Ultra. Tier subsumes the API gate:
 * pre-27 hardware is LOW-tier hardware. Same locked numbers as NuvioTV's `demuxerBytesFor`;
 * MID/HIGH keep the field-proven 64+32MiB split. The back buffer is the first thing cut
 * because mpv fills it to its cap on network streams ("it will simply use as much memory
 * this option allows" — options.rst, v0.41.0) and docked live never seeks into it — rewind
 * is catch-up, a separate stream. Research: research/mpv-demuxer-cache-tiering.md.
 */
internal fun demuxerBytesFor(tier: MemoryTier): MpvDemuxerBytes = when (tier) {
    MemoryTier.LOW -> MpvDemuxerBytes(
        maxBytes = 48L * 1024L * 1024L,
        maxBackBytes = 16L * 1024L * 1024L,
    )
    MemoryTier.MID, MemoryTier.HIGH -> MpvDemuxerBytes(
        maxBytes = 64L * 1024L * 1024L,
        maxBackBytes = 32L * 1024L * 1024L,
    )
}

/**
 * ExoPlayer's media buffer is plain byte[] on the Java heap. A flat 100MB target was ~40% of
 * the 256MB growth limit most phones grant this app (no largeHeap), and the field showed the
 * consequence: OutOfMemoryError at the growth limit on flagships and low_memory_kills on
 * everything else, minutes into a stream. Budget a quarter of the real heap instead, and less
 * on LOW-tier devices — the shared [MemoryTier] selector (isLowRamDevice OR memoryClass ≤ 192)
 * rather than the raw flag, so low-memory-class devices that never set it are covered too.
 */
private fun playerTargetBufferBytes(): Int = playerTargetBufferBytes(
    tier = MemoryPortAccess.current().baseTier(),
    maxHeapBytes = Runtime.getRuntime().maxMemory(),
)

internal fun playerTargetBufferBytes(tier: MemoryTier, maxHeapBytes: Long): Int {
    if (tier == MemoryTier.LOW) return 24 * 1024 * 1024
    return (maxHeapBytes / 4)
        .coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
        .toInt()
}

private fun Int.logIfMpvError(option: String) {
    if (this < 0) Log.w(TAG, "libmpv option failed: $option status=$this")
}

private fun Double?.toMillis(): Long =
    this?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L

private fun MPVNode.nodeString(key: String): String? =
    runCatching { this[key]?.asString() }.getOrNull()?.takeIf { it.isNotBlank() }

private fun MPVNode.nodeInt(key: String): Int? =
    runCatching { this[key]?.asInt()?.toInt() }.getOrNull()

private fun MPVNode.nodeBoolean(key: String): Boolean? =
    runCatching { this[key]?.asBoolean() }.getOrNull()

private fun MPVNode.nodeDouble(key: String): Double? =
    runCatching { this[key]?.asDouble() }.getOrNull()

private fun androidx.compose.ui.graphics.Color.toMpvColor(): String {
    val argb = toArgb()
    val alpha = (argb ushr 24) and 0xff
    val red = (argb shr 16) and 0xff
    val green = (argb shr 8) and 0xff
    val blue = argb and 0xff
    return "#%02X%02X%02X%02X".format(alpha, red, green, blue)
}

private fun androidx.compose.ui.graphics.Color.alphaByte(): Int =
    (toArgb() ushr 24) and 0xff

private fun SubtitleStyleState.toMpvSubtitleFontSize(): Int =
    (fontSizeSp * MPV_SUBTITLE_FONT_SIZE_SCALE).toInt().coerceIn(
        MPV_SUBTITLE_FONT_SIZE_MIN,
        MPV_SUBTITLE_FONT_SIZE_MAX,
    )

private fun SubtitleStyleState.toMpvSubtitleOutlineSize(): Int =
    if (!outlineEnabled) 0 else (outlineWidth * MPV_SUBTITLE_OUTLINE_SIZE_SCALE).toInt().coerceAtLeast(1)

private fun SubtitleStyleState.toMpvSubtitleBorderStyle(): String =
    if (outlineEnabled) {
        "outline-and-shadow"
    } else if (backgroundColor.alphaByte() > 0) {
        "opaque-box"
    } else {
        "outline-and-shadow"
    }

private const val MPV_SUBTITLE_FONT_SIZE_SCALE = 55.0 / 18.0
private const val MPV_SUBTITLE_FONT_SIZE_MIN = 36
private const val MPV_SUBTITLE_FONT_SIZE_MAX = 122
private const val MPV_SUBTITLE_OUTLINE_SIZE_SCALE = 1.5

private fun ExoPlayer.snapshot(): PlayerPlaybackSnapshot {
    val (videoWidth, videoHeight) = videoDimensions()
    return PlayerPlaybackSnapshot(
        isLoading = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING,
        isPlaying = isPlaying,
        isEnded = playbackState == Player.STATE_ENDED,
        durationMs = duration.coerceAtLeast(0L),
        positionMs = currentPosition.coerceAtLeast(0L),
        bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
        playbackSpeed = playbackParameters.speed,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        // The renderer's own count of frames it put on screen — the one playback signal audio
        // cannot keep alive, and the direct analogue of libvlc's `i_displayed_pictures`.
        videoProgressTicks = videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
        hasVideoTrack = videoFormat != null,
    )
}

private fun ExoPlayer.videoDimensions(): Pair<Int, Int> {
    val format = videoFormat ?: return videoSize.width to videoSize.height
    val hasCrop = format.decodedWidth != Format.NO_VALUE &&
        format.decodedHeight != Format.NO_VALUE &&
        (format.decodedWidth > format.width || format.decodedHeight > format.height)
    val baseWidth = if (hasCrop) format.width else (format.width.takeIf { it > 0 } ?: videoSize.width)
    val baseHeight = if (hasCrop) format.height else (format.height.takeIf { it > 0 } ?: videoSize.height)
    val ratio = format.pixelWidthHeightRatio
    return if (ratio != 1f) (baseWidth * ratio).roundToInt() to baseHeight else baseWidth to baseHeight
}

private fun ExoPlayer.shouldKeepPlayerScreenOn(): Boolean =
    playerError == null &&
        playWhenReady &&
        playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)

private data class TrackSelectionSnapshot(
    val trackType: Int,
    val index: Int,
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val codecs: String?,
    val channelCount: Int,
    val roleFlags: Int,
)

private fun ExoPlayer.captureSelectedTrack(trackType: Int): TrackSelectionSnapshot? {
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        if (group.isSelected) {
            val format = group.mediaTrackGroup.getFormat(0)
            return TrackSelectionSnapshot(
                trackType = trackType,
                index = idx,
                id = format.id,
                language = format.language,
                label = format.label,
                sampleMimeType = format.sampleMimeType,
                codecs = format.codecs,
                channelCount = format.channelCount,
                roleFlags = format.roleFlags,
            )
        }
        idx++
    }
    return null
}

private fun ExoPlayer.restoreTrackSelection(selection: TrackSelectionSnapshot): Boolean {
    selection.id?.takeIf { it.isNotBlank() }?.let { id ->
        val restored = selectTrackByPredicate(selection.trackType, "id=$id") { _, format ->
            format.id == id
        }
        if (restored) {
            return true
        }
    }

    selection.label?.takeIf { it.isNotBlank() }?.let { label ->
        val restored = selectTrackByPredicate(selection.trackType, "label=$label") { _, format ->
            format.label.equals(label, ignoreCase = true) &&
                (selection.language.isNullOrBlank() ||
                    format.language.equals(selection.language, ignoreCase = true))
        }
        if (restored) {
            return true
        }
    }

    val technicalMatchIndexes = mutableListOf<Int>()
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != selection.trackType) continue
        val format = group.mediaTrackGroup.getFormat(0)
        if (
            !selection.language.isNullOrBlank() &&
            format.language.equals(selection.language, ignoreCase = true) &&
            format.sampleMimeType == selection.sampleMimeType &&
            format.codecs == selection.codecs &&
            format.channelCount == selection.channelCount &&
            format.roleFlags == selection.roleFlags
        ) {
            technicalMatchIndexes.add(idx)
        }
        idx++
    }
    if (technicalMatchIndexes.size == 1) {
        return selectTrackByIndex(selection.trackType, technicalMatchIndexes.first())
    }

    return selectTrackByIndex(selection.trackType, selection.index)
}

/**
 * The server refused the URL itself rather than the content being unplayable: 401/403 (token
 * expired, session taken over by another device, stream no longer authorised) or 410 (gone).
 *
 * These must NOT trigger the libmpv failover — libmpv would be handed the same dead link — and
 * must reach the screen so the expired-link recovery can mint a fresh one.
 */
private fun PlaybackException.isLinkAuthFailure(): Boolean {
    var current: Throwable? = cause
    while (current != null) {
        if (current is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            return current.responseCode == 401 || current.responseCode == 403 || current.responseCode == 410
        }
        current = current.cause
    }
    return false
}

private fun PlaybackException.isDecoderFailure(): Boolean =
    errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

private fun PlayerResizeMode.toExoResizeMode(): Int =
    when (this) {
        PlayerResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PlayerResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlayerResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

private fun PlayerView.syncLibassOverlay(
    player: ExoPlayer,
    enabled: Boolean,
    renderType: LibassRenderType,
) {
    val containerId = if (renderType == LibassRenderType.OVERLAY_OPEN_GL) {
        R.id.libass_overlay_container_gl
    } else {
        R.id.libass_overlay_container
    }
    val overlayContainer = findViewById<android.widget.FrameLayout>(containerId) ?: return
    val needsOverlay = enabled && renderType.usesOverlaySubtitleView()
    val boundPlayer = getTag(R.id.libass_overlay_bound_player) as? ExoPlayer
    val hasOverlayChild = overlayContainer.hasAssOverlayChild()

    if (!needsOverlay) {
        if (hasOverlayChild) {
            overlayContainer.removeAssOverlayChildren()
        }
        if (boundPlayer != null) {
            setTag(R.id.libass_overlay_bound_player, null)
        }
        return
    }

    val assHandler = player.getAssHandlerCompat() ?: return
    if (boundPlayer === player && hasOverlayChild) {
        return
    }

    overlayContainer.removeAssOverlayChildren()
    val assSubtitleView = AssSubtitleView(overlayContainer.context, assHandler)
    overlayContainer.addView(
        assSubtitleView,
        android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    )
    setTag(R.id.libass_overlay_bound_player, player)
}

private fun LibassRenderType.usesOverlaySubtitleView(): Boolean =
    this == LibassRenderType.OVERLAY_CANVAS || this == LibassRenderType.OVERLAY_OPEN_GL

private fun android.widget.FrameLayout.hasAssOverlayChild(): Boolean {
    for (index in 0 until childCount) {
        if (getChildAt(index) is AssSubtitleView) {
            return true
        }
    }
    return false
}

private fun android.widget.FrameLayout.removeAssOverlayChildren() {
    for (index in childCount - 1 downTo 0) {
        if (getChildAt(index) is AssSubtitleView) {
            removeViewAt(index)
        }
    }
}

private fun PlayerView.applySubtitleStyle(style: SubtitleStyleState, pipScale: Float = 1.0f) {
    subtitleView?.apply {
        val baseBottomPaddingFraction = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f
        val offsetFraction = (style.bottomOffset / 1000f).coerceIn(0f, 0.2f)
        val bottomPaddingFraction = (baseBottomPaddingFraction + offsetFraction).coerceIn(0f, 0.4f)

        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setBottomPaddingFraction(bottomPaddingFraction)
        setStyle(
            CaptionStyleCompat(
                style.textColor.toArgb(),
                style.backgroundColor.toArgb(),
                android.graphics.Color.TRANSPARENT,
                if (style.outlineEnabled) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
                style.outlineColor.toArgb(),
                if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            )
        )
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp.toFloat() * pipScale)
    }
}

private fun ExoPlayer.extractAudioTracks(context: Context): List<AudioTrack> {
    val tracks = mutableListOf<AudioTrack>()
    val trackNameProvider = CustomDefaultTrackNameProvider(context.resources)
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        val format = group.mediaTrackGroup.getFormat(0)
        val label = trackNameProvider.getTrackName(format).takeIf { it.isNotBlank() }
            ?: runBlocking { getString(Res.string.compose_player_track_number, idx + 1) }
        tracks.add(
            AudioTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = label,
                language = format.language,
                isSelected = group.isSelected,
            )
        )
        idx++
    }
    return tracks
}

private fun ExoPlayer.extractSubtitleTracks(context: Context): List<SubtitleTrack> {
    val tracks = mutableListOf<SubtitleTrack>()
    val trackNameProvider = CustomDefaultTrackNameProvider(context.resources)
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        val format = group.mediaTrackGroup.getFormat(0)
        val hasForcedSelectionFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        tracks.add(
            SubtitleTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = trackNameProvider.getTrackName(format),
                language = format.language,
                isSelected = group.isSelected,
                isForced = inferForcedSubtitleTrack(
                    label = format.label,
                    language = format.language,
                    trackId = format.id,
                    hasForcedSelectionFlag = hasForcedSelectionFlag,
                ),
            )
        )
        idx++
    }
    return tracks
}

private fun ExoPlayer.selectTrackByIndex(trackType: Int, targetIndex: Int): Boolean {
    return selectTrackByPredicate(trackType, "index=$targetIndex") { idx, _ ->
        idx == targetIndex
    }
}

private fun ExoPlayer.selectTrackByPredicate(
    trackType: Int,
    targetDescription: String,
    predicate: (index: Int, format: Format) -> Boolean,
): Boolean {
    val typeName = if (trackType == C.TRACK_TYPE_AUDIO) "AUDIO" else "TEXT"
    Log.d(TAG, "selectTrack: type=$typeName target=$targetDescription")
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        val format = group.mediaTrackGroup.getFormat(0)
        if (!predicate(idx, format)) {
            idx++
            continue
        }
        Log.d(TAG, "selectTrack: found group at idx=$idx, format.id=${format.id}, lang=${format.language}, label=${format.label}")
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(0))
            )
            .build()
        Log.d(TAG, "selectTrack: override applied")
        return true
    }
    Log.w(TAG, "selectTrack: no group found for type=$typeName target=$targetDescription (total groups scanned=$idx)")
    return false
}

private fun ExoPlayer.logCurrentTracks(context: String) {
    Log.d(TAG, "--- logCurrentTracks ($context) ---")
    Log.d(TAG, "  textDisabled=${trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
    for (group in currentTracks.groups) {
        val typeName = when (group.type) {
            C.TRACK_TYPE_AUDIO -> "AUDIO"
            C.TRACK_TYPE_TEXT -> "TEXT"
            C.TRACK_TYPE_VIDEO -> "VIDEO"
            else -> "OTHER(${group.type})"
        }
        if (group.type != C.TRACK_TYPE_TEXT && group.type != C.TRACK_TYPE_AUDIO) continue
        val format = group.mediaTrackGroup.getFormat(0)
        Log.d(TAG, "  group type=$typeName id=${format.id} lang=${format.language} label=${format.label} selected=${group.isSelected} supported=${group.isSupported}")
    }
    Log.d(TAG, "--- end logCurrentTracks ---")
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerView.videoBoundsFraction(aspectRatio: Float): RectF? {
    val subtitleView = this.subtitleView ?: return null
    val viewWidth = subtitleView.width.toFloat()
    val viewHeight = subtitleView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    if (aspectRatio > 0f) {
        val parentRatio = viewWidth / viewHeight
        return if (parentRatio > aspectRatio) {
            val fitW = viewHeight * aspectRatio
            val leftPx = (viewWidth - fitW) / 2f
            RectF(leftPx / viewWidth, 0f, (leftPx + fitW) / viewWidth, 1f)
        } else {
            val fitH = viewWidth / aspectRatio
            val topPx = (viewHeight - fitH) / 2f
            RectF(0f, topPx / viewHeight, 1f, (topPx + fitH) / viewHeight)
        }
    }

    val contentFrame = getTag(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout
        ?: findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.also { setTag(androidx.media3.ui.R.id.exo_content_frame, it) }
        ?: return null
    val frameWidth = contentFrame.width.toFloat()
    val frameHeight = contentFrame.height.toFloat()
    if (frameWidth <= 0f || frameHeight <= 0f) return null
    if (frameWidth > viewWidth || frameHeight > viewHeight) return null
    val left = contentFrame.x / viewWidth
    val top = contentFrame.y / viewHeight
    return RectF(
        left,
        top,
        left + frameWidth / viewWidth,
        top + frameHeight / viewHeight,
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val shouldStripSdhProvider: () -> Boolean,
    private val videoBoundsFractionProvider: () -> RectF?,
) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = output,
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider,
            shouldStripSdhProvider = shouldStripSdhProvider,
            videoBoundsFractionProvider = videoBoundsFractionProvider,
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
            )
        }
    }
}

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val shouldStripSdhProvider: () -> Boolean,
    private val videoBoundsFractionProvider: () -> RectF?,
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        val processed = cueGroup.cues.mapNotNull(::processCue)
        delegate.onCues(CueGroup(processed, cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(cues.mapNotNull(::processCue))
    }

    private fun processCue(cue: Cue): Cue? {
        var processed = fixRtlCueText(cue)
        if (shouldStripSdhProvider()) {
            val text = processed.text?.toString() ?: return processed
            val filtered = SubtitleSdhFilter.filter(text) ?: return null
            if (filtered != text) {
                processed = processed.buildUpon().setText(filtered).build()
            }
        }
        if (shouldNormalizeCuePositionProvider()) {
            processed = normalizeCuePosition(processed)
        }
        if (processed.bitmap != null) {
            val bounds = videoBoundsFractionProvider()
            if (bounds != null && bounds.width() > 0f && bounds.height() > 0f) {
                val isIdentity = bounds.left == 0f && bounds.top == 0f
                    && bounds.width() == 1f && bounds.height() == 1f
                if (!isIdentity) {
                    processed = remapBitmapCueToVideoBounds(processed, bounds)
                }
            }
        }
        return processed
    }

    private fun remapBitmapCueToVideoBounds(cue: Cue, bounds: RectF): Cue {
        val builder = cue.buildUpon()
        if (cue.position != Cue.DIMEN_UNSET) {
            builder.setPosition(bounds.left + cue.position * bounds.width())
        }
        if (cue.size != Cue.DIMEN_UNSET) {
            builder.setSize(cue.size * bounds.width())
        }
        if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET) {
            builder.setLine(bounds.top + cue.line * bounds.height(), Cue.LINE_TYPE_FRACTION)
        }
        if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
            builder.setBitmapHeight(cue.bitmapHeight * bounds.height())
        }
        return builder.build()
    }

    private fun normalizeCuePosition(cue: Cue): Cue {
        if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
            return cue
        }
        return cue.buildUpon()
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .build()
    }

    private fun fixRtlCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue
        if (!containsRtlChars(text)) return cue
        val original = text.toString()
        val fixed = original.split('\n').joinToString("\n") { line ->
            moveLeadingRtlPunctuationToEnd(line)
        }
        if (fixed == original) return cue
        return cue.buildUpon().setText(SpannableString(fixed)).build()
    }

    private fun moveLeadingRtlPunctuationToEnd(line: String): String {
        if (line.isEmpty()) return line
        var end = 0
        while (end < line.length && line[end] in RTL_PUNCTUATION) end++
        if (end == 0) return line
        return line.substring(end) + line.substring(0, end)
    }

    private fun containsRtlChars(text: CharSequence): Boolean {
        for (char in text) {
            val directionality = Character.getDirectionality(char)
            if (
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }
        }
        return false
    }

    companion object {
        private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(')
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderer(
    baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
) : ForwardingRenderer(baseRenderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

private fun resolveSubtitleMimeType(url: String, headers: Map<String, String>? = null): String {
    probeSubtitleHeaders(url, headers)?.let { (contentType, contentDisposition) ->
        mapSubtitleMime(contentType)?.let { return it }
        filenameFromContentDisposition(contentDisposition)?.let(::guessSubtitleMime)?.let { return it }
    }
    return guessSubtitleMime(url)
}

private fun probeSubtitleHeaders(url: String, headers: Map<String, String>? = null): Pair<String?, String?>? {
    val methods = listOf("HEAD", "GET")
    methods.forEach { method ->
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "*/*")
                headers?.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            try {
                connection.responseCode
                connection.contentType to connection.getHeaderField("Content-Disposition")
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.let { return it }
    }
    return null
}

private fun mapSubtitleMime(contentType: String?): String? {
    val normalized = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return null

    return when (normalized) {
        "application/x-subrip",
        "application/srt",
        "text/srt",
        "text/plain" -> MimeTypes.APPLICATION_SUBRIP
        "text/vtt",
        "application/vtt" -> MimeTypes.TEXT_VTT
        "text/x-ssa",
        "text/ssa",
        "text/ass",
        "application/x-ssa" -> MimeTypes.TEXT_SSA
        "application/ttml+xml",
        "text/xml",
        "application/xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }
}

private fun filenameFromContentDisposition(contentDisposition: String?): String? =
    contentDisposition
        ?.substringAfter("filename=", missingDelimiterValue = "")
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotEmpty() }

private fun guessSubtitleMime(url: String): String {
    val lower = url.lowercase()
    return when {
        lower.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
        lower.contains(".vtt") || lower.contains(".webvtt") -> MimeTypes.TEXT_VTT
        lower.contains(".ass") || lower.contains(".ssa") -> MimeTypes.TEXT_SSA
        lower.contains(".ttml") || lower.contains(".dfxp") || lower.contains(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}

private fun diagnosticElapsedSince(startedAtMs: Long): Long =
    if (startedAtMs <= 0L) -1L else (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)

private fun diagnosticPlaybackSource(value: String): String = runCatching {
    val uri = Uri.parse(value)
    val host = uri.host.orEmpty()
    val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
    "scheme=${uri.scheme ?: "none"},host=${host.ifBlank { "none" }},port=${uri.port},loopback=$isLoopback"
}.getOrDefault("unparseable")

private fun isLoopbackPlaybackSource(value: String): Boolean = runCatching {
    when (Uri.parse(value).host.orEmpty().lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }
}.getOrDefault(false)

private fun diagnosticPlayerMessage(value: String?): String =
    value?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "none"

private fun diagnosticThrowableChain(value: Throwable): String =
    generateSequence(value) { it.cause }
        .take(6)
        .joinToString(" -> ") { error ->
            "${error.javaClass.simpleName}:${diagnosticPlayerMessage(error.message)}"
        }
        .let(::diagnosticPlayerMessage)

internal class SubtitleRequestHeaderDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        SubtitleRequestHeaderDataSource(
            upstream = upstreamFactory.createDataSource(),
            externalSubtitles = externalSubtitles,
        )
}

internal class SubtitleRequestHeaderDataSource(
    private val upstream: DataSource,
    private val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val subtitle = externalSubtitles.find { it.url == url }
        val headers = subtitle?.headers
        
        return if (headers.isNullOrEmpty()) {
            upstream.open(dataSpec)
        } else {
            val mergedHeaders = dataSpec.httpRequestHeaders.toMutableMap()
            headers.forEach { (key, value) ->
                mergedHeaders[key] = value
            }
            upstream.open(dataSpec.buildUpon().setHttpRequestHeaders(mergedHeaders).build())
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}
