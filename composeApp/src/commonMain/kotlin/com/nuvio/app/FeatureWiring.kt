package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import co.touchlab.kermit.Logger
import com.nuvio.app.features.common.lifecycle.FeatureRegistry
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.memory.MemoryPortImpl
import com.nuvio.app.features.common.lifecycle.LocalRevertFailureSink
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.contracts.IptvContentClassifierAccess
import com.nuvio.app.features.iptv.XtreamContentClassifier
import com.nuvio.app.features.iptv.XtreamSyncParticipant
import com.nuvio.app.features.iptv.overlay.IptvOverlaySyncParticipant
import com.nuvio.app.features.radar.RadarSyncParticipant
import com.nuvio.app.core.contracts.SyncParticipantRegistry
import com.nuvio.app.core.contracts.LocalStateCleanerRegistry
import com.nuvio.app.features.iptv.XtreamRecentsCleaner
import com.nuvio.app.features.iptv.XtreamAccountsCleaner
import com.nuvio.app.core.rec.RecLocalStateCleaner
import com.nuvio.app.core.contracts.IptvSearchAccess
import com.nuvio.app.features.iptv.XtreamSearchProvider
import com.nuvio.app.core.contracts.RecTrackingAccess
import com.nuvio.app.core.rec.RecPlaybackReporterImpl
import com.nuvio.app.core.rec.RecSettingsImpl
import com.nuvio.app.core.contracts.LocalIptvCatalog
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.core.contracts.ProfileChangeParticipants
import com.nuvio.app.features.iptv.IptvProfileChange
import com.nuvio.app.features.radar.RadarProfileChange
import com.nuvio.app.core.contracts.HomeRecAccess
import com.nuvio.app.core.rec.HomeRecBinderImpl
import com.nuvio.app.core.contracts.IptvSettingsSectionAccess
import com.nuvio.app.features.iptv.IptvSettingsSectionImpl
import com.nuvio.app.core.contracts.LiveRecentsAccess
import com.nuvio.app.features.iptv.XtreamLiveRecentsProvider
import com.nuvio.app.core.contracts.HomeSportsSectionAccess
import com.nuvio.app.features.radar.RadarHomeSportsSection
import com.nuvio.app.core.contracts.StreamSourceAccess
import com.nuvio.app.features.iptv.XtreamStreamSourceProvider
import com.nuvio.app.core.contracts.MetaSourceAccess
import com.nuvio.app.features.iptv.XtreamMetaSource
import com.nuvio.app.core.contracts.PlaybackGateAccess
import com.nuvio.app.features.iptv.IptvPlaybackGateAdapter
import com.nuvio.app.core.contracts.LivePlaybackAccess
import com.nuvio.app.features.iptv.XtreamLivePlaybackProvider
import com.nuvio.app.core.contracts.IptvHubContentAccess
import com.nuvio.app.features.iptv.XtreamHubContent
import com.nuvio.app.core.contracts.SportsHubContentAccess
import com.nuvio.app.features.radar.RadarHubContent
import com.nuvio.app.core.contracts.LiveTvContentAccess
import com.nuvio.app.features.livetv.LiveTvContentImpl

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
    // S10: app-wide memory port (AppMemory + BudgetRegistry) — image loaders, player buffer
    // sizing, and the platform startup probes size their budgets through this.
    MemoryPortAccess.register(MemoryPortImpl)
    // S3a: register the IptvCatalog read port for non-Compose consumers.
    IptvCatalogAccess.register(XtreamRepository)
    IptvContentClassifierAccess.register(XtreamContentClassifier)
    SyncParticipantRegistry.register(XtreamSyncParticipant)
    SyncParticipantRegistry.register(RadarSyncParticipant)
    SyncParticipantRegistry.register(IptvOverlaySyncParticipant)
    LocalStateCleanerRegistry.register(XtreamRecentsCleaner)
    LocalStateCleanerRegistry.register(RecLocalStateCleaner)
    LocalStateCleanerRegistry.register(XtreamAccountsCleaner)
    IptvSearchAccess.register(XtreamSearchProvider)
    RecTrackingAccess.register(RecPlaybackReporterImpl)
    RecTrackingAccess.registerSettings(RecSettingsImpl)
    ProfileChangeParticipants.register(IptvProfileChange)
    ProfileChangeParticipants.register(RadarProfileChange)
    HomeRecAccess.register(HomeRecBinderImpl)
    IptvSettingsSectionAccess.register(IptvSettingsSectionImpl)
    LiveRecentsAccess.register(XtreamLiveRecentsProvider)
    HomeSportsSectionAccess.register(RadarHomeSportsSection)
    StreamSourceAccess.register(XtreamStreamSourceProvider)
    MetaSourceAccess.register(XtreamMetaSource)
    PlaybackGateAccess.register(IptvPlaybackGateAdapter)
    LivePlaybackAccess.register(XtreamLivePlaybackProvider)
    IptvHubContentAccess.register(XtreamHubContent)
    SportsHubContentAccess.register(RadarHubContent)
    LiveTvContentAccess.register(LiveTvContentImpl)
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
