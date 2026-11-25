import Foundation
import TVServices

/// Turns the feed into Top Shelf content. Shared so the app's tests can run the same code the extension runs.
enum TopShelfContentFactory {
    /// nil (the plain Tuvora banner) when there is no feed or nothing in it is showable at `now`.
    static func content(from feed: TopShelfFeed?, now: Date) -> TVTopShelfSectionedContent? {
        guard let feed else { return nil }
        let sections = feed.visibleSections(at: now)
        guard !sections.isEmpty else { return nil }
        return TVTopShelfSectionedContent(sections: sections.map(collection))
    }

    private static func collection(_ section: TopShelfFeed.Section) -> TVTopShelfItemCollection<TVTopShelfSectionedItem> {
        let items: [TVTopShelfSectionedItem] = section.items.map { entry in
            let item = TVTopShelfSectionedItem(identifier: entry.id)
            item.title = entry.subtitle.map { "\(entry.title) · \($0)" } ?? entry.title
            let isChannel = entry.kind == TopShelfFeed.Kind.channel
            item.imageShape = isChannel ? .square : .hdtv
            if let string = entry.imageURL, let url = URL(string: string) {
                item.setImageURL(url, for: .screenScale1x)
                item.setImageURL(url, for: .screenScale2x)
            }
            if !isChannel { item.playbackProgress = entry.progress }
            if let url = URL(string: entry.deepLink) {
                item.displayAction = TVTopShelfAction(url: url)
                // A channel has nothing to open besides playing it; a title resumes on Play/Pause.
                item.playAction = TVTopShelfAction(url: isChannel ? url : url.appendingQuery("play", "1"))
            }
            return item
        }
        let collection = TVTopShelfItemCollection(items: items)
        collection.title = section.title
        return collection
    }
}

private extension URL {
    func appendingQuery(_ name: String, _ value: String) -> URL {
        var components = URLComponents(url: self, resolvingAgainstBaseURL: false)
        let existing = components?.queryItems ?? []
        components?.queryItems = existing + [URLQueryItem(name: name, value: value)]
        return components?.url ?? self
    }
}
