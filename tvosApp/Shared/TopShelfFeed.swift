import Foundation

/// What the Top Shelf extension shows, written by the app into the shared App Group (tvOS gives an app group only
/// Library/Caches — purgeable, and the app rewrites it on every change).
///
/// v2: `version`, `sections: [{id, title, items}]`, per item `kind` and `airsAtMs`. Section titles are localised by the
/// app and carried here, so the extension holds no strings. A v1 feed (`{"items": […]}`) still decodes, as one
/// "Continue Watching" section.
struct TopShelfFeed: Codable, Equatable {
    static let currentVersion = 2

    enum Kind {
        static let continueWatching = "continue"
        static let newEpisode = "new_episode"
        static let channel = "channel"
    }

    enum SectionID {
        static let continueWatching = "continue_watching"
        static let newEpisodes = "new_episodes"
        static let recentChannels = "recent_channels"
    }

    struct Item: Codable, Equatable {
        let id: String
        let title: String
        let subtitle: String?
        let imageURL: String?
        let progress: Double
        /// tuvora://title?type=…&id=…&video=… (or tuvora://live?id=…) — opens the title (Select) or resumes it (Play/Pause).
        let deepLink: String
        /// `Kind.*`; nil in a v1 feed (a Continue Watching card).
        let kind: String?
        /// A new episode's air time (epoch ms). An item still in the future is hidden until then.
        let airsAtMs: Int64?

        init(id: String, title: String, subtitle: String? = nil, imageURL: String? = nil, progress: Double = 0,
             deepLink: String, kind: String? = nil, airsAtMs: Int64? = nil) {
            self.id = id; self.title = title; self.subtitle = subtitle; self.imageURL = imageURL
            self.progress = progress; self.deepLink = deepLink; self.kind = kind; self.airsAtMs = airsAtMs
        }

        /// Not yet on air at `now`: the extension must not show it.
        func isUnaired(at now: Date) -> Bool {
            guard let airsAtMs else { return false }
            return Double(airsAtMs) > now.timeIntervalSince1970 * 1000
        }
    }

    struct Section: Codable, Equatable {
        let id: String
        let title: String
        let items: [Item]
    }

    let version: Int
    let sections: [Section]

    init(sections: [Section]) {
        self.version = Self.currentVersion
        self.sections = sections
    }

    static let empty = TopShelfFeed(sections: [])

    private enum CodingKeys: String, CodingKey { case version, sections, items }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let sections = try container.decodeIfPresent([Section].self, forKey: .sections) {
            self.version = try container.decodeIfPresent(Int.self, forKey: .version) ?? Self.currentVersion
            self.sections = sections
        } else {
            // v1: one flat list, titled by the extension before; the title now travels in the feed.
            let items = try container.decode([Item].self, forKey: .items)
            self.version = 1
            self.sections = items.isEmpty ? [] : [Section(id: SectionID.continueWatching, title: "Continue Watching", items: items)]
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encode(sections, forKey: .sections)
    }

    /// The sections to show at `now`: unaired items dropped, then sections left with nothing dropped.
    func visibleSections(at now: Date) -> [Section] {
        sections.compactMap { section in
            let items = section.items.filter { !$0.isUnaired(at: now) }
            return items.isEmpty ? nil : Section(id: section.id, title: section.title, items: items)
        }
    }

    static let appGroup = "group.com.tuvora.media"

    static var fileURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("Library/Caches", isDirectory: true)
            .appendingPathComponent("topshelf.json")
    }

    static func decode(_ data: Data) -> TopShelfFeed? { try? JSONDecoder().decode(TopShelfFeed.self, from: data) }

    static func read() -> TopShelfFeed? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    func write() {
        guard let url = Self.fileURL, let data = try? JSONEncoder().encode(self) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }
}
