package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.PlaybackEngine
import com.nuvio.app.core.telemetry.api.PlaybackFailureProps
import com.nuvio.app.core.telemetry.api.PlaybackKind
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryBuckets
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryKeys

/**
 * The validator the tests run over every playback-telemetry payload (and the service over what it adds to an event).
 * PostHog's privacy layer redacts properties named `code`, `state`, `url`, `token`; hosts, users, passwords, MACs and
 * keys must never travel at all. Every string value must belong to the closed enum registered for its property; a
 * string property nobody registered is a violation, so a new field cannot slip out unreviewed.
 */
internal object PlaybackTelemetryPrivacy {
    val FORBIDDEN_NAMES: Set<String> = setOf("code", "state", "url", "token", "host", "user", "password", "mac", "key")

    private val kinds = PlaybackKind.entries.map { it.label }.toSet()
    private val engines = PlaybackEngine.entries.map { it.label }.toSet()

    /** Property name -> the only string values it may carry. */
    val ENUMS: Map<String, Set<String>> = mapOf(
        "ext_class" to PlaybackFailureProps.EXT_CLASS,
        "content_type_class" to PlaybackFailureProps.CONTENT_TYPE_CLASS,
        "source_type" to PlaybackFailureProps.SOURCE_TYPE,
        "res_class" to PlaybackFailureProps.RES_CLASS,
        "http_class" to PlaybackFailureProps.HTTP_CLASS,
        "ram_class" to PlaybackTelemetryBuckets.RAM_CLASS.toSet(),
        "prev_kind" to kinds + "other",
        "prev_engine" to engines,
        "playback_kind" to kinds + "other",
        "playback_engine" to engines + setOf("exoplayer", "libmpv", "other"),
        "reason" to setOf("unclean_exit_suspected"),
        "metrickit_source" to MetricKitEvent.SOURCES,
    )

    /** Numbers only: counts, megabytes, flags. */
    private val numericProps = setOf("attempt_n", "prev_tiles")
    private val numericPrefixes = listOf("mem_", "mk_")
    private val booleanProps = setOf("prev_playing", "was_playing")

    /** Empty when the payload is clean. Each entry names the offending property. */
    fun violations(props: Map<String, Any>): List<String> = buildList {
        for ((name, value) in props) {
            val lower = name.lowercase()
            if (lower in FORBIDDEN_NAMES) { add("forbidden property name: $name"); continue }
            if (':' in name) {
                // A session_health count: `n_<key>` where <key> is built from the closed vocabularies.
                if (!name.startsWith("n_") || !PlaybackTelemetryKeys.isValidKey(name.removePrefix("n_"))) add("open count key: $name")
                if (value !is Int) add("count not a number: $name")
                continue
            }
            when {
                name in booleanProps -> if (value !is Boolean) add("not a boolean: $name")
                name in numericProps || numericPrefixes.any { name.startsWith(it) } ->
                    if (value !is Int && value !is Long) add("not a number: $name")
                value is String -> {
                    val allowed = ENUMS[name]
                    when {
                        allowed == null -> add("unregistered string property: $name")
                        value !in allowed -> add("value outside its enum: $name")
                    }
                }
                value is Int || value is Long || value is Boolean || value is Double -> Unit
                else -> add("unsupported value type: $name")
            }
        }
    }
}
