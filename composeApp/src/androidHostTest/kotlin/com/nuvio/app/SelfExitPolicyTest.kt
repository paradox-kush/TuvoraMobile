package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    // --- post-report cleanup: one marker explains at most one earlier process, and never eats a newer one ---

    private val stale = "user_exit|100|1000"
    private val fresh = "user_exit|200|5000"

    @Test
    fun `a marker this process writes while the report runs is kept`() {
        assertFalse(
            SelfExitMarker.shouldClearAfterReport(stale, fresh, currentPid = 200, reportCompleted = true),
            "Exit pressed mid-report: deleting the fresh marker makes the next launch report a fatal SIGKILL",
        )
    }

    @Test
    fun `a marker written after an empty read is kept`() {
        assertFalse(
            SelfExitMarker.shouldClearAfterReport(null, fresh, currentPid = 200, reportCompleted = true),
            "nothing was consumed so nothing may be cleared",
        )
    }

    @Test
    fun `the consumed marker is cleared once the report completes`() {
        assertTrue(SelfExitMarker.shouldClearAfterReport(stale, stale, currentPid = 200, reportCompleted = true))
    }

    @Test
    fun `a failed report keeps the marker for the next launch`() {
        assertFalse(
            SelfExitMarker.shouldClearAfterReport(stale, stale, currentPid = 200, reportCompleted = false),
            "the unreported exit is retried next launch and still needs its tag",
        )
    }

    @Test
    fun `a marker this process wrote before the read is kept`() {
        assertFalse(SelfExitMarker.shouldClearAfterReport(fresh, fresh, currentPid = 200, reportCompleted = true))
    }

    @Test
    fun `nothing stored means nothing to clear`() {
        assertFalse(SelfExitMarker.shouldClearAfterReport(null, null, currentPid = 200, reportCompleted = true))
    }

    @Test
    fun `an unreadable marker that was read is cleared`() {
        assertTrue(SelfExitMarker.shouldClearAfterReport("junk", "junk", currentPid = 200, reportCompleted = true))
    }
}
