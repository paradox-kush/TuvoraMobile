import SwiftUI
import TuvoraCore

/// NuvioTV's details screen (ui/screens/detail/HeroSection.kt, MetaDetailsScreen.kt, EpisodesSection.kt)
/// and stream picker (ui/screens/stream/StreamScreen.kt), translated.
struct TitleDetailsScreen: View {
    let preview: MetaPreview
    @EnvironmentObject private var playback: PlaybackCoordinator
    /// Save / Watched in flight: a second Select must not toggle again before the first lands.
    @State private var toggling = false
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var state: MetaDetailsUiState?
    @State private var season: Int32?
    @State private var sourcesFor: SourceTarget?
    @State private var saved = false
    @State private var smokePressed = false
    @State private var watched = false
    @State private var presentation: MetaScreenSettingsUiState?
    @State private var progressRevision = 0
    /// A title or person opened from the lower sections. MetaDetailsRepository holds one title at a time,
    /// so while a child is up this screen ignores its updates and reloads its own (a cache hit) on return.
    @State private var childDetails: PreviewBox?
    @State private var personTarget: PersonTarget?
    @State private var smokeScrolled = false
    /// NuvioTV fades the backdrop to 15% once the page scrolls 200dp past the hero, so the rows stay legible.
    @State private var scrolledPastHero = false

    private var meta: MetaDetails? { state?.meta }
    private var isSeries: Bool { preview.type == "series" || (meta?.videos.count ?? 0) > 1 }

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            DetailBackdrop(url: meta?.background ?? preview.banner ?? preview.poster)
                .opacity(scrolledPastHero ? 0.15 : 1)
                .animation(.easeInOut(duration: scrolledPastHero ? 0.3 : 0.8), value: scrolledPastHero)

            if let meta {
                ScrollViewReader { proxy in
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(alignment: .leading, spacing: dp(24)) {
                            hero(meta).frame(minHeight: dp(540), alignment: .bottomLeading)
                                .onGeometryChange(for: Bool.self) { $0.frame(in: .global).minY < -dp(200) } action: { past in
                                    scrolledPastHero = past
                                }
                            if isSeries && TvTitlePresentation.shared.sectionEnabled(key: .episodes) { episodes(meta) }
                            DetailLowerSections(meta: meta,
                                                onOpenTitle: { childDetails = PreviewBox(preview: $0) },
                                                onOpenPerson: { person, lead in
                                                    // NuvioTV opens a person page only for TMDB people.
                                                    if TvTitleSections.shared.canOpenPerson(person: person) { personTarget = PersonTarget(person: person, preferCrew: lead) }
                                                })
                        }
                        .padding(.horizontal, dp(48))
                        .padding(.bottom, dp(48))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .task(id: meta.id) { await smokeScroll(proxy) }
                }
            } else if let error = state?.errorMessage {
                NuvioStateMessage(title: "Couldn't load this title", message: error) { TvTitle.shared.load(type: preview.type, id: preview.id) }
            } else {
                DeadlineLoading(retry: { TvTitle.shared.load(type: preview.type, id: preview.id) }) { ProgressView() }
            }
        }
        .ignoresSafeArea()
        // The shell's toast sits under this full-screen cover; repeat it here.
        .overlay(alignment: .bottom) { PlaybackToast() }
        .task {
            #if DEBUG && targetEnvironment(simulator)
            // Simulator smoke hook: `-smokeStallTitle` never loads the title, so the page waits (with `-smokeLoadDeadline`).
            if AppArguments.list.contains("-smokeStallTitle") { return }
            if AppArguments.list.contains("-smokePresentationTitle") {
                let sample = TvTitlePresentation.shared.sample()
                state = MetaDetailsUiState(isLoading: false,
                                           meta: Self.smokeMdbRatings ? TvTitle.shared.smokeWithMdbRatings(meta: sample) : sample,
                                           errorMessage: nil)
                return
            }
            #endif
            TvTitle.shared.load(type: preview.type, id: preview.id)
            for await next in TvTitle.shared.details {
                if childDetails != nil || personTarget != nil { continue }
                state = next
                // Simulator smoke hook: `-smokeTmdbEnrich` shows the title as a TMDB-enabled profile sees it.
                if let meta = next.meta, !next.isLoading, AppArguments.list.contains("-smokeTmdbEnrich"),
                   let enriched = try? await TvTitleSections.shared.smokeTmdbEnriched(meta: meta) {
                    state = MetaDetailsUiState(isLoading: false, meta: enriched, errorMessage: nil)
                }
                // Simulator smoke hook: `-smokeMdbRatings` shows sample MDBList scores (no API key on the simulator).
                if let meta = next.meta, Self.smokeMdbRatings {
                    state = MetaDetailsUiState(isLoading: next.isLoading, meta: TvTitle.shared.smokeWithMdbRatings(meta: meta), errorMessage: nil)
                }
                if let meta = next.meta { saved = TvTitle.shared.isSaved(meta: meta); watched = TvTitle.shared.isWatched(meta: meta) }
                // Simulator smoke hook: `-smokePressPlay` presses Play once the title loads.
                if let meta = next.meta, sourcesFor == nil, !smokePressed, AppArguments.list.contains("-smokePressPlay") {
                    smokePressed = true
                    smokeLog("SMOKE details loaded %@ videos=%d", meta.name, meta.videos.count)
                    sourcesFor = SourceTarget(meta: meta, video: isSeries ? resumeEpisode(meta) : nil)
                }
            }
        }
        .task {
            MetaScreenSettingsRepository.shared.ensureLoaded()
            for await value in MetaScreenSettingsRepository.shared.uiState { presentation = value }
        }
        .task { for await _ in WatchProgressRepository.shared.uiState { progressRevision += 1 } }
        .task { for await _ in WatchedRepository.shared.uiState { progressRevision += 1 } }
        .fullScreenCover(item: $childDetails, onDismiss: reloadSelf) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
        .fullScreenCover(item: $personTarget, onDismiss: reloadSelf) { target in
            PersonDetailScreen(person: target.person, preferCrew: target.preferCrew).environmentObject(playback).environment(\.nuvio, colors)
        }
        .fullScreenCover(item: $sourcesFor) { target in
            StreamPickerScreen(meta: target.meta, video: target.video).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    private static let smokeMdbRatings = AppArguments.list.contains("-smokeMdbRatings")
    private var mdbListActive: Bool { Self.smokeMdbRatings || TvTitle.shared.mdbListActive() }

    private func reloadSelf() { TvTitle.shared.load(type: preview.type, id: preview.id) }

    /// Simulator smoke hook: `-smokeDetailsScroll <cast|more|bottom>` scrolls the loaded page to that section.
    private func smokeScroll(_ proxy: ScrollViewProxy) async {
        let args = AppArguments.list
        guard !smokeScrolled, let i = args.firstIndex(of: "-smokeDetailsScroll"), i + 1 < args.count else { return }
        smokeScrolled = true
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        let anchor: DetailAnchor = ["cast": .cast, "more": .more, "bottom": .companies][args[i + 1]] ?? .tabs
        smokeLog("SMOKE details scroll to %@", args[i + 1])
        withAnimation { proxy.scrollTo(anchor, anchor: .center) }
    }

    // Hero column: logo 100dp / 40% width (fallback displayMedium), Play pill + circle buttons, meta, synopsis at 60%.
    private func hero(_ meta: MetaDetails) -> some View {
        VStack(alignment: .leading, spacing: dp(16)) {
            Spacer(minLength: dp(120))
            if let logo = meta.logo, !logo.isEmpty {
                CachedPosterArtwork(urlString: logo, width: dp(384), height: dp(100), maximumWidth: dp(768)) {
                    Text(meta.name).font(NuvioType.displayMedium).foregroundStyle(colors.textPrimary)
                }
                .environment(\.artworkContentMode, .fit)
                .frame(maxWidth: dp(384), maxHeight: dp(100), alignment: .leading)
            } else {
                Text(meta.name).font(NuvioType.displayMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
            }
            HStack(spacing: dp(12)) {
                PlayPill(title: playLabel(meta)) { openSources(meta: meta, video: isSeries ? resumeEpisode(meta) : nil) }
                    .prefersDefaultFocus(true, in: namespace)
                CircleIconButton(icon: saved ? "md_check_circle" : "library_add_plus", label: saved ? "Remove from library" : "Save to library") {
                    guard !toggling else { return }
                    toggling = true
                    Task {
                        defer { toggling = false }
                        do { try await TvTitle.shared.toggleSaved(meta: meta) } catch { playback.notify("Couldn't update your library.") }
                        saved = TvTitle.shared.isSaved(meta: meta)
                    }
                }
                CircleIconButton(icon: watched ? "md_check_circle" : "md_bookmark_border", filledWhite: watched, label: watched ? "Mark as unwatched" : "Mark as watched") {
                    guard !toggling else { return }
                    toggling = true
                    Task {
                        defer { toggling = false }
                        do { try await TvTitle.shared.toggleWatched(meta: meta) } catch { playback.notify("Couldn't update watched status.") }
                        watched = TvTitle.shared.isWatched(meta: meta)
                    }
                }
            }
            .scrollClipDisabled()
            .focusSection()
            metaRow(meta)
            if presentation?.showOverallRatings != false { ratingsRow(meta) }
            if TvTitlePresentation.shared.sectionEnabled(key: .overview), let description = meta.description_ {
                Text(description).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary)
                    .lineLimit(4).frame(maxWidth: dp(560), alignment: .leading)
            }
        }
    }
    @Namespace private var namespace

    private func metaRow(_ meta: MetaDetails) -> some View {
        HStack(spacing: dp(8)) {
            let parts = [meta.genres.first, meta.releaseInfo, meta.runtime].compactMap { $0 }
            ForEach(Array(parts.enumerated()), id: \.offset) { index, part in
                if index > 0 { Circle().fill(colors.textSecondary).frame(width: dp(3), height: dp(3)) }
                Text(part).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
            }
            if presentation?.showOverallRatings != false, let rating = meta.imdbRating,
               TvRatingsPolicy.shared.showsImdbLine(imdbRating: rating, ratings: meta.externalRatings, mdbListActive: mdbListActive) {
                Circle().fill(colors.textSecondary).frame(width: dp(3), height: dp(3))
                ImdbBadge()
                Text(ImdbBadge.format(rating)).font(NuvioType.labelLarge).foregroundStyle(colors.textPrimary)
            }
        }
    }

    /// NuvioTV MDBListRatingsRow: logo (spacing.xl = 24dp) + labelMedium score in textSecondary, 14dp apart,
    /// between the meta line and the synopsis. The scores come from MetaDetailsRepository's MDBList enrichment.
    @ViewBuilder
    private func ratingsRow(_ meta: MetaDetails) -> some View {
        let chips = TvRatingsPolicy.shared.chips(ratings: meta.externalRatings, mdbListActive: mdbListActive)
        if !chips.isEmpty {
            HStack(spacing: dp(14)) {
                ForEach(chips, id: \.source) { chip in
                    HStack(spacing: dp(6)) {
                        Image(chip.logo).resizable().scaledToFit().frame(width: dp(24), height: dp(24))
                        Text(verbatim: chip.text).font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(L(chip.sourceName)) \(chip.text)")
                }
            }
            .accessibilityElement(children: .contain)
        }
    }

    private func playLabel(_ meta: MetaDetails) -> String {
        let video = isSeries ? resumeEpisode(meta) : nil
        let ms = resume(meta, video)
        if ms > 0 { return video.map { "Resume S\($0.season?.int32Value ?? 0) E\($0.episode?.int32Value ?? 0)" } ?? "Resume" }
        return video.map { "Play S\($0.season?.int32Value ?? 0) E\($0.episode?.int32Value ?? 0)" } ?? "Play"
    }

    private func resumeEpisode(_ meta: MetaDetails) -> MetaVideo? {
        TvTitle.shared.primaryEpisode(meta: meta) ?? meta.videos.first { ($0.season?.int32Value ?? 0) > 0 } ?? meta.videos.first
    }

    private func resume(_ meta: MetaDetails, _ video: MetaVideo?) -> Int64 {
        TvTitle.shared.resumePositionMs(videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                        season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
    }

    private func openSources(meta: MetaDetails, video: MetaVideo?) {
        sourcesFor = SourceTarget(meta: meta, video: video)
    }

    // Season tabs (radius 20, padding 20×10, titleMedium) + episode cards (320×207, 2dp ring, no scale).
    private func episodes(_ meta: MetaDetails) -> some View {
        let _ = progressRevision
        let seasons = Array(Set(meta.videos.compactMap { $0.season?.int32Value })).sorted()
        let current = season ?? seasons.first(where: { $0 > 0 }) ?? seasons.first ?? 1
        return VStack(alignment: .leading, spacing: dp(16)) {
            if seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: dp(12)) {
                        ForEach(seasons, id: \.self) { s in
                            SeasonTab(title: s == 0 ? "Specials" : "Season \(s)", selected: s == current) { season = s }
                        }
                    }
                    .padding(.vertical, dp(6))
                }
                .scrollClipDisabled()
            .focusSection()
            }
            if presentation?.episodeCardStyle == .list {
                // The season is bounded. Eager focus targets let tvOS move down from the hero
                // before these rows scroll onscreen; a nested LazyVStack hid every target.
                VStack(spacing: dp(12)) {
                    ForEach(meta.videos.filter { ($0.season?.int32Value ?? current) == current }, id: \.id) { video in
                        EpisodeCard(video: video, presentation: TvTitlePresentation.shared.episode(meta: meta, video: video), listStyle: true) { openSources(meta: meta, video: video) }
                    }
                }.focusSection()
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: dp(16)) {
                        ForEach(meta.videos.filter { ($0.season?.int32Value ?? current) == current }, id: \.id) { video in
                            EpisodeCard(video: video, presentation: TvTitlePresentation.shared.episode(meta: meta, video: video)) { openSources(meta: meta, video: video) }
                        }
                    }.padding(.vertical, dp(8))
                }.scrollClipDisabled().focusSection()
            }
        }
    }

}

private struct PersonTarget: Identifiable {
    let person: MetaPerson
    let preferCrew: Bool
    var id: String { "\(person.tmdbId?.int32Value ?? 0)|\(person.name)" }
}

private struct SourceTarget: Identifiable {
    let meta: MetaDetails
    let video: MetaVideo?
    var id: String { video?.id ?? meta.id }
}

/// Details backdrop: full screen, left fade across 78% and a bottom fade from 38% height, both in Background.
struct DetailBackdrop: View {
    let url: String?
    @Environment(\.nuvio) private var colors

    var body: some View {
        GeometryReader { geo in
            CachedPosterArtwork(urlString: url, width: geo.size.width, height: geo.size.height, maximumWidth: 1920) { colors.background }
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
                .overlay(
                    LinearGradient(stops: [
                        .init(color: colors.background, location: 0), .init(color: colors.background.opacity(0.95), location: 0.1 * 0.78),
                        .init(color: colors.background.opacity(0.84), location: 0.22 * 0.78), .init(color: colors.background.opacity(0.70), location: 0.36 * 0.78),
                        .init(color: colors.background.opacity(0.52), location: 0.52 * 0.78), .init(color: colors.background.opacity(0.34), location: 0.66 * 0.78),
                        .init(color: colors.background.opacity(0.18), location: 0.78 * 0.78), .init(color: colors.background.opacity(0.07), location: 0.9 * 0.78),
                        .init(color: .clear, location: 0.78),
                    ], startPoint: .leading, endPoint: .trailing)
                )
                .overlay(
                    LinearGradient(stops: [
                        .init(color: .clear, location: 0.38), .init(color: colors.background.opacity(0.52), location: 0.7),
                        .init(color: colors.background, location: 1),
                    ], startPoint: .top, endPoint: .bottom)
                )
        }
        .ignoresSafeArea()
    }
}

/// Play: white pill (radius 32), black content, padding 24×14, 18dp icon, labelLarge; 2dp FocusRing on focus.
struct PlayPill: View {
    let title: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(8)) {
                Image("md_play_arrow").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                Text(ui: title).font(NuvioType.labelLargeSemi)
            }
            .foregroundStyle(Color.black)
            .padding(.horizontal, dp(24)).padding(.vertical, dp(14))
            .background(Capsule().fill(Color.white))
            .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus).padding(-dp(3)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .accessibilityIdentifier("details.play")
    }
}

/// 48dp circle action: BackgroundCard, Secondary when focused, 22dp icon, 2dp ring.
struct CircleIconButton: View {
    let icon: String
    var filledWhite = false   // NuvioTV: watched-selected circle is white
    /// VoiceOver label and UI-test id; an icon-only button is otherwise unnamed.
    var label: String? = nil
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Image(icon).renderingMode(.template).resizable().frame(width: dp(22), height: dp(22))
                .foregroundStyle(focused ? colors.onSecondary : (filledWhite ? Color.black : colors.textPrimary))
                .frame(width: dp(48), height: dp(48))
                .background(Circle().fill(focused ? colors.secondary : (filledWhite ? Color.white : colors.backgroundCard)))
                .overlay(Circle().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus).padding(-dp(3)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .accessibilityLabel(label ?? "")
    }
}

private struct SeasonTab: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(ui: title).font(NuvioType.titleMedium)
                .foregroundStyle(focused ? colors.onSecondary : colors.textPrimary)
                .padding(.horizontal, dp(20)).padding(.vertical, dp(10))
                .background(RoundedRectangle(cornerRadius: dp(20)).fill(focused ? colors.secondary : (selected ? colors.surfaceVariant : colors.backgroundCard)))
                .overlay(RoundedRectangle(cornerRadius: dp(20)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

private struct EpisodeCard: View {
    let video: MetaVideo
    let presentation: TvEpisodePresentation
    var listStyle = false
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    private var artwork: some View {
        CachedPosterArtwork(urlString: video.thumbnail, width: dp(320), height: dp(listStyle ? 140 : 207), maximumWidth: dp(640)) { colors.backgroundCard }
            .frame(width: dp(320), height: dp(listStyle ? 140 : 207))
            .blur(radius: presentation.hideSpoilers ? dp(16) : 0).clipped()
    }
    private var caption: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            HStack {
                Text("E\(video.episode?.int32Value ?? 0)").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                if presentation.watched { Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(16), height: dp(16)).foregroundStyle(colors.success).accessibilityLabel("Watched") }
            }
            Text(presentation.hideSpoilers ? "Unwatched episode" : video.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
            if !presentation.hideSpoilers, let overview = video.overview {
                Text(overview).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(2)
            }
            if presentation.progress > 0 {
                ProgressView(value: presentation.progress).tint(colors.secondary)
                    .accessibilityLabel("Episode progress").accessibilityValue("\(Int(presentation.progress * 100)) percent")
            }
        }.padding(dp(12)).frame(maxWidth: .infinity, alignment: .leading)
    }
    var body: some View {
        Button(action: action) {
            Group {
                if listStyle {
                    HStack(spacing: dp(12)) { artwork; caption }.frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    ZStack(alignment: .bottomLeading) {
                        artwork
                        LinearGradient(stops: [.init(color: .black.opacity(0.04), location: 0.35), .init(color: .black.opacity(0.95), location: 1)], startPoint: .top, endPoint: .bottom)
                        caption
                    }.frame(width: dp(320), height: dp(207))
                }
            }
            .background(colors.backgroundCard)
            .clipShape(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard))
            .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .hoverEffect(.highlight)
        }
        .accessibilityIdentifier("episode.\(video.id)")
        .buttonStyle(PlainNoChromeButtonStyle()).focused($focused).reportsFocus(focused)
    }
}

// MARK: - Stream picker (StreamScreen.kt): full screen, left 40% logo, right 60% source list.

struct StreamPickerScreen: View {
    let meta: MetaDetails
    let video: MetaVideo?
    /// Set when presented through UIKit (PlaybackCoordinator.openSources); SwiftUI covers use dismiss().
    var onClose: (() -> Void)? = nil
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var streams: StreamsUiState?
    @State private var opening = false
    @State private var smokePicked = false

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            CachedPosterArtwork(urlString: meta.background, width: 1920, height: 1080, maximumWidth: 1920) { colors.background }
                .frame(maxWidth: .infinity, maxHeight: .infinity).clipped()
                .overlay(LinearGradient(stops: [
                    .init(color: colors.background, location: 0), .init(color: colors.background.opacity(0.85), location: 0.2),
                    .init(color: colors.background.opacity(0.40), location: 0.4), .init(color: colors.background.opacity(0.15), location: 0.5),
                    .init(color: colors.background.opacity(0.40), location: 0.6), .init(color: colors.background.opacity(0.85), location: 0.8),
                    .init(color: colors.background, location: 1),
                ], startPoint: .leading, endPoint: .trailing))
                .ignoresSafeArea()

            HStack(alignment: .top, spacing: dp(32)) {
                VStack(alignment: .leading, spacing: dp(12)) {
                    if let logo = meta.logo, !logo.isEmpty {
                        CachedPosterArtwork(urlString: logo, width: dp(300), height: dp(120), maximumWidth: dp(600)) {
                            Text(meta.name).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                        }
                        .environment(\.artworkContentMode, .fit)
                        .frame(maxWidth: dp(300), maxHeight: dp(120), alignment: .leading)
                    } else {
                        Text(meta.name).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                    }
                    if let video {
                        Text("S\(video.season?.int32Value ?? 0) E\(video.episode?.int32Value ?? 0) · \(video.title)")
                            .font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .containerRelativeFrame(.horizontal) { w, _ in w * 0.4 }

                sourceList.containerRelativeFrame(.horizontal) { w, _ in w * 0.6 - dp(80) }
            }
            .padding(dp(48))
        }
        // The title page's toast is under this cover: without one here a dead source gave no feedback.
        .overlay(alignment: .bottom) { PlaybackToast() }
        .task {
            #if DEBUG && targetEnvironment(simulator)
            // Simulator smoke hook: `-smokePickerToast` raises the playback toast as the picker opens.
            if AppArguments.list.contains("-smokePickerToast") { playback.notify("Smoke source toast") }
            #endif
            loadSources()
            for await next in TvTitle.shared.streams {
                streams = next
                // Auto Stream Selection (phone parity): play the nominated source without the list.
                // Simulator smoke hook: `-smokePickFirstSource` opens the first listed source.
                if AppArguments.list.contains("-smokePickFirstSource"), !opening, !smokePicked,
                   let first = next.groups.first(where: { !$0.streams.isEmpty })?.streams.first {
                    smokePicked = true
                    smokeLog("SMOKE sources loaded groups=%d; opening %@", next.groups.count, first.streamLabel)
                    open(first)
                }
                if let pick = next.autoPlayStream, !opening {
                    TvTitle.shared.consumeAutoPlay()
                    open(pick)
                }
            }
        }
        .onDisappear { TvTitle.shared.cancelStreams() }
    }

    private func loadSources() {
        TvTitle.shared.loadStreams(type: meta.type, videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                   season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
    }

    @ViewBuilder
    private var sourceList: some View {
        let groups = (streams?.groups ?? []).filter { !$0.streams.isEmpty }
        if groups.isEmpty {
            if streams?.isAnyLoading ?? true {
                DeadlineLoading(title: "Sources are taking a while", message: "Your add-ons haven't answered yet.", retry: loadSources) {
                    VStack(spacing: dp(12)) { ForEach(0..<5, id: \.self) { _ in NuvioShimmer().frame(height: dp(84)) } }
                }
            } else {
                NuvioStateMessage(title: "No sources found", message: StoreCopy.noSourcesMessage)
            }
        } else {
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: dp(12)) {
                    ForEach(groups, id: \.addonId) { group in
                        ForEach(Array(group.streams.enumerated()), id: \.offset) { _, stream in
                            StreamCard(stream: stream) { open(stream) }
                        }
                    }
                }
                .padding(.vertical, dp(8))
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }

    private func open(_ stream: StreamItem) {
        guard !opening else { return }
        opening = true
        let resume = TvTitle.shared.resumePositionMs(videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                                     season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
        Task {
            defer { opening = false }
            guard let result = try? await TvTitle.shared.open(stream: stream, meta: meta, video: video, resumeMs: resume) else {
                playback.notify("This source couldn't be opened."); return
            }
            switch onEnum(of: result) {
            case .play(let play):
                // Close the picker first, then present the player over whatever is underneath.
                if let onClose { onClose() } else { dismiss() }
                try? await Task.sleep(nanoseconds: 350_000_000)
                playback.play(play.session)
            case .message(let message):
                playback.notify(message.text)
            }
        }
    }
}

/// StreamCard: BackgroundElevated, radius 12, padding 16, titleMedium name, bodySmall description,
/// addon name on the right; 2dp FocusRing on focus.
private struct StreamCard: View {
    let stream: StreamItem
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: dp(12)) {
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text(stream.streamLabel).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
                    if let sub = stream.streamSubtitle {
                        Text(sub).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(3)
                    }
                }
                Spacer()
                Text(stream.addonName).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).lineLimit(1)
            }
            .padding(dp(16))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundElevated))
            .overlay(RoundedRectangle(cornerRadius: dp(12)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}
