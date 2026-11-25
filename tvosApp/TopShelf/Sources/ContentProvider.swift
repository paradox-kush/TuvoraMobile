import Foundation
import TVServices

/// Tuvora's Top Shelf: sectioned rows of cards (HIG › Top Shelf) — Continue Watching, New episodes, Recent channels, each
/// only when the viewer left it on. The app writes the feed; this extension only reads it. Unaired episodes are in the
/// feed and held back here against the clock, so they appear at air time with the app closed.
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping (TVTopShelfContent?) -> Void) {
        completionHandler(TopShelfContentFactory.content(from: TopShelfFeed.read(), now: Date()))
    }
}
