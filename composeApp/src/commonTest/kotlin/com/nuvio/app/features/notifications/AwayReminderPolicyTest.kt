@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

class AwayReminderPolicyTest {
    private val ny = TimeZone.of("America/New_York")

    // Thursday 2026-11-19, 10:00 in New York: the app has just been opened.
    private val today = LocalDate(2026, 11, 19)
    private val opened = at(today, 10, 0)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant =
        LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute).toInstant(ny)

    private fun day(offset: Int): LocalDate = today.plus(DatePeriod(days = offset))

    private fun ep(show: String, offset: Int, id: String = "$show-$offset") =
        AlertCandidate(show, show.uppercase(), id, day(offset))

    private fun episodeAlert(offset: Int) = PlannedAlert(
        fireAt = at(day(offset), 9, 0),
        fireDate = day(offset),
        candidates = listOf(ep("x", offset)),
        sendingLessNotice = false,
    )

    private fun input(
        candidates: List<AlertCandidate> = listOf(ep("a", 3)),
        days: Int = 7,
        now: Instant = opened,
        lastOpenedAt: Instant? = opened,
        times: AlertTimes = AlertTimes.Default,
        quiet: QuietHours = QuietHours(),
        cap: AlertCap = AlertCap.PerDay,
        openedDates: Set<LocalDate> = setOf(today),
        sent: Set<LocalDate> = emptySet(),
        streak: Int = 0,
        noticeShown: Boolean = false,
        episodeAlerts: List<PlannedAlert> = emptyList(),
    ) = AwayReminderInput(now, ny, days, lastOpenedAt, candidates, times, quiet, cap, openedDates, sent, streak, noticeShown, episodeAlerts)

    // ---- the default ----

    @Test
    fun `default is seven days with permission and the episode switch on`() {
        assertEquals(AwayReminder.Days7, AwayReminderPolicy.effective(null, permissionGranted = true, episodeAlertsOn = true))
    }

    @Test
    fun `default is off without permission`() {
        assertEquals(AwayReminder.Off, AwayReminderPolicy.effective(null, permissionGranted = false, episodeAlertsOn = true))
    }

    @Test
    fun `default is off with the episode switch off`() {
        assertEquals(AwayReminder.Off, AwayReminderPolicy.effective(null, permissionGranted = true, episodeAlertsOn = false))
    }

    @Test
    fun `default is off with neither`() {
        assertEquals(AwayReminder.Off, AwayReminderPolicy.effective(null, permissionGranted = false, episodeAlertsOn = false))
    }

    @Test
    fun `a choice beats the default in every permission state`() {
        for (permission in listOf(true, false)) for (switch in listOf(true, false)) {
            assertEquals(AwayReminder.Off, AwayReminderPolicy.effective(AwayReminder.Off, permission, switch), "off stays off")
            assertEquals(AwayReminder.Days14, AwayReminderPolicy.effective(AwayReminder.Days14, permission, switch), "14 stays 14")
            assertEquals(AwayReminder.Days3, AwayReminderPolicy.effective(AwayReminder.Days3, permission, switch), "3 stays 3")
        }
    }

    @Test
    fun `the ui state reports the default only while alerts can reach the phone`() {
        assertEquals(AwayReminder.Off, EpisodeReleaseNotificationsUiState().awayReminder)
        val ready = EpisodeReleaseNotificationsUiState(isEnabled = true, permissionGranted = true)
        assertEquals(AwayReminder.Days7, ready.awayReminder)
        assertEquals(AwayReminder.Days3, ready.copy(preferences = AlertPreferences(awayReminder = AwayReminder.Days3)).awayReminder)
    }

    @Test
    fun `only the four listed day counts are choices`() {
        assertEquals(listOf(0, 3, 7, 14), AwayReminder.entries.map { it.days })
        listOf(-1, 1, 5, 8, 15, 30, 99999, null).forEach { assertNull(AwayReminder.fromDays(it), "$it") }
        assertEquals(AwayReminder.Days14, AwayReminder.fromDays(14))
        assertEquals(AwayReminder.Off, AwayReminder.fromDays(0))
    }

    // ---- when it is scheduled ----

    @Test
    fun `the reminder is due the chosen number of days after the last open at the alert time`() {
        for ((days, offset) in listOf(3 to 3, 7 to 7, 14 to 14)) {
            val plan = AwayReminderPolicy.plan(input(candidates = listOf(ep("a", offset)), days = days))!!
            assertEquals(day(offset), plan.fireDate, "$days days")
            assertEquals(at(day(offset), 9, 0), plan.fireAt, "$days days at nine")
        }
    }

    @Test
    fun `it uses the learned time of the day it lands on`() {
        // 2026-11-26 is a Thursday, 2026-11-28 a Saturday.
        val times = AlertTimes(weekdayMinute = 20 * 60 + 10, weekendMinute = 11 * 60)
        val weekday = AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 5)), days = 7, times = times))!!
        assertEquals(at(day(7), 20, 10), weekday.fireAt)
        val weekend = AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 5)), days = 9, times = times))!!
        assertEquals(day(9), weekend.fireDate, "an open on Thursday plus nine days is a Saturday")
        assertEquals(at(day(9), 11, 0), weekend.fireAt)
    }

    @Test
    fun `a time set by the viewer is used`() {
        val plan = AwayReminderPolicy.plan(input(times = AlertTimes(18 * 60, 18 * 60), days = 3))!!
        assertEquals(at(day(3), 18, 0), plan.fireAt)
    }

    @Test
    fun `off schedules nothing`() {
        assertNull(AwayReminderPolicy.plan(input(days = 0)))
    }

    @Test
    fun `an open that was never recorded schedules nothing`() {
        assertNull(AwayReminderPolicy.plan(input(lastOpenedAt = null)))
    }

    @Test
    fun `quiet hours move it to when they end`() {
        val plan = AwayReminderPolicy.plan(input(times = AlertTimes(23 * 60, 23 * 60), days = 3))!!
        assertEquals(at(day(4), 8, 0), plan.fireAt, "23:00 with quiet 22:00 to 08:00 goes to 08:00 the next morning")
        assertEquals(day(4), plan.fireDate)
    }

    @Test
    fun `quiet hours off or empty block nothing`() {
        val late = AlertTimes(23 * 60, 23 * 60)
        assertEquals(at(day(3), 23, 0), AwayReminderPolicy.plan(input(times = late, days = 3, quiet = QuietHours(enabled = false)))!!.fireAt)
        assertEquals(at(day(3), 23, 0), AwayReminderPolicy.plan(input(times = late, days = 3, quiet = QuietHours(startMinute = 480, endMinute = 480)))!!.fireAt)
    }

    @Test
    fun `an episode released on the day it moves to still counts`() {
        val plan = AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 4)), times = AlertTimes(23 * 60, 23 * 60), days = 3))
        assertEquals(listOf("A"), plan!!.showTitles)
    }

    // ---- once per absence ----

    @Test
    fun `once the moment has passed nothing is planned until the next open`() {
        val after = at(day(7), 9, 5)
        assertNull(AwayReminderPolicy.plan(input(now = after, openedDates = emptySet())), "already fired: no second one while still away")
    }

    @Test
    fun `working it out again before it is due gives the same moment`() {
        val first = AwayReminderPolicy.plan(input())!!
        val later = AwayReminderPolicy.plan(input(now = at(day(2), 15, 0), openedDates = emptySet()))!!
        assertEquals(first.fireAt, later.fireAt, "anchored to the open, not to now")
    }

    @Test
    fun `a new open moves it a full absence ahead again`() {
        val reopened = at(day(5), 20, 0)
        val plan = AwayReminderPolicy.plan(
            input(candidates = listOf(ep("a", 9)), now = reopened, lastOpenedAt = reopened, openedDates = setOf(day(5))),
        )!!
        assertEquals(day(12), plan.fireDate)
    }

    @Test
    fun `a reminder less than a minute away is not scheduled`() {
        val almost = at(day(7), 8, 59).let { Instant.fromEpochSeconds(it.epochSeconds + 30) }
        assertNull(AwayReminderPolicy.plan(input(now = almost, openedDates = emptySet())))
    }

    @Test
    fun `a day the app was opened is never used`() {
        assertNull(AwayReminderPolicy.plan(input(openedDates = setOf(day(7)))))
    }

    // ---- content and count ----

    @Test
    fun `no new episode in the absence means nothing is scheduled`() {
        assertNull(AwayReminderPolicy.plan(input(candidates = emptyList())))
    }

    @Test
    fun `episodes already out on the day of the open do not count`() {
        assertNull(AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 0), ep("a", -2)))))
    }

    @Test
    fun `episodes after the fire day do not count`() {
        assertNull(AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 8), ep("b", 20)))))
    }

    @Test
    fun `an episode on the fire day counts and one the day after the open counts`() {
        assertEquals(listOf("A"), AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 7))))!!.showTitles)
        assertEquals(listOf("A"), AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 1))))!!.showTitles)
    }

    @Test
    fun `a show is counted once however many episodes it has`() {
        val plan = AwayReminderPolicy.plan(input(candidates = listOf(ep("a", 2), ep("a", 3), ep("a", 6), ep("b", 4))))!!
        assertEquals(2, plan.showCount)
        assertEquals(listOf("A", "B"), plan.showTitles, "in the order their first new episode comes out")
    }

    @Test
    fun `the count follows the number of shows with something new`() {
        val many = (1..5).map { ep("s$it", it) }
        assertEquals(5, AwayReminderPolicy.plan(input(candidates = many))!!.showCount)
    }

    private object Words : AwayTemplates {
        override suspend fun titleOne() = "A show you watch has new episodes"
        override suspend fun titleMany(showCount: Int) = "$showCount shows you watch have new episodes"
        override suspend fun bodyNames(first: String, second: String?) =
            (if (second == null) first else "$first and $second") + ". Turn this off in Settings › Notifications."
        override suspend fun bodyCount() = "Open Tuvora to see them. Turn this off in Settings › Notifications."
        override suspend fun lessOftenLine() = "Sending these less often. Change in Settings"
    }

    private fun plan(vararg titles: String, notice: Boolean = false) =
        PlannedAwayReminder(opened, today, titles.toList(), notice)

    @Test
    fun `one show is named`() = runTest {
        val content = AwayReminderContentBuilder.build(plan("Severance"), Words)
        assertEquals("A show you watch has new episodes", content.title)
        assertEquals("Severance. Turn this off in Settings › Notifications.", content.body)
    }

    @Test
    fun `two shows are named with and`() = runTest {
        val content = AwayReminderContentBuilder.build(plan("Severance", "Slow Horses"), Words)
        assertEquals("2 shows you watch have new episodes", content.title)
        assertEquals("Severance and Slow Horses. Turn this off in Settings › Notifications.", content.body)
    }

    @Test
    fun `three or more shows are counted and not named`() = runTest {
        val content = AwayReminderContentBuilder.build(plan("A", "B", "C"), Words)
        assertEquals("3 shows you watch have new episodes", content.title)
        assertEquals("Open Tuvora to see them. Turn this off in Settings › Notifications.", content.body)
        assertFalse(content.body.contains("A"), "no titles on the lock screen past two")
    }

    @Test
    fun `every body says where to turn it off`() = runTest {
        for (titles in listOf(listOf("A"), listOf("A", "B"), listOf("A", "B", "C", "D"))) {
            val body = AwayReminderContentBuilder.build(PlannedAwayReminder(opened, today, titles, false), Words).body
            assertTrue(body.contains("Turn this off in Settings › Notifications."), body)
        }
    }

    @Test
    fun `the less often line is added when the reminder carries the notice`() = runTest {
        val content = AwayReminderContentBuilder.build(plan("A", notice = true), Words)
        assertTrue(content.body.endsWith("\nSending these less often. Change in Settings"), content.body)
    }

    // ---- the cap and back-off ----

    @Test
    fun `a day that already has an episode alert drops the reminder`() {
        assertNull(AwayReminderPolicy.plan(input(episodeAlerts = listOf(episodeAlert(7)))))
    }

    @Test
    fun `an episode alert on another day does not stop it under a daily cap`() {
        assertEquals(day(7), AwayReminderPolicy.plan(input(episodeAlerts = listOf(episodeAlert(6), episodeAlert(8))))!!.fireDate)
    }

    @Test
    fun `one a week counts an alert already sent that week`() {
        assertNull(AwayReminderPolicy.plan(input(cap = AlertCap.OnePerWeek, sent = setOf(day(3)))))
    }

    @Test
    fun `one a week allows it once the week is clear`() {
        assertNotNullPlan(AwayReminderPolicy.plan(input(cap = AlertCap.OnePerWeek, sent = setOf(day(0)))))
    }

    @Test
    fun `one a week counts a planned episode alert after it too`() {
        assertNull(AwayReminderPolicy.plan(input(cap = AlertCap.OnePerWeek, episodeAlerts = listOf(episodeAlert(9)))))
    }

    @Test
    fun `three a week stops at three in any window that holds it`() {
        val full = AlertCap.ThreePerWeek
        assertNull(AwayReminderPolicy.plan(input(cap = full, sent = setOf(day(5), day(6)), episodeAlerts = listOf(episodeAlert(8)))), "three would sit inside one window")
        assertNotNullPlan(AwayReminderPolicy.plan(input(cap = full, sent = setOf(day(5)), episodeAlerts = listOf(episodeAlert(8)))))
        assertNotNullPlan(AwayReminderPolicy.plan(input(cap = full, episodeAlerts = listOf(episodeAlert(0), episodeAlert(1), episodeAlert(2)))))
    }

    @Test
    fun `back off tightens the cap for the reminder`() {
        // Three ignored alerts turn a daily cap into three a week; two already in the week fill it.
        assertNull(AwayReminderPolicy.plan(input(streak = 3, sent = setOf(day(5), day(6), day(4)))))
        assertNotNullPlan(AwayReminderPolicy.plan(input(streak = 3, sent = setOf(day(5), day(6)))))
        // Six ignored make it one a week.
        assertNull(AwayReminderPolicy.plan(input(streak = 6, sent = setOf(day(4)))))
    }

    @Test
    fun `the one time notice rides on the reminder only when no episode alert carries it`() {
        assertTrue(AwayReminderPolicy.plan(input(streak = 3))!!.sendingLessNotice)
        assertFalse(AwayReminderPolicy.plan(input(streak = 3, noticeShown = true))!!.sendingLessNotice, "already shown")
        assertFalse(AwayReminderPolicy.plan(input(streak = 0))!!.sendingLessNotice, "not backed off")
        assertFalse(AwayReminderPolicy.plan(input(streak = 3, episodeAlerts = listOf(episodeAlert(2))))!!.sendingLessNotice, "an episode alert carries it")
    }

    @Test
    fun `a daily cap never drops a reminder on a clear day`() {
        assertNotNullPlan(AwayReminderPolicy.plan(input(sent = setOf(day(-1), day(6), day(8)))))
    }

    private fun assertNotNullPlan(plan: PlannedAwayReminder?) = assertTrue(plan != null, "expected a reminder")

    // ---- Android fire-time re-check ----

    private val quiet = QuietHours()

    @Test
    fun `an away reminder is skipped when another alert went out today`() {
        val now = at(day(7), 9, 5)
        val decision = FireTimeGate.decide(now, ny, quiet, lastOpenedAt = opened, isAwayReminder = true, lastAlertSentAt = at(day(7), 8, 30))
        assertEquals(FireDecision.Skip, decision)
    }

    @Test
    fun `an alert sent yesterday does not stop it`() {
        val now = at(day(7), 9, 5)
        val decision = FireTimeGate.decide(now, ny, quiet, lastOpenedAt = opened, isAwayReminder = true, lastAlertSentAt = at(day(6), 9, 0))
        assertEquals(FireDecision.Send, decision)
    }

    @Test
    fun `an episode alert ignores what was sent today`() {
        val now = at(day(7), 9, 5)
        assertEquals(FireDecision.Send, FireTimeGate.decide(now, ny, quiet, opened, isAwayReminder = false, lastAlertSentAt = at(day(7), 8, 30)))
    }

    @Test
    fun `an away reminder still waits out quiet hours and skips an opened day`() {
        val lateAtNight = at(day(7), 23, 30)
        assertTrue(FireTimeGate.decide(lateAtNight, ny, quiet, opened, isAwayReminder = true) is FireDecision.DelayUntil)
        assertEquals(FireDecision.Skip, FireTimeGate.decide(at(day(7), 9, 5), ny, quiet, lastOpenedAt = at(day(7), 7, 0), isAwayReminder = true))
    }

    // ---- the sync field and the stored form ----

    @Test
    fun `an unchosen reminder is not sent in the sync object`() {
        assertFalse(AlertPreferencesSyncCodec.encode(AlertPreferences()).containsKey("away_reminder_days"))
    }

    @Test
    fun `every choice round trips through the sync object including off`() {
        for (choice in AwayReminder.entries) {
            val prefs = AlertPreferences(awayReminder = choice)
            val encoded = AlertPreferencesSyncCodec.encode(prefs)
            assertEquals(choice.days, (encoded["away_reminder_days"] as kotlinx.serialization.json.JsonPrimitive).content.toInt(), "wire value")
            assertEquals(prefs, AlertPreferencesSyncCodec.applyOver(AlertPreferences(), encoded), "$choice")
        }
    }

    @Test
    fun `a pulled choice replaces the local one and a missing key keeps it`() {
        val remote = kotlinx.serialization.json.Json.parseToJsonElement("""{"away_reminder_days":14}""") as kotlinx.serialization.json.JsonObject
        assertEquals(AwayReminder.Days14, AlertPreferencesSyncCodec.applyOver(AlertPreferences(awayReminder = AwayReminder.Days3), remote).awayReminder)
        val other = kotlinx.serialization.json.Json.parseToJsonElement("""{"cap":"OnePerWeek"}""") as kotlinx.serialization.json.JsonObject
        assertEquals(AwayReminder.Days3, AlertPreferencesSyncCodec.applyOver(AlertPreferences(awayReminder = AwayReminder.Days3), other).awayReminder)
        assertNull(AlertPreferencesSyncCodec.applyOver(AlertPreferences(), other).awayReminder, "still unchosen")
    }

    @Test
    fun `invalid synced values are ignored and keep what this device has`() {
        val mine = AlertPreferences(awayReminder = AwayReminder.Days3)
        for (bad in listOf("5", "-1", "15", "99999", "\"7\"", "7.5", "true", "null", "{\"v\":7}", "[7]")) {
            val remote = kotlinx.serialization.json.Json.parseToJsonElement("""{"away_reminder_days":$bad,"cap":"OnePerWeek"}""") as kotlinx.serialization.json.JsonObject
            val merged = AlertPreferencesSyncCodec.applyOver(mine, remote)
            assertEquals(AwayReminder.Days3, merged.awayReminder, "ignored: $bad")
            assertEquals(AlertCap.OnePerWeek, merged.cap, "valid keys beside $bad still apply")
        }
        val fromNothing = AlertPreferencesSyncCodec.applyOver(AlertPreferences(), kotlinx.serialization.json.Json.parseToJsonElement("""{"away_reminder_days":5,"cap":"OnePerWeek"}""") as kotlinx.serialization.json.JsonObject)
        assertNull(fromNothing.awayReminder, "an invalid value never becomes a choice")
    }

    @Test
    fun `stored form round trips and an invalid stored value reads as unchosen`() {
        for (choice in AwayReminder.entries) {
            val prefs = AlertPreferences(awayReminder = choice)
            assertEquals(prefs, StoredAlertPreferences.of(prefs).toModel())
        }
        assertNull(StoredAlertPreferences().toModel().awayReminder, "a payload from before this setting existed")
        assertNull(StoredAlertPreferences(awayReminderDays = 9).toModel().awayReminder)
    }
}
