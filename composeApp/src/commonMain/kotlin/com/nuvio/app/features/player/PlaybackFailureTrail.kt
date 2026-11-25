package com.nuvio.app.features.player

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.PlaybackFailureProps
import com.nuvio.app.core.telemetry.api.TelemetryAccess

/**
 * A playback failure as a flight-recorder entry, so a "won't play" report says WHY.
 *
 * Phone 1.21.0: a report for an add-on movie had a trail of screen changes ending at `player`, with nothing about the
 * failure. NuvioTV's trail carries failure code, engine and phase; this is the same intent with the phone's engines.
 *
 * Closed fields only: `surface` (vod/live/catchup), `engine`, `category` ([PlaybackFailureCategoryPolicy]) and
 * `phase` (open = no frame yet, playing = failed after playback started). Never the stream URL, title, provider or
 * the engine's message text — the message is read here, on the device, and only its category leaves.
 */
object PlaybackFailureTrail {
    const val CODE = "playback_failed"

    enum class Surface { VOD, LIVE, CATCHUP }
    enum class Phase { OPEN, PLAYING }

    private val engines = setOf("exoplayer", "libmpv", "avplayer", "vlc", "mpv")

    fun surfaceOf(streamType: String?, isCatchUpPlayback: Boolean): Surface = when {
        isCatchUpPlayback -> Surface.CATCHUP
        streamType?.trim()?.lowercase() == "live" -> Surface.LIVE
        else -> Surface.VOD
    }

    /** The engine label as one of a fixed set; anything else is `other`. */
    fun engineCode(engine: String): String = engine.trim().lowercase().takeIf { it in engines } ?: "other"

    /**
     * [extras] are the playback-telemetry failure properties (`ext_class`, `res_class`, `http_class`, `attempt_n`; see
     * `PlaybackFailureProps`): closed classes and a count, never the facts they were derived from.
     */
    fun entry(
        nowMs: Long,
        surface: Surface,
        engine: String,
        category: String,
        phase: Phase,
        extras: Map<String, Any> = emptyMap(),
    ): DiagEntry =
        DiagEntry(
            atMs = nowMs,
            area = DiagArea.PLAYBACK,
            code = CODE,
            fields = linkedMapOf<String, Any>(
                "surface" to surface.name.lowercase(),
                "engine" to engineCode(engine),
                "category" to category,
                "phase" to phase.name.lowercase(),
            ).also { it.putAll(extras) },
        )
}

/**
 * Turns an engine's failure text into a closed category. The text is the engine's own (ExoPlayer's display message ends
 * in its `[ERROR_CODE_…]` name, mpv's carries `HTTP error NNN` and its file_error/log line); it is read locally and the
 * result is always one of: `http_<status>`, network, timeout, decoder, unsupported, source_not_found, unknown.
 */
object PlaybackFailureCategoryPolicy {
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val DECODER = "decoder"
    const val UNSUPPORTED = "unsupported"
    const val SOURCE_NOT_FOUND = "source_not_found"
    const val UNKNOWN = "unknown"

    private val httpStatus = Regex("""\bhttp(?:\s+error)?\s+([1-5][0-9]{2})\b""", RegexOption.IGNORE_CASE)
    private val exoCode = Regex("""\bERROR_CODE_([A-Z0-9_]+)""")

    private val timeoutWords = listOf("timed out", "timeout", "time out")
    private val networkWords = listOf(
        "failed to resolve", "could not resolve", "name or service not known", "unknown host", "connection refused",
        "connection reset", "network is unreachable", "no route to host", "network error", "tls", "ssl", "certificate",
        "broken pipe",
    )
    private val unsupportedWords = listOf(
        "unrecognized file format", "not supported", "unsupported", "invalid data found", "no audio or video data played",
    )
    private val decoderWords = listOf(
        "decoder", "codec", "hwdec", "mediacodec", "video output initialization failed", "audio output initialization failed",
    )
    private val notFoundWords = listOf("no such file", "not found")

    fun categorize(message: String?): String {
        val text = message.orEmpty()
        if (text.isBlank()) return UNKNOWN
        httpStatus.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return "http_$it" }
        exoCode.find(text)?.groupValues?.get(1)?.let { return fromExoCodeName(it) }
        val lower = text.lowercase()
        return when {
            timeoutWords.any(lower::contains) -> TIMEOUT
            networkWords.any(lower::contains) -> NETWORK
            unsupportedWords.any(lower::contains) -> UNSUPPORTED
            decoderWords.any(lower::contains) -> DECODER
            notFoundWords.any(lower::contains) -> SOURCE_NOT_FOUND
            else -> UNKNOWN
        }
    }

    /** Media3 `PlaybackException.errorCodeName` without its `ERROR_CODE_` prefix (a stable public constant name). */
    private fun fromExoCodeName(name: String): String = when {
        name == "IO_NETWORK_CONNECTION_TIMEOUT" || name == "TIMEOUT" -> TIMEOUT
        name == "IO_NETWORK_CONNECTION_FAILED" || name == "IO_CLEARTEXT_NOT_PERMITTED" -> NETWORK
        name == "IO_FILE_NOT_FOUND" -> SOURCE_NOT_FOUND
        name == "DECODING_FORMAT_UNSUPPORTED" || name == "DECODING_FORMAT_EXCEEDS_CAPABILITIES" -> UNSUPPORTED
        name == "IO_INVALID_HTTP_CONTENT_TYPE" || name.startsWith("PARSING_") || name.startsWith("DRM_") -> UNSUPPORTED
        name.startsWith("DECODER_") || name.startsWith("DECODING_") || name.startsWith("AUDIO_TRACK_") -> DECODER
        else -> UNKNOWN
    }
}

/**
 * One per player surface. Remembers whether a frame has shown (the phase) and writes one trail entry per distinct
 * failure report: the iOS and Apple TV engines are polled, and a failure stays reported across polls.
 */
class PlaybackFailureTrailRecorder(
    private val surface: PlaybackFailureTrail.Surface,
    /** Only its extension is read (`ext_class`); never stored or sent. Null leaves `ext_class` out. */
    private val sourceUrl: String? = null,
    private val nowMs: () -> Long = { com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() },
    private val sink: (DiagEntry) -> Unit = { runCatching { TelemetryAccess.current().record(it) } },
) {
    private var started = false
    private var lastReported: String? = null
    private var attempts = 0
    private var videoHeight = 0

    fun onSnapshot(snapshot: PlayerPlaybackSnapshot) {
        if (snapshot.videoHeight > 0) videoHeight = snapshot.videoHeight
        // Not positionMs alone: a resume opens at its saved position before any frame shows.
        if (snapshot.videoProgressTicks > 0L || (snapshot.isPlaying && !snapshot.isLoading)) started = true
    }

    fun onError(message: String?, engine: String) {
        if (message == null) {
            lastReported = null
            return
        }
        val key = "$engine|$message"
        if (key == lastReported) return
        lastReported = key
        attempts += 1
        val category = PlaybackFailureCategoryPolicy.categorize(message)
        val extras = linkedMapOf<String, Any>()
        if (sourceUrl != null) extras["ext_class"] = PlaybackFailureProps.extClass(sourceUrl)
        extras["res_class"] = PlaybackFailureProps.resClass(videoHeight)
        extras["http_class"] = PlaybackFailureProps.httpClass(category)
        extras["attempt_n"] = attempts
        sink(
            PlaybackFailureTrail.entry(
                nowMs = nowMs(),
                surface = surface,
                engine = engine,
                category = category,
                phase = if (started) PlaybackFailureTrail.Phase.PLAYING else PlaybackFailureTrail.Phase.OPEN,
                extras = extras,
            ),
        )
    }
}
