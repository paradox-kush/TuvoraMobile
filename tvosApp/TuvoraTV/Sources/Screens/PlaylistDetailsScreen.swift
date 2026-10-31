import SwiftUI
import TuvoraCore

// The playlist details page (Step 2, build plan 3.6): a full-screen, two-pane page for EVERY playlist,
// replacing the old stack-of-rows dialog.
//
//   LEFT   read-only facts, never focusable: name, "Managed by X", days left + thin bar (or "Expiry not
//          reported by this provider"), connections, status, counts, and a lock note for server/login.
//   RIGHT  three labelled shelves of cards: <PROVIDER> (Contact), YOUR LIBRARY, REMOVE (Detach, Remove).
//          Up/Down changes shelf and lands on the card that shelf last had; Left/Right walks along a shelf;
//          the labels are plain text and are skipped by focus.
//
// What each card does lives in the shared Kotlin (TvPlaylistDetailsPolicy / TvShelfFocus); this file only
// draws it. Detach and Remove never fire on a press: they open a small dialog with Cancel focused first,
// whose destructive button needs OK HELD for two seconds (HoldConfirmDialog).

/// One card on the page: which shelf and where along it.
struct DetailCardKey: Hashable {
    let shelf: Int
    let index: Int
}

@MainActor
final class PlaylistDetailsModel: ObservableObject {
    let accountId: String
    private let session: TvPlaylistDetailsSession
    @Published var state: TvDetailsState
    /// UX179: the Refresh now card's words (built by the shared policy).
    @Published var refreshHint: String

    init(accountId: String) {
        self.accountId = accountId
        session = TvPlaylistDetailsSession(accountId: accountId)
        state = session.state.value
        refreshHint = session.refreshHint.value as String
    }

    func observe() async {
        for await next in session.state { state = next }
    }

    func observeRefresh() async {
        for await next in session.refreshHint { refreshHint = next }
    }

    func refreshNow() { session.refreshNow() }

    func close() { session.close() }

    func rematch() { session.rematch() }

    func detach() async -> Bool { (try? await session.detach())?.boolValue ?? false }
}

struct PlaylistDetailsPage: View {
    let accountId: String
    /// A persistent line under the title, such as "Starshare added your playlist" (never fades).
    var banner: String? = nil
    @ObservedObject var model: SettingsModel
    @ObservedObject var dialogs: SettingsDialogs
    @StateObject private var details: PlaylistDetailsModel
    @Environment(\.nuvio) private var colors
    @FocusState private var focus: DetailCardKey?
    @State private var memory = ShelfFocusBox()
    @State private var depth = 0
    @State private var returnTo: DetailCardKey?
    @State private var rematchStarted = false

    init(accountId: String, banner: String? = nil, model: SettingsModel, dialogs: SettingsDialogs) {
        self.accountId = accountId
        self.banner = banner
        self.model = model
        self.dialogs = dialogs
        _details = StateObject(wrappedValue: PlaylistDetailsModel(accountId: accountId))
    }

    private var account: XtreamAccount? { model.xtream?.accounts.first { $0.id == accountId } }

    var body: some View {
        let state = details.state
        HStack(alignment: .top, spacing: dp(36)) {
            facts(state)
                .frame(width: dp(300), alignment: .topLeading)
            shelves(state)
                .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .padding(.horizontal, 80).padding(.vertical, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(colors.background.ignoresSafeArea())
        .task { await details.observe() }
        .task { await details.observeRefresh() }
        .onAppear {
            depth = dialogs.stack.count
            memory.reset(sizes: state.shelves.map { $0.cards.count })
            let start = memory.start()
            DispatchQueue.main.async { focus = DetailCardKey(shelf: start.0, index: start.1) }
            smokeCard()
        }
        .onDisappear { details.close() }
        .onChange(of: state.shelves.map { $0.cards.count }) { _, sizes in memory.resize(sizes: sizes) }
        .onChange(of: dialogs.stack.count) { _, count in
            // Back on top after a dialog closed: the dialog host re-picks focus, so claim it for the card
            // that opened the dialog once that settles (a few tries, as the playlist form does).
            guard count == depth, let target = returnTo else { return }
            for delay in [0.2, 0.45, 0.8, 1.3] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus != target { focus = target } }
            }
        }
        .onChange(of: focus) { old, now in
            guard let now else { return }
            // Up/Down between shelves: the focus engine lands on the nearest card; send it to the card that
            // shelf last had instead (the first, the first time). Only a change of shelf is redirected.
            if let old, old.shelf != now.shelf,
               let target = memory.vertical(from: old.shelf, down: now.shelf > old.shelf),
               target.0 == now.shelf, target.1 != now.index {
                focus = DetailCardKey(shelf: target.0, index: target.1)
                return
            }
            memory.focused(now)
        }
    }

    // MARK: Left - read-only facts

    @ViewBuilder
    private func facts(_ state: TvDetailsState) -> some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            Text(verbatim: state.name).font(NuvioType.headlineLarge).foregroundStyle(colors.textPrimary)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
            if let ribbon = state.ribbon {
                HStack(spacing: dp(8)) {
                    Image("md_link").renderingMode(.template).resizable().frame(width: dp(16), height: dp(16))
                        .foregroundStyle(colors.secondary)
                    Text(verbatim: ribbon).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
                }
                .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
                .background(Capsule().fill(colors.secondary.opacity(0.14)))
            }
            if let address = state.addressLine {
                Text(verbatim: address).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary).lineLimit(1)
            }
            if let banner {
                HStack(spacing: dp(8)) {
                    Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(16), height: dp(16))
                        .foregroundStyle(colors.success)
                    Text(verbatim: banner).font(NuvioType.labelMedium).foregroundStyle(colors.textPrimary)
                }
                .accessibilityIdentifier("details.banner")
            }

            VStack(alignment: .leading, spacing: dp(8)) {
                if let line = state.expiryLine {
                    Text(verbatim: line).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
                        .accessibilityIdentifier("details.expiry")
                }
                if let bar = state.expiryBar?.floatValue {
                    ZStack(alignment: .leading) {
                        Capsule().fill(colors.border)
                        Capsule().fill(colors.secondary).frame(width: dp(220) * CGFloat(bar))
                    }
                    .frame(width: dp(220), height: dp(4))
                }
            }
            .padding(.top, dp(6))

            if let connections = state.connectionsLine {
                Text(verbatim: connections).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
            }
            if let status = state.statusLine {
                Text(verbatim: status).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !state.counts.isEmpty {
                VStack(alignment: .leading, spacing: dp(4)) {
                    ForEach(state.counts, id: \.label) { count in
                        HStack(spacing: dp(8)) {
                            Text(ui: count.label).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
                            Text(verbatim: count.value).font(NuvioType.bodySmall).foregroundStyle(colors.textPrimary)
                        }
                    }
                }
            }
            if let locked = state.lockedNote {
                HStack(alignment: .top, spacing: dp(8)) {
                    Image("md_lock").renderingMode(.template).resizable().frame(width: dp(16), height: dp(16))
                        .foregroundStyle(colors.textTertiary).padding(.top, dp(2))
                    VStack(alignment: .leading, spacing: dp(2)) {
                        Text("Server and login").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                        Text(verbatim: locked).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.top, dp(8))
                .accessibilityIdentifier("details.locked")
            }
            Spacer(minLength: 0)
        }
    }

    // MARK: Right - shelves

    @ViewBuilder
    private func shelves(_ state: TvDetailsState) -> some View {
        if !state.found {
            VStack(alignment: .leading, spacing: dp(12)) {
                Text("This playlist no longer exists.").font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary)
                SettingsDialogButton(title: "Back", primary: true, initialFocus: true) { dialogs.pop() }
            }
        } else {
            VStack(alignment: .leading, spacing: dp(14)) {
                ForEach(Array(state.shelves.enumerated()), id: \.offset) { si, shelf in
                    VStack(alignment: .leading, spacing: dp(6)) {
                        // The label is text, not a control: focus never stops on it.
                        Text(verbatim: shelf.title).font(NuvioType.labelLarge).tracking(dp(1))
                            .foregroundStyle(shelf.kind == .remove ? colors.error.opacity(0.9) : colors.textTertiary)
                            .padding(.leading, dp(6))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: dp(12)) {
                                ForEach(Array(shelf.cards.enumerated()), id: \.offset) { ci, card in
                                    let key = DetailCardKey(shelf: si, index: ci)
                                    Button { act(card, key: key, state: state) } label: {
                                        DetailCardView(card: card, state: state, account: account,
                                                       focused: focus == key, rematchStarted: rematchStarted,
                                                       refreshHint: details.refreshHint)
                                    }
                                    .buttonStyle(PlainNoChromeButtonStyle())
                                    .focused($focus, equals: key)
                                    .reportsFocus(focus == key)
                                    .accessibilityIdentifier("details.card.\(DetailCardCopy.id(card))")
                                }
                            }
                            .padding(.horizontal, dp(8)).padding(.vertical, dp(10))
                        }
                        .scrollClipDisabled()
                    }
                    .focusSection()
                }
                Spacer(minLength: 0)
            }
        }
    }

    /// Simulator smoke hook: `-smokeDetailsCard <contact|detach|remove>` opens that card's dialog (screenshots).
    private func smokeCard() {
        #if DEBUG
        let args = AppArguments.list
        guard let i = args.firstIndex(of: "-smokeDetailsCard"), i + 1 < args.count else { return }
        let card: TvDetailCard
        switch args[i + 1] {
        case "contact": card = .contact
        case "detach": card = .detach
        default: card = .remove
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { act(card, key: DetailCardKey(shelf: 0, index: 0), state: details.state) }
        #endif
    }

    // MARK: Actions

    private func act(_ card: TvDetailCard, key: DetailCardKey, state: TvDetailsState) {
        returnTo = key
        guard let account else { return }
        switch card {
        case .contact:
            dialogs.push(.custom(AnyView(ContactDialog(provider: state.providerName ?? "your provider",
                                                       contacts: state.contacts, dialogs: dialogs))))
        case .contentCategories:
            dialogs.push(.contentTypes(accountId))
        case .hidden:
            dialogs.push(.hiddenItems(account))
        case .toggleEnabled:
            TvPlaylists.shared.setEnabled(accountId: accountId, enabled: !account.enabled)
        case .editServer:
            TvPlaylists.shared.clearError()
            dialogs.push(.playlistForm(PlaylistFormModel(editing: account)))
        case .refresh:
            details.refreshNow()
        case .rematch:
            details.rematch()
            rematchStarted = true
        case .catchupContainer:
            TvIptvContentSettings.shared.setPreferM3u8(accountId: accountId, prefer: !account.catchUpPreferM3u8)
        case .catchupTime:
            dialogs.push(.picker(IptvOffsetPickers.catchUp(account)))
        case .guideOffset:
            dialogs.push(.picker(IptvOffsetPickers.guide(account)))
        case .detach:
            let copy = TvDestructiveCopy.shared.detach(playlistName: state.name, providerName: state.providerName)
            dialogs.push(.holdConfirm(HoldConfirmSpec(title: copy.title, message: copy.message, extra: copy.extra,
                                                      confirmLabel: "Detach", id: "detach") {
                await details.detach()
            }))
        case .remove:
            let copy = TvDestructiveCopy.shared.remove(playlistName: state.name, providerName: state.providerName, managed: state.managedBy != nil)
            dialogs.push(.holdConfirm(HoldConfirmSpec(title: copy.title, message: copy.message, extra: copy.extra,
                                                      confirmLabel: "Remove", id: "remove") {
                TvPlaylists.shared.remove(accountId: accountId)
                return true
            }))
        default: break
        }
    }
}

/// The Kotlin focus-memory object, held by reference so SwiftUI state can keep it.
final class ShelfFocusBox {
    private var inner = TvShelfFocus(sizes: [])

    func reset(sizes: [Int]) { inner = TvShelfFocus(sizes: sizes.map { KotlinInt(int: Int32($0)) }) }
    func resize(sizes: [Int]) { inner.resize(newSizes: sizes.map { KotlinInt(int: Int32($0)) }) }
    func start() -> (Int, Int) { let p = inner.start(); return (Int(p.shelf), Int(p.index)) }
    func focused(_ key: DetailCardKey) { inner.onFocused(shelf: Int32(key.shelf), index: Int32(key.index)) }
    func vertical(from shelf: Int, down: Bool) -> (Int, Int)? {
        guard let p = inner.vertical(from: Int32(shelf), down: down) else { return nil }
        return (Int(p.shelf), Int(p.index))
    }
}

// MARK: - Cards

/// The words on a card. Titles are the agreed ones ("Content & categories", "Hidden channels & groups",
/// "Disable"/"Enable", "Detach from X", "Remove playlist"); hints follow the phone's details page.
enum DetailCardCopy {
    static func id(_ card: TvDetailCard) -> String {
        switch card {
        case .contact: return "contact"
        case .contentCategories: return "content"
        case .hidden: return "hidden"
        case .toggleEnabled: return "toggle"
        case .refresh: return "refresh"
        case .editServer: return "edit"
        case .rematch: return "rematch"
        case .catchupContainer: return "catchupContainer"
        case .catchupTime: return "catchupTime"
        case .guideOffset: return "guideOffset"
        case .detach: return "detach"
        case .remove: return "remove"
        }
    }

    static func title(_ card: TvDetailCard, state: TvDetailsState, account: XtreamAccount?) -> String {
        switch card {
        case .contact: return L("Contact")
        case .contentCategories: return L("Content & categories")
        case .hidden: return L("Hidden channels & groups")
        case .toggleEnabled: return L((account?.enabled ?? state.enabled) ? "Disable" : "Enable")
        case .refresh: return L("Refresh now")
        case .editServer: return L("Edit URL / credentials")
        case .rematch: return L("Re-match catalog")
        case .catchupContainer: return L("Catch-up container")
        case .catchupTime: return L("Catch-up time correction")
        case .guideOffset: return L("Guide EPG offset")
        case .detach: return String(format: L("Detach from %@"), state.providerName ?? L("your provider"))
        case .remove: return L("Remove playlist")
        }
    }

    static func hint(_ card: TvDetailCard, state: TvDetailsState, account: XtreamAccount?, rematchStarted: Bool, refreshHint: String) -> String {
        switch card {
        case .contact: return state.contacts.map { $0.label }.joined(separator: " \u{00B7} ")
        case .contentCategories: return L("Choose what to load")
        case .hidden: return L("Bring back what you hid")
        case .toggleEnabled:
            return (account?.enabled ?? state.enabled) ? L("Hide it from Live TV, Movies and Series") : L("Use this playlist for Live TV, Movies and Series")
        case .refresh: return refreshHint
        case .editServer: return L("Change its server or login")
        case .rematch: return rematchStarted ? L("Checking your catalog again") : L("Look again for missing titles")
        case .catchupContainer: return account.map { $0.catchUpPreferM3u8 ? L("Prefer m3u8") : L("Prefer TS") } ?? ""
        case .catchupTime: return account.map { IptvOffsetPickers.catchUpLabel($0.catchUpTimeCorrectionMinutes) } ?? ""
        case .guideOffset: return account.map { IptvOffsetPickers.guideLabel($0.guideEpgCorrectionMinutes) } ?? ""
        case .detach: return L("Keep it, but stop updates")
        case .remove: return L("Delete it from this profile")
        }
    }

    static func icon(_ card: TvDetailCard) -> String {
        switch card {
        case .contact: return "md_phone_android"
        case .contentCategories: return "md_grid_view"
        case .hidden: return "md_history"
        case .toggleEnabled: return "md_check_circle"
        case .refresh: return "md_sync"
        case .editServer: return "md_vpn_key"
        case .rematch: return "md_sync"
        case .catchupContainer: return "md_history"
        case .catchupTime: return "md_fast_rewind"
        case .guideOffset: return "md_fast_forward"
        case .detach: return "md_link"
        case .remove: return "md_remove"
        }
    }

    static func destructive(_ card: TvDetailCard) -> Bool { card == .detach || card == .remove }
}

/// One card: warm dark fill, gold ring and a small lift on focus, one focus target per card. The card
/// is drawn here; the shelf makes it focusable.
private struct DetailCardView: View {
    let card: TvDetailCard
    let state: TvDetailsState
    let account: XtreamAccount?
    let focused: Bool
    let rematchStarted: Bool
    let refreshHint: String
    @Environment(\.nuvio) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: dp(14), style: .continuous)
        let destructive = DetailCardCopy.destructive(card)
        VStack(alignment: .leading, spacing: dp(8)) {
            Image(DetailCardCopy.icon(card)).renderingMode(.template).resizable().scaledToFit()
                .frame(width: dp(18), height: dp(18))
                .foregroundStyle(destructive ? colors.error : (focused ? colors.secondary : colors.textSecondary))
            Spacer(minLength: 0)
            Text(verbatim: DetailCardCopy.title(card, state: state, account: account))
                .font(NuvioType.titleMediumSemi).foregroundStyle(destructive && focused ? colors.error : colors.textPrimary)
                .lineLimit(2).multilineTextAlignment(.leading)
            Text(verbatim: DetailCardCopy.hint(card, state: state, account: account, rematchStarted: rematchStarted, refreshHint: refreshHint))
                .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(2).multilineTextAlignment(.leading)
        }
        .padding(dp(12))
        .frame(width: dp(160), height: dp(112), alignment: .topLeading)
        .background(shape.fill(focused ? colors.focusBackground : colors.backgroundCard))
        .overlay(shape.stroke(focused ? (destructive ? colors.error : colors.focusRing) : colors.border,
                              lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
        .scaleEffect(focused ? 1.05 : 1)
        .animation(NuvioTokens.Motion.fast, value: focused)
    }
}

// MARK: - Contact

/// Contact opens a sub-dialog of text + QR codes: Apple TV cannot open WhatsApp, Telegram or Mail, so each
/// contact the provider set is a code to scan with a phone, with its handle written beside it.
struct ContactDialog: View {
    let provider: String
    let contacts: [TvSetupContact]
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors

    var body: some View {
        NuvioDialog(title: String(format: L("Contact %@"), provider),
                    subtitle: "Scan a code with your phone to reach them.", width: dp(760)) {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: dp(16)), GridItem(.flexible(), spacing: dp(16))], spacing: dp(16)) {
                ForEach(contacts, id: \.kind) { contact in
                    HStack(spacing: dp(14)) {
                        if let image = QrCode.image(for: contact.url) {
                            Image(decorative: image, scale: 1).interpolation(.none).resizable()
                                .padding(dp(6)).frame(width: dp(104), height: dp(104))
                                .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
                        }
                        VStack(alignment: .leading, spacing: dp(2)) {
                            Text(ui: contact.label).font(NuvioType.titleSmall).foregroundStyle(colors.textPrimary)
                            Text(verbatim: contact.text).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(dp(10))
                    .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("contact.\(contact.kind)")
                }
            }
            SettingsDialogButton(title: "Close", primary: true, fullWidth: true, initialFocus: true) { dialogs.pop() }
                .accessibilityIdentifier("contact.close")
        }
    }
}

// MARK: - Hold to confirm

/// What a Detach/Remove confirmation says and does. [action] returns true when it worked (the dialog
/// then closes); a false answer keeps the dialog open with the reason.
struct HoldConfirmSpec {
    let title: String
    let message: String
    let extra: String?
    let confirmLabel: String
    let id: String
    let action: () async -> Bool
}

/// Detach and Remove open this: Cancel has focus first, and the destructive button fires only when OK is
/// HELD for two seconds (a ring fills while it is held; letting go early, or a quick press, does nothing).
struct HoldConfirmDialog: View {
    let spec: HoldConfirmSpec
    @ObservedObject var dialogs: SettingsDialogs
    @State private var working = false
    @State private var failed = false
    @Environment(\.nuvio) private var colors

    var body: some View {
        NuvioDialog(title: spec.title, subtitle: spec.message, width: dp(560)) {
            if let extra = spec.extra {
                Text(verbatim: extra).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
            }
            if failed {
                Text("Couldn't do that right now. Check your connection and try again.")
                    .font(NuvioType.bodySmall).foregroundStyle(colors.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            SettingsDialogButton(title: "Cancel", fullWidth: true, initialFocus: true) { dialogs.pop() }
                .accessibilityIdentifier("confirm.cancel")
            HoldToConfirmButton(label: spec.confirmLabel, working: working, identifier: "confirm.hold.\(spec.id)") {
                guard !working else { return }
                working = true
                failed = false
                Task { @MainActor in
                    let ok = await spec.action()
                    working = false
                    if ok { dialogs.pop() } else { failed = true }
                }
            }
        }
    }
}

/// The destructive button: a capsule with a ring that fills while OK is held. A quick press does nothing;
/// the press must last the shared policy's hold time (2 s) with the button focused. SwiftUI has no
/// long-press on tvOS, so the press itself is read by [HoldSelectSurface] (a UIKit focusable view laid
/// over the capsule); the drawing, and the ring's arithmetic (TvHoldToConfirm), stay in SwiftUI/Kotlin.
struct HoldToConfirmButton: View {
    let label: String
    var working = false
    /// Accessibility identifier of the focusable surface (UITests find the button by it).
    var identifier = ""
    let onConfirm: () -> Void
    @Environment(\.nuvio) private var colors
    @State private var focused = false
    @State private var pressStart: Date?
    /// OK was let go before the ring filled: the hint says to keep holding (until the next press; nothing fades).
    @State private var releasedEarly = false

    private let holdSeconds = Double(TvHoldToConfirm.shared.HOLD_MS) / 1000

    private var hint: String { releasedEarly ? "Keep holding OK until the ring is full" : "Hold OK for 2 seconds" }

    var body: some View {
        let shape = Capsule()
        TimelineView(.animation(paused: pressStart == nil)) { timeline in
            let heldMs = pressStart.map { Int64(max(0, timeline.date.timeIntervalSince($0)) * 1000) } ?? 0
            let progress = CGFloat(TvHoldToConfirm.shared.progress(heldMs: heldMs))
            HStack(spacing: dp(12)) {
                ZStack {
                    Circle().stroke(colors.error.opacity(0.35), lineWidth: dp(3))
                    Circle().trim(from: 0, to: progress).stroke(colors.error, style: StrokeStyle(lineWidth: dp(3), lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                .frame(width: dp(22), height: dp(22))
                VStack(alignment: .leading, spacing: dp(1)) {
                    Text(ui: working ? "Working\u{2026}" : label).font(NuvioType.labelLarge)
                        .foregroundStyle(focused ? Color.white : colors.textPrimary)
                    Text(ui: hint).font(NuvioType.bodySmall)
                        .foregroundStyle(focused ? Color.white.opacity(0.8) : colors.textTertiary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, dp(18)).padding(.vertical, dp(10))
            .frame(maxWidth: .infinity, minHeight: dp(54), alignment: .leading)
            .background(
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        shape.fill(Color(argb: 0xFF4A2323))
                        shape.fill(colors.error.opacity(focused ? 0.7 : 0.45)).frame(width: proxy.size.width * progress)
                    }
                }
            )
            .clipShape(shape)
            .overlay(shape.stroke(focused ? colors.error : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .scaleEffect(focused ? 1.02 : 1)
        }
        .accessibilityHidden(true)
        .overlay {
            HoldSelectSurface(holdSeconds: holdSeconds, label: label, hint: hint, identifier: identifier,
                              onFocus: { focused = $0; if !$0 { pressStart = nil } },
                              onPress: { pressing in
                                  if pressing { releasedEarly = false; pressStart = Date() }
                                  else {
                                      // Released (or lost focus) before the hold was reached: say to keep holding.
                                      if pressStart != nil, focused { releasedEarly = true }
                                      pressStart = nil
                                  }
                              },
                              onConfirm: { pressStart = nil; releasedEarly = false; onConfirm() })
        }
        .onChange(of: focused) { _, now in if now { ContentFocusActivity.touched() } }
    }
}

/// A transparent, focusable UIKit view that reports how long OK (select) is held. It confirms only when
/// the press lasts [holdSeconds] while this view is focused; releasing earlier, or a quick press, only
/// reports "released". Menu and the directions are left to the focus engine.
struct HoldSelectSurface: UIViewRepresentable {
    let holdSeconds: TimeInterval
    let label: String
    let hint: String
    let identifier: String
    let onFocus: (Bool) -> Void
    let onPress: (Bool) -> Void
    let onConfirm: () -> Void

    func makeUIView(context: Context) -> HoldSelectView { HoldSelectView() }

    func updateUIView(_ view: HoldSelectView, context: Context) {
        view.holdSeconds = holdSeconds
        view.onFocus = onFocus
        view.onPress = onPress
        view.onConfirm = onConfirm
        view.accessibilityLabel = label
        view.accessibilityIdentifier = identifier
        // VoiceOver reads the hold instruction (and cannot shortcut it: there is no accessibility activate here).
        view.accessibilityHint = hint
        view.accessibilityValue = hint
    }
}

final class HoldSelectView: UIView {
    var holdSeconds: TimeInterval = 2
    var onFocus: ((Bool) -> Void)?
    var onPress: ((Bool) -> Void)?
    var onConfirm: (() -> Void)?
    private var timer: Timer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = .button
        accessibilityIdentifier = ""
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var canBecomeFocused: Bool { true }

    override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
        let now = context.nextFocusedView === self
        onFocus?(now)
        if !now { cancel() }
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard presses.contains(where: { $0.type == .select }) else { return super.pressesBegan(presses, with: event) }
        cancel()
        onPress?(true)
        timer = Timer.scheduledTimer(withTimeInterval: holdSeconds, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.timer = nil
            self.onPress?(false)
            self.onConfirm?()
        }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard presses.contains(where: { $0.type == .select }) else { return super.pressesEnded(presses, with: event) }
        cancel()
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard presses.contains(where: { $0.type == .select }) else { return super.pressesCancelled(presses, with: event) }
        cancel()
    }

    private func cancel() {
        guard timer != nil else { return }
        timer?.invalidate()
        timer = nil
        onPress?(false)
    }
}
