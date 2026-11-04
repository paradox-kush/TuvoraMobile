package com.nuvio.app

import com.nuvio.app.core.diag.installLogRedaction
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.contracts.IptvSubtitleIdAccess
import com.nuvio.app.core.contracts.LivePlaybackAccess
import com.nuvio.app.core.contracts.LiveRecentsAccess
import com.nuvio.app.core.contracts.LocalStateCleanerRegistry
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.PlaybackGateAccess
import com.nuvio.app.core.contracts.ProfileChangeParticipants
import com.nuvio.app.core.contracts.RecTrackingAccess
import com.nuvio.app.core.contracts.SyncParticipantRegistry
import com.nuvio.app.core.memory.MemoryPortImpl
import com.nuvio.app.core.rec.RecLocalStateCleaner
import com.nuvio.app.core.rec.RecPlaybackReporterImpl
import com.nuvio.app.core.rec.RecSettingsImpl
import com.nuvio.app.features.iptv.IptvPlaybackGateAdapter
import com.nuvio.app.features.iptv.DeviceSetupClaimCoordinator
import com.nuvio.app.features.iptv.IptvProfileChange
import com.nuvio.app.features.iptv.XtreamAccountsCleaner
import com.nuvio.app.features.iptv.XtreamLivePlaybackProvider
import com.nuvio.app.features.iptv.XtreamLiveRecentsProvider
import com.nuvio.app.features.iptv.XtreamRecentsCleaner
import com.nuvio.app.features.iptv.IptvSourceRegistrations
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamSubtitleIdResolver
import com.nuvio.app.features.iptv.XtreamSyncParticipant
import com.nuvio.app.features.iptv.overlay.IptvOverlaySyncParticipant
import com.nuvio.app.features.radar.RadarProfileChange
import com.nuvio.app.features.radar.RadarSyncParticipant

/**
 * Process-init registration of the fork's LOGIC ports: data, sync, playback and memory — nothing that
 * renders. Split out of [registerFeatureContributions] (FeatureWiring.kt) so the Apple TV build
 * (NuvioMobile :tvosCore), which cannot compile the Compose UI slots, registers exactly the same list
 * from its own composition root (TvAppGraph). Part of the composition root: the second file the
 * architecture test allows to name fork implementations. Add a new logic port HERE, not in
 * FeatureWiring.kt, or Apple TV will run without it.
 */
fun registerLogicFeatureContributions() {
    // B116: first, so every later log line (all Kermit loggers share this config) is redacted.
    installLogRedaction()
    // S10: app-wide memory port (AppMemory + BudgetRegistry) — image loaders, player buffer
    // sizing, and the platform startup probes size their budgets through this.
    MemoryPortAccess.register(MemoryPortImpl)
    // S3a: register the IptvCatalog read port for non-Compose consumers.
    IptvCatalogAccess.register(XtreamRepository)
    // Plural source ports (media-servers design 5.1): IPTV registers as ONE entry in each - content
    // classifier, stream source, meta source, search provider - plus its own-source id predicates.
    IptvSourceRegistrations.register()
    // Jellyfin/Emby is upstream's features/servers, wired directly in the shared spine (sync via
    // sync_pull/push_media_servers) - nothing to register here.
    SyncParticipantRegistry.register(XtreamSyncParticipant)
    SyncParticipantRegistry.register(RadarSyncParticipant)
    SyncParticipantRegistry.register(IptvOverlaySyncParticipant)
    LocalStateCleanerRegistry.register(XtreamRecentsCleaner)
    LocalStateCleanerRegistry.register(RecLocalStateCleaner)
    LocalStateCleanerRegistry.register(XtreamAccountsCleaner)
    RecTrackingAccess.register(RecPlaybackReporterImpl)
    RecTrackingAccess.registerSettings(RecSettingsImpl)
    ProfileChangeParticipants.register(IptvProfileChange)
    ProfileChangeParticipants.register(RadarProfileChange)
    LiveRecentsAccess.register(XtreamLiveRecentsProvider)
    PlaybackGateAccess.register(IptvPlaybackGateAdapter)
    LivePlaybackAccess.register(XtreamLivePlaybackProvider)
    // F17: public subtitle ids for IPTV movies/episodes (OpenSubtitles in the IPTV section).
    IptvSubtitleIdAccess.register(XtreamSubtitleIdResolver)
    // B205: playlists redeemed from a setup code with no account move into the account once one signs in.
    DeviceSetupClaimCoordinator.shared.start()
}
