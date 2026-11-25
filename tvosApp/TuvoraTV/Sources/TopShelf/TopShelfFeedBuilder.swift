import Foundation

/// The three Top Shelf switches. Device-local on purpose (the Top Shelf is this TV's shared screen): never synced.
struct TopShelfSwitches: Equatable {
    var continueWatching = true
    var newEpisodes = true
    /// Off by default: channel names show on a shared screen and IPTV lists can include adult channels.
    var recentChannels = false

    static let continueWatchingKey = "tvos.topshelf.continueWatching"
    static let newEpisodesKey = "tvos.topshelf.newEpisodes"
    static let recentChannelsKey = "tvos.topshelf.recentChannels"

    static func stored(_ defaults: UserDefaults = .standard) -> TopShelfSwitches {
        TopShelfSwitches(
            continueWatching: defaults.object(forKey: continueWatchingKey) as? Bool ?? true,
            newEpisodes: defaults.object(forKey: newEpisodesKey) as? Bool ?? true,
            recentChannels: defaults.object(forKey: recentChannelsKey) as? Bool ?? false
        )
    }
}

/// The section titles, localised by the app (the extension holds no strings).
struct TopShelfTitles: Equatable {
    var continueWatching = "Continue Watching"
    var newEpisodes = "New episodes"
    var recentChannels = "Recent channels"
}

/// What the app has to offer each section, before the switches decide.
struct TopShelfSelection: Equatable {
    var continueWatching: [TopShelfFeed.Item] = []
    var newEpisodes: [TopShelfFeed.Item] = []
    var recentChannels: [TopShelfFeed.Item] = []
}

/// Pure: the feed to write for a selection, the switches and whether anything may be shown now. A switch that is off
/// leaves its section out; everything off, or `active == false` (signed out, profile picker, profile switching), is the
/// empty feed — nothing of the previous viewer is left on a shared screen.
enum TopShelfFeedBuilder {
    static func build(_ selection: TopShelfSelection, switches: TopShelfSwitches, titles: TopShelfTitles, active: Bool) -> TopShelfFeed {
        guard active else { return .empty }
        var sections: [TopShelfFeed.Section] = []
        if switches.continueWatching, !selection.continueWatching.isEmpty {
            sections.append(.init(id: TopShelfFeed.SectionID.continueWatching, title: titles.continueWatching, items: selection.continueWatching))
        }
        if switches.newEpisodes, !selection.newEpisodes.isEmpty {
            sections.append(.init(id: TopShelfFeed.SectionID.newEpisodes, title: titles.newEpisodes, items: selection.newEpisodes))
        }
        if switches.recentChannels, !selection.recentChannels.isEmpty {
            sections.append(.init(id: TopShelfFeed.SectionID.recentChannels, title: titles.recentChannels, items: selection.recentChannels))
        }
        return TopShelfFeed(sections: sections)
    }
}

/// Pure: when the publisher may show a profile's titles. Anything but the main shell (sign-in, profile picker,
/// switching) hides them; returning to the main shell shows them again only for the same session, or once the new
/// session's own Continue Watching has arrived, so the previous profile's list is never republished.
struct TopShelfPublishGate: Equatable {
    private(set) var inMain = false
    private(set) var needsFreshItems = false
    private var generationWhenLeft: Int32?

    var mayShow: Bool { inMain && !needsFreshItems }

    mutating func screenChanged(isMain: Bool, sessionGeneration: Int32) {
        if isMain {
            // Back in the same session (the picker was closed without switching) the list is still theirs; a new
            // session (profile switch, sign-in) must wait for its own list.
            if !inMain { needsFreshItems = generationWhenLeft != nil && generationWhenLeft != sessionGeneration }
            inMain = true
        } else {
            if inMain { generationWhenLeft = sessionGeneration }
            inMain = false
        }
    }

    /// Continue Watching emitted: it now belongs to the current session.
    mutating func itemsArrived() { needsFreshItems = false }
}
