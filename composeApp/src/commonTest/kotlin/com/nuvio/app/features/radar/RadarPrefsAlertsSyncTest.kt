package com.nuvio.app.features.radar

import com.nuvio.app.features.radar.alerts.AlertLevel
import com.nuvio.app.features.radar.alerts.SportsAlertSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sports-alerts sync rides radar_prefs exactly like Hide scores: a pulled row never resets what it lacks, and a local
 * change no push has confirmed ([RadarPrefs.alertsDirty]) survives every pull until a push carries it. The two flags are
 * independent. kotlin.test → assertEquals(expected, actual, message).
 */
class RadarPrefsAlertsSyncTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun row(text: String) = json.decodeFromString<RadarPrefsRow>(text)

    private val quietAlerts = SportsAlertSettings(teamLevel = AlertLevel.FinalOnly, maxPerHour = 3)
    private val local = RadarPrefs(alerts = quietAlerts)
    private val dirty = local.copy(alertsDirty = true)

    // --- push payload ----------------------------------------------------------------------------------------------

    @Test
    fun `the sync payload carries alerts and never alerts_dirty`() {
        val payload = radarPrefsPayload(dirty)
        val alerts = assertIs<JsonObject>(payload["alerts"], "alerts is an object")
        assertEquals("final", alerts["team_level"]?.jsonPrimitive?.content, "the local value goes out")
        assertFalse(payload.containsKey("alerts_dirty"), "device-local only")
    }

    // --- pull ------------------------------------------------------------------------------------------------------

    @Test
    fun `a pulled row without alerts keeps the local alerts`() {
        assertEquals(quietAlerts, RadarPrefsPull.merge(local, row("""{"featured_event_id":"wc"}""")).alerts, "absent column")
        assertEquals(quietAlerts, RadarPrefsPull.merge(local, row("""{"alerts":null}""")).alerts, "null column")
        assertEquals(quietAlerts, RadarPrefsPull.merge(local, row("""{"alerts":"on"}""")).alerts, "not an object")
    }

    @Test
    fun `a clean pull takes the remote alerts`() {
        val merged = RadarPrefsPull.merge(local, row("""{"alerts":{"enabled":false,"max_per_hour":12}}"""))
        assertEquals(false, merged.alerts.enabled, "remote applies")
        assertEquals(12, merged.alerts.maxPerHour, "remote applies")
        assertEquals(AlertLevel.All, merged.alerts.teamLevel, "a key the remote object lacks reads as the default")
        assertFalse(merged.alertsDirty, "still clean")
    }

    @Test
    fun `a pull keeps the local alerts while dirty and still applies the rest`() {
        val merged = RadarPrefsPull.merge(dirty, row("""{"featured_event_id":"wc","scores_shown":false,"alerts":{"enabled":false}}"""))
        assertEquals(quietAlerts, merged.alerts, "the unpushed local change wins")
        assertTrue(merged.alertsDirty, "still waiting for its push")
        assertEquals(false, merged.scoresShown, "Hide scores is not held back by an alerts change")
        assertEquals("wc", merged.featuredEventId, "the other prefs still apply")
    }

    @Test
    fun `a dirty Hide scores change does not hold back pulled alerts`() {
        val spoilerDirty = local.copy(spoilerDirty = true)
        val merged = RadarPrefsPull.merge(spoilerDirty, row("""{"alerts":{"enabled":false}}"""))
        assertEquals(false, merged.alerts.enabled, "alerts are clean: remote applies")
    }

    @Test
    fun `a pull with a dirty alerts change pushes after`() {
        assertTrue(RadarPrefsPull.pushAfterPull(RadarPrefsPull.merge(dirty, row("{}"))), "dirty -> push")
        assertFalse(RadarPrefsPull.pushAfterPull(RadarPrefsPull.merge(local, row("{}"))), "clean -> no push")
    }

    // --- push ack --------------------------------------------------------------------------------------------------

    @Test
    fun `a push clears alerts_dirty when the local alerts still match`() {
        assertFalse(RadarPrefsPull.afterPush(current = dirty, pushed = dirty).alertsDirty, "confirmed")
    }

    @Test
    fun `an alerts change made after the push read stays dirty`() {
        val since = dirty.copy(alerts = quietAlerts.copy(enabled = false))
        assertTrue(RadarPrefsPull.afterPush(current = since, pushed = dirty).alertsDirty, "changed since the read")
    }

    @Test
    fun `each flag clears on its own`() {
        val both = dirty.copy(spoilerDirty = true)
        val pushed = both.copy(scoresShown = !both.scoresShown) // spoiler changed since; alerts the same
        val after = RadarPrefsPull.afterPush(current = both, pushed = pushed)
        assertFalse(after.alertsDirty, "alerts confirmed")
        assertTrue(after.spoilerDirty, "the spoiler change still waits")
    }

    @Test
    fun `alerts_dirty survives the local blob round-trip`() {
        val text = json.encodeToString(RadarLocalState.serializer(), RadarLocalState(prefs = dirty))
        val back = json.decodeFromString<RadarLocalState>(text).prefs
        assertEquals(true, back.alertsDirty, "round-trip keeps it")
        assertEquals(quietAlerts, back.alerts, "and the values")
    }
}
