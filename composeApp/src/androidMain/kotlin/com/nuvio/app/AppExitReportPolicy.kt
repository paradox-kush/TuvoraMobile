package com.nuvio.app

/**
 * Decides whether an [android.app.ApplicationExitInfo] record is worth reporting to analytics, or is
 * normal OS lifecycle that would skew crash metrics.
 *
 * On low-RAM devices the OS SIGKILLs a *cached* (backgrounded) process to reclaim memory when the
 * user switches apps; Android surfaces that as `REASON_SIGNALED` + `SIGKILL` (status 9) with a
 * background importance (and on devices that don't report `REASON_LOW_MEMORY`, it's the only way a
 * reclaim shows up). Per Android's docs that is normal resource management, not a crash.
 *
 * Rule (mirrors Google Play vitals' "user-perceived crash" concept):
 *  - App faults — crash / native_crash / anr / initialization_failure — ALWAYS report.
 *  - OS-initiated reclaim — signaled / low_memory_kill / excessive_resource_usage — reports only when
 *    the process was still USER-VISIBLE when it died; a cached/background kill is dropped.
 *
 * Pure + unit-tested: no Android types, just the reason string + the raw importance int.
 */
object AppExitReportPolicy {
    /**
     * Max `ActivityManager.RunningAppProcessInfo` importance that still counts as user-visible.
     * Foreground=100 .. cant-save-state=270 are user-visible; service=300, cached=400, gone=1000 are
     * background. Lower = more foreground. A literal so it compiles on every API level.
     */
    const val USER_VISIBLE_IMPORTANCE_MAX = 270

    private val APP_FAULT = setOf("crash", "native_crash", "anr", "initialization_failure")
    private val OS_RECLAIM = setOf("signaled", "low_memory_kill", "excessive_resource_usage")

    /** @param importance the raw `ApplicationExitInfo.getImportance()` value (0/unknown when unset). */
    fun shouldReport(reason: String, importance: Int): Boolean = when (reason) {
        in APP_FAULT -> true
        in OS_RECLAIM -> importance in 1..USER_VISIBLE_IMPORTANCE_MAX
        else -> false
    }

    /** What the reporter does with one exit record. */
    sealed interface Outcome {
        /** Emit `app_exit`; [promote] also files a PostHog issue. */
        data class Report(val promote: Boolean) : Outcome

        /** Our own deliberate exit: counted in session_health under [tag], never an issue. */
        data class CountOnly(val tag: String) : Outcome

        /** Normal OS lifecycle (cached reclaim): counted by the send policy, nothing else. */
        data object Drop : Outcome
    }

    /** `ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED`, as a literal so this stays Android-free. */
    private const val IMPORTANCE_CACHED = 400

    /**
     * @param selfExitTag the tag of a self-exit marker already matched to THIS exit record (pid and time), or null.
     */
    fun classify(reason: String, importance: Int, selfExitTag: String?): Outcome {
        // Only a SIGKILL record can be our own kill; a tag never excuses a crash, ANR or OS reclaim.
        if (reason == "signaled" && selfExitTag != null) return Outcome.CountOnly(selfExitTag)
        if (!shouldReport(reason, importance)) return Outcome.Drop
        val cachedLowMemory = reason == "low_memory_kill" && importance == IMPORTANCE_CACHED
        return Outcome.Report(promote = !cachedLowMemory)
    }
}

/**
 * A durable note, written right before the process kills ITSELF on purpose (user Exit, restart, icon change), that
 * lets the next launch tell our own SIGKILL apart from the OS killing us. Pure: no Android types.
 *
 * Wire form `tag|pid|atMs`. The match is by the dead process's pid AND a short time window, so a stale marker can
 * never excuse a later real kill (a reused pid after reboot, or a kill hours later, fails the window).
 */
data class SelfExitMarker(val tag: String, val pid: Int, val atMs: Long) {
    companion object {
        const val USER_EXIT = "user_exit"
        const val RESTART = "restart"
        const val ICON_CHANGE = "icon_change"
        private val ALLOWED = setOf(USER_EXIT, RESTART, ICON_CHANGE)

        /** Max gap between writing the marker and the OS stamping the exit record. */
        const val MATCH_WINDOW_MS = 15_000L

        fun encode(tag: String, pid: Int, atMs: Long): String? =
            if (tag in ALLOWED && pid > 0 && atMs > 0L) "$tag|$pid|$atMs" else null

        fun decode(raw: String?): SelfExitMarker? {
            val parts = raw?.takeIf { it.length <= 64 }?.split('|') ?: return null
            if (parts.size != 3 || parts[0] !in ALLOWED) return null
            val pid = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val at = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
            return SelfExitMarker(parts[0], pid, at)
        }

        /** The tag when [marker] explains an exit of [exitPid] stamped [exitTimestampMs], else null. */
        fun tagFor(marker: SelfExitMarker?, exitPid: Int, exitTimestampMs: Long): String? {
            if (marker == null || marker.pid != exitPid) return null
            val gap = exitTimestampMs - marker.atMs
            // Small negative slack: the exit record is stamped by system_server, the marker by this process.
            return if (gap in -1_000L..MATCH_WINDOW_MS) marker.tag else null
        }
    }
}
