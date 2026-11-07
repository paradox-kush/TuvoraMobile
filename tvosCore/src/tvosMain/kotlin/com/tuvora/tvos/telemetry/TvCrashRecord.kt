package com.tuvora.tvos.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The crash record (contract CrashRecordCodec, JSON v1): `support_code, app_version, at_ms, thread,
 * exception_type, stack (max 64 frames, cause chain), trail, memory`. Pure: the hook builds it from a
 * Throwable's text and the platform layer writes it. Messages are never included (they can carry URLs);
 * the stack is frames only.
 */
object TvCrashRecord {
    const val SCHEMA_VERSION = 1
    const val MAX_FRAMES = 64

    fun encode(
        crashId: String,
        supportCode: String,
        appVersion: String,
        atMs: Long,
        thread: String,
        exceptionType: String,
        stackFrames: List<String>,
        memoryPhysicalBytes: Long,
    ): String = buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("crash_id", crashId)
        put("support_code", supportCode)
        put("app_version", appVersion)
        put("at_ms", atMs)
        put("thread", thread)
        put("exception_type", exceptionType)
        put("stack", JsonArray(stackFrames.take(MAX_FRAMES).map { JsonPrimitive(scrub(it)) }))
        // The ring belongs to the Mobile lane's recorder; until it is merged there is no trail to attach.
        put("trail", JsonArray(emptyList()))
        put("memory", buildJsonObject { put("physical_bytes", memoryPhysicalBytes) })
    }.toString()

    /** Stack lines only: drops the "Exception: message" header and any URL that sneaked into a frame. */
    fun framesOf(stackTrace: String): List<String> =
        stackTrace.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && (it.startsWith("at ") || it.startsWith("Caused by") || it.contains("kfun:")) }
            .map { if (it.startsWith("Caused by")) "Caused by: " + it.substringAfter("Caused by:").trim().substringBefore(':') else it }
            .toList()

    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S+""")
    internal fun scrub(line: String): String = url.replace(line, "[REDACTED_URL]").take(300)
}
