package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import co.touchlab.kermit.Logger
import com.nuvio.app.features.common.lifecycle.FeatureRegistry
import com.nuvio.app.features.common.lifecycle.LocalRevertFailureSink
import com.nuvio.app.core.contracts.LocalIptvCatalog
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.core.contracts.HomeRecAccess
import com.nuvio.app.core.rec.HomeRecBinderImpl
import com.nuvio.app.core.contracts.IptvSettingsSectionAccess
import com.nuvio.app.features.iptv.IptvSettingsSectionImpl
import com.nuvio.app.core.contracts.HomeSportsSectionAccess
import com.nuvio.app.features.radar.RadarHomeSportsSection
import com.nuvio.app.core.contracts.IptvHubContentAccess
import com.nuvio.app.core.contracts.SetupCodeEntryAccess
import com.nuvio.app.features.iptv.SetupCodeEntryImpl
import com.nuvio.app.features.iptv.XtreamHubContent
import com.nuvio.app.core.contracts.SportsHubContentAccess
import com.nuvio.app.features.radar.RadarHubContent
import com.nuvio.app.core.contracts.LiveTvContentAccess
import com.nuvio.app.features.livetv.LiveTvContentImpl
import com.nuvio.app.core.contracts.HomeAnnouncementsSectionAccess
import com.nuvio.app.features.announcements.api.AnnouncementsFeature
import com.nuvio.app.core.contracts.CalendarScreenContentAccess
import com.nuvio.app.core.contracts.DetailsCalendarBellAccess
import com.nuvio.app.core.contracts.HomeCalendarSectionAccess
import com.nuvio.app.features.calendar.api.CalendarUiSlots

/**
 * THE one firewall exception (rules doc Rule 1 / R2b): the only non-fork file allowed to name fork
 * implementations, because KMP commonMain has no classpath auto-discovery (no ServiceLoader /
 * reflection) — something must statically reference the fork registrations, and this is that
 * something. It is the permanent, single allowlist entry in the architecture test, and is fork-only
 * in practice (absent upstream) so it carries zero merge cost.
 *
 * Two wiring points, deliberately separate:
 *   - [registerFeatureContributions] — PROCESS-INIT (contributions + ports registered once per
 *     process), called from the platform entry point.
 *   - [installFeatures] — COMPOSITION (provides the ports as CompositionLocals to the UI tree).
 *
 * Phase 0 status: no feature ports exist yet (S3a defines the first). This file lands as the
 * structure + the loud init guard, so the bootstrap is proven before any port is built on it. Each
 * later seam adds its registration/provision line here — do NOT invent ports to fill it.
 */

private val revertLog = Logger.withTag("EffectScope")

/**
 * Called ONCE per PROCESS from a genuinely once-per-process entry point:
 *   Android → `NuvioApplication.onCreate` (NOT `MainActivity.onCreate`, which re-runs on every
 *     configuration-change recreation in the same process → duplicate registration → crash on rotate);
 *   iOS → the app's `@main` bootstrap (NOT a view-controller factory — controllers recreate);
 *   Desktop → `main` before the window opens.
 * NEVER call from a @Composable body (recomposition re-runs it — same crash class).
 */
fun registerFeatureContributions() {
    registerLogicFeatureContributions()
    // UI slots: Compose content the fork contributes to shared screens (not compiled on Apple TV).
    HomeRecAccess.register(HomeRecBinderImpl)
    IptvSettingsSectionAccess.register(IptvSettingsSectionImpl)
    // 13th sync: the fork media-server settings section was retired in favour of upstream SettingsPage.MediaServers.
    HomeSportsSectionAccess.register(RadarHomeSportsSection)
    IptvHubContentAccess.register(XtreamHubContent)
    SetupCodeEntryAccess.register(SetupCodeEntryImpl)
    SportsHubContentAccess.register(RadarHubContent)
    LiveTvContentAccess.register(LiveTvContentImpl)
    // In-app announcements card on Home (lazy: the repository touches storage on first refresh).
    HomeAnnouncementsSectionAccess.register(AnnouncementsFeature.homeSection())
    // Calendar + reminders: the Home section (reminders card, opt-in Coming up row), the bell on details, the page.
    HomeCalendarSectionAccess.register(CalendarUiSlots.homeSection())
    DetailsCalendarBellAccess.register(CalendarUiSlots.detailsBell())
    CalendarScreenContentAccess.register(CalendarUiSlots.screen())
    FeatureRegistry.markInitialized()
}

/**
 * Wraps the app content, providing the fork ports to the UI tree. Provisioning ONLY — never
 * registration (that is process-init, above). The [check] makes a forgotten app-init call a loud
 * startup error instead of silently emptying every registry.
 */
@Composable
fun installFeatures(content: @Composable () -> Unit) {
    check(FeatureRegistry.isInitialized) {
        "registerFeatureContributions() was not called at app init — see FeatureWiring.kt"
    }
    CompositionLocalProvider(
        LocalRevertFailureSink provides ::onRevertFailure,
        LocalIptvCatalog provides XtreamRepository,   // S3a — stable object, safe in a static local
        // Later seams add their ports here: LocalSportsData, LocalClock, …
    ) { content() }
}

/**
 * Previews and UI tests never run app-init, so they cannot use [installFeatures] (its [check] would
 * throw). This provides the ports as fakes and bypasses the guard. It lives in THIS file on purpose:
 * it names fork api types too, so any other home would need a second firewall exemption, eroding the
 * one-exception invariant. Phase 0 has no ports, so it currently only supplies the revert sink.
 */
@Composable
fun PreviewFeatureWiring(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRevertFailureSink provides { /* no-op in previews */ },
    ) { content() }
}

/**
 * Telemetry-at-the-disposal-boundary (design G4 / S9). Phase 0 logs; S9 additionally captures a
 * telemetry event so a failed/late release becomes a measured signal instead of a silent leak.
 */
private fun onRevertFailure(t: Throwable) {
    revertLog.w(t) { "revert failed during teardown" }
}
