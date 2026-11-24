package com.tuvora.tvos.screens

import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.features.radar.RevealedGames
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.radar.alerts.AlertContext
import com.nuvio.app.features.radar.alerts.AlertDecision
import com.nuvio.app.features.radar.alerts.AlertKind
import com.nuvio.app.features.radar.alerts.AlertLedger
import com.nuvio.app.features.radar.alerts.BannerAction
import com.nuvio.app.features.radar.alerts.SportsAlert
import com.nuvio.app.features.radar.alerts.SportsAlertPolicy
import com.nuvio.app.features.radar.alerts.SportsAlertSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Apple TV runs the phone's alert decision and only presents it: hint text, 10 s, and the settings rows Swift draws.
 * kotlin.test → assertEquals(expected, actual, message).
 */
class TvSportsAlertsTest {
    private val t0 = 1_800_000_000_000L

    private fun banner(kind: AlertKind, spoiler: ScoreSpoilerSettings = ScoreSpoilerSettings()) =
        SportsAlertPolicy.onAlert(
            SportsAlert(
                key = "e1:${kind.wire}", eventId = "e1", kind = kind, sport = "soccer", leagueId = "4328", league = "Premier League",
                homeId = "133604", awayId = "133610", home = "Arsenal", away = "Chelsea", homeScore = 2, awayScore = 1,
                progress = "FT", corrected = false, atMs = t0,
            ),
            SportsAlertSettings(),
            AlertContext(t0, 15 * 60, PlaybackContext.LiveTv, null, emptySet(), emptyMap(), emptySet(), setOf("133604"), spoiler, RevealedGames()),
            AlertLedger(),
        ).first.let { (it as AlertDecision.ShowNow).banner }

    @Test
    fun `apple tv shows the same banner text as the phone`() {
        val tv = TvSportsAlertsPolicy.banner(banner(AlertKind.Final))
        assertEquals("Full time · Arsenal 2–1 Chelsea", tv.title, "shared headline")
        assertEquals("Premier League · FT", tv.subtitle, "league and progress")
        assertEquals("Hold Select to open", tv.hint, "Open game until a Watch channel is found")
    }

    @Test
    fun `hidden scores give apple tv a full time without numbers`() {
        val tv = TvSportsAlertsPolicy.banner(banner(AlertKind.Final, ScoreSpoilerSettings(shown = false)))
        assertEquals("Full time · Arsenal v Chelsea", tv.title, "no numbers")
    }

    @Test
    fun `the hint follows the action`() {
        assertEquals("Hold Select to watch", TvSportsAlertsPolicy.hint(BannerAction.Watch("e1", "live:1", "Sky")), "watch")
        assertEquals("Hold Select to open", TvSportsAlertsPolicy.hint(BannerAction.OpenSports), "summary")
        assertEquals(10_000L, TvSportsAlertsPolicy.DISMISS_MS, "10 s on Apple TV")
    }

    @Test
    fun `the settings rows follow the switches`() {
        assertEquals(listOf(TvSportsAlertsPolicy.ROW_ENABLED), TvSportsAlertsPolicy.rows(SportsAlertSettings(), signedIn = false).map { it.id }, "signed out: one row")
        assertEquals(listOf(TvSportsAlertsPolicy.ROW_ENABLED), TvSportsAlertsPolicy.rows(SportsAlertSettings(enabled = false), signedIn = true).map { it.id }, "off: one row")
        val all = TvSportsAlertsPolicy.rows(SportsAlertSettings(), signedIn = true)
        assertEquals(9, all.size, "every setting while on with quiet hours on")
        assertEquals("22:00", all.first { it.id == TvSportsAlertsPolicy.ROW_QUIET_FROM }.value, "quiet from")
        assertEquals(7, TvSportsAlertsPolicy.rows(SportsAlertSettings(quietOn = false), signedIn = true).size, "no time rows with quiet hours off")
    }
}
