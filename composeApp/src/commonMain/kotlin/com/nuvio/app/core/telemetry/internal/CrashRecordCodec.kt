package com.nuvio.app.core.telemetry.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What survives a crash: written synchronously in the dying process, sent once on the next launch.
 * JSON v1 (design contract): `support_code, app_version, at_ms, thread, exception_type, stack (max 64 frames,
 * cause chain), trail, memory`, plus `crash_id` (idempotency key) and `screen`.
 */
@Serializable
internal data class CrashRecord(
    val schema_version: Int = CrashRecordCodec.SCHEMA_VERSION,
    val crash_id: String,
    val support_code: String,
    val app_version: String,
    val at_ms: Long,
    val thread: String,
    val exception_type: String,
    val message: String? = null,
    val stack: List<String> = emptyList(),
    val trail: String? = null,
    val memory: Map<String, Long> = emptyMap(),
    val screen: String? = null,
)

internal object CrashRecordCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_FRAMES = 64
    const val MAX_MESSAGE = 300
    private const val MAX_FRAME_LENGTH = 220

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(record: CrashRecord): String = json.encodeToString(CrashRecord.serializer(), record)

    /** Null for anything unreadable or from a schema we do not know: the caller deletes it, never loops on it. */
    fun decode(text: String): CrashRecord? = runCatching {
        json.decodeFromString(CrashRecord.serializer(), text)
    }.getOrNull()?.takeIf { it.schema_version == SCHEMA_VERSION && it.crash_id.isNotBlank() }

    /** Flattens the throwable (cause chain included) into at most [MAX_FRAMES] scrubbed lines. */
    fun framesOf(error: Throwable): List<String> =
        error.stackTraceToString().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_FRAMES)
            .map { DiagTrailPolicy.scrubText(it, MAX_FRAME_LENGTH) }
            .toList()

    fun exceptionType(error: Throwable): String = error::class.qualifiedName ?: error::class.simpleName ?: "Throwable"

    fun messageOf(error: Throwable): String? =
        error.message?.let { DiagTrailPolicy.scrubText(it, MAX_MESSAGE) }?.takeIf { it.isNotBlank() }
}
