import TuvoraCore

// Copy that names add-ons or plugins. The App Store build compiles both systems out
// (AppFeaturePolicy.addonsEnabled / pluginsEnabled, same as the phone's iosAppStore build), so it must
// not advertise them; the NuvioTV wording stays for a build that has them. The store variants reuse the
// phone's store copy where one exists (home_empty_iptv_hint_*), so they pick up its translations.
enum StoreCopy {
    static var hasAddons: Bool { AppFeaturePolicy.shared.addonsEnabled }
    static var hasPlugins: Bool { AppFeaturePolicy.shared.pluginsEnabled }

    /// Settings > Content & Discovery is the add-on/plugin manager: nothing to show without either.
    static var showsContentDiscovery: Bool { hasAddons || hasPlugins }

    /// Empty Home copy for TvHome.emptyHomeHint(): a viewer who already has a playlist is never asked to add one.
    static func emptyHomeTitle(_ hint: TvEmptyHomeHint) -> String {
        hint == .addPlaylist ? "No content yet" : "Nothing to show yet"
    }
    static func emptyHomeMessage(_ hint: TvEmptyHomeHint) -> String {
        switch hint {
        case .addPlaylist: return "Add your IPTV playlist in Settings to see your channels, movies, and series here."
        case .playlistInIptvTab: return "Your playlist's channels, movies, and series are in the IPTV tab, and Search finds them too."
        default: return "Install add-ons or add a playlist to fill your home screen."
        }
    }
    static var noSourcesMessage: String {
        hasAddons ? "None of your add-ons or playlists have this title." : "None of your playlists have this title."
    }
    static var noSearchCatalogsMessage: String {
        hasAddons ? "No searchable catalogs found in installed addons"
            : "Add your IPTV playlist in Settings to see your channels, movies, and series here."
    }
    static var accountSyncDescription: String {
        hasAddons ? "Sync your library, watch progress, addons, and plugins across devices."
            : "Sync your library, watch progress, and playlists across devices."
    }
    static var signOutSubtitle: String {
        hasAddons ? "You will need to sign in again to sync library, watch progress, addons, and plugins on this device."
            : "You will need to sign in again to sync library, watch progress, and playlists on this device."
    }
    static var deleteProfileSubtitle: String {
        hasAddons
            ? "This will permanently delete this profile and all its data including library, watch history, and addon settings. This cannot be undone."
            : "This will permanently delete this profile and all its data including library, watch history, and settings. This cannot be undone."
    }
    static var tmdbEnrichmentSubtitle: String {
        hasAddons ? "Use TMDB as a metadata source to enhance addon data" : "Use TMDB as a metadata source to enrich titles and artwork"
    }
}
