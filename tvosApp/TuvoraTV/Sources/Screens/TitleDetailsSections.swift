import SwiftUI
import TuvoraCore

/// NuvioTV's details sections below the hero and episodes (ui/screens/detail/MetaDetailsScreen.kt):
/// the tabbed people row (CastSection, EpisodeRatingsSection, MoreLikeThisSection, TrailerSection,
/// CollectionSection), then the split-out collection, CommentsSection and CompanyLogosSection.
/// Which sections exist and in what order is TvDetailSectionsPolicy (tvosCore, tested).
struct DetailLowerSections: View {
    let meta: MetaDetails
    let onOpenTitle: (MetaPreview) -> Void
    let onOpenPerson: (MetaPerson, Bool) -> Void
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var activeTab: TvDetailTab?
    @State private var cast: TvCastSplit?
    @State private var comments: [TraktCommentReview]?
    @State private var commentsFailed = false
    @State private var ratings: [TvEpisodeRating]?
    @State private var openComment: TraktCommentReview?

    private var layout: TvDetailSectionsLayout { TvTitleSections.shared.layout(meta: meta, episodeRatingsLoaded: ratings?.isEmpty == false) }

    var body: some View {
        let layout = layout
        let tabs = layout.tabs
        let active = activeTab.flatMap { tabs.contains($0) ? $0 : nil } ?? tabs.first
        VStack(alignment: .leading, spacing: 0) {
            if let active {
                if layout.showsTabStrip {
                    PeopleTabStrip(tabs: tabs, active: active, label: tabLabel) { activeTab = $0 }
                        .id(DetailAnchor.tabs)
                } else {
                    Text(ui: tabLabel(active)).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                        .padding(.top, dp(20)).id(DetailAnchor.tabs)
                }
                tabContent(active)
                    .id(active)
                    .transition(.opacity)
                    .animation(.easeInOut(duration: 0.16), value: active)
            }
            ForEach(layout.rows, id: \.self) { row in
                rowContent(row)
            }
        }
        // Fetched once per title open; each shared repository caches (no polling, egress rule).
        // Re-split when enrichment replaces the cast (the repository publishes base meta, then enriched).
        .task(id: "\(meta.id)|\(meta.cast.count)|\(meta.cast.first?.name ?? "")") {
            if let i = AppArguments.list.firstIndex(of: "-smokeDetailsScroll"),
               AppArguments.list.dropFirst(i + 1).first == "more" { activeTab = .moreLikeThis }
            cast = try? await TvTitleSections.shared.cast(meta: meta)
            smokeLog("SMOKE sections tabs=%@ rows=%@ leading=%d cast=%d roles=%@ more=%d trailers=%d collection=%d",
                  "\(layout.tabs)", "\(layout.rows)", cast?.leading.count ?? -1, cast?.cast.count ?? -1,
                  meta.cast.prefix(3).map { $0.role ?? "-" }.joined(separator: ","), meta.moreLikeThis.count, meta.trailers.count, meta.collectionItems.count)
            // Simulator smoke hook: `-smokePressTrailer` presses the first trailer once sections load.
            if smokeArg("-smokePressTrailer"), let first = TvTitleSections.shared.trailers(meta: meta).first {
                activeTab = .trailer
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                openTrailer(first)
            }
            if smokeArg("-smokePerson"), let first = (cast?.leading ?? []).first ?? cast?.cast.first {
                smokeLog("SMOKE opening person %@", first.name)
                onOpenPerson(first, cast?.leading.contains(first) ?? false)
            }
        }
        .task(id: "ratings" + meta.id) {
            guard TvTitleSections.shared.showsEpisodeRatings(meta: meta) else { return }
            ratings = (try? await TvTitleSections.shared.episodeRatings(meta: meta)) ?? []
        }
        .task(id: "comments" + meta.id) {
            guard TvTitleSections.shared.showsComments(meta: meta) else { return }
            do { comments = try await TvTitleSections.shared.comments(meta: meta) } catch { commentsFailed = true; comments = [] }
        }
        .sheet(item: Binding(get: { openComment.map(CommentBox.init) }, set: { openComment = $0?.review })) { box in
            CommentDialog(review: box.review)
        }
    }

    private func tabLabel(_ tab: TvDetailTab) -> String {
        switch tab {
        case .cast: return "Creator and Cast"
        case .ratings: return "Ratings"
        case .moreLikeThis: return "More like this"
        case .trailer: return "Trailer"
        case .collection: return meta.collectionName ?? "Collections"
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: TvDetailTab) -> some View {
        switch tab {
        case .cast:
            if let cast { CastRow(split: cast, onOpen: onOpenPerson).id(DetailAnchor.cast) }
            else { Color.clear.frame(height: dp(190)) }
        case .ratings:
            EpisodeRatingsRow(meta: meta, ratings: ratings)
        case .moreLikeThis:
            VStack(alignment: .trailing, spacing: dp(2)) {
                LandscapeRow(items: meta.moreLikeThis, onOpen: onOpenTitle).id(DetailAnchor.more)
                if let label = TvTitleSections.shared.moreLikeThisSourceLabel(meta: meta) {
                    Text(ui: label).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary).padding(.trailing, dp(10))
                }
            }
        case .trailer:
            TrailerRow(trailers: TvTitleSections.shared.trailers(meta: meta)) { trailer in
                openTrailer(trailer)
            }
        case .collection:
            LandscapeRow(items: meta.collectionItems, onOpen: onOpenTitle)
        }
    }

    @ViewBuilder
    private func rowContent(_ row: TvDetailRow) -> some View {
        switch row {
        case .collection:
            SectionTitle(title: meta.collectionName ?? "Collections")
            LandscapeRow(items: meta.collectionItems, onOpen: onOpenTitle)
        case .comments:
            CommentsRow(comments: comments, failed: commentsFailed) { openComment = $0 }
        case .networks:
            SectionTitle(title: "Network")
            CompanyRow(companies: meta.networks).id(DetailAnchor.companies)
        case .production:
            SectionTitle(title: "Production")
            CompanyRow(companies: meta.productionCompanies).id(DetailAnchor.companies)
        }
    }

    /// NuvioTV plays trailers in-app through a YouTube extractor; the App Store build of the shared code
    /// has none (TrailerPlaybackMode.EXTERNAL, resolver returns null), so Apple TV hands the video to the
    /// YouTube app and says so when that app isn't installed.
    private func openTrailer(_ trailer: MetaTrailer) {
        guard let url = URL(string: TvDetailSectionsPolicy.shared.youtubeAppUrl(key: trailer.key)) else { return }
        UIApplication.shared.open(url) { opened in
            smokeLog("SMOKE trailer %@ opened=%d", url.absoluteString, opened ? 1 : 0)
            if !opened { playback.notify(L("Unable to play trailer")) }
        }
    }
}

/// Scroll anchors for the simulator smoke hook (`-smokeDetailsScroll <cast|more|bottom>`).
enum DetailAnchor: Hashable { case tabs, cast, more, companies }

private func smokeArg(_ name: String) -> Bool { AppArguments.list.contains(name) }

private struct CommentBox: Identifiable {
    let review: TraktCommentReview
    var id: Int64 { review.id }
}

private struct SectionTitle: View {
    let title: String
    @Environment(\.nuvio) private var colors
    var body: some View {
        Text(ui: title).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
            .padding(.top, dp(20)).padding(.bottom, dp(8))
    }
}

// MARK: - Tab strip (PeopleSectionTabs): "Cast | More like this | …", titleLarge; the focused tab selects.

private struct PeopleTabStrip: View {
    let tabs: [TvDetailTab]
    let active: TvDetailTab
    let label: (TvDetailTab) -> String
    let onSelect: (TvDetailTab) -> Void
    @FocusState private var focusedTab: TvDetailTab?
    @Environment(\.nuvio) private var colors

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(tabs.enumerated()), id: \.element) { index, tab in
                if index > 0 {
                    Text(verbatim: "|").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary.opacity(0.45))
                        .padding(.horizontal, dp(10))
                }
                PeopleTabButton(title: label(tab), selected: tab == active, focused: focusedTab == tab) { onSelect(tab) }
                    .focused($focusedTab, equals: tab)
            }
        }
        .padding(.top, dp(20))
        .padding(.bottom, dp(8))
        .focusSection()
        // Coming back up from the row lands on the tab that row belongs to, not the one above the card.
        .defaultFocus($focusedTab, active, priority: .userInitiated)
        .onChange(of: focusedTab) { _, tab in if let tab, tab != active { onSelect(tab) } }
    }
}

private struct PeopleTabButton: View {
    let title: String
    let selected: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        Button(action: action) {
            Text(ui: title).font(NuvioType.titleLarge)
                .foregroundStyle(colors.textPrimary.opacity(focused ? 1 : (selected ? 0.92 : 0.55)))
                .padding(.horizontal, dp(8)).padding(.vertical, dp(4))
                .background(RoundedRectangle(cornerRadius: dp(16)).fill(focused ? colors.focusBackground : .clear))
                .overlay(RoundedRectangle(cornerRadius: dp(16)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
                .scaleEffect(focused ? 1.03 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .reportsFocus(focused)
    }
}

// MARK: - Cast (CastSection.kt): 150-wide items, 100 circle, 2dp ring; leading credits, divider, cast.

private struct CastRow: View {
    let split: TvCastSplit
    let onOpen: (MetaPerson, Bool) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: dp(8)) {
                // Distinct identities across both lists (leading use negative indices), or the lazy stack mixes them up.
                ForEach(split.leading.indices.map { -1 - $0 }, id: \.self) { key in
                    let person = split.leading[-1 - key]
                    CastCard(person: person) { onOpen(person, true) }
                }
                if !split.leading.isEmpty && !split.cast.isEmpty {
                    Rectangle().fill(colors.surfaceVariant.opacity(0.9)).frame(width: dp(1), height: dp(72))
                        .frame(height: dp(100))
                }
                ForEach(split.cast.indices, id: \.self) { index in
                    let person = split.cast[index]
                    CastCard(person: person) { onOpen(person, false) }
                }
            }
            .padding(.vertical, dp(6))
        }
        .scrollClipDisabled()
        .focusSection()
    }
}

private struct CastCard: View {
    let person: MetaPerson
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(10)) {
                ZStack {
                    Circle().fill(focused ? colors.focusBackground : colors.surfaceVariant)
                    CachedPosterArtwork(urlString: person.photo, width: dp(100), height: dp(100), maximumWidth: dp(200)) {
                        Text(verbatim: String(person.name.prefix(1)).uppercased()).font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                    }
                    .frame(width: dp(100), height: dp(100))
                    .clipShape(Circle())
                }
                .frame(width: dp(100), height: dp(100))
                .clipShape(Circle())
                .overlay(Circle().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus).padding(-dp(3)))
                .scaleEffect(focused ? 1.05 : 1)
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text(verbatim: person.name).font(NuvioType.labelMedium)
                        .foregroundStyle(focused ? colors.textPrimary : colors.textSecondary).lineLimit(2)
                    if let role = person.role, !role.isEmpty {
                        Text(ui: role).font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(1)
                    }
                }
                .frame(width: dp(150), height: dp(64), alignment: .topLeading)
            }
            .frame(width: dp(150), alignment: .leading)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - More like this / Collection (MoreLikeThisSection.kt, CollectionSection.kt): 260×146 landscape cards.

private struct LandscapeRow: View {
    let items: [MetaPreview]
    let onOpen: (MetaPreview) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: dp(12)) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: landscapeImage(item),
                                    width: dp(260), height: dp(146), showLabel: true) { onOpen(item) }
                        .titleActions(item) { onOpen(item) }
                }
            }
            .padding(.vertical, dp(6))
        }
        .scrollClipDisabled()
        .focusSection()
    }

    private func landscapeImage(_ item: MetaPreview) -> String? {
        if item.posterShape == .landscape { return item.poster ?? item.banner }
        return item.landscapePoster ?? item.banner ?? item.poster
    }
}

// MARK: - Trailers (TrailerSection.kt): 260×146 YouTube thumbnails, name + type.

private struct TrailerRow: View {
    let trailers: [MetaTrailer]
    let onOpen: (MetaTrailer) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: dp(12)) {
                ForEach(trailers, id: \.key) { trailer in
                    NuvioPosterCard(title: trailer.displayName ?? trailer.name, subtitle: trailer.type,
                                    imageURL: TvDetailSectionsPolicy.shared.youtubeThumbnail(key: trailer.key),
                                    width: dp(260), height: dp(146), showLabel: true) { onOpen(trailer) }
                }
            }
            .padding(.vertical, dp(6))
        }
        .scrollClipDisabled()
        .focusSection()
    }
}

// MARK: - Episode ratings (EpisodeRatingsSection.kt): season chips, summary, 72×46 colour-banded episode chips.

private struct EpisodeRatingsRow: View {
    let meta: MetaDetails
    let ratings: [TvEpisodeRating]?
    @State private var season: Int32?
    @Environment(\.nuvio) private var colors

    var body: some View {
        let seasons = Array(Set(meta.videos.compactMap { $0.season?.int32Value }.filter { $0 > 0 })).sorted()
        let current = season.flatMap { seasons.contains($0) ? $0 : nil } ?? seasons.first ?? 0
        let episodes = Array(Set(meta.videos.filter { $0.season?.int32Value == current }.compactMap { $0.episode?.int32Value })).sorted()
        VStack(alignment: .leading, spacing: dp(4)) {
            if ratings == nil {
                Text("Loading episode ratings...").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).padding(.vertical, dp(12))
            } else if seasons.isEmpty || ratings?.isEmpty == true {
                Text("Episode ratings are unavailable.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).padding(.vertical, dp(12))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: dp(6)) {
                        ForEach(seasons, id: \.self) { s in
                            RatingChip(selected: s == current, fill: nil, onDark: false, selectOnFocus: { season = s }) {
                                Text(verbatim: String(format: L("S%1$d"), Int(s))).font(NuvioType.labelMedium)
                                    .padding(.horizontal, dp(11)).padding(.vertical, dp(6))
                            }
                        }
                    }
                    .padding(.vertical, dp(6))
                }
                .scrollClipDisabled()
                .focusSection()
                Text(verbatim: String(format: L("Season %1$d - %2$d episodes"), Int(current), episodes.count))
                    .font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: dp(6)) {
                        ForEach(episodes, id: \.self) { e in
                            let value = ratings?.first { $0.season == current && $0.episode == e }?.rating
                            let onDark = value.map { TvDetailSectionsPolicy.shared.ratingOnDarkText(value: $0) } ?? false
                            RatingChip(selected: false, fill: value.map { Color(argb: UInt32(TvDetailSectionsPolicy.shared.ratingColor(value: $0))) },
                                       onDark: onDark, selectOnFocus: nil) {
                                VStack(spacing: 0) {
                                    Text(verbatim: String(format: L("E%1$d"), Int(e))).font(NuvioType.labelSmall)
                                    Text(verbatim: value.map { String(format: "%.1f", $0) } ?? "—").font(NuvioType.labelLarge)
                                }
                                .frame(width: dp(72), height: dp(46))
                            }
                        }
                    }
                    .padding(.vertical, dp(6))
                }
                .scrollClipDisabled()
                .focusSection()
            }
        }
    }
}

private struct RatingChip<Label: View>: View {
    let selected: Bool
    let fill: Color?
    let onDark: Bool
    let selectOnFocus: (() -> Void)?
    @ViewBuilder let label: Label
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button { selectOnFocus?() } label: {
            label
                .foregroundStyle(fill == nil ? (selected || focused ? colors.textPrimary : colors.textSecondary)
                                 : (onDark ? Color(argb: 0xFF1D1D1F) : Color.white))
                .background(RoundedRectangle(cornerRadius: dp(14)).fill(fill ?? (selected || focused ? colors.focusBackground : colors.backgroundCard)))
                .overlay(RoundedRectangle(cornerRadius: dp(14)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
                .scaleEffect(focused && fill != nil ? 1.03 : 1)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, now in if now { selectOnFocus?() } }
    }
}

// MARK: - Comments (CommentsSection.kt): header, 360×230 BackgroundCard cards; OK opens the full review.

private struct CommentsRow: View {
    let comments: [TraktCommentReview]?
    let failed: Bool
    let onOpen: (TraktCommentReview) -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            Text("Comments").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary).padding(.top, dp(20))
            Text("Reviews from Trakt").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
            if let comments {
                if comments.isEmpty {
                    Text(failed ? "Couldn't load Trakt reviews right now." : "No Trakt reviews available yet.")
                        .font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).padding(.vertical, dp(12))
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: dp(12)) {
                            ForEach(comments, id: \.id) { review in CommentCard(review: review) { onOpen(review) } }
                        }
                        .padding(.vertical, dp(8))
                    }
                    .scrollClipDisabled()
                    .focusSection()
                }
            } else {
                HStack(spacing: dp(12)) { ForEach(0..<3, id: \.self) { _ in NuvioShimmer().frame(width: dp(360), height: dp(230)) } }
                    .padding(.vertical, dp(8))
            }
        }
    }
}

private struct CommentCard: View {
    let review: TraktCommentReview
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: dp(10)) {
                Text(verbatim: review.authorDisplayName).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                if review.review { CommentChip(text: "Review") }
                Group {
                    if review.hasSpoilerContent { Text("Spoiler review. Press OK to reveal.") } else { Text(verbatim: review.comment) }
                }
                .font(NuvioType.bodyMedium)
                .foregroundStyle(review.hasSpoilerContent ? colors.textTertiary : colors.textSecondary)
                .lineLimit(5)
                Spacer(minLength: 0)
                HStack(spacing: dp(12)) {
                    if let rating = review.rating?.int32Value {
                        Text(verbatim: String(format: L("%1$d/10"), Int(rating)))
                    }
                    Text(verbatim: String(format: L("%1$d likes"), Int(review.likes)))
                }
                .font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
            }
            .padding(dp(18))
            .frame(width: dp(360), height: dp(230), alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: dp(16)).fill(colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: dp(16)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? 1.02 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

private struct CommentChip: View {
    let text: String
    @Environment(\.nuvio) private var colors
    var body: some View {
        Text(ui: text).font(NuvioType.labelSmall).foregroundStyle(colors.textPrimary)
            .padding(.horizontal, dp(8)).padding(.vertical, dp(2))
            .background(Capsule().fill(colors.backgroundElevated))
    }
}

/// The full review (NuvioTV's comment overlay): spoilers show here, since opening it is the reveal.
private struct CommentDialog: View {
    let review: TraktCommentReview
    @Environment(\.nuvio) private var colors

    var body: some View {
        NuvioDialog(title: review.authorDisplayName, subtitle: review.hasSpoilerContent ? L("Spoiler") : nil, width: dp(640)) {
            Text(verbatim: review.comment).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusable()   // lets the Siri Remote scroll a long review
        }
    }
}

// MARK: - Company logos (CompanyLogosSection.kt): 140×56 white cards, radius 8, logo fit or name.

private struct CompanyRow: View {
    let companies: [MetaCompany]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: dp(12)) {
                ForEach(Array(companies.enumerated()), id: \.offset) { _, company in CompanyCard(company: company) }
            }
            .padding(.vertical, dp(6))
        }
        .scrollClipDisabled()
        .focusSection()
    }
}

private struct CompanyCard: View {
    let company: MetaCompany
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        // NuvioTV opens a TMDB company/network browse page here; Apple TV has no such screen yet, so the
        // card is focusable (the page must scroll to it) but pressing it does nothing.
        Button(action: {}) {
            ZStack {
                Color.white
                CachedPosterArtwork(urlString: company.logo, width: dp(112), height: dp(36), maximumWidth: dp(280)) {
                    Text(verbatim: company.name).font(NuvioType.labelLarge).foregroundStyle(NuvioPrimitives.neutral700)
                        .lineLimit(1).padding(.horizontal, dp(16))
                }
                .environment(\.artworkContentMode, .fit)
                .frame(width: dp(112), height: dp(36))
            }
            .frame(width: dp(140), height: dp(56))
            .clipShape(RoundedRectangle(cornerRadius: dp(8)))
            .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus).padding(-dp(3)))
            .scaleEffect(focused ? 1.03 : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - Person page (NuvioTV ui/screens/cast/CastDetailScreen.kt)

struct PersonDetailScreen: View {
    let person: MetaPerson
    let preferCrew: Bool
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var detail: PersonDetail?
    @State private var failed = false
    @State private var details: PreviewBox?

    var body: some View {
        ZStack(alignment: .topLeading) {
            colors.background.ignoresSafeArea()
            LinearGradient(stops: [
                .init(color: colors.secondary.opacity(0.26), location: 0), .init(color: colors.secondary.opacity(0.18), location: 0.12),
                .init(color: colors.secondary.opacity(0.10), location: 0.28), .init(color: colors.secondary.opacity(0.04), location: 0.45),
                .init(color: .clear, location: 0.60),
            ], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()

            if let detail {
                content(detail)
            } else if failed {
                NuvioStateMessage(title: "Something went wrong", message: person.name, retry: { Task { await load() } })
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task { await load() }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    private func load() async {
        failed = false
        guard let id = person.tmdbId?.int32Value else { failed = true; return }
        detail = try? await TvTitleSections.shared.person(tmdbId: id, preferCrew: preferCrew)
        failed = detail == nil
    }

    private func content(_ detail: PersonDetail) -> some View {
        let credits = TvDetailSectionsPolicy.shared.filmography(movieCredits: detail.movieCredits, tvCredits: detail.tvCredits)
        return VStack(alignment: .leading, spacing: dp(8)) {
            HStack(alignment: .top, spacing: dp(24)) {
                CachedPosterArtwork(urlString: detail.profilePhoto ?? person.photo, width: dp(160), height: dp(240), maximumWidth: dp(320)) {
                    Text(verbatim: String(detail.name.prefix(1))).font(NuvioType.displayMedium).foregroundStyle(colors.textTertiary)
                        .frame(width: dp(160), height: dp(240)).background(colors.surfaceVariant)
                }
                .frame(width: dp(160), height: dp(240))
                .clipShape(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard))
                .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard).stroke(colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                VStack(alignment: .leading, spacing: dp(10)) {
                    Text(verbatim: detail.name).font(NuvioType.headlineLarge.weight(.bold)).foregroundStyle(colors.textPrimary)
                    if let born = bornLine(detail) {
                        Text(verbatim: born).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                    }
                    if let bio = detail.biography, !bio.isEmpty {
                        Text(verbatim: bio).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                            .lineLimit(7).frame(maxWidth: dp(620), alignment: .leading)
                    }
                }
            }
            .padding(.top, dp(32))
            if !credits.isEmpty {
                HStack(spacing: dp(12)) {
                    Text("Filmography").font(NuvioType.titleLarge.weight(.semibold)).foregroundStyle(colors.textPrimary)
                    Text(verbatim: "\(credits.count)").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                        .padding(.horizontal, dp(8)).padding(.vertical, dp(2))
                        .background(RoundedRectangle(cornerRadius: dp(4)).fill(colors.surfaceVariant))
                }
                .padding(.top, dp(12))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: dp(12)) {
                        ForEach(Array(credits.enumerated()), id: \.offset) { _, item in
                            NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                            width: dp(112), height: dp(168), showLabel: true) { details = PreviewBox(preview: item) }
                                .titleActions(item) { details = PreviewBox(preview: item) }
                        }
                    }
                    .padding(.vertical, dp(6))
                }
                .scrollClipDisabled()
                .focusSection()
            }
        }
        .padding(.horizontal, dp(48))
    }

    private func bornLine(_ d: PersonDetail) -> String? {
        guard let birthday = d.birthday, !birthday.isEmpty else { return nil }
        let place = d.placeOfBirth.map { " · \($0)" } ?? ""
        if let death = d.deathday, !death.isEmpty {
            return L("Born: %1$s — †%2$s").replacingOccurrences(of: "%1$s", with: birthday).replacingOccurrences(of: "%2$s", with: death) + place
        }
        return L("Born: %1$s").replacingOccurrences(of: "%1$s", with: birthday) + place
    }
}
