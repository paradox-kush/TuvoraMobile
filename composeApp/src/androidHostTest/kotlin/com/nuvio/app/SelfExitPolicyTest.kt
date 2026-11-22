package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Our own deliberate process kill must be counted but never filed as a fatal `signaled` issue. */
class SelfExitPolicyTest {
    private val foreground = 100
    private val cached = 400

    @Test
    fun `a self exit tagged user_exit at foreground is counted but not promoted`() {
        assertEquals(
            AppExitReportPolicy.Outcome.CountOnly("user_exit"),
            AppExitReportPolicy.classify("signaled", foreground, "user_exit"),
            "our own SIGKILL at foreground is a deliberate exit, not a crash",
        )
    }

    @Test
    fun `an untagged foreground SIGKILL still promotes`() {
        assertEquals(
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("signaled", foreground, null),
            "a real kill must never be hidden",
        )
    }

    @Test
    fun `an untagged cached SIGKILL is still dropped`() {
        assertEquals(
            AppExitReportPolicy.Outcome.Drop,
            AppExitReportPolicy.classify("signaled", cached, null),
        )
    }

    @Test
    fun `a tag never excuses a crash or anr that happens to share the record`() {
        assertEquals(
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("native_crash", foreground, "user_exit"),
        )
        assertEquals(
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("anr", foreground, "restart"),
        )
    }

    @Test
    fun `foreground low memory kill still reports and promotes`() {
        assertEquals(
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("low_memory_kill", foreground, null),
        )
    }

    @Test
    fun `marker round trips and rejects unknown tags`() {
        assertEquals("restart|123|456", SelfExitMarker.encode("restart", 123, 456))
        assertEquals(SelfExitMarker("icon_change", 7, 99L), SelfExitMarker.decode("icon_change|7|99"))
        assertNull(SelfExitMarker.encode("rm -rf", 1, 1), "tags are an allow-list")
        assertNull(SelfExitMarker.decode("bogus|1|2"))
        assertNull(SelfExitMarker.decode("user_exit|x|2"))
        assertNull(SelfExitMarker.decode(null))
    }

    @Test
    fun `marker matches only the same pid within the window`() {
        val m = SelfExitMarker("user_exit", pid = 2973, atMs = 1_000_000L)
        assertEquals("user_exit", SelfExitMarker.tagFor(m, 2973, 1_000_050L))
        assertNull(SelfExitMarker.tagFor(m, 2974, 1_000_050L), "different pid is a different process")
        assertNull(SelfExitMarker.tagFor(m, 2973, 1_000_000L + SelfExitMarker.MATCH_WINDOW_MS + 1), "a stale marker must not mask a later kill")
        assertNull(SelfExitMarker.tagFor(m, 2973, 900_000L), "an exit before the marker is not ours")
        assertNull(SelfExitMarker.tagFor(null, 2973, 1_000_050L))
    }
}
