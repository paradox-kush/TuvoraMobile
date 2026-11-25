@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertSupportPoliciesTest {
    private val ny = TimeZone.of("America/New_York")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, zone: TimeZone = ny): Instant =
        LocalDateTime(y, m, d, h, min).toInstant(zone)

    private fun ldt(d: Int, h: Int, min: Int = 0) = LocalDateTime(2026, 11, d, h, min)

    // ---- viewing history ----

    @Test
    fun `a start is recorded`() {
        assertEquals(listOf("2026-11-19T20:05"), ViewingHistory.append(emptyList(), ldt(19, 20, 5)))
    }

    @Test
    fun `a start within ten minutes of the last is not recorded again`() {
        val once = ViewingHistory.append(emptyList(), ldt(19, 20, 0))
        assertEquals(once, ViewingHistory.append(once, ldt(19, 20, 9)), "a pause and resume")
        assertEquals(2, ViewingHistory.append(once, ldt(19, 20, 10)).size, "ten minutes later is recorded")
    }

    @Test
    fun `history stays in order and drops entries beyond the window`() {
        val old = ViewingHistory.append(emptyList(), LocalDateTime(2026, 6, 1, 20, 0))
        val withNew = ViewingHistory.append(old, ldt(19, 20, 0))
        assertEquals(listOf("2026-11-19T20:00"), withNew, "June is far outside twelve weeks plus a week")
    }

    @Test
    fun `history is capped at the newest three hundred`() {
        var history = emptyList<String>()
        for (i in 0 until 400) {
            val day = 1 + (i / 24) % 28
            history = ViewingHistory.append(history, LocalDateTime(2026, 11, day, i % 24, 30))
        }
        assertTrue(history.size <= ViewingHistory.MaxEntries, "size ${history.size}")
    }

    @Test
    fun `unreadable entries are skipped`() {
        assertEquals(listOf(ldt(19, 20, 0)), ViewingHistory.parse(listOf("garbage", "2026-11-19T20:00")))
    }

    // ---- fire time re-check ----

    private fun quiet() = QuietHours()

    @Test
    fun `an alert is sent when nothing stands in its way`() {
        val decision = FireTimeGate.decide(at(2026, 11, 20, 19, 40), ny, quiet(), lastOpenedAt = at(2026, 11, 19, 21))
        assertEquals(FireDecision.Send, decision)
    }

    @Test
    fun `an alert is skipped when the app was opened that day`() {
        val decision = FireTimeGate.decide(at(2026, 11, 20, 19, 40), ny, quiet(), lastOpenedAt = at(2026, 11, 20, 8, 5))
        assertEquals(FireDecision.Skip, decision)
    }

    @Test
    fun `opened late the previous night does not skip the next day`() {
        val decision = FireTimeGate.decide(at(2026, 11, 20, 9, 0), ny, quiet(), lastOpenedAt = at(2026, 11, 19, 23, 59))
        assertEquals(FireDecision.Send, decision)
    }

    @Test
    fun `an alert delivered late into quiet hours waits for the morning`() {
        val decision = FireTimeGate.decide(at(2026, 11, 20, 23, 15), ny, quiet(), lastOpenedAt = null)
        assertEquals(FireDecision.DelayUntil(at(2026, 11, 21, 8, 0)), decision)
    }

    @Test
    fun `an alert delivered in the small hours waits until eight the same day`() {
        val decision = FireTimeGate.decide(at(2026, 11, 21, 3, 0), ny, quiet(), lastOpenedAt = null)
        assertEquals(FireDecision.DelayUntil(at(2026, 11, 21, 8, 0)), decision)
    }

    @Test
    fun `quiet hours off never delay`() {
        val decision = FireTimeGate.decide(at(2026, 11, 20, 23, 15), ny, QuietHours(enabled = false), lastOpenedAt = null)
        assertEquals(FireDecision.Send, decision)
    }

    @Test
    fun `opened today wins over quiet hours`() {
        val decision = FireTimeGate.decide(at(2026, 11, 20, 23, 15), ny, quiet(), lastOpenedAt = at(2026, 11, 20, 12))
        assertEquals(FireDecision.Skip, decision)
    }

    // ---- end of episode prompt ----

    @Test
    fun `nearly caught up means the next episode is unaired or missing`() {
        assertTrue(EndOfEpisodePromptPolicy.nearlyCaughtUp(null))
        assertTrue(EndOfEpisodePromptPolicy.nearlyCaughtUp(false))
        assertFalse(EndOfEpisodePromptPolicy.nearlyCaughtUp(true))
    }

    @Test
    fun `the prompt is offered only when every condition allows it`() {
        fun offer(dont: Boolean = false, prompted: Boolean = false, on: Boolean = false, caught: Boolean = true) =
            EndOfEpisodePromptPolicy.shouldOffer(dont, prompted, on, caught)
        assertTrue(offer())
        assertFalse(offer(dont = true), "never when don't ask again is on")
        assertFalse(offer(prompted = true), "once per show")
        assertFalse(offer(on = true), "not when alerts are already on for the show")
        assertFalse(offer(caught = false), "not while there is plenty left to watch")
    }

    // ---- show defaults ----

    @Test
    fun `saved shows default on and continue watching shows default off`() {
        assertTrue(ShowAlertDefaults.isOn(null, FollowSource.Library))
        assertFalse(ShowAlertDefaults.isOn(null, FollowSource.ContinueWatching))
    }

    @Test
    fun `the viewer's own choice beats the default`() {
        assertFalse(ShowAlertDefaults.isOn(false, FollowSource.Library))
        assertTrue(ShowAlertDefaults.isOn(true, FollowSource.ContinueWatching))
    }

    @Test
    fun `an unknown source reads as a saved show`() {
        assertEquals(FollowSource.Library, FollowSource.fromWire(null))
        assertEquals(FollowSource.Library, FollowSource.fromWire("nonsense"))
    }

    // ---- alert wording ----

    private object FakeTemplates : AlertTemplates {
        override suspend fun batchTitle(episodeCount: Int) = "$episodeCount new episodes from shows you watch"
        override suspend fun sameShowBody(episodeCount: Int) = "$episodeCount new episodes are out now"
        override suspend fun listBody(labels: List<String>) = labels.joinToString(", ") + " are out now"
        override suspend fun listBodyWithMore(labels: List<String>, more: Int) =
            labels.joinToString(", ") + " and $more more are out now"
        override suspend fun lessOftenLine() = "Sending these less often. Change in Settings"
        override suspend fun privateShowName() = "A show you follow"
        override suspend fun genericBody() = "A new episode aired today"
    }

    private fun candidate(show: String, episode: String, label: String = "$show $episode", body: String = "$episode is out now") =
        AlertCandidate(show, show, episode, LocalDate(2026, 11, 20), label = label, bodyText = body)

    private fun alert(vararg candidates: AlertCandidate, notice: Boolean = false) = PlannedAlert(
        fireAt = at(2026, 11, 20, 19, 40),
        fireDate = LocalDate(2026, 11, 20),
        candidates = candidates.toList(),
        sendingLessNotice = notice,
    )

    @Test
    fun `one episode keeps the single episode wording`() = runTest {
        val content = AlertContentBuilder.build(alert(candidate("Severance", "S3E4")), FakeTemplates)
        assertEquals(AlertContent("Severance", "S3E4 is out now"), content)
    }

    @Test
    fun `two episodes of one show are counted under the show name`() = runTest {
        val content = AlertContentBuilder.build(
            alert(candidate("Severance", "S3E4"), candidate("Severance", "S3E5")),
            FakeTemplates,
        )
        assertEquals(AlertContent("Severance", "2 new episodes are out now"), content)
    }

    @Test
    fun `episodes from two shows become one batched alert`() = runTest {
        val content = AlertContentBuilder.build(
            alert(candidate("Severance", "S3E4"), candidate("Andor", "S1E9")),
            FakeTemplates,
        )
        assertEquals("2 new episodes from shows you watch", content.title)
        assertEquals("Severance S3E4, Andor S1E9 are out now", content.body)
    }

    @Test
    fun `a long list names three and counts the rest`() = runTest {
        val content = AlertContentBuilder.build(
            alert(candidate("A", "e1"), candidate("B", "e2"), candidate("C", "e3"), candidate("D", "e4"), candidate("E", "e5")),
            FakeTemplates,
        )
        assertEquals("5 new episodes from shows you watch", content.title)
        assertEquals("A e1, B e2, C e3 and 2 more are out now", content.body)
    }

    @Test
    fun `the less often line is added once on the alert that carries the notice`() = runTest {
        val content = AlertContentBuilder.build(alert(candidate("Severance", "S3E4"), notice = true), FakeTemplates)
        assertEquals("S3E4 is out now\nSending these less often. Change in Settings", content.body)
        val plain = AlertContentBuilder.build(alert(candidate("Severance", "S3E4")), FakeTemplates)
        assertFalse(plain.body.contains("less often"))
    }

    // ---- after the app opens ----

    private val hour = 3_600_000L
    private val today = at(2026, 11, 20, 12)

    private fun runtimeWith(vararg fireAtMs: Long, streak: Int = 0, noticeShown: Boolean = false, notice: Boolean = false) =
        StoredAlertRuntime(
            ignoredStreak = streak,
            backOffNoticeShown = noticeShown,
            scheduled = fireAtMs.map { ScheduledAlertRecord(it, notice) },
        )

    @Test
    fun `opening the app within a day of an alert resets the streak`() {
        val fired = today.toEpochMilliseconds() - 3 * hour
        val after = AlertAftermath.onAppOpened(runtimeWith(fired, streak = 2), today, ny, alertsCouldHaveFired = true)
        assertEquals(0, after.ignoredStreak)
        assertEquals(emptyList(), after.scheduled)
        assertEquals(listOf("2026-11-20"), after.sentDates)
    }

    @Test
    fun `opening the app two days after an alert counts it as ignored`() {
        val fired = today.toEpochMilliseconds() - 48 * hour
        val after = AlertAftermath.onAppOpened(runtimeWith(fired, streak = 2), today, ny, alertsCouldHaveFired = true)
        assertEquals(3, after.ignoredStreak)
    }

    @Test
    fun `alerts still in the future stay scheduled and change nothing`() {
        val later = today.toEpochMilliseconds() + 5 * hour
        val after = AlertAftermath.onAppOpened(runtimeWith(later, streak = 2), today, ny, alertsCouldHaveFired = true)
        assertEquals(2, after.ignoredStreak)
        assertEquals(listOf(ScheduledAlertRecord(later)), after.scheduled)
        assertEquals(emptyList(), after.sentDates)
    }

    @Test
    fun `nothing counts as delivered when alerts could not fire`() {
        val fired = today.toEpochMilliseconds() - 48 * hour
        val after = AlertAftermath.onAppOpened(runtimeWith(fired, streak = 1), today, ny, alertsCouldHaveFired = false)
        assertEquals(1, after.ignoredStreak)
        assertEquals(emptyList(), after.sentDates)
    }

    @Test
    fun `an ignored alert that carried the notice marks it as shown`() {
        val fired = today.toEpochMilliseconds() - 48 * hour
        val after = AlertAftermath.onAppOpened(runtimeWith(fired, streak = 3, notice = true), today, ny, alertsCouldHaveFired = true)
        assertTrue(after.backOffNoticeShown)
    }

    @Test
    fun `acting on an alert re-arms the notice for a later back-off`() {
        val fired = today.toEpochMilliseconds() - 2 * hour
        val after = AlertAftermath.onAppOpened(
            runtimeWith(fired, streak = 4, noticeShown = true),
            today, ny, alertsCouldHaveFired = true,
        )
        assertEquals(0, after.ignoredStreak)
        assertFalse(after.backOffNoticeShown)
    }

    @Test
    fun `the opened day is recorded and old days fall away`() {
        val runtime = StoredAlertRuntime(openedDates = listOf("2026-11-01", "2026-11-18"), sentDates = listOf("2026-10-01", "2026-11-15"))
        val after = AlertAftermath.onAppOpened(runtime, today, ny, alertsCouldHaveFired = true)
        assertEquals(listOf("2026-11-18", "2026-11-20"), after.openedDates)
        assertEquals(listOf("2026-11-15"), after.sentDates)
        assertEquals(today.toEpochMilliseconds(), after.lastOpenedAtMs)
    }

    // ---- preferences: defaults, timing, sync ----

    @Test
    fun `a new install starts with the documented defaults`() {
        val defaults = AlertPreferences()
        assertEquals(AlertTimingMode.Learned, defaults.timingMode)
        assertEquals(QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 8 * 60), defaults.quietHours)
        assertEquals(AlertCap.PerDay, defaults.cap)
        assertFalse(defaults.dontAskAgain)
    }

    @Test
    fun `still learning uses nine in the morning`() {
        val times = AlertPreferences().timesFor(WatchHabitPolicy.Habit.StillLearning(2, 2))
        assertEquals(AlertTimes(9 * 60, 9 * 60), times)
    }

    @Test
    fun `a learned habit sets the times twenty minutes ahead`() {
        val times = AlertPreferences().timesFor(WatchHabitPolicy.Habit.Ready(20 * 60, 11 * 60))
        assertEquals(AlertTimes(19 * 60 + 40, 10 * 60 + 40), times)
    }

    @Test
    fun `a set time ignores what was learned`() {
        val prefs = AlertPreferences(timingMode = AlertTimingMode.SetTime, setTimeMinute = 18 * 60 + 30)
        assertEquals(AlertTimes(18 * 60 + 30, 18 * 60 + 30), prefs.timesFor(WatchHabitPolicy.Habit.Ready(20 * 60, 11 * 60)))
    }

    @Test
    fun `a learned time inside quiet hours is reported with where it moves to`() {
        // Usually starts 23:00, alert 22:40, which is inside 22:00 to 08:00.
        val conflict = AlertTimingExplainer.conflict(WatchHabitPolicy.Habit.Ready(23 * 60, 23 * 60), AlertPreferences())
        assertEquals(QuietHoursConflict(usualMinute = 23 * 60, movedToMinute = 8 * 60), conflict)
    }

    @Test
    fun `no conflict when the alert time is outside quiet hours or the time is set by hand`() {
        val ready = WatchHabitPolicy.Habit.Ready(20 * 60, 20 * 60)
        assertEquals(null, AlertTimingExplainer.conflict(ready, AlertPreferences()))
        val late = WatchHabitPolicy.Habit.Ready(23 * 60, 23 * 60)
        assertEquals(null, AlertTimingExplainer.conflict(late, AlertPreferences(timingMode = AlertTimingMode.SetTime)))
        assertEquals(null, AlertTimingExplainer.conflict(late, AlertPreferences(quietHours = QuietHours(enabled = false))))
        assertEquals(null, AlertTimingExplainer.conflict(WatchHabitPolicy.Habit.StillLearning(1, 1), AlertPreferences()))
    }

    @Test
    fun `stored preferences round trip and clamp bad values`() {
        val prefs = AlertPreferences(
            timingMode = AlertTimingMode.SetTime, setTimeMinute = 18 * 60,
            quietHours = QuietHours(enabled = false, startMinute = 23 * 60, endMinute = 7 * 60),
            cap = AlertCap.OnePerWeek, dontAskAgain = true,
        )
        assertEquals(prefs, StoredAlertPreferences.of(prefs).toModel())
        val bad = StoredAlertPreferences(timingMode = "??", setTimeMinute = 99999, cap = "nope", quietStartMinute = -5).toModel()
        assertEquals(AlertTimingMode.Learned, bad.timingMode)
        assertEquals(MinutesPerDay - 1, bad.setTimeMinute)
        assertEquals(AlertCap.PerDay, bad.cap)
        assertEquals(0, bad.quietHours.startMinute)
    }

    // ---- sync codec ----

    @Test
    fun `preferences round trip through the sync object`() {
        val prefs = AlertPreferences(
            timingMode = AlertTimingMode.SetTime, setTimeMinute = 19 * 60,
            quietHours = QuietHours(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60),
            cap = AlertCap.ThreePerWeek, dontAskAgain = true,
        )
        assertEquals(prefs, AlertPreferencesSyncCodec.applyOver(AlertPreferences(), AlertPreferencesSyncCodec.encode(prefs)))
    }

    @Test
    fun `an empty or foreign sync object keeps what this device has`() {
        val mine = AlertPreferences(cap = AlertCap.OnePerWeek, setTimeMinute = 17 * 60)
        assertEquals(mine, AlertPreferencesSyncCodec.applyOver(mine, kotlinx.serialization.json.JsonObject(emptyMap())))
    }

    @Test
    fun `odd values in the sync object are ignored one by one`() {
        val remote = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"timing_mode":"banana","set_time_minute":"noon","quiet_hours_enabled":{"type":"boolean","value":false},
               "quiet_hours_start_minute":99999,"cap":"OnePerWeek","dont_ask_again":true}""",
        ) as kotlinx.serialization.json.JsonObject
        val merged = AlertPreferencesSyncCodec.applyOver(AlertPreferences(), remote)
        assertEquals(AlertTimingMode.Learned, merged.timingMode, "unknown mode keeps the local one")
        assertEquals(AlertTimes.DefaultMinute, merged.setTimeMinute, "a string is not a minute")
        assertTrue(merged.quietHours.enabled, "a wrapped value is not a flag")
        assertEquals(22 * 60, merged.quietHours.startMinute, "out of range keeps the local one")
        assertEquals(AlertCap.OnePerWeek, merged.cap, "valid keys still apply")
        assertTrue(merged.dontAskAgain)
    }

    // ---- the master switch stays opt-in ----

    @Test
    fun `a new install has the master switch off`() {
        assertFalse(EpisodeReleaseNotificationsUiState().isEnabled)
        assertFalse(StoredEpisodeReleaseNotificationsPayload().enabled)
        assertFalse(EpisodeReleaseNotificationsUiState().permissionDenied)
    }

    @Test
    fun `a payload stored before alert controls existed decodes with the switch as it was`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val oldOptOut = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(
            """{"enabled":false,"followedShows":[{"contentId":"tt1","contentType":"series","followedOnIsoDate":"2026-01-01"}]}""",
        )
        assertFalse(oldOptOut.enabled, "a stored opt-out survives")
        assertEquals(FollowSource.Library.wire, oldOptOut.followedShows.single().source)
        assertEquals(null, oldOptOut.followedShows.single().alertsOn)
        val oldOptIn = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>("""{"enabled":true}""")
        assertTrue(oldOptIn.enabled, "a stored opt-in is kept")
    }

    @Test
    fun `an opt-out survives a round trip with every new setting changed`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val payload = StoredEpisodeReleaseNotificationsPayload(
            enabled = false,
            preferences = StoredAlertPreferences.of(AlertPreferences(cap = AlertCap.OnePerWeek, dontAskAgain = true)),
            promptedShowKeys = listOf("series:tt1"),
        )
        val back = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(json.encodeToString(StoredEpisodeReleaseNotificationsPayload.serializer(), payload))
        assertEquals(payload, back)
        assertFalse(back.enabled)
    }

    @Test
    fun `the synced master flag still defaults off and a missing preferences object changes nothing`() {
        val decoded = com.nuvio.app.core.sync.decodeMobileProfileSettingsBlob(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"version":4,"features":{}}""") as kotlinx.serialization.json.JsonObject,
        )
        assertFalse(decoded.features.notificationsSettings.episodeReleaseAlertsEnabled)
        assertTrue(decoded.features.notificationsSettings.alertPreferences.isEmpty())
    }

    // ---- clock text ----

    @Test
    fun `clock text for the steppers is twenty four hour`() {
        assertEquals("22:00", AlertClock.hhmm(22 * 60))
        assertEquals("00:00", AlertClock.hhmm(0))
        assertEquals("08:05", AlertClock.hhmm(8 * 60 + 5))
    }

    @Test
    fun `clock text in sentences is plain english`() {
        assertEquals("8:30 pm", AlertClock.words(20 * 60 + 30))
        assertEquals("11 pm", AlertClock.words(23 * 60))
        assertEquals("12 am", AlertClock.words(0))
        assertEquals("12 pm", AlertClock.words(12 * 60))
        assertEquals("9:05 am", AlertClock.words(9 * 60 + 5))
    }

    @Test
    fun `the stepper moves half an hour and wraps around midnight`() {
        assertEquals(22 * 60 + 30, AlertClock.step(22 * 60, 1))
        assertEquals(0, AlertClock.step(23 * 60 + 30, 1))
        assertEquals(23 * 60 + 30, AlertClock.step(0, -1))
    }
}
