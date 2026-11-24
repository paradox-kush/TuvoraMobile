package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.FirstRunNote
import com.nuvio.app.core.telemetry.api.FirstRunStep
import com.nuvio.app.core.telemetry.api.FirstRunVocab
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted funnel state. [existing] = an install that predates the feature: all steps count as done, nothing is
 * ever sent. [done] holds [FirstRunStep.wire] names.
 */
@Serializable
internal data class FirstRunState(
    val existing: Boolean,
    val firstLaunchAtMs: Long = 0L,
    val launchIndex: Int = 0,
    val done: Set<String> = emptySet(),
    /** First-visit facts for `first_leave`; persisted because the form may be opened in one launch and left in another. */
    val openedSourceForm: Boolean = false,
    val triedSave: Boolean = false,
    val saveError: String? = null,
)

/** [state] to persist (null = nothing to persist) and the event props to emit (null = send nothing). */
internal class FirstRunTransition(val state: FirstRunState?, val emit: Map<String, Any>?)

/**
 * The first-run funnel decisions. Pure: no I/O, no clock, no SDK, so it tests without the network
 * (contract: research/first-run-funnel-2026-11/CONTRACT.md).
 */
internal object FirstRunFunnel {
    const val EVENT = "first_run_step"
    const val SCHEMA_VERSION = 1

    /**
     * Once per process start. With no persisted state the install is decided here, once: [existingInstall] is only
     * asked then. An existing install persists "all done" and never emits; a new one persists its first-launch time
     * and `launch_index = 1` and emits `first_launch`. With state, a new install's `launch_index` goes up by one.
     */
    fun start(state: FirstRunState?, nowMs: Long, existingInstall: () -> Boolean): FirstRunTransition {
        if (state == null) {
            if (existingInstall()) return FirstRunTransition(FirstRunState(existing = true), null)
            val fresh = FirstRunState(
                existing = false,
                firstLaunchAtMs = nowMs,
                launchIndex = 1,
                done = setOf(FirstRunStep.FIRST_LAUNCH.wire),
            )
            return FirstRunTransition(fresh, props(fresh, FirstRunStep.FIRST_LAUNCH, nowMs, emptyMap()))
        }
        if (state.existing) return FirstRunTransition(null, null)
        return FirstRunTransition(state.copy(launchIndex = state.launchIndex + 1), null)
    }

    /** A step happened. Emits it the first time on a new install, never otherwise (also before [start]: dropped). */
    fun record(state: FirstRunState?, step: FirstRunStep, nowMs: Long, extra: Map<String, Any>): FirstRunTransition {
        if (state == null || state.existing || step.wire in state.done) return FirstRunTransition(null, null)
        // Someone who already played is not a drop-off: no leave summary (and nothing to mark done).
        if (step == FirstRunStep.FIRST_LEAVE && FirstRunStep.FIRST_PLAYBACK.wire in state.done) return FirstRunTransition(null, null)
        val next = state.copy(done = state.done + step.wire)
        return FirstRunTransition(next, props(next, step, nowMs, extra))
    }

    /**
     * A first-visit fact. Returns the state to persist, or null when nothing changes: no state yet, an existing install,
     * the leave gate already closed (it fired, or playback happened), an unchanged flag, or a note that is not persisted
     * (a screen lives in the per-process visit).
     */
    fun note(state: FirstRunState?, note: FirstRunNote): FirstRunState? {
        if (state == null || state.existing) return null
        if (FirstRunStep.FIRST_LEAVE.wire in state.done || FirstRunStep.FIRST_PLAYBACK.wire in state.done) return null
        val next = when (note) {
            is FirstRunNote.Screen -> return null
            FirstRunNote.SourceFormOpened -> state.copy(openedSourceForm = true)
            FirstRunNote.SaveTried -> state.copy(triedSave = true)
            is FirstRunNote.SaveFailed -> state.copy(saveError = note.kind.lowercase().takeIf { it in FirstRunVocab.saveErrors } ?: FirstRunVocab.SAVE_ERROR_OTHER)
        }
        return next.takeIf { it != state }
    }

    private fun props(state: FirstRunState, step: FirstRunStep, nowMs: Long, extra: Map<String, Any>): Map<String, Any> =
        LinkedHashMap<String, Any>().apply {
            put("step", step.wire)
            put("schema_version", SCHEMA_VERSION)
            put("launch_index", state.launchIndex)
            put("secs_since_first_launch", ((nowMs - state.firstLaunchAtMs) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            when (step) {
                FirstRunStep.SOURCE_ADDED -> {
                    put("source_kind", closed(extra["source_kind"], FirstRunVocab.sourceKinds))
                    put("via", closed(extra["via"], FirstRunVocab.vias))
                }
                FirstRunStep.FIRST_CONTENT_LOADED -> token(extra["surface"])?.let { put("surface", it) }
                FirstRunStep.FIRST_PLAYBACK -> {
                    put("kind", closed(extra["kind"], setOf("live", "vod")))
                    token(extra["engine"])?.let { put("engine", it) }
                }
                FirstRunStep.FIRST_LAUNCH -> Unit
                FirstRunStep.FIRST_LEAVE -> {
                    put("last_screen", FirstLeavePolicy.sanitizeScreen(extra["last_screen"] as? String))
                    put("secs_in_app", (extra["secs_in_app"] as? Int ?: 0).coerceAtLeast(0))
                    put("screens_seen", (extra["screens_seen"] as? Int ?: 0).coerceAtLeast(0))
                    put("opened_source_form", state.openedSourceForm)
                    put("tried_save", state.triedSave)
                    put("save_error", state.saveError ?: FirstRunVocab.SAVE_ERROR_NONE)
                }
            }
        }

    private fun closed(raw: Any?, allowed: Set<String>): String =
        (raw as? String)?.lowercase()?.takeIf { it in allowed } ?: "unknown"

    /** A code-like identifier (engine id, surface key): letters, digits and `_` only, so no URL or host can ride along. */
    private fun token(raw: Any?): String? =
        (raw as? String)?.lowercase()?.takeWhile { it.isLetterOrDigit() || it == '_' }?.take(24)?.ifEmpty { null }
}

internal object FirstRunStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: FirstRunState): String = json.encodeToString(FirstRunState.serializer(), state)

    fun decode(text: String?): FirstRunState? =
        if (text.isNullOrBlank()) null else runCatching { json.decodeFromString(FirstRunState.serializer(), text) }.getOrNull()
}

/** Pure reads of stored blobs for the existing-install check (the storage reads themselves live in the composition root). */
internal object FirstRunInstallSignals {
    /** A playlist store (a JSON array of accounts) with at least one row. A damaged, non-empty blob counts: something was saved. */
    fun playlistsPresent(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        return t.isNotEmpty() && t != "[]"
    }

    /** A watch-progress / watched payload that holds at least one entry anywhere (an empty shell written by a bare launch does not count). */
    fun hasEntries(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return false
        val element = runCatching { Json.parseToJsonElement(t) }.getOrNull() ?: return true // unreadable but present: something was saved
        return hasNonEmptyArray(element)
    }

    private fun hasNonEmptyArray(e: kotlinx.serialization.json.JsonElement): Boolean = when (e) {
        is kotlinx.serialization.json.JsonArray -> e.isNotEmpty()
        is kotlinx.serialization.json.JsonObject -> e.values.any(::hasNonEmptyArray)
        else -> false
    }
}
