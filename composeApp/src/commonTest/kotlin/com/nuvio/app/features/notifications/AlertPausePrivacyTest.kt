@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class AlertPausePrivacyTest {
    // ---- pause everything and private names: defaults and sync ----

    @Test
    fun `pause everything and private names are off by default`() {
        val defaults = AlertPreferences()
        assertFalse(defaults.allPaused)
        assertFalse(defaults.privateNames)
    }

    @Test
    fun `both choices travel in alert preferences`() {
        val encoded = AlertPreferencesSyncCodec.encode(AlertPreferences(allPaused = true, privateNames = true))
        assertEquals(JsonPrimitive(true), encoded["all_paused"])
        assertEquals(JsonPrimitive(true), encoded["private_names"])
        val decoded = AlertPreferencesSyncCodec.applyOver(AlertPreferences(), encoded)
        assertTrue(decoded.allPaused)
        assertTrue(decoded.privateNames)
    }

    @Test
    fun `a blob from an older app without the keys leaves both as they were`() {
        val old = buildJsonObject { put("cap", "PerDay") }
        val current = AlertPreferences(allPaused = true, privateNames = true)
        val applied = AlertPreferencesSyncCodec.applyOver(current, old)
        assertTrue(applied.allPaused)
        assertTrue(applied.privateNames)
    }

    @Test
    fun `an odd value for the new keys is ignored and never fails the pull`() {
        val odd = buildJsonObject {
            put("all_paused", "yes please")
            put("private_names", 7)
            put("cap", "ThreePerWeek")
        }
        val applied = AlertPreferencesSyncCodec.applyOver(AlertPreferences(), odd)
        assertFalse(applied.allPaused)
        assertFalse(applied.privateNames)
        assertEquals(AlertCap.ThreePerWeek, applied.cap, "the valid key in the same blob still applies")
        assertEquals(AlertPreferences(), AlertPreferencesSyncCodec.applyOver(AlertPreferences(), JsonObject(emptyMap())))
    }

    @Test
    fun `the stored payload without the new fields decodes as off`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"timingMode":"learned","cap":"PerDay"}"""
        val model = json.decodeFromString<StoredAlertPreferences>(old).toModel()
        assertFalse(model.allPaused)
        assertFalse(model.privateNames)
        val roundTrip = json.decodeFromString<StoredAlertPreferences>(
            json.encodeToString(StoredAlertPreferences.of(AlertPreferences(allPaused = true, privateNames = true))),
        ).toModel()
        assertTrue(roundTrip.allPaused)
        assertTrue(roundTrip.privateNames)
    }

    // ---- private names: the text ----

    private object Words : AlertTemplates {
        override suspend fun batchTitle(episodeCount: Int) = "$episodeCount new episodes aired"
        override suspend fun sameShowBody(episodeCount: Int) = "$episodeCount new episodes aired today"
        override suspend fun listBody(labels: List<String>) = labels.joinToString(", ") + " aired today"
        override suspend fun listBodyWithMore(labels: List<String>, more: Int) =
            labels.joinToString(", ") + " and $more more aired today"
        override suspend fun lessOftenLine() = "Sending fewer"
        override suspend fun privateShowName() = "A show you follow"
        override suspend fun genericBody() = "A new episode aired today"
    }

    private val severance = AlertCandidate(
        showKey = "series:tt1", showTitle = "Severance", episodeKey = "e1", releaseDate = LocalDate(2026, 11, 20),
        label = "Severance S3E4", bodyText = "S3E4 • Cold Harbor aired today", privateBodyText = "S3E4 aired today",
    )
    private val andor = AlertCandidate(
        showKey = "series:tt2", showTitle = "Andor", episodeKey = "e2", releaseDate = LocalDate(2026, 11, 20),
        label = "Andor S2E9", bodyText = "S2E9 • Jedha aired today", privateBodyText = "S2E9 aired today",
    )

    private fun alert(vararg candidates: AlertCandidate, notice: Boolean = false) = PlannedAlert(
        fireAt = Instant.fromEpochMilliseconds(0),
        fireDate = LocalDate(2026, 11, 20),
        candidates = candidates.toList(),
        sendingLessNotice = notice,
    )

    private fun assertNoNames(content: AlertContent) {
        val text = content.title + "\n" + content.body
        listOf("Severance", "Andor", "Cold Harbor", "Jedha").forEach { assertFalse(text.contains(it), "leaked $it in: $text") }
    }

    @Test
    fun `names off keeps the show name and the episode title`() = runTest {
        val content = AlertContentBuilder.build(alert(severance), Words)
        assertEquals(AlertContent("Severance", "S3E4 • Cold Harbor aired today"), content)
    }

    @Test
    fun `private names say a show you follow with the code alone`() = runTest {
        val content = AlertContentBuilder.build(alert(severance), Words, privateNames = true)
        assertEquals(AlertContent("A show you follow", "S3E4 aired today"), content)
        assertNoNames(content)
    }

    @Test
    fun `private names fall back to the generic line when the episode has no code`() = runTest {
        val noCode = severance.copy(privateBodyText = "")
        val content = AlertContentBuilder.build(alert(noCode), Words, privateNames = true)
        assertEquals(AlertContent("A show you follow", "A new episode aired today"), content)
    }

    @Test
    fun `private names hide the show when two episodes of one show aired`() = runTest {
        val second = severance.copy(episodeKey = "e1b", label = "Severance S3E5")
        val content = AlertContentBuilder.build(alert(severance, second), Words, privateNames = true)
        assertEquals(AlertContent("A show you follow", "2 new episodes aired today"), content)
        assertNoNames(content)
    }

    @Test
    fun `private names hide every show in a batch and only count`() = runTest {
        val content = AlertContentBuilder.build(alert(severance, andor), Words, privateNames = true)
        assertEquals(AlertContent("2 new episodes aired", "2 new episodes aired today"), content)
        assertNoNames(content)
    }

    @Test
    fun `private names still carry the less often line`() = runTest {
        val content = AlertContentBuilder.build(alert(severance, notice = true), Words, privateNames = true)
        assertEquals("S3E4 aired today\nSending fewer", content.body)
    }

    private object AwayWords : AwayTemplates {
        override suspend fun titleOne() = "While you were out: a show you watch aired a new episode"
        override suspend fun titleMany(showCount: Int) = "While you were out: $showCount of your shows aired new episodes"
        override suspend fun bodyNames(first: String, second: String?) = (if (second == null) first else "$first and $second") + ". Only nudge."
        override suspend fun bodyCount() = "Open Tuvora to see them. Only nudge."
        override suspend fun lessOftenLine() = "Sending fewer"
    }

    private fun away(vararg titles: String) = PlannedAwayReminder(
        fireAt = Instant.fromEpochMilliseconds(0), fireDate = LocalDate(2026, 11, 20),
        showTitles = titles.toList(), sendingLessNotice = false,
    )

    @Test
    fun `the away reminder names shows unless names are private`() = runTest {
        assertEquals("Severance and Andor. Only nudge.", AwayReminderContentBuilder.build(away("Severance", "Andor"), AwayWords).body)
        val hidden = AwayReminderContentBuilder.build(away("Severance", "Andor"), AwayWords, privateNames = true)
        assertEquals("Open Tuvora to see them. Only nudge.", hidden.body)
        assertNoNames(hidden)
        assertNoNames(AwayReminderContentBuilder.build(away("Severance"), AwayWords, privateNames = true))
    }
}
