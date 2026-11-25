package com.nuvio.app.features.notifications

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class AlertTelemetryTest {
    private val original = AlertTelemetry.sink

    @AfterTest
    fun restoreSink() { AlertTelemetry.sink = original }

    private val forbiddenNames = setOf("code", "state", "url", "token", "title", "id", "time", "minute")

    private fun allEvents(): List<AlertTelemetry.Event> =
        AlertTelemetry.Setting.entries.flatMap { setting ->
            listOf("on", "off").map { AlertTelemetry.settingChanged(setting, it) }
        } + AlertTelemetry.PermissionEntry.entries.flatMap { entry ->
            listOf(true, false).map { AlertTelemetry.permissionResult(it, entry) }
        } + AlertTelemetry.aftermath(AftermathSummary(delivered = 3, acted = 1, backoffTier = 1)) +
            AlertTelemetry.counters(planned = 2, stats = GateStats(1, 1, 1), metaFetches = 4)

    @Test
    fun `no event carries a forbidden property name`() {
        allEvents().forEach { event ->
            event.props.keys.forEach { key -> assertFalse(key in forbiddenNames, "${event.name} has $key") }
        }
    }

    @Test
    fun `setting and permission properties use closed values only`() {
        val changed = AlertTelemetry.settingChanged(AlertTelemetry.Setting.AllPaused, AlertTelemetry.onOff(true))
        assertEquals(AlertTelemetry.SETTING_CHANGED, changed.name)
        assertEquals(mapOf("setting" to "all_paused", "value" to "on"), changed.props)

        val result = AlertTelemetry.permissionResult(false, AlertTelemetry.PermissionEntry.Chip)
        assertEquals(mapOf("result" to "denied", "entry" to "chip"), result.props)
        assertEquals("granted", AlertTelemetry.permissionResult(true, AlertTelemetry.PermissionEntry.Prompt).props["result"])

        assertEquals("per_day", AlertTelemetry.capValue(AlertCap.PerDay))
        assertEquals("days_7", AlertTelemetry.awayValue(AwayReminder.Days7))
        assertEquals("off", AlertTelemetry.awayValue(AwayReminder.Off))
    }

    @Test
    fun `aftermath is counts and a tier from zero to two`() {
        val event = AlertTelemetry.aftermath(AftermathSummary(delivered = 3, acted = 1, backoffTier = 9))
        assertEquals(AlertTelemetry.AFTERMATH, event.name)
        assertEquals(mapOf("delivered" to 3, "acted" to 1, "backoff_tier" to 2), event.props)
    }

    @Test
    fun `counters leave zeroes out and carry only a number`() {
        val events = AlertTelemetry.counters(planned = 2, stats = GateStats(skippedOpenedToday = 1, delayedQuiet = 0, capped = 3), metaFetches = 0)
        assertEquals(
            listOf(AlertTelemetry.PLANNED, AlertTelemetry.SKIPPED_OPENED_TODAY, AlertTelemetry.CAPPED),
            events.map { it.name },
        )
        assertTrue(events.all { it.props.keys == setOf("n") })
        assertTrue(AlertTelemetry.counters(0, GateStats(), 0).isEmpty(), "an idle pass reports nothing")
    }

    @Test
    fun `a throwing sink never breaks alert planning`() {
        AlertTelemetry.sink = { _, _ -> error("sdk down") }
        AlertTelemetry.send(AlertTelemetry.settingChanged(AlertTelemetry.Setting.Cap, "per_day"))
    }

    @Test
    fun `events reach the sink with their name and properties`() {
        val seen = ArrayList<Pair<String, Map<String, Any>>>()
        AlertTelemetry.sink = { name, props -> seen += name to props }
        AlertTelemetry.send(AlertTelemetry.settingChanged(AlertTelemetry.Setting.PrivateNames, "on"))
        assertEquals(listOf(AlertTelemetry.SETTING_CHANGED to mapOf<String, Any>("setting" to "private_names", "value" to "on")), seen)
    }

    // ---- aftermath counting ----

    private val zone = kotlinx.datetime.TimeZone.UTC
    private val hour = 60L * 60 * 1000

    @Test
    fun `an app open that resolved no alert reports nothing`() {
        val runtime = StoredAlertRuntime(scheduled = listOf(ScheduledAlertRecord(fireAtMs = 10 * hour)))
        val now = Instant.fromEpochMilliseconds(5 * hour)
        assertNull(AlertAftermath.summarize(runtime, runtime, now, alertsCouldHaveFired = true), "not yet due")
        val due = StoredAlertRuntime(scheduled = listOf(ScheduledAlertRecord(fireAtMs = hour)))
        assertNull(AlertAftermath.summarize(due, due, Instant.fromEpochMilliseconds(5 * hour), alertsCouldHaveFired = false), "alerts could not fire")
    }

    @Test
    fun `aftermath counts delivered and acted and reports the tier after`() {
        val before = StoredAlertRuntime(
            ignoredStreak = 2,
            scheduled = listOf(
                ScheduledAlertRecord(fireAtMs = 100 * hour),
                ScheduledAlertRecord(fireAtMs = 10 * hour),
            ),
        )
        val now = Instant.fromEpochMilliseconds(101 * hour)
        val after = AlertAftermath.onAppOpened(before, now, zone, alertsCouldHaveFired = true)
        val summary = AlertAftermath.summarize(before, after, now, alertsCouldHaveFired = true)
        // Two came due; one was opened within a day of its time, the older one was ignored.
        assertEquals(AftermathSummary(delivered = 2, acted = 1, backoffTier = BackOff.tierOf(after.ignoredStreak)), summary)
    }

    @Test
    fun `back-off tiers follow the thresholds`() {
        assertEquals(0, BackOff.tierOf(2))
        assertEquals(1, BackOff.tierOf(BackOff.TierOneIgnored))
        assertEquals(1, BackOff.tierOf(5))
        assertEquals(2, BackOff.tierOf(BackOff.TierTwoIgnored))
    }
}
