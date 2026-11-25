import Foundation
import TVServices
import TuvoraCore

/// Keeps the Top Shelf feed in step with Continue Watching (TvHome), new episodes and recent channels, and tells tvOS
/// it changed. Which sections exist is the viewer's (Settings › Home rows › Top Shelf); what is in each is shared
/// policy (TvTopShelfPolicy); the feed it writes is `TopShelfFeedBuilder`'s. Nothing of a profile is on the shared
/// screen unless the app is in that profile's main shell (`TopShelfPublishGate`).
@MainActor
enum TopShelfPublisher {
    private static var started = false
    private static var watchingGate = false
    private static var gate = TopShelfPublishGate()
    private static var continueWatching: [TvCwItem] = []
    private static var channels: [XtreamLiveRecent] = []
    private static var lastWritten: TopShelfFeed?

    /// Called once from the root (launch): from then on the feed is empty whenever the app is not in a profile's main
    /// shell — sign-in, the profile picker, a profile switch — and so no previous profile's titles stay on the Top Shelf.
    static func watchGate() {
        guard !watchingGate else { return }
        watchingGate = true
        Task { @MainActor in
            for await screen in TvAppLifecycle.shared.screen {
                gate.screenChanged(isMain: screen == .main, sessionGeneration: TvAppLifecycle.shared.sessionGeneration.value.int32Value)
                republish()
            }
        }
    }

    /// From the main shell: starts following the data.
    static func start() {
        watchGate()
        guard !started else { return }
        started = true
        TvHome.shared.start()
        Task { @MainActor in
            for await items in TvHome.shared.continueWatching {
                gate.itemsArrived()
                continueWatching = items
                republish()
            }
        }
        Task { @MainActor in
            for await recents in TvTopShelf.shared.recentChannels {
                channels = recents
                republish()
            }
        }
    }

    /// A Top Shelf switch changed: rewrite the feed now (a section turned off leaves it at once).
    static func refresh() { republish() }

    private static func republish() {
        let feed = TopShelfFeedBuilder.build(
            selection(), switches: .stored(),
            titles: TopShelfTitles(
                continueWatching: LK("home_section_continue_watching", "Continue Watching"),
                newEpisodes: LK("home_section_new_episodes", "New episodes"),
                recentChannels: LK("home_section_recent_channels", "Recent channels")),
            active: gate.mayShow)
        guard feed != lastWritten else { return }
        lastWritten = feed
        feed.write()
        TVTopShelfContentProvider.topShelfContentDidChange()
    }

    private static func selection() -> TopShelfSelection {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        return TopShelfSelection(
            continueWatching: TvTopShelfPolicy.shared.continueWatching(items: continueWatching).map { item(from: $0, kind: TopShelfFeed.Kind.continueWatching) },
            newEpisodes: TvTopShelfPolicy.shared.newEpisodes(items: continueWatching, nowMs: nowMs).map { item(from: $0, kind: TopShelfFeed.Kind.newEpisode) },
            recentChannels: channels.map { channel in
                let link = LiveDeepLink(id: channel.contentId, name: channel.name, logo: channel.logo).url.absoluteString
                return TopShelfFeed.Item(id: "channel|" + channel.contentId, title: channel.name, imageURL: channel.logo, deepLink: link, kind: TopShelfFeed.Kind.channel)
            })
    }

    private static func item(from cw: TvCwItem, kind: String) -> TopShelfFeed.Item {
        TopShelfFeed.Item(
            id: kind + "|" + cw.parentMetaId + "|" + cw.videoId,
            title: cw.title,
            subtitle: TvContinueWatching.shared.episodeLabel(item: cw),
            imageURL: cw.artwork ?? cw.background,
            progress: Double(cw.progress),
            deepLink: DeepLink.url(type: cw.parentMetaType, id: cw.parentMetaId, videoId: cw.seasonNumber != nil ? cw.videoId : nil).absoluteString,
            kind: kind,
            airsAtMs: kind == TopShelfFeed.Kind.newEpisode ? cw.releasedAtMs?.int64Value : nil)
    }
}

/// tuvora://title?type=…&id=…[&video=…][&play=1]
struct DeepLink: Equatable {
    let type: String
    let id: String
    let videoId: String?
    let play: Bool

    static func url(type: String, id: String, videoId: String?) -> URL {
        var c = URLComponents()
        c.scheme = "tuvora"; c.host = "title"
        c.queryItems = [URLQueryItem(name: "type", value: type), URLQueryItem(name: "id", value: id)]
            + (videoId.map { [URLQueryItem(name: "video", value: $0)] } ?? [])
        return c.url!
    }

    init(type: String, id: String, videoId: String?, play: Bool) {
        self.type = type; self.id = id; self.videoId = videoId; self.play = play
    }

    init?(url: URL) {
        guard let scheme = url.scheme?.lowercased(), scheme == "tuvora" || scheme == "nuvio", url.host == "title",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let type = items.first(where: { $0.name == "type" })?.value,
              let id = items.first(where: { $0.name == "id" })?.value else { return nil }
        self.type = type; self.id = id
        videoId = items.first(where: { $0.name == "video" })?.value
        play = items.first(where: { $0.name == "play" })?.value == "1"
    }
}

/// tuvora://live?id=<channel content id>&name=…[&logo=…] — plays that live channel (a Top Shelf "Recent channels" card).
/// The name and logo ride along because playing a channel needs them and the app need not look them up first.
struct LiveDeepLink: Equatable {
    let id: String
    let name: String
    let logo: String?

    var url: URL {
        var c = URLComponents()
        c.scheme = "tuvora"; c.host = "live"
        c.queryItems = [URLQueryItem(name: "id", value: id), URLQueryItem(name: "name", value: name)]
            + (logo.map { [URLQueryItem(name: "logo", value: $0)] } ?? [])
        return c.url!
    }

    init(id: String, name: String, logo: String?) { self.id = id; self.name = name; self.logo = logo }

    init?(url: URL) {
        guard let scheme = url.scheme?.lowercased(), scheme == "tuvora" || scheme == "nuvio", url.host == "live",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let id = items.first(where: { $0.name == "id" })?.value, !id.isEmpty else { return nil }
        self.id = id
        name = items.first(where: { $0.name == "name" })?.value ?? ""
        logo = items.first(where: { $0.name == "logo" })?.value
    }
}

/// The deep link waiting to be opened once the app reaches its main screen.
@MainActor
final class DeepLinkCenter: ObservableObject {
    static let shared = DeepLinkCenter()
    @Published var pending: DeepLink?
    /// A live-channel link (tuvora://live?…): the shell plays it once it is up.
    @Published var pendingLive: LiveDeepLink?
    /// The IPTV hub's empty state asked for the setup-code screen: the shell switches to Settings, which opens it.
    @Published var openSetupCode = false
    /// The empty Home's "Open IPTV" action (a playlist exists, it just lives in the IPTV tab): the shell switches tabs.
    @Published var openIptvTab = false
    /// A watch-party link (tuvora://party/<code>): the shell opens Settings › Watch party with the code typed in.
    @Published var partyCode: String?
}
