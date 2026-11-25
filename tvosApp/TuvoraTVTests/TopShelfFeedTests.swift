import XCTest
@testable import TuvoraTV

final class TopShelfFeedTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    private func item(_ id: String, kind: String? = nil, airsAtMs: Int64? = nil) -> TopShelfFeed.Item {
        TopShelfFeed.Item(id: id, title: id, deepLink: "tuvora://title?type=series&id=\(id)", kind: kind, airsAtMs: airsAtMs)
    }

    func testV1FeedStillDecodesAsOneContinueWatchingSection() throws {
        let json = #"{"items":[{"id":"a|1","title":"Show","subtitle":"S1E2","imageURL":null,"progress":0.4,"deepLink":"tuvora://title?type=series&id=a"}]}"#
        let feed = try XCTUnwrap(TopShelfFeed.decode(Data(json.utf8)))
        XCTAssertEqual(feed.version, 1)
        XCTAssertEqual(feed.sections.count, 1)
        XCTAssertEqual(feed.sections[0].id, TopShelfFeed.SectionID.continueWatching)
        XCTAssertEqual(feed.sections[0].items.first?.title, "Show")
        XCTAssertNil(feed.sections[0].items.first?.kind)
    }

    func testV2FeedRoundTripsWithTitlesFromTheFeed() throws {
        let feed = TopShelfFeed(sections: [.init(id: "new_episodes", title: "Nuevos episodios", items: [item("x", kind: TopShelfFeed.Kind.newEpisode, airsAtMs: 5)])])
        let decoded = try XCTUnwrap(TopShelfFeed.decode(try JSONEncoder().encode(feed)))
        XCTAssertEqual(decoded, feed)
        XCTAssertEqual(decoded.version, 2)
        XCTAssertEqual(decoded.visibleSections(at: t0).first?.title, "Nuevos episodios")
    }

    func testUnairedEpisodeIsHiddenBeforeAirsAtMsAndShownAfter() {
        let airs = Int64(t0.timeIntervalSince1970 * 1000) + 60_000
        let feed = TopShelfFeed(sections: [.init(id: "new_episodes", title: "New", items: [item("e", kind: TopShelfFeed.Kind.newEpisode, airsAtMs: airs)])])
        XCTAssertTrue(feed.visibleSections(at: t0).isEmpty, "before air time the section has nothing to show")
        XCTAssertEqual(feed.visibleSections(at: t0.addingTimeInterval(61)).first?.items.count, 1)
    }

    func testUnairedItemsAreDroppedButAiredOnesInTheSameSectionStay() {
        let now = Int64(t0.timeIntervalSince1970 * 1000)
        let feed = TopShelfFeed(sections: [.init(id: "new_episodes", title: "New", items: [
            item("aired", kind: TopShelfFeed.Kind.newEpisode, airsAtMs: now - 1000),
            item("later", kind: TopShelfFeed.Kind.newEpisode, airsAtMs: now + 1000)])])
        XCTAssertEqual(feed.visibleSections(at: t0).first?.items.map(\.id), ["aired"])
    }

    func testEmptyOrMissingFeedGivesNoTopShelfContent() {
        XCTAssertNil(TopShelfContentFactory.content(from: nil, now: t0))
        XCTAssertNil(TopShelfContentFactory.content(from: .empty, now: t0))
        let onlyUnaired = TopShelfFeed(sections: [.init(id: "new_episodes", title: "New", items: [item("e", airsAtMs: Int64(t0.timeIntervalSince1970 * 1000) + 1000)])])
        XCTAssertNil(TopShelfContentFactory.content(from: onlyUnaired, now: t0))
    }

    func testFeedWithAShowableItemGivesContent() {
        let feed = TopShelfFeed(sections: [.init(id: "continue_watching", title: "Continue", items: [item("a")])])
        XCTAssertNotNil(TopShelfContentFactory.content(from: feed, now: t0))
    }
}

final class TopShelfFeedBuilderTests: XCTestCase {
    private let titles = TopShelfTitles(continueWatching: "CW-loc", newEpisodes: "NE-loc", recentChannels: "RC-loc")
    private func item(_ id: String) -> TopShelfFeed.Item { TopShelfFeed.Item(id: id, title: id, deepLink: "tuvora://title?type=movie&id=\(id)") }
    private var selection: TopShelfSelection {
        TopShelfSelection(continueWatching: [item("c")], newEpisodes: [item("n")], recentChannels: [item("r")])
    }

    func testDefaultsHaveChannelsOffAndTheRestOn() {
        let defaults = TopShelfSwitches()
        XCTAssertTrue(defaults.continueWatching)
        XCTAssertTrue(defaults.newEpisodes)
        XCTAssertFalse(defaults.recentChannels, "channel names show on a shared screen")
    }

    func testSectionTitlesComeFromTheApp() {
        let feed = TopShelfFeedBuilder.build(selection, switches: TopShelfSwitches(recentChannels: true), titles: titles, active: true)
        XCTAssertEqual(feed.sections.map(\.title), ["CW-loc", "NE-loc", "RC-loc"])
    }

    func testSwitchOffLeavesItsSectionOut() {
        let feed = TopShelfFeedBuilder.build(selection, switches: TopShelfSwitches(continueWatching: false, newEpisodes: true, recentChannels: false), titles: titles, active: true)
        XCTAssertEqual(feed.sections.map(\.id), [TopShelfFeed.SectionID.newEpisodes])
    }

    func testAllSwitchesOffWritesTheEmptyFeed() {
        let off = TopShelfSwitches(continueWatching: false, newEpisodes: false, recentChannels: false)
        XCTAssertEqual(TopShelfFeedBuilder.build(selection, switches: off, titles: titles, active: true), .empty)
    }

    func testInactiveAppWritesTheEmptyFeedWhateverTheSwitches() {
        XCTAssertEqual(TopShelfFeedBuilder.build(selection, switches: TopShelfSwitches(recentChannels: true), titles: titles, active: false), .empty)
    }

    func testEmptySelectionsWriteNoSections() {
        XCTAssertTrue(TopShelfFeedBuilder.build(TopShelfSelection(), switches: TopShelfSwitches(), titles: titles, active: true).sections.isEmpty)
    }
}

final class TopShelfPublishGateTests: XCTestCase {
    func testNothingShowsBeforeTheMainShell() {
        XCTAssertFalse(TopShelfPublishGate().mayShow)
    }

    func testMainShellShowsAndLeavingHidesAtOnce() {
        var gate = TopShelfPublishGate()
        gate.screenChanged(isMain: true, sessionGeneration: 1)
        XCTAssertTrue(gate.mayShow)
        gate.screenChanged(isMain: false, sessionGeneration: 1)
        XCTAssertFalse(gate.mayShow, "sign-out and the profile picker clear the shared screen")
    }

    func testClosingThePickerInTheSameSessionShowsTheSameListAgain() {
        var gate = TopShelfPublishGate()
        gate.screenChanged(isMain: true, sessionGeneration: 1)
        gate.screenChanged(isMain: false, sessionGeneration: 1)
        gate.screenChanged(isMain: true, sessionGeneration: 1)
        XCTAssertTrue(gate.mayShow)
    }

    func testANewProfileWaitsForItsOwnListBeforeAnythingShows() {
        var gate = TopShelfPublishGate()
        gate.screenChanged(isMain: true, sessionGeneration: 1)
        gate.screenChanged(isMain: false, sessionGeneration: 1)   // picker, then switching
        gate.screenChanged(isMain: true, sessionGeneration: 2)
        XCTAssertFalse(gate.mayShow, "the previous profile's list must not be republished")
        gate.itemsArrived()
        XCTAssertTrue(gate.mayShow)
    }
}

final class LiveDeepLinkTests: XCTestCase {
    func testRoundTrip() throws {
        let link = LiveDeepLink(id: "xtream:1:42", name: "News & More", logo: "https://img.example/l.png")
        let parsed = try XCTUnwrap(LiveDeepLink(url: link.url))
        XCTAssertEqual(parsed, link)
    }

    func testTitleLinksAreNotLiveLinks() {
        XCTAssertNil(LiveDeepLink(url: DeepLink.url(type: "movie", id: "tt1", videoId: nil)))
        XCTAssertNil(DeepLink(url: LiveDeepLink(id: "c", name: "n", logo: nil).url))
    }
}
