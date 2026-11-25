package com.nuvio.app.core.telemetry.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The last on-device memory sample, persisted so the NEXT launch can attach it to the exit of the run that produced it. */
internal data class MemoryRecord(val atMs: Long, val sample: Map<String, Long>)

internal object MemoryContext {
    const val FILE = "last_memory.json"

    fun encode(record: MemoryRecord): String =
        JsonObject(
            mapOf(
                "at" to JsonPrimitive(record.atMs),
                "m" to JsonObject(record.sample.mapValues { JsonPrimitive(it.value) }),
            ),
        ).toString()

    fun decode(text: String?): MemoryRecord? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val at = root["at"]?.jsonPrimitive?.longOrNull ?: return null
            val sample = root["m"]?.jsonObject?.mapNotNull { (k, v) -> v.jsonPrimitive.longOrNull?.let { k to it } }?.toMap().orEmpty()
            if (sample.isEmpty()) null else MemoryRecord(at, sample)
        }.getOrNull()
    }

    /** `mem_pss_mb`, `mem_java_mb`, `mem_native_mb`, `mem_avail_mb`, `mem_threads`, `mem_fds`: the property names on events. */
    fun asProperties(sample: Map<String, Long>): Map<String, Long> =
        sample.filterKeys { it in PROPERTY_KEYS }.mapKeys { "mem_${it.key}" }

    val PROPERTY_KEYS: Set<String> = setOf("pss_mb", "java_mb", "native_mb", "avail_mb", "threads", "fds", "ram_free_mb", "java_max_mb")

    /**
     * What an `app_exit` (always reported one launch late) gains: the previous run's last memory sample, when that sample
     * is not newer than the exit (an older exit in the same batch belongs to an earlier run), and `prev_*` copied from the
     * breadcrumb fields the exit already carries (`was_playing`, `playback_kind`, `playback_engine`).
     */
    fun exitContext(props: Map<String, Any>, previous: MemoryRecord?): Map<String, Any> = buildMap {
        val exitAt = (props["exit_timestamp_ms"] as? Long)
        if (previous != null && (exitAt == null || exitAt >= previous.atMs)) putAll(asProperties(previous.sample))
        (props["was_playing"] as? Boolean)?.let { put("prev_playing", it) }
        (props["playback_kind"] as? String)?.let { put("prev_kind", closed("prev_kind", it)) }
        (props["playback_engine"] as? String)?.let { put("prev_engine", closed("prev_engine", it)) }
    }

    /** The breadcrumb's free label reduced to its property's enum; anything else is `other`. */
    private fun closed(property: String, value: String): String {
        val v = value.lowercase()
        val allowed = PlaybackTelemetryPrivacy.ENUMS[property].orEmpty()
        return when {
            v in allowed -> v
            property == "prev_engine" -> com.nuvio.app.core.telemetry.api.PlaybackEngine.fromLabel(v).label
            else -> "other"
        }
    }
}
