package com.nuvio.app.core.telemetry.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Playback telemetry contract (2026-11-25), shared names with NuvioTV and NuvioDesktop.
 *
 * Routine playback successes are NEVER events: they are folded into `session_health` counts through
 * [TelemetryPort.recordPlayback]. Every dimension below is a closed enum, so a count key can never carry a host,
 * URL, channel name or error text. Pure code only: no SDK, no Compose, no platform imports (`:tvosCore` compiles it).
 */

enum class PlaybackKind { LIVE, VOD, PREVIEW, MULTIVIEW_TILE;
    val label: String get() = name.lowercase()
}

enum class PlaybackEngine { MEDIA3, LIBMPV, AVPLAYER, OTHER;
    val label: String get() = name.lowercase()

    companion object {
        /** The app's engine labels (`ExoPlayer`, `libmpv`, `avplayer`, ...) onto the contract's closed set. */
        fun fromLabel(label: String?): PlaybackEngine = when (label?.trim()?.lowercase()) {
            "media3", "exoplayer", "exo" -> MEDIA3
            "libmpv", "mpv" -> LIBMPV
            "avplayer", "avfoundation" -> AVPLAYER
            else -> OTHER
        }
    }
}

enum class FallbackTrigger { MANIFEST_INVALID, DEMUX_FAILED, DECODER_FAILED, RENDERER_FAILED, NETWORK, TIMEOUT, WATCHDOG, USER, OTHER;
    val label: String get() = name.lowercase()

    companion object {
        /** From a `PlaybackFailureCategoryPolicy` category (`network`, `decoder`, `http_403`, ...): never from raw text. */
        fun fromFailureCategory(category: String?): FallbackTrigger = when {
            category == null -> OTHER
            category == "timeout" -> TIMEOUT
            category == "network" || category.startsWith("http_") -> NETWORK
            category == "decoder" -> DECODER_FAILED
            category == "unsupported" -> DEMUX_FAILED
            else -> OTHER
        }
    }
}

/** `abandoned` = the viewer left (Back / zapped / stopped) while the picture was still frozen. */
enum class StallOutcome { RECOVERED, GAVE_UP, ABANDONED;
    val label: String get() = name.lowercase()
}

enum class TeardownOutcome { CLEAN, SLOW, FAILED;
    val label: String get() = name.lowercase()
}

/** One playback fact. The service turns it into counts (never an event) and, when the QA mirror is on, a logcat line. */
sealed interface PlaybackSignal {
    /** Milliseconds from the play request to the first rendered frame (or the app's first-frame/ready signal). */
    data class Ttff(val kind: PlaybackKind, val engine: PlaybackEngine, val ms: Long) : PlaybackSignal
    data class Fallback(val from: PlaybackEngine, val to: PlaybackEngine, val trigger: FallbackTrigger) : PlaybackSignal
    data class Stall(val kind: PlaybackKind, val outcome: StallOutcome, val ms: Long) : PlaybackSignal
    data class Teardown(val engine: PlaybackEngine, val ms: Long, val failed: Boolean = false) : PlaybackSignal

    /** The OS told the app it is short of memory (Apple: `didReceiveMemoryWarning`). Counted by where the app was, never an event. */
    data class MemoryWarning(val foreground: Boolean) : PlaybackSignal
}

/** Bucket boundaries. Names are the contract's; the lower bound of each bucket is inclusive. */
object PlaybackTelemetryBuckets {
    const val TEARDOWN_SLOW_MS = 1_500L

    val TTFF = listOf("lt250", "lt500", "lt1000", "lt2000", "lt4000", "lt8000", "ge8000")
    val STALL = listOf("lt2000", "lt5000", "lt15000", "lt45000", "ge45000")
    val RAM_CLASS = listOf("lt1_2", "lt2", "lt3", "ge3")

    fun ttff(ms: Long): String = when {
        ms < 250L -> "lt250"
        ms < 500L -> "lt500"
        ms < 1_000L -> "lt1000"
        ms < 2_000L -> "lt2000"
        ms < 4_000L -> "lt4000"
        ms < 8_000L -> "lt8000"
        else -> "ge8000"
    }

    fun stall(ms: Long): String = when {
        ms < 2_000L -> "lt2000"
        ms < 5_000L -> "lt5000"
        ms < 15_000L -> "lt15000"
        ms < 45_000L -> "lt45000"
        else -> "ge45000"
    }

    /** `slow` is strictly over [TEARDOWN_SLOW_MS]. A failed teardown is `failed` whatever it took. */
    fun teardown(ms: Long, failed: Boolean): TeardownOutcome = when {
        failed -> TeardownOutcome.FAILED
        ms > TEARDOWN_SLOW_MS -> TeardownOutcome.SLOW
        else -> TeardownOutcome.CLEAN
    }

    /** Total device RAM: under 1.2 GB, under 2 GB, under 3 GB, 3 GB or more (binary GB: 1.2 GB = 1229 MB). */
    fun ramClass(totalMb: Long): String = when {
        totalMb < 1_229L -> "lt1_2"
        totalMb < 2_048L -> "lt2"
        totalMb < 3_072L -> "lt3"
        else -> "ge3"
    }
}

/** Builds the `session_health.counts` keys (`<name>:<dim1>:<dim2>`), the same strings on every platform. */
object PlaybackTelemetryKeys {
    const val TTFF = "ttff"
    const val FALLBACK = "fallback"
    const val STALL = "stall"
    const val STALL_MS = "stall_ms"
    const val TEARDOWN = "teardown"
    const val MEMWARN = "memwarn"

    fun memwarn(foreground: Boolean): String = "$MEMWARN:${if (foreground) "foreground" else "background"}"

    fun ttff(kind: PlaybackKind, engine: PlaybackEngine, ms: Long): String =
        "$TTFF:${kind.label}:${engine.label}:${PlaybackTelemetryBuckets.ttff(ms)}"

    fun fallback(from: PlaybackEngine, to: PlaybackEngine, trigger: FallbackTrigger): String =
        "$FALLBACK:${from.label}:${to.label}:${trigger.label}"

    fun stall(kind: PlaybackKind, outcome: StallOutcome): String = "$STALL:${kind.label}:${outcome.label}"

    fun stallMs(kind: PlaybackKind, ms: Long): String = "$STALL_MS:${kind.label}:${PlaybackTelemetryBuckets.stall(ms)}"

    fun teardown(engine: PlaybackEngine, outcome: TeardownOutcome): String = "$TEARDOWN:${engine.label}:${outcome.label}"

    /** Every key one signal increments. Routine successes are counts only: nothing here is an event. */
    fun forSignal(signal: PlaybackSignal): List<String> = when (signal) {
        is PlaybackSignal.Ttff -> listOf(ttff(signal.kind, signal.engine, signal.ms))
        is PlaybackSignal.Fallback -> listOf(fallback(signal.from, signal.to, signal.trigger))
        is PlaybackSignal.Stall -> listOf(stall(signal.kind, signal.outcome), stallMs(signal.kind, signal.ms))
        is PlaybackSignal.Teardown ->
            listOf(teardown(signal.engine, PlaybackTelemetryBuckets.teardown(signal.ms, signal.failed)))
        is PlaybackSignal.MemoryWarning -> listOf(memwarn(signal.foreground))
    }

    private val kinds = PlaybackKind.entries.map { it.label }.toSet()
    private val engines = PlaybackEngine.entries.map { it.label }.toSet()
    private val triggers = FallbackTrigger.entries.map { it.label }.toSet()
    private val stallOutcomes = StallOutcome.entries.map { it.label }.toSet()
    private val teardownOutcomes = TeardownOutcome.entries.map { it.label }.toSet()

    /** True only for a key built from the closed vocabularies above. The service refuses anything else. */
    fun isValidKey(key: String): Boolean {
        val p = key.split(':')
        return when (p.firstOrNull()) {
            TTFF -> p.size == 4 && p[1] in kinds && p[2] in engines && p[3] in PlaybackTelemetryBuckets.TTFF
            FALLBACK -> p.size == 4 && p[1] in engines && p[2] in engines && p[3] in triggers
            STALL -> p.size == 3 && p[1] in kinds && p[2] in stallOutcomes
            STALL_MS -> p.size == 3 && p[1] in kinds && p[2] in PlaybackTelemetryBuckets.STALL
            TEARDOWN -> p.size == 3 && p[1] in engines && p[2] in teardownOutcomes
            MEMWARN -> p.size == 2 && p[1] in setOf("foreground", "background")
            else -> false
        }
    }
}

/**
 * Closed-vocabulary properties for the failure diagnostics (`ext_class`, `source_type`, `res_class`, `http_class`,
 * `attempt_n`, `ram_class`). Each function maps raw local facts to one token; the raw fact never leaves.
 */
object PlaybackFailureProps {
    val EXT_CLASS = setOf("m3u8", "ts", "mp4", "mkv", "none", "other")
    val CONTENT_TYPE_CLASS = setOf("hls", "mp2t", "octet", "html", "text", "video", "other")
    val SOURCE_TYPE = setOf("xtream", "stalker", "m3u", "file", "addon", "media_server")
    val RES_CLASS = setOf("sd", "hd720", "fhd", "uhd", "unknown")
    val HTTP_CLASS = setOf("none", "2xx", "3xx", "401_403", "404", "456", "4xx_other", "5xx")

    /** The path's extension only; the URL itself is never kept. */
    fun extClass(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val last = path.substringAfterLast('/')
        if ('.' !in last) return "none"
        return when (last.substringAfterLast('.')) {
            "m3u8", "m3u" -> "m3u8"
            "ts" -> "ts"
            "mp4", "m4v" -> "mp4"
            "mkv" -> "mkv"
            else -> "other"
        }
    }

    /** A `Content-Type` header value reduced to a class. No engine on Mobile exposes it today; kept for the engines that will. */
    fun contentTypeClass(header: String?): String {
        val v = header?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            v.isEmpty() -> "other"
            v.contains("mpegurl") -> "hls"
            v == "video/mp2t" -> "mp2t"
            v == "application/octet-stream" || v == "binary/octet-stream" -> "octet"
            v == "text/html" || v == "application/xhtml+xml" -> "html"
            v.startsWith("text/") -> "text"
            v.startsWith("video/") -> "video"
            else -> "other"
        }
    }

    fun resClass(videoHeight: Int): String = when {
        videoHeight <= 0 -> "unknown"
        videoHeight < 720 -> "sd"
        videoHeight < 1_080 -> "hd720"
        videoHeight < 2_160 -> "fhd"
        else -> "uhd"
    }

    /** From a failure category (`http_403`, `network`, ...); anything that is not an HTTP status is `none`. */
    fun httpClass(category: String?): String {
        val status = category?.takeIf { it.startsWith("http_") }?.removePrefix("http_")?.toIntOrNull() ?: return "none"
        return when {
            status in 200..299 -> "2xx"
            status in 300..399 -> "3xx"
            status == 401 || status == 403 -> "401_403"
            status == 404 -> "404"
            status == 456 -> "456"
            status in 400..499 -> "4xx_other"
            status in 500..599 -> "5xx"
            else -> "none"
        }
    }

    /** Id prefixes only (`xtream:`); the caller says whether the id is a Stalker one. */
    fun sourceType(contentId: String?, isStalker: Boolean): String = when {
        isStalker -> "stalker"
        contentId?.startsWith("xtream:") == true -> "xtream"
        else -> "addon"
    }
}

/**
 * Local-only mirror of every playback signal and memory sample for the QA lab: one logcat line per signal, JSON, exact
 * values. [lineFor] is pure; [mirror] writes only when [enabled], so an off mirror costs one boolean test.
 */
object QaMirror {
    const val TAG = "TuvoraQa"

    /** Android system property (`setprop debug.tuvora.qa 1`) and the JVM one (`-Dtuvora.qa=1`). */
    const val ANDROID_PROPERTY = "debug.tuvora.qa"
    const val JVM_PROPERTY = "tuvora.qa"

    fun isEnabledValue(value: String?): Boolean = value?.trim() == "1"

    /** `{"ev":"ttff","kind":"live","engine":"media3","ms":1840}`: one line, field order kept. */
    fun lineFor(ev: String, fields: Map<String, Any>): String {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        out["ev"] = JsonPrimitive(ev)
        for ((k, v) in fields) {
            out[k] = when (v) {
                is Boolean -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                is Double -> JsonPrimitive(v)
                is Float -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }
        return JsonObject(out).toString()
    }

    fun mirror(enabled: Boolean, sink: (String) -> Unit, ev: String, fields: Map<String, Any>) {
        if (!enabled) return
        runCatching { sink(lineFor(ev, fields)) }
    }

    /** The exact-value fields of a signal (the count keys only carry buckets). */
    fun fieldsFor(signal: PlaybackSignal): Pair<String, Map<String, Any>> = when (signal) {
        is PlaybackSignal.Ttff -> "ttff" to linkedMapOf("kind" to signal.kind.label, "engine" to signal.engine.label, "ms" to signal.ms)
        is PlaybackSignal.Fallback -> "fallback" to linkedMapOf(
            "from" to signal.from.label, "to" to signal.to.label, "trigger" to signal.trigger.label,
        )
        is PlaybackSignal.Stall -> "stall" to linkedMapOf("kind" to signal.kind.label, "outcome" to signal.outcome.label, "ms" to signal.ms)
        is PlaybackSignal.Teardown -> "teardown" to linkedMapOf(
            "engine" to signal.engine.label,
            "outcome" to PlaybackTelemetryBuckets.teardown(signal.ms, signal.failed).label,
            "ms" to signal.ms,
        )
        is PlaybackSignal.MemoryWarning -> "memwarn" to linkedMapOf("where" to if (signal.foreground) "foreground" else "background")
    }
}
