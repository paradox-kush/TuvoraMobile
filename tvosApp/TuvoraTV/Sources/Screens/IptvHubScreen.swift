import SwiftUI
import TuvoraCore

/// NuvioTV's IPTV hub (ui/screens/iptv/XtreamHubScreen.kt): a header row of Live TV / Movies / Series
/// hub chips with the playlist chip on the right, over the live guide or the category shelves.
struct IptvHubScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var hub: XtreamHubUiState?
    @State private var choosingPlaylist = false
    @State private var details: PreviewBox?
    @State private var smokeOpened = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let hub, hub.accountsLoaded, !hub.accounts.isEmpty {
                header(hub)
                    .padding(.top, 60).padding(.horizontal, NuvioTokens.Layout.gutter)
                    .padding(.bottom, dp(16))
                    .scrollClipDisabled()
            .focusSection()
                if let error = hub.loadError {
                    NuvioStateMessage(title: "Couldn't load this playlist", message: error.detail) { TvIptvBrowse.shared.retry() }
                } else if hub.section == .live {
                    LiveGuideView(accountId: hub.selectedAccountId ?? hub.accounts[0].id,
                                  categories: hub.categories.map { ($0.id, $0.name) })
                } else {
                    IptvShelves(hub: hub) { details = PreviewBox(preview: $0) }
                }
            } else if let hub, hub.accountsLoaded {
                NuvioStateMessage(title: "No playlists yet",
                                  message: "Add an IPTV playlist in Settings, or at tuvora.co on your phone or computer.",
                                  actionTitle: "I have a setup code", actionId: "iptv.empty.setupCode") {
                    DeepLinkCenter.shared.openSetupCode = true
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task {
            TvIptvBrowse.shared.open(section: hub?.section ?? .live)
            for await next in TvIptvBrowse.shared.state {
                hub = next
                // Simulator smoke hook: `-smokeIptvOpen <movies|series>` opens the first loaded title's details.
                let args = AppArguments.list
                if details == nil, let i = args.firstIndex(of: "-smokeIptvOpen"), i + 1 < args.count {
                    let want: XtreamHubSection = args[i + 1] == "series" ? .series : .movies
                    if next.section != want { TvIptvBrowse.shared.open(section: want) }
                    else if let first = next.categories.first(where: { !$0.items.isEmpty })?.items.first,
                            !smokeOpened { smokeOpened = true; details = PreviewBox(preview: first) }
                }
            }
        }
        // Follow the playlist store while the tab is on screen (the phone's repeatOnLifecycle): a playlist
        // that arrives by sync, or a removal that resets the hub, shows here without leaving the tab.
        // The task ends with the view, so nothing runs for a tab nobody is looking at; it never polls.
        .task { try? await TvIptvBrowse.shared.followPlaylists() }
        .sheet(isPresented: $choosingPlaylist) {
            if let hub { PlaylistDialog(hub: hub) { choosingPlaylist = false } }
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    private func header(_ hub: XtreamHubUiState) -> some View {
        HStack(spacing: dp(8)) {
            ForEach([(XtreamHubSection.live, "Live TV"), (.movies, "Movies"), (.series, "Series")], id: \.1) { section, title in
                HubChip(title: title, selected: hub.section == section) { TvIptvBrowse.shared.open(section: section) }
                    .accessibilityIdentifier("hubchip.\(title)")
            }
            Spacer()
            if let account = hub.accounts.first(where: { $0.id == hub.selectedAccountId }) ?? hub.accounts.first {
                HubChip(title: TvIptvSettingsPolicy.shared.playlistName(name: account.name), trailingIcon: hub.accounts.count > 1 ? "md_arrow_drop_down" : nil, selected: false) {
                    if hub.accounts.count > 1 { smokeLog("SMOKE playlist chip pressed"); choosingPlaylist = true }
                }
            }
        }
    }
}

struct PreviewBox: Identifiable {
    let preview: MetaPreview
    var id: String { preview.id }
}

/// Provider picker: a NuvioDialog listing the playlists as SettingsActionRows.
private struct PlaylistDialog: View {
    let hub: XtreamHubUiState
    let dismiss: () -> Void

    var body: some View {
        NuvioDialog(title: "Choose a playlist") {
            ForEach(hub.accounts, id: \.id) { account in
                SettingsActionRow(title: TvIptvSettingsPolicy.shared.playlistName(name: account.name), value: account.id == hub.selectedAccountId ? "Selected" : nil) {
                    TvIptvBrowse.shared.selectPlaylist(accountId: account.id)
                    dismiss()
                }
            }
        }
    }
}

// MARK: - Movies / Series shelves

private struct IptvShelves: View {
    let hub: XtreamHubUiState
    let onOpen: (MetaPreview) -> Void

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: NuvioTokens.Layout.rowGap) {
                ForEach(hub.categories, id: \.id) { category in
                    IptvShelf(category: category, onOpen: onOpen)
                }
            }
            .padding(.vertical, dp(12))
        }
    }
}

private struct IptvShelf: View {
    let category: XtreamHubCategory
    let onOpen: (MetaPreview) -> Void
    @State private var seeAll: String?

    var body: some View {
        let size = NuvioCardSize.hubPortrait
        VStack(alignment: .leading, spacing: 0) {
            NuvioShelfHeader(title: category.name) {
                if category.hasMore || category.items.count > 12 { SeeAllButton { seeAll = category.id } }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: NuvioTokens.Layout.itemGap) {
                    if category.items.isEmpty {
                        ForEach(0..<8, id: \.self) { _ in NuvioShimmer().frame(width: size.width, height: size.height) }
                    } else {
                        ForEach(category.items, id: \.id) { item in
                            NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                            width: size.width, height: size.height) { onOpen(item) }
                                .titleActions(item) { onOpen(item) }
                                .onAppear {
                                    if item.id == category.items.last?.id && category.hasMore {
                                        TvIptvBrowse.shared.loadMore(categoryId: category.id)
                                    }
                                }
                        }
                    }
                }
                .padding(.horizontal, NuvioTokens.Layout.gutter)
                .padding(.vertical, dp(8))
            }
            .scrollClipDisabled()
            .focusSection()
        }
        .onAppear { if !category.loaded && !category.loading { TvIptvBrowse.shared.loadCategory(categoryId: category.id) } }
        .fullScreenCover(item: Binding(get: { seeAll.map(CategoryBox.init) }, set: { seeAll = $0?.id })) { box in
            IptvCategoryScreen(categoryId: box.id, onOpen: onOpen)
        }
    }
}

private struct CategoryBox: Identifiable { let id: String }

/// "See all": NuvioTV's category page — headlineSmall title over an adaptive grid (poster width + 12).
private struct IptvCategoryScreen: View {
    let categoryId: String
    let onOpen: (MetaPreview) -> Void
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var category: XtreamHubCategory?

    var body: some View {
        let size = NuvioCardSize.hubPortrait
        ZStack {
            colors.background.ignoresSafeArea()
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: dp(16)) {
                    Text(category?.name ?? "").font(NuvioType.headlineSmall).foregroundStyle(colors.textPrimary)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: size.width, maximum: size.width), spacing: dp(12))], alignment: .leading, spacing: dp(24)) {
                        ForEach(category?.items ?? [], id: \.id) { item in
                            NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster, width: size.width, height: size.height) {
                                dismiss(); onOpen(item)
                            }
                            .onAppear {
                                if item.id == category?.items.last?.id, category?.hasMore == true { TvIptvBrowse.shared.loadMore(categoryId: categoryId) }
                            }
                        }
                    }
                    .focusSection()
                }
                .padding(.horizontal, dp(52)).padding(.vertical, 60)
            }
        }
        .task { for await hub in TvIptvBrowse.shared.state { category = hub.categories.first { $0.id == categoryId } } }
    }
}

// MARK: - Live guide

/// XtreamLiveGuideScreen.kt: a 220dp category column (collapses once focus enters the channels), a 180dp
/// preview strip (16:9 video + info pane), the 2-hour time header, and 44dp channel rows with programme
/// cells and a 2dp now line. OK previews a channel; OK again goes full screen.
///
/// Catch-up (XtreamGuideCatchUp.kt, GuideTimeTravel.kt, GuideCellIntent.kt): RIGHT on a channel steps
/// into its timeline, where the cells OK can act on take focus (solid Primary + 2dp ring) — finished
/// programmes the panel kept replay at once (⟲), the airing one on an archive channel asks "Start over /
/// Watch live", an airing one without archive tunes live; future programmes are not targets. LEFT/RIGHT
/// at the strip's edge page the window a full two hours (back to the stored archive); Menu leaves the
/// timeline. The info pane describes the focused cell. Hold OK on a channel or group to hide it.
private struct LiveGuideView: View {
    let accountId: String
    let categories: [(String, String)]
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors

    @State private var channels: [LiveGuideChannel] = []
    @State private var loading = true
    @State private var category: String = "all"
    @State private var focusedChannel: LiveGuideChannel?
    @State private var previewing: LiveGuideChannel?
    @State private var previewSession: TvPlayerSession?
    /// Latest preview request wins: one that resolves after another channel was chosen or the guide
    /// closed would otherwise play on with no screen (B112).
    @State private var previewRequests = TvPlaybackRequestGate()
    /// Live TV display frame-rate matching: the guide keeps a matched mode while shown.
    @State private var holdsLiveDisplay = false
    @State private var programmes: [String: [XtreamProgram]] = [:]
    @State private var now = TvLiveGuide.shared.nowMs()
    @FocusState private var channelsFocused: Bool
    /// LEFT from a channel brings the category column back (it hides while channels have focus) and
    /// focuses the current category, rather than letting LEFT fall through to the sidebar.
    @FocusState private var categoryFocus: String?
    @State private var revealCategories = false
    @State private var recents: [XtreamLiveRecent] = []
    @State private var favorites: Set<String> = []
    /// F03: every playlist's favourites, in the synced favourites order (the All favorites row; this
    /// playlist's Favorites row is its part of it).
    @State private var favoriteOrder: [LiveGuideChannel] = []
    /// F03: the favourite just toggled — Undo (in the hold-OK menu) for a few seconds, like a hide.
    @State private var lastFavoriteToggle: (contentId: String, at: Date)?
    @State private var lastFavoriteUndo: LibrarySavedUndo?
    /// P5: a favourite removed from a favourites row stays listed (marked as removed) while Undo is offered.
    @State private var pendingRemoval: TvFavouriteRows.PendingRemoval?
    @State private var pendingRemovalChannel: LiveGuideChannel?
    /// P5: Undo is saving the favourite back; the held row stays until the real one is listed again.
    @State private var undoInFlight = false
    /// P5: the Undo window closed while the removed row had focus; it leaves when focus does.
    @State private var holdRemoved = false

    // Catch-up
    @State private var windowStart = TvGuideTimeline.shared.liveWindowStartMs(nowMs: TvLiveGuide.shared.nowMs())
    @State private var timelineChannel: String?
    @State private var focusedCell: XtreamProgram?
    @State private var cellDescription: String?
    @State private var historyShown: Set<String> = []
    @State private var sheet: GuideSheetTarget?
    @State private var notice: String?
    @State private var hideAsk: (String, String)?
    @State private var catchUpSupported = false
    @State private var pendingEdge: Int = 0
    @FocusState private var cellFocus: Int64?

    private static let categoryWidth = dp(220), labelWidth = dp(230), rowHeight = dp(44), previewHeight = dp(180)
    private var timeline: TvGuideTimeline { TvGuideTimeline.shared }
    private var atLive: Bool { timeline.isAtLiveEdge(startMs: windowStart, nowMs: now) }

    private var visible: [LiveGuideChannel] {
        switch category {
        case "all": return channels
        // F03: favourites rows keep the synced favourites order; this playlist's rows reuse the full
        // channel (schedule, pin, archive flags), another playlist's come from the library entry.
        case "favorites", "allfavorites":
            let prefix = TvLiveGuide.shared.accountPrefix(accountId: accountId)
            var byId = Dictionary(channels.map { ($0.contentId, $0) }, uniquingKeysWith: { a, _ in a })
            let rows = favoriteOrder.filter { category == "allfavorites" || $0.contentId.hasPrefix(prefix) }
            for row in rows where byId[row.contentId] == nil { byId[row.contentId] = row }
            if let removed = pendingRemovalChannel, byId[removed.contentId] == nil { byId[removed.contentId] = removed }
            // P5: a just-removed favourite keeps its row until the Undo window closes.
            let ids = TvFavouriteRows.shared.visibleIds(rowIds: rows.map(\.contentId), pending: pendingRemoval, nowMs: now)
            let held = TvFavouriteRows.shared.heldIds(rowIds: rows.map(\.contentId), pending: pendingRemoval)
            return ((undoInFlight || holdRemoved) ? held : ids).compactMap { byId[$0] }
        case "recent":
            let ids = recents.map(\.contentId)
            return ids.compactMap { id in channels.first { $0.contentId == id } }
        default: return channels.filter { $0.categoryId == category }
        }
    }

    var body: some View {
        ZStack {
            HStack(alignment: .top, spacing: dp(12)) {
                if !channelsFocused || revealCategories {
                    categoryColumn.frame(width: Self.categoryWidth).transition(.move(edge: .leading).combined(with: .opacity))
                }
                VStack(alignment: .leading, spacing: dp(8)) {
                    previewStrip.frame(height: Self.previewHeight)
                    timeHeader
                    channelList
                }
                // One section for the whole right pane: RIGHT from an upper category (level with the
                // non-focusable preview) finds nothing in its beam otherwise, and focus goes nowhere.
                .focusSection()
            }
            .padding(.leading, NuvioTokens.Layout.gutter).padding(.trailing, dp(24))
            .disabled(sheet != nil || hideAsk != nil)
            if let sheet {
                GuideStartOverSheet(target: sheet,
                                    onStartOver: { self.sheet = nil; startReplay(sheet.channel, sheet.programme) },
                                    onWatchLive: { self.sheet = nil; select(sheet.channel) },
                                    onDismiss: { self.sheet = nil })
            }
            if let hideAsk {
                HideGroupDialog(name: hideAsk.1,
                                onHide: {
                                    _ = TvIptvPersonalize.shared.hideCategory(categoryId: hideAsk.0)
                                    smokeLog("SMOKE guide hid group=%@", hideAsk.1)
                                    if category == hideAsk.0 { category = "all" }
                                    self.hideAsk = nil
                                },
                                onCancel: { self.hideAsk = nil })
            }
        }
        .animation(NuvioTokens.Motion.medium, value: channelsFocused)
        .task(id: accountId) {
            loading = true
            await reloadChannels()
            catchUpSupported = channels.first.map { TvCatchUp.shared.supportsCatchUp(contentId: $0.contentId) } ?? false
            loading = false
            runSmokeHooks()
        }
        .task {
            // A hide made here, on tuvora.co or on another device re-reads the guide's channels.
            var first = true
            for await _ in TvIptvPersonalize.shared.overlayRevision {
                if first { first = false; continue }
                await reloadChannels()
            }
        }
        .task(id: categories.count) {
            // Simulator hook: `-smokeGuideHideAsk` opens the hide-group confirmation for the first category.
            if AppArguments.list.contains("-smokeGuideHideAsk"), hideAsk == nil, let first = categories.first {
                hideAsk = first
            }
        }
        .task { for await next in TvLiveGuide.shared.recents { recents = next } }
        .task {
            for await _ in TvLiveGuide.shared.libraryChanges {
                refreshFavorites()
            }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                let previous = now
                now = TvLiveGuide.shared.nowMs()
                windowStart = timeline.onClockTick(startMs: windowStart, previousNowMs: previous, nowMs: now)
            }
        }
        .task {
            // Rows fill from the guide EPG loader (TvGuideEpg): one ask per (row, window), two at a time.
            TvGuideEpg.shared.resetSession()
            for await row in TvGuideEpg.shared.results {
                programmes[Self.windowKey(row.contentId, row.windowStartMs)] = row.programmes
                smokeLog("SMOKE guide row=%@ window=%lld programmes=%d", row.contentId, row.windowStartMs, row.programmes.count)
            }
        }
        .task(id: prefetchKey) {
            // The fan-out rule (TvGuideEpgPrefetch): once focus settles, ask for the rows around it — in a
            // past window too, so every visible row shows its schedule, not only the focused channel's.
            // A D-pad sweep cancels this before it asks for anything.
            try? await Task.sleep(nanoseconds: UInt64(TvGuideEpgPrefetch.shared.SETTLE_MS) * 1_000_000)
            guard !Task.isCancelled, !loading else { return }
            let list = visible
            let anchor = focusedChannel.flatMap { f in list.firstIndex { $0.contentId == f.contentId } } ?? 0
            TvGuideEpg.shared.request(channels: list, anchor: Int32(anchor), windowStartMs: windowStart,
                                      travelling: !atLive, catchUpSupported: catchUpSupported)
            smokeLog("SMOKE guide prefetch anchor=%d window=%lld asked=%d", anchor, windowStart, TvGuideEpg.shared.askedCount)
        }
        .task(id: focusedCell?.startMs) {
            cellDescription = nil
            guard let cell = focusedCell, let channel = focusedChannel else { return }
            cellDescription = try? await TvCatchUp.shared.description(contentId: channel.contentId, programme: cell)
        }
        .onChange(of: timelineChannel) { _, _ in syncEdgeOwnership() }
        // P5: leaving the favourites row lets a held removed row go.
        .onChange(of: category) { _, _ in if !undoInFlight { dropHeldRemoval() } }
        // The guide keeps a display mode the fullscreen player matched (it never switches one itself, as
        // on Android TV): fullscreen <-> guide costs no HDMI blackout. Leaving the guide gives it back.
        .onAppear {
            if !holdsLiveDisplay { holdsLiveDisplay = true; LiveDisplayCriteriaController.shared.enter() }
        }
        .onDisappear {
            previewRequests.cancel()
            previewSession?.close(); previewSession = nil; ContentFocusActivity.shared.leftEdgeOwned = false
            if holdsLiveDisplay { holdsLiveDisplay = false; LiveDisplayCriteriaController.shared.exit() }
        }
    }

    /// What re-asks the loader: the settled focus, the window, and the list being shown.
    private var prefetchKey: String { "\(focusedChannel?.contentId ?? "-")|\(windowStart)|\(category)|\(visible.count)|\(loading)" }

    static func windowKey(_ contentId: String, _ windowStart: Int64) -> String { "\(contentId)@\(windowStart)" }

    /// A row's programmes for the window on screen (nil until its ask has answered).
    private func rowProgrammes(_ channel: LiveGuideChannel) -> [XtreamProgram] {
        programmes[Self.windowKey(channel.contentId, windowStart)] ?? []
    }

    private var isFavoritesCategory: Bool { category == "favorites" || category == "allfavorites" }

    private func refreshFavorites() {
        favoriteOrder = TvLiveGuide.shared.favoriteChannels(accountId: nil)
        // All favorites also lists OTHER playlists' favourites; they are favourites too (★, and the
        // hold-OK menu offers Remove — it said "Add to Favorites" for them; device pass 2026-10-05).
        favorites = Set(channels.map(\.contentId).filter { TvLiveGuide.shared.isFavorite(contentId: $0) })
            .union(favoriteOrder.map(\.contentId))
        // P5: once Undo's favourite is back in the rows, the held row hands over to it. An add can land
        // in the library a moment after the call returns; dropping the held row before that emptied the
        // list for a frame and focus fell to the sidebar.
        if undoInFlight, let held = pendingRemoval, favoriteOrder.contains(where: { $0.contentId == held.contentId }) {
            dropHeldRemoval()
        }
    }

    /// P5: the removed row leaves (window closed and focus moved on, or the favourite is back).
    private func dropHeldRemoval() {
        pendingRemoval = nil
        pendingRemovalChannel = nil
        undoInFlight = false
        holdRemoved = false
    }

    /// F03 (owner 2026-10-04): a favourite toggle is confirmed — with Undo — rather than a popup.
    private func toggleFavorite(_ channel: LiveGuideChannel, undoable: Bool) {
        let adding = !favorites.contains(channel.contentId)
        let membershipUndo = undoable
            ? TvLiveGuide.shared.captureFavoriteUndo(contentId: channel.contentId)
            : lastFavoriteUndo
        // P5: Undo of a removal puts the favourite back in its old place, not at the top.
        let undoing = !undoable ? pendingRemoval.flatMap { $0.contentId == channel.contentId ? $0 : nil } : nil
        // P5: removing inside a favourites row keeps the row (marked as removed) while Undo is offered —
        // dropping it at once left the promised "Hold OK to undo" nothing to hold OK on.
        let stamp = TvLiveGuide.shared.nowMs()
        let removal = undoable && !adding && isFavoritesCategory
            ? TvFavouriteRows.shared.pendingRemoval(
                rowIds: visible.map(\.contentId), contentId: channel.contentId,
                savedAtEpochMs: TvLiveGuide.shared.favoriteSavedAt(contentId: channel.contentId), nowMs: stamp)
            : nil
        // Hold the row BEFORE the library changes: the libraryChanges observer refreshes the rows as
        // soon as the toggle lands, and a row that vanishes even for a frame takes focus with it.
        if let removal {
            now = stamp
            pendingRemoval = removal
            pendingRemovalChannel = channel
        }
        Task {
            // Undo: the removal's window timer must not drop the row while the favourite is being saved
            // back (it would vanish for a moment and take focus with it).
            if undoing != nil {
                undoInFlight = true
                lastFavoriteToggle = nil
                notice = nil
            }
            if !undoable {
                membershipUndo?.undo()
                lastFavoriteUndo = nil
            } else {
                try? await TvLiveGuide.shared.toggleFavorite(channel: channel)
            }
            now = TvLiveGuide.shared.nowMs()
            if removal == nil && undoing == nil { dropHeldRemoval() }
            refreshFavorites()
            if undoing != nil {
                // Fallback: never hold the row for long if the favourite does not come back.
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if undoInFlight {
                    dropHeldRemoval()
                    refreshFavorites()
                }
                return
            }
            let at = Date()
            lastFavoriteToggle = undoable ? (channel.contentId, at) : nil
            lastFavoriteUndo = undoable ? membershipUndo : nil
            notice = undoable
                ? "\u{201C}\(channel.name)\u{201D} \(adding ? "added to" : "removed from") Favorites. Hold OK to undo."
                : nil
            // The notice lasts as long as Undo is offered (the hold-OK menu's 6 s window).
            guard undoable else { return }
            try? await Task.sleep(nanoseconds: UInt64(TvFavouriteRows.shared.UNDO_WINDOW_MS) * 1_000_000)
            if lastFavoriteToggle?.at == at {
                notice = nil
                now = TvLiveGuide.shared.nowMs()
                // The window closed: the removed row leaves now — unless it still has focus (the hold-OK
                // menu may even be open on it); then it leaves when focus does.
                if TvFavouriteRows.shared.dropsAtWindowClose(pending: pendingRemoval, focusedId: focusedChannel?.contentId) {
                    dropHeldRemoval()
                } else if pendingRemoval != nil {
                    holdRemoved = true
                }
            }
        }
    }

    private func reloadChannels() async {
        channels = (try? await TvLiveGuide.shared.channels(accountId: accountId)) ?? []
        refreshFavorites()
    }

    // Category column: plain rows, radius 8, padding 12×8, bodyMedium; focused grey Primary, selected BackgroundElevated.
    private var categoryColumn: some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: dp(2)) {
                GuideCategoryRow(title: "All favorites", selected: category == "allfavorites") { category = "allfavorites" }
                    .focused($categoryFocus, equals: "allfavorites")
                GuideCategoryRow(title: "Favorites", selected: category == "favorites") { category = "favorites" }
                    .focused($categoryFocus, equals: "favorites")
                GuideCategoryRow(title: "Recent", selected: category == "recent") { category = "recent" }
                    .focused($categoryFocus, equals: "recent")
                GuideCategoryRow(title: "All channels", selected: category == "all") { category = "all" }
                    .focused($categoryFocus, equals: "all")
                ForEach(categories, id: \.0) { id, name in
                    GuideCategoryRow(title: name, selected: category == id) { category = id }
                        .focused($categoryFocus, equals: id)
                        // NuvioTV MENU on a provider category = the tvOS hold-OK menu.
                        .contextMenu { Button("Hide group") { hideAsk = (id, name) } }
                }
            }
        }
        .focusSection()
    }

    private var previewStrip: some View {
        HStack(alignment: .top, spacing: dp(16)) {
            ZStack {
                Color.black
                if let previewSession {
                    EngineHost(session: previewSession, generation: 0)
                }
            }
            .frame(width: Self.previewHeight * 16 / 9, height: Self.previewHeight)
            .clipShape(RoundedRectangle(cornerRadius: dp(8)))

            infoPane.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// PreviewInfoPane: the channel name in Primary, then the programme — the focused cell's when the
    /// viewer is in a timeline, else what's on now — its time range and progress, and the synopsis; a
    /// playback / catch-up notice takes the programme's slot.
    private var infoPane: some View {
        VStack(alignment: .leading, spacing: dp(4)) {
            if let channel = focusedChannel ?? previewing {
                Text(channel.name).font(NuvioType.labelLargeSemi).foregroundStyle(colors.primary).lineLimit(1)
                if let notice {
                    Text(notice).font(NuvioType.bodySmall).foregroundStyle(colors.error).lineLimit(4)
                } else if let programme = focusedCell ?? currentProgramme(channel) {
                    Text(programme.title).font(NuvioType.titleMediumSemi).foregroundStyle(colors.textPrimary).lineLimit(1)
                    HStack(spacing: dp(12)) {
                        Text("\(Self.clock(programme.startMs)) – \(Self.clock(programme.endMs))")
                            .font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                            .lineLimit(1).fixedSize()
                        if programme.startMs <= now && programme.endMs > now { progress(programme) }
                    }
                    let description = focusedCell != nil ? (cellDescription ?? programme.descriptionText) : programme.descriptionText
                    if !description.isEmpty {
                        Text(description).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(3)
                    }
                } else {
                    Text("No information").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                }
            }
            Spacer(minLength: 0)
            Text(timelineChannel != nil ? "OK play · ← → earlier / later · Menu back to channels"
                                        : "OK preview · OK again fullscreen · → programmes · hold OK")
                .font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary).lineLimit(2)
        }
    }

    private func progress(_ programme: XtreamProgram) -> some View {
        let total = max(1, Double(programme.endMs - programme.startMs))
        let done = min(1, max(0, Double(now - programme.startMs) / total))
        let left = max(0, (programme.endMs - now) / 60_000)
        return HStack(spacing: dp(12)) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(colors.border)
                    Capsule().fill(colors.primary).frame(width: geo.size.width * done)
                }
            }
            .frame(minWidth: dp(60), maxWidth: dp(160))
            .frame(height: dp(3))
            Text("\(left) min left").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary).lineLimit(1).fixedSize()
        }
    }

    /// GuideTimeHeaderWithDay: the day the window sits on (Primary Bold once travelled), then the ticks.
    private var timeHeader: some View {
        HStack(spacing: 0) {
            Text(ui: Self.dayLabel(windowStart: windowStart, now: now))
                .font(atLive ? NuvioType.labelSmall : NuvioType.labelSmall.weight(.bold))
                .foregroundStyle(atLive ? colors.textSecondary : colors.primary)
                .frame(width: Self.labelWidth, alignment: .leading)
            GeometryReader { geo in
                ForEach(Array(timeline.slotStarts(startMs: windowStart).enumerated()), id: \.offset) { index, slot in
                    Text(Self.clock(slot.int64Value)).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                        .offset(x: geo.size.width * CGFloat(index) / CGFloat(TvGuideTimeline.shared.SLOTS))
                }
            }
            .padding(.leading, dp(8))   // the strip's own inset, so ticks sit over their cells
            .frame(height: dp(16))
        }
        .padding(.bottom, dp(6))
    }

    private var channelList: some View {
        Group {
            if loading {
                VStack(spacing: dp(4)) { ForEach(0..<6, id: \.self) { _ in NuvioShimmer(cornerRadius: dp(8)).frame(height: Self.rowHeight) } }
            } else if visible.isEmpty {
                NuvioStateMessage(title: "No channels here", message: category == "recent" ? "Channels you watch show up here." : "This category is empty on your provider.")
            } else {
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(spacing: dp(4)) {
                        ForEach(Array(visible.enumerated()), id: \.element.contentId) { index, channel in
                            let cells = timeline.cells(programmes: rowProgrammes(channel), channel: channel,
                                                       startMs: windowStart, nowMs: now, catchUpSupported: catchUpSupported)
                            GuideChannelRow(number: index + 1, channel: channel, cells: cells,
                                            favorite: favorites.contains(channel.contentId),
                                            removed: pendingRemoval?.contentId == channel.contentId && !undoInFlight,
                                            interactive: timelineChannel == channel.contentId,
                                            nowFraction: timeline.containsNow(startMs: windowStart, nowMs: now)
                                                ? timeline.nowFraction(startMs: windowStart, nowMs: now) : nil,
                                            labelWidth: Self.labelWidth, height: Self.rowHeight,
                                            cellFocus: $cellFocus,
                                            onFocus: {
                                                focusedChannel = channel
                                                focusedCell = nil
                                                // F03 (device pass 2026-10-05): the hold-OK menu closing hands
                                                // focus back to this row, which wiped "Hold OK to undo" at once —
                                                // keep the notice while it is about this row.
                                                let undoNotice = lastFavoriteToggle.map {
                                                    $0.contentId == channel.contentId && Date().timeIntervalSince($0.at) < 6
                                                } ?? false
                                                if !undoNotice { notice = nil }
                                                // P5: a removed row held past its window leaves once focus moves on.
                                                if holdRemoved, let held = pendingRemoval, held.contentId != channel.contentId {
                                                    dropHeldRemoval()
                                                }
                                                if timelineChannel != nil { leaveTimeline() }
                                            },
                                            onSelect: { select(channel) },
                                            onEnterTimeline: { enterTimeline(channel, cells: cells) },
                                            onCellFocus: { focusedCell = $0 },
                                            onCell: { cell in act(cell, channel: channel) },
                                            onEdge: { direction in travel(direction, channel: channel) },
                                            onLeaveTimeline: { leaveTimeline(refocus: channel) },
                                            onLeft: showCategories)
                                // Hold OK (NuvioTV) = the tvOS context menu.
                                .contextMenu {
                                    // F03: Undo for the favourite toggle just made (the tvOS stand-in for
                                    // NuvioTV's Undo toast: hold OK again within a few seconds).
                                    if let last = lastFavoriteToggle, last.contentId == channel.contentId,
                                       Date().timeIntervalSince(last.at) < 6 {
                                        Button("Undo") { toggleFavorite(channel, undoable: false) }
                                    }
                                    Button(favorites.contains(channel.contentId) ? "Remove from Favorites" : "Add to Favorites") {
                                        toggleFavorite(channel, undoable: true)
                                    }
                                    // F03: reorder a favourite (favourites rows) or a pinned channel (its group).
                                    if isFavoritesCategory {
                                        let ids = visible.map(\.contentId)
                                        if TvLiveGuide.shared.canMoveFavorite(rowIds: ids, contentId: channel.contentId, delta: -1) {
                                            Button("Move up") { TvLiveGuide.shared.moveFavorite(rowIds: ids, contentId: channel.contentId, delta: -1) }
                                        }
                                        if TvLiveGuide.shared.canMoveFavorite(rowIds: ids, contentId: channel.contentId, delta: 1) {
                                            Button("Move down") { TvLiveGuide.shared.moveFavorite(rowIds: ids, contentId: channel.contentId, delta: 1) }
                                        }
                                    } else if channel.pinned {
                                        if TvLiveGuide.shared.canMovePinned(shown: visible, channel: channel, delta: -1) {
                                            Button("Move up") { TvLiveGuide.shared.movePinned(shown: visible, channel: channel, delta: -1) }
                                        }
                                        if TvLiveGuide.shared.canMovePinned(shown: visible, channel: channel, delta: 1) {
                                            Button("Move down") { TvLiveGuide.shared.movePinned(shown: visible, channel: channel, delta: 1) }
                                        }
                                    }
                                    // NuvioTV MENU on a channel: hide it (personalization overlay, synced).
                                    Button("Hide channel") {
                                        TvIptvPersonalize.shared.hideChannel(channel: channel)
                                        smokeLog("SMOKE guide hid channel=%@", channel.name)
                                    }
                                }
                        }
                    }
                    .padding(.vertical, dp(4))
                }
                .focused($channelsFocused)
                .onChange(of: channelsFocused) { _, _ in syncEdgeOwnership() }
                // Clipped: with the clip disabled, a list scrolled to a lower row drew its top rows up
                // over the time header ("Yesterday" sat on row 1). The 4pt inset keeps the focus ring whole.
                .clipped()
                .focusSection()
            }
        }
    }

    // MARK: Catch-up

    /// The FOCUSED row's window, loaded directly (an edge press needs its cells to place the cursor):
    /// its stored table first when there is a past to show, then the shared GuideWindowSource rule
    /// (history wins; now-and-next only paints the live window). Every other row goes through TvGuideEpg.
    private func loadWindow(_ channel: LiveGuideChannel, force: Bool) async {
        let start = windowStart
        let key = Self.windowKey(channel.contentId, start)
        let travelling = !timeline.isAtLiveEdge(startMs: start, nowMs: now)
        if catchUpSupported, travelling || channel.hasArchive {
            try? await TvCatchUp.shared.ensureHistory(contentId: channel.contentId)
        }
        guard let result = try? await TvCatchUp.shared.windowProgrammes(
            contentId: channel.contentId, fromMs: start, toMs: start + TvGuideTimeline.shared.WINDOW_MS,
            travelling: travelling, historyShown: historyShown.contains(key)),
              start == windowStart else { return }
        if result.fromHistory { historyShown.insert(key) }
        if result.fromHistory || !result.programmes.isEmpty || !historyShown.contains(key) {
            programmes[key] = result.programmes
        }
    }

    private func enterTimeline(_ channel: LiveGuideChannel, cells: [TvGuideCell]) {
        guard let first = cells.first(where: { $0.intent != .none })?.programme else { return }
        timelineChannel = channel.contentId
        DispatchQueue.main.async { cellFocus = first.startMs }
    }

    /// The guide owns LEFT while channels or the timeline have focus: a channel's LEFT opens the
    /// categories, a first cell's LEFT travels back in time. Elsewhere LEFT may reach the sidebar.
    private func syncEdgeOwnership() {
        ContentFocusActivity.shared.leftEdgeOwned = channelsFocused || timelineChannel != nil
    }

    private func showCategories() {
        revealCategories = true
        // After the column is laid out (an immediate assignment finds no such view yet).
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            categoryFocus = category
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { revealCategories = false }
        }
    }

    private func leaveTimeline(refocus channel: LiveGuideChannel? = nil) {
        timelineChannel = nil
        focusedCell = nil
        cellFocus = nil
        windowStart = timeline.liveWindowStartMs(nowMs: now)
    }

    /// Edge press: page the window (NuvioTV onTravel), then put the cursor on the cell nearest the push.
    private func travel(_ direction: Int, channel: LiveGuideChannel) {
        let next = timeline.shift(startMs: windowStart, slots: Int32(direction * Int(TvGuideTimeline.shared.EDGE_TRAVEL_SLOTS)),
                                  nowMs: now, catchUpDays: channel.catchUpDays)
        guard next != windowStart else { return }
        windowStart = next
        smokeLog("SMOKE guide travel start=%lld live=%d", next, atLive)
        Task {
            await loadWindow(channel, force: true)
            let cells = timeline.cells(programmes: rowProgrammes(channel), channel: channel,
                                       startMs: windowStart, nowMs: now, catchUpSupported: catchUpSupported)
            let actionable = cells.filter { $0.intent != .none }
            if let target = (direction < 0 ? actionable.last : actionable.first)?.programme {
                cellFocus = target.startMs
            }
        }
    }

    /// NuvioTV's OK rule on a cell (GuideCellIntent): replay at once, ask when airing with archive, tune live.
    private func act(_ cell: TvGuideCell, channel: LiveGuideChannel) {
        guard let programme = cell.programme else { return }
        switch cell.intent {
        case .replay: startReplay(channel, programme)
        case .openSheet: sheet = GuideSheetTarget(channel: channel, programme: programme)
        case .playLive: select(channel)
        default: break
        }
    }

    /// A replay plays full screen through the shared catch-up walk; each failed URL shape walks on,
    /// and a programme nothing plays comes back to the guide as NuvioTV's notice.
    private func startReplay(_ channel: LiveGuideChannel, _ programme: XtreamProgram) {
        notice = nil
        previewRequests.cancel()
        previewSession?.close(); previewSession = nil; previewing = nil
        smokeLog("SMOKE guide replay channel=%@ programme=%@", channel.name, programme.title)
        Task {
            guard let pair = try? await TvCatchUp.shared.startReplay(channel: channel, programme: programme) else { return }
            guard var current = pair.first.session else {
                notice = pair.first.notice
                smokeLog("SMOKE guide replay notice=%@", pair.first.notice ?? "-")
                return
            }
            playback.play(current)
            while let step = try? await pair.replay.supervise(session: current) {
                if let next = step.session {
                    smokeLog("SMOKE guide replay next dialect")
                    guard playback.session?.session === current else { next.close(); return }   // viewer left
                    playback.continuePlaying(next, from: current)
                    current = next
                } else {
                    notice = step.notice
                    smokeLog("SMOKE guide replay notice=%@", step.notice ?? "-")
                    if playback.session?.session === current { playback.stop() }
                    return
                }
            }
            smokeLog("SMOKE guide replay settled state playing=%d", current.state.value.isPlaying)
        }
    }

    private func currentProgramme(_ channel: LiveGuideChannel) -> XtreamProgram? {
        programmes[Self.windowKey(channel.contentId, timeline.liveWindowStartMs(nowMs: now))]?.first { $0.startMs <= now && $0.endMs > now }
            ?? rowProgrammes(channel).first { $0.startMs <= now && $0.endMs > now }
    }

    /// OK previews a channel in the strip; OK on the previewing channel goes full screen.
    private func select(_ channel: LiveGuideChannel) {
        notice = nil
        if previewing?.contentId == channel.contentId, let previewSession {
            previewRequests.cancel()
            self.previewSession = nil
            previewing = nil
            // T4: zapping from All favorites stays inside the playing channel's own playlist.
            let zapIds = Set(TvFavouriteRows.shared.zapIds(rowIds: visible.map(\.contentId), playingId: channel.contentId))
            let list = visible.filter { zapIds.contains($0.contentId) }
            var index = list.firstIndex { $0.contentId == channel.contentId } ?? 0
            playback.play(previewSession) { offset in
                guard !list.isEmpty else { return nil }
                index = (index + offset + list.count) % list.count
                let next = list[index]
                return try? await TvIptvBrowse.shared.playChannel(contentId: next.contentId, name: next.name, logo: next.logo)
            }
            return
        }
        previewSession?.close()
        previewSession = nil
        previewing = channel
        let request = previewRequests.begin()
        Task {
            let resolved = try? await TvIptvBrowse.shared.playChannel(contentId: channel.contentId, name: channel.name, logo: channel.logo)
            guard previewRequests.isCurrent(token: request) else { resolved?.close(); return }
            if let session = resolved {
                session.attach()
                previewSession = session
            } else {
                previewing = nil
                playback.notify("\(channel.name) isn't available right now.")
            }
        }
    }

    /// Simulator hooks. `-smokeGuidePlay` previews the first channel then goes full screen;
    /// `-smokeGuideTimeline` steps into the first archive channel's timeline; `-smokeGuideTravel <n>`
    /// then pages back n windows; `-smokeGuideSheet` opens the start-over sheet on an airing archive
    /// programme; `-smokeGuideReplay` replays its first finished kept programme; `-smokeGuideHideAsk`
    /// opens the hide-group confirmation for the first category; `-smokeGuideHideChannel` hides the first channel.
    private func runSmokeHooks() {
        let args = AppArguments.list
        if args.contains("-smokeGuidePlay"), let first = channels.first {
            Task {
                select(first)
                try? await Task.sleep(nanoseconds: 12_000_000_000)
                smokeLog("SMOKE guide preview=%@ session=%d", first.name, previewSession == nil ? 0 : 1)
                select(first)
            }
        }
        // `-smokeGuideHideChannel` hides the first channel (a hide + unhide round trip for verification).
        if args.contains("-smokeGuideHideChannel"), let first = channels.first {
            let before = channels.count
            TvIptvPersonalize.shared.hideChannel(channel: first)
            Task {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                smokeLog("SMOKE guide hide channel=%@ before=%d after=%d", first.name, before, channels.count)
            }
        }
        let archive = channels.filter(\.hasArchive)
        smokeLog("SMOKE guide channels=%d archive=%d catchUp=%d first=%@", channels.count, archive.count, catchUpSupported,
              archive.first?.name ?? "-")
        guard let channel = archive.first,
              args.contains("-smokeGuideTimeline") || args.contains("-smokeGuideSheet") || args.contains("-smokeGuideReplay") else { return }
        category = channel.categoryId ?? "all"
        focusedChannel = channel
        Task {
            if let i = args.firstIndex(of: "-smokeGuideTravel"), i + 1 < args.count, let n = Int(args[i + 1]) {
                windowStart = timeline.shift(startMs: windowStart, slots: Int32(-n * Int(TvGuideTimeline.shared.EDGE_TRAVEL_SLOTS)),
                                             nowMs: now, catchUpDays: channel.catchUpDays)
            }
            await loadWindow(channel, force: true)
            let cells = timeline.cells(programmes: rowProgrammes(channel), channel: channel,
                                       startMs: windowStart, nowMs: now, catchUpSupported: catchUpSupported)
            smokeLog("SMOKE guide cells=%@", cells.map { "\($0.programme?.title ?? "·"):\($0.intent)" }.joined(separator: " | "))
            if args.contains("-smokeGuideSheet"), let airing = cells.first(where: { $0.intent == .openSheet })?.programme {
                sheet = GuideSheetTarget(channel: channel, programme: airing)
            } else if args.contains("-smokeGuideReplay"), let past = cells.first(where: { $0.intent == .replay })?.programme {
                startReplay(channel, past)
            } else {
                timelineChannel = channel.contentId
                let target = cells.last(where: { $0.intent == .replay }) ?? cells.first(where: { $0.intent != .none })
                if let p = target?.programme { focusedCell = p; cellFocus = p.startMs }
            }
        }
    }

    static func clock(_ ms: Int64) -> String {
        let f = DateFormatter(); f.dateFormat = DateFormatter.dateFormat(fromTemplate: "jmm", options: 0, locale: .current)
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }

    /// guideDayLabel: by calendar day, anchored on now while the now-line is on screen.
    static func dayLabel(windowStart: Int64, now: Int64) -> String {
        let anchorMs = TvGuideTimeline.shared.containsNow(startMs: windowStart, nowMs: now) ? now : windowStart
        let cal = Calendar.current
        let anchor = Date(timeIntervalSince1970: TimeInterval(anchorMs) / 1000)
        let today = Date(timeIntervalSince1970: TimeInterval(now) / 1000)
        let days = cal.dateComponents([.day], from: cal.startOfDay(for: anchor), to: cal.startOfDay(for: today)).day ?? 0
        switch days {
        case 0: return "Today"
        case 1: return "Yesterday"
        case -1: return "Tomorrow"
        default:
            let f = DateFormatter(); f.dateFormat = DateFormatter.dateFormat(fromTemplate: "EEEdMMM", options: 0, locale: .current)
            return f.string(from: anchor)
        }
    }
}

private extension XtreamProgram {
    var descriptionText: String { description_.trimmingCharacters(in: .whitespacesAndNewlines) }
}

struct GuideSheetTarget: Equatable {
    let channel: LiveGuideChannel
    let programme: XtreamProgram
    static func == (a: GuideSheetTarget, b: GuideSheetTarget) -> Bool {
        a.channel.contentId == b.channel.contentId && a.programme.startMs == b.programme.startMs
    }
}

private struct GuideCategoryRow: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(ui: title).font(NuvioType.bodyMedium).lineLimit(1)
                .foregroundStyle(focused ? colors.onPrimary : (selected ? colors.textPrimary : colors.textSecondary))
                .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.primary : (selected ? colors.backgroundElevated : .clear)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// Channel row: number (30dp), logo (30dp, radius 4), name (bodySmall) in a 230dp label block, then the
/// programme cells (radius 4, 2dp gaps; now-airing Primary 20%, others BackgroundElevated, gaps 40%).
/// Focused row: Primary 22% fill + 2dp Primary border, radius 8. In the timeline, the actionable cells
/// take focus (GuideCell: solid Primary + 2dp focus ring, ⟲ on kept programmes) and the label does not.
private struct GuideChannelRow: View {
    let number: Int
    let channel: LiveGuideChannel
    let cells: [TvGuideCell]
    let favorite: Bool
    /// P5: a favourite just removed, kept in its favourites row while Undo is offered.
    var removed: Bool = false
    let interactive: Bool
    let nowFraction: Double?
    let labelWidth: CGFloat
    let height: CGFloat
    var cellFocus: FocusState<Int64?>.Binding
    let onFocus: () -> Void
    let onSelect: () -> Void
    let onEnterTimeline: () -> Void
    let onCellFocus: (XtreamProgram) -> Void
    let onCell: (TvGuideCell) -> Void
    let onEdge: (Int) -> Void
    let onLeaveTimeline: () -> Void
    let onLeft: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onSelect) { label }
                .buttonStyle(PlainNoChromeButtonStyle())
                .focused($focused)
                .reportsFocus(focused)
                .disabled(interactive)
                .onMoveCommand { direction in
                    if direction == .right { onEnterTimeline() }
                    if direction == .left { onLeft() }
                }
                .onChange(of: focused) { _, isFocused in if isFocused { onFocus() } }
            strip
        }
        .frame(height: height)
        .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused || interactive ? colors.primary.opacity(0.22) : .clear))
        .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(focused || interactive ? colors.primary : .clear, lineWidth: NuvioTokens.Stroke.focus))
    }

    private var label: some View {
        HStack(spacing: dp(8)) {
            Text("\(number)").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).frame(width: dp(30), alignment: .leading)
            CachedPosterArtwork(urlString: channel.logo, width: dp(30), height: dp(30), maximumWidth: dp(60)) { Color.clear }
                .frame(width: dp(30), height: dp(30))
                .clipShape(RoundedRectangle(cornerRadius: dp(4)))
            // P5: a just-removed favourite reads as removed (struck through, no ★) until the Undo window
            // closes; the row keeps its normal focus look so it is clearly still the one to hold OK on.
            Text(channel.name).font(NuvioType.bodySmall).foregroundStyle(removed ? colors.textSecondary : colors.textPrimary)
                .strikethrough(removed).lineLimit(1)
            if channel.hasArchive {
                Text("⟲").font(NuvioType.labelSmall.weight(.bold)).foregroundStyle(colors.primary)
            }
            if favorite {
                Image("md_star").renderingMode(.template).resizable().frame(width: dp(10), height: dp(10)).foregroundStyle(colors.primary)
            }
            if channel.pinned {
                Image("md_favorite").renderingMode(.template).resizable().frame(width: dp(10), height: dp(10)).foregroundStyle(colors.primary)
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, dp(8))
        .frame(width: labelWidth, height: height, alignment: .leading)
        .contentShape(Rectangle())
    }

    private var strip: some View {
        let actionable = cells.filter { $0.intent != .none }
        let firstStart = actionable.first?.programme?.startMs
        let lastStart = actionable.last?.programme?.startMs
        return GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                    let x = geo.size.width * CGFloat(cell.from)
                    let w = max(0, geo.size.width * CGFloat(cell.width) - dp(2))
                    if interactive, cell.intent != .none, let programme = cell.programme {
                        GuideCellButton(cell: cell, width: w, height: geo.size.height - dp(4), focus: cellFocus) {
                            onCell(cell)
                        }
                        .onMoveCommand { direction in
                            if direction == .left, programme.startMs == firstStart { onEdge(-1) }
                            if direction == .right, programme.startMs == lastStart { onEdge(1) }
                        }
                        .onExitCommand(perform: onLeaveTimeline)
                        .onChange(of: cellFocus.wrappedValue) { _, v in if v == programme.startMs { onCellFocus(programme) } }
                        .offset(x: x, y: dp(2))
                    } else {
                        GuideCellView(cell: cell, focused: false, width: w, height: geo.size.height - dp(4))
                            .offset(x: x, y: dp(2))
                    }
                }
                if let nowFraction {
                    Rectangle().fill(colors.error).frame(width: dp(2), height: geo.size.height)
                        .offset(x: geo.size.width * CGFloat(nowFraction))
                        .allowsHitTesting(false)
                }
            }
        }
        .padding(.leading, dp(8))
        .focusSection()
    }
}

/// GuideCell: filler 40% BackgroundElevated, programme BackgroundElevated, airing Primary 20%, focused
/// solid Primary + 2dp focus ring with OnPrimary text; ⟲ before the title of a kept programme.
private struct GuideCellView: View {
    let cell: TvGuideCell
    let focused: Bool
    let width: CGFloat
    let height: CGFloat
    @Environment(\.nuvio) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(4))
        HStack(spacing: dp(4)) {
            if cell.replayBadge {
                Text("⟲").font(NuvioType.labelSmall.weight(.bold)).foregroundStyle(focused ? colors.onPrimary : colors.primary)
            }
            if let title = cell.programme?.title {
                Text(ui: title).font(NuvioType.labelMedium).lineLimit(1)
                    .foregroundStyle(focused ? colors.onPrimary : (cell.airing ? colors.textPrimary : colors.textSecondary))
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, dp(6))
        .frame(width: width, height: height, alignment: .leading)
        .background(shape.fill(fill))
        .overlay(shape.stroke(focused ? colors.focusRing : .clear, lineWidth: dp(2)))
        .clipShape(shape)
    }

    private var fill: Color {
        if focused { return colors.primary }
        if cell.airing { return colors.primary.opacity(0.2) }
        if cell.programme == nil { return colors.backgroundElevated.opacity(0.4) }
        return colors.backgroundElevated
    }
}

private struct GuideCellButton: View {
    let cell: TvGuideCell
    let width: CGFloat
    let height: CGFloat
    var focus: FocusState<Int64?>.Binding
    let action: () -> Void

    var body: some View {
        let start = cell.programme?.startMs ?? 0
        Button(action: action) {
            GuideCellView(cell: cell, focused: focus.wrappedValue == start, width: width, height: height)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused(focus, equals: start)
        .reportsFocus(focus.wrappedValue == start)
    }
}

/// GuideStartOverSheet: black 72% scrim, 560dp BackgroundElevated panel, "CHANNEL · 20:00–21:00 · 60 MIN",
/// title, three lines of synopsis, "⟲ Start over" (focused first) and "▶ Watch live".
private struct GuideStartOverSheet: View {
    let target: GuideSheetTarget
    let onStartOver: () -> Void
    let onWatchLive: () -> Void
    let onDismiss: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focus: Int?

    var body: some View {
        let p = target.programme
        ZStack {
            Color.black.opacity(0.72).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                Text("\(target.channel.name.uppercased()) · \(LiveGuideViewClock.clock(p.startMs))–\(LiveGuideViewClock.clock(p.endMs)) · \(TvGuideTimeline.shared.durationMinutes(programme: p)) MIN")
                    .font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary).lineLimit(1)
                Spacer().frame(height: dp(4))
                Text(p.title).font(NuvioType.titleLarge.weight(.bold)).foregroundStyle(colors.textPrimary).lineLimit(2)
                if !p.description_.isEmpty {
                    Spacer().frame(height: dp(8))
                    Text(p.description_).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(3)
                }
                Spacer().frame(height: dp(16))
                HStack(spacing: dp(12)) {
                    SheetButton(label: "⟲  Start over", primary: true, focused: focus == 0, action: onStartOver).focused($focus, equals: 0)
                    SheetButton(label: "\u{25B6}\u{FE0E}  Watch live", primary: false, focused: focus == 1, action: onWatchLive).focused($focus, equals: 1)
                }
            }
            .padding(dp(24))
            .frame(width: dp(560), alignment: .leading)
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundElevated))
        }
        .focusSection()
        .onExitCommand(perform: onDismiss)
        .onAppear { DispatchQueue.main.async { focus = 0 } }
    }
}

/// GuideSheetButton: focused solid Primary + focus ring; unfocused primary keeps a Primary outline.
private struct SheetButton: View {
    let label: String
    let primary: Bool
    let focused: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(8))
        Button(action: action) {
            Text(ui: label).font(NuvioType.titleSmall.weight(.bold)).lineLimit(1)
                .foregroundStyle(focused ? colors.onPrimary : colors.textPrimary)
                .padding(.horizontal, dp(16)).padding(.vertical, dp(8))
                .background(shape.fill(focused ? colors.primary : (primary ? colors.primary.opacity(0.22) : .clear)))
                .overlay(shape.stroke(focused ? colors.focusRing : (primary ? colors.primary : colors.border), lineWidth: dp(2)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .reportsFocus(focused)
    }
}

/// "Hide “Group”?" (NuvioDialog 460dp): Hide group / Cancel, Cancel focused so a stray OK can't hide.
private struct HideGroupDialog: View {
    let name: String
    let onHide: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            NuvioDialog(title: "Hide \u{201C}\(name)\u{201D}?",
                        subtitle: "Its channels leave the guide on all your devices. Bring it back any time in Settings \u{2192} Integrations \u{2192} IPTV \u{2192} this playlist \u{2192} Hidden channels & groups.",
                        width: dp(460)) {
                SettingsDialogButton(title: "Hide group", fullWidth: true, action: onHide)
                SettingsDialogButton(title: "Cancel", fullWidth: true, initialFocus: true, action: onCancel)
            }
            .focusSection()
        }
        .onExitCommand(perform: onCancel)
    }
}

private enum LiveGuideViewClock {
    static func clock(_ ms: Int64) -> String {
        let f = DateFormatter(); f.dateFormat = DateFormatter.dateFormat(fromTemplate: "jmm", options: 0, locale: .current)
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}
