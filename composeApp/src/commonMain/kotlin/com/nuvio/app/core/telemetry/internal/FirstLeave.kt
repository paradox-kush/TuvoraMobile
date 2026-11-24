package com.nuvio.app.core.telemetry.internal

/**
 * Pure pieces of the `first_leave` summary (contract Addendum 1): no clock, no SDK, no Android. The persisted facts
 * (form opened, save tried, last save error) live in [FirstRunState]; the per-process ones live in [FirstLeaveVisit].
 */
internal object FirstLeavePolicy {
    const val OTHER = "other"
    const val MAX_SCREEN_LENGTH = 40

    /**
     * A screen identifier reduced to `[a-z0-9_.]`, lower-cased, at most [MAX_SCREEN_LENGTH] chars; any other run of
     * characters becomes one `_`. Empty or nothing left -> `other`. The input is always one of the app's own route names,
     * so this is a second guard, never the only one.
     */
    fun sanitizeScreen(raw: String?): String {
        val out = StringBuilder()
        for (ch in raw.orEmpty().lowercase()) {
            val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '.'
            if (ok) out.append(ch) else if (out.isNotEmpty() && out.last() != '_') out.append('_')
        }
        val cleaned = out.toString().trim('_').take(MAX_SCREEN_LENGTH).trimEnd('_')
        return cleaned.ifEmpty { OTHER }
    }
}

/**
 * What the person did since this process started: the last screen, how many distinct screens, and when the current
 * foreground stint began. Caller serialises access (the service holds its funnel lock). A bounded set: a screen
 * identifier comes from a fixed route vocabulary, and the cap keeps a bug from growing it without end.
 */
internal class FirstLeaveVisit(startMs: Long) {
    private var foregroundSinceMs = startMs
    private var lastScreen: String? = null
    private val seen = LinkedHashSet<String>()

    fun screen(raw: String) {
        if (raw.isBlank()) return
        val id = FirstLeavePolicy.sanitizeScreen(raw)
        lastScreen = id
        if (seen.size < MAX_SCREENS) seen += id
    }

    /** The app came back to the foreground: a new stint starts. */
    fun foreground(nowMs: Long) {
        foregroundSinceMs = nowMs
    }

    /** `last_screen`, `secs_in_app`, `screens_seen` as of [nowMs] (the moment the app left the foreground). */
    fun props(nowMs: Long): Map<String, Any> = mapOf(
        "last_screen" to (lastScreen ?: FirstLeavePolicy.OTHER),
        "secs_in_app" to ((nowMs - foregroundSinceMs) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        "screens_seen" to seen.size,
    )

    private companion object {
        const val MAX_SCREENS = 256
    }
}
