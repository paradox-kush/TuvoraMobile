import SwiftUI
import TuvoraCore

// Watch party on Apple TV. Everything that decides anything is shared Kotlin: the phone's PartyRepository /
// PartySync / PartyClock (features/party) behind the TvParty facade, TvPartyViews (what each state shows) and
// TvPartySync (the player's half). These views only draw TvPartyView and forward presses.

/// The shared party, observed for SwiftUI.
@MainActor
final class PartyModel: ObservableObject {
    @Published var view: TvPartyView = TvParty.shared.state.value

    func observe() async {
        for await next in TvParty.shared.state { view = next }
    }
}

/// A member's status in the viewer's language (the phone's party_status_* copy).
func partyStatusLabel(_ row: TvPartyMemberRow) -> String {
    if let behind = row.behindMs?.int64Value {
        return String(format: L("%@ behind"), TvParty.shared.clock(ms: behind))
    }
    switch row.state {
    case .watching: return L("Watching")
    case .buffering: return L("Buffering")
    case .loading: return L("Loading")
    case .choosing: return L("Choosing a source")
    case .elsewhere: return L("Watching something else")
    case .offline: return L("Offline")
    }
}

/// A round initial in the member's avatar colour (the phone draws the same fallback when there is no photo).
struct PartyAvatar: View {
    let row: TvPartyMemberRow
    var size: CGFloat = dp(34)
    @Environment(\.nuvio) private var colors

    var body: some View {
        Text(verbatim: row.initial).font(.system(size: size * 0.45, weight: .bold)).foregroundStyle(Color.white)
            .frame(width: size, height: size)
            .background(Circle().fill(Color(hex: row.avatarColor) ?? colors.secondary.opacity(0.7)))
            .overlay(Circle().stroke(row.isHost ? colors.secondary : Color.clear, lineWidth: NuvioTokens.Stroke.hairline * 2))
    }
}

private extension Color {
    init?(hex: String?) {
        guard let hex, hex.count == 7, hex.hasPrefix("#"), let value = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        self.init(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }
}

// MARK: - Settings › Watch party

/// Settings › Watch party (NuvioTV's Watch party category): join with a code, or the party you are in.
struct WatchPartySettingsDetail: View {
    @ObservedObject var party: PartyModel
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: dp(14)) {
            SettingsDetailHeader(title: "Watch party", subtitle: "Join friends with a party code")
            let view = party.view
            if !view.available {
                SettingsGroupCard { SettingsHelperText(text: "Watch parties aren't available right now.") }
            } else if !view.canUse {
                SettingsGroupCard { SettingsHelperText(text: "Sign in to your Tuvora account to use watch parties.") }
                    .accessibilityIdentifier("party.signIn")
            } else if view.inParty {
                PartyDetailsContent(view: view, dialogs: dialogs)
            } else {
                PartyJoinContent(busy: view.busy)
            }
        }
    }
}

/// Join: six boxes, the code keypad, Delete / Join, the system field (iPhone keyboard), and the party's
/// preview once six characters are in.
private struct PartyJoinContent: View {
    let busy: Bool
    @Environment(\.nuvio) private var colors
    @ObservedObject private var links = DeepLinkCenter.shared
    @State private var code = ""
    @State private var card: TvPartyPreviewCard?
    @State private var problem: String?
    @State private var lookupTask: Task<Void, Never>?

    var body: some View {
        SettingsGroupCard(title: "Join a watch party", subtitle: "Enter the 6-character code your friend shared.") {
            HStack(spacing: dp(6)) {
                ForEach(Array(TvParty.shared.boxes(code: code).enumerated()), id: \.offset) { index, text in
                    Text(verbatim: text).font(NuvioType.inter(22, .bold)).foregroundStyle(colors.textPrimary)
                        .frame(width: dp(34), height: dp(42))
                        .background(RoundedRectangle(cornerRadius: dp(6)).fill(colors.background))
                        .overlay(RoundedRectangle(cornerRadius: dp(6))
                            .stroke(index == code.count ? colors.focusRing.opacity(0.8) : colors.border, lineWidth: NuvioTokens.Stroke.hairline))
                    if index == 2 { Spacer().frame(width: dp(8)) }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(code)
            .accessibilityIdentifier("party.code")
            if let card { previewCard(card) }
            if let problem {
                Text(verbatim: problem).font(NuvioType.bodyMedium).foregroundStyle(colors.error)
                    .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("party.problem")
            }
            VStack(alignment: .leading, spacing: dp(6)) {
                ForEach(Array(TvParty.shared.keypadRows().enumerated()), id: \.offset) { _, row in
                    HStack(spacing: dp(6)) {
                        ForEach(row, id: \.self) { key in
                            KeypadKey(label: key) { set(TvParty.shared.append(code: code, key: key)) }
                                .accessibilityIdentifier("party.key.\(key)")
                        }
                    }
                }
            }
            .focusSection()
            HStack(spacing: dp(10)) {
                SettingsDialogButton(title: "Delete") { set(TvParty.shared.backspace(code: code)) }
                    .accessibilityIdentifier("party.delete")
                ContinueButton(title: busy ? "Joining\u{2026}" : "Join", enabled: TvParty.shared.canJoin(code: code) && !busy) { join() }
                    .accessibilityIdentifier("party.join")
            }
            .focusSection()
            SettingsTextField(label: "Or use the iPhone keyboard", hint: "ABC DEF",
                              text: Binding(get: { code }, set: { set(TvParty.shared.cleanCode(raw: $0)) }),
                              onSubmit: { join() }, id: "party.field")
            SettingsHelperText(text: "To host, start a party from the player.")
        }
        .onAppear(perform: takeLinkCode)
        .onChange(of: links.partyCode) { _, _ in takeLinkCode() }
        .onDisappear { lookupTask?.cancel() }
    }

    private func previewCard(_ card: TvPartyPreviewCard) -> some View {
        HStack(alignment: .top, spacing: dp(12)) {
            CachedPosterArtwork(urlString: card.poster, width: dp(54), height: dp(80), maximumWidth: dp(108)) { colors.background }
                .frame(width: dp(54), height: dp(80)).clipShape(RoundedRectangle(cornerRadius: dp(6)))
            VStack(alignment: .leading, spacing: dp(4)) {
                Text(verbatim: card.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                if let detail = card.detail { Text(verbatim: detail).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(1) }
                if let host = card.hostName {
                    Text(verbatim: String(format: L("Hosted by %@"), host)).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                }
                Text(verbatim: String(format: L("%1$d of %2$d seats"), card.members, card.maxMembers))
                    .font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            }
        }
        .accessibilityIdentifier("party.preview")
    }

    private func set(_ next: String) {
        guard next != code else { return }
        code = next
        problem = nil
        card = nil
        lookupTask?.cancel()
        guard TvParty.shared.canJoin(code: next) else { return }
        lookupTask = Task {
            guard let lookup = try? await TvParty.shared.lookup(code: next), !Task.isCancelled, code == next else { return }
            card = lookup.card
            problem = lookup.error.map { TvParty.shared.message(error: $0) }
        }
    }

    private func join() {
        guard TvParty.shared.canJoin(code: code), !busy else { return }
        let target = code
        Task {
            guard let result = try? await TvParty.shared.join(code: target) else {
                problem = L("Couldn't reach the watch party. Check your connection.")
                return
            }
            if !result.joined { problem = result.message }
        }
    }

    /// A party link (tuvora://party/<code>) brought the viewer here: the code is typed in for them.
    private func takeLinkCode() {
        guard let linked = links.partyCode else { return }
        links.partyCode = nil
        set(linked)
    }
}

/// The party you are in: code and invite, what it is watching, who is in it, and the host's controls.
private struct PartyDetailsContent: View {
    let view: TvPartyView
    let dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors

    var body: some View {
        SettingsGroupCard {
            HStack(alignment: .top, spacing: dp(18)) {
                VStack(alignment: .leading, spacing: dp(6)) {
                    Text(ui: view.isHost ? "Watch party" : "You're in").font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
                    if !view.isHost, let host = view.hostName {
                        Text(verbatim: String(format: L("Hosted by %@"), host)).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    }
                    Text("Party code").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).padding(.top, dp(6))
                    Text(verbatim: view.code).font(NuvioType.inter(34, .bold)).tracking(dp(2)).foregroundStyle(colors.textPrimary)
                        .accessibilityIdentifier("party.activeCode")
                    Text(verbatim: String(format: L("%1$d of %2$d seats"), view.seatsUsed, view.seatsMax))
                        .font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
                    if !view.connected {
                        Text("Reconnecting").font(NuvioType.labelMedium).foregroundStyle(colors.secondary).accessibilityIdentifier("party.reconnecting")
                    }
                }
                Spacer(minLength: 0)
                if let image = QrCode.image(for: view.inviteLink) {
                    VStack(spacing: dp(6)) {
                        Image(decorative: image, scale: 1).interpolation(.none).resizable()
                            .padding(dp(6)).frame(width: dp(110), height: dp(110))
                            .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
                            .accessibilityLabel("Party invite QR code")
                        Text("Invite a friend").font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                    }
                }
            }
        }
        if let title = view.mediaTitle {
            SettingsGroupCard {
                Text(verbatim: String(format: L("The party is watching %@"), title)).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary)
                if let detail = view.mediaDetail { Text(verbatim: detail).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary) }
                SettingsActionRow(title: "Watch with the party", leadingIcon: "md_play_arrow") { watchWithParty() }
                    .accessibilityIdentifier("party.watch")
            }
        }
        SettingsGroupCard {
            ForEach(view.members, id: \.id) { row in
                PartyMemberLine(row: row, removable: view.isHost && !row.isYou) {
                    dialogs.push(.custom(AnyView(PartyConfirmDialog(
                        title: String(format: L("Remove %@?"), row.name), message: "They won't be able to join this party again.",
                        confirm: "Remove", dialogs: dialogs) { TvParty.shared.removeMember(memberId: row.id) })))
                }
            }
        }
        if view.canToggleHostOnly {
            SettingsToggleRow(title: "Only I can control playback", subtitle: "Others can still pause or rewind for themselves.",
                              isOn: view.hostOnly) { TvParty.shared.setHostOnly(enabled: !view.hostOnly) }
                .accessibilityIdentifier("party.hostOnly")
        } else if view.hostOnly {
            SettingsHelperText(text: "Only the host can control playback")
        }
        HStack(spacing: dp(10)) {
            SettingsDialogButton(title: "Leave party") { TvParty.shared.leave() }
                .accessibilityIdentifier("party.leave")
            if view.canEnd {
                SettingsDialogButton(title: "End for everyone", destructive: true) {
                    dialogs.push(.custom(AnyView(PartyConfirmDialog(
                        title: "End the watch party?", message: "Everyone will be disconnected from the party.",
                        confirm: "End for everyone", dialogs: dialogs) { TvParty.shared.end() })))
                }
                .accessibilityIdentifier("party.end")
            }
        }
        .focusSection()
    }

    /// Opens the party's title the way a Top Shelf "Play" does: its sources, then the player.
    private func watchWithParty() {
        guard let type = view.mediaType, let id = view.mediaId else { return }
        DeepLinkCenter.shared.pending = DeepLink(type: type, id: id, videoId: view.mediaVideoId, play: true)
    }
}

/// One member: avatar, name with Host / You, status. Focusable (to remove them) only for the host.
private struct PartyMemberLine: View {
    let row: TvPartyMemberRow
    let removable: Bool
    let onRemove: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let content = HStack(spacing: dp(12)) {
            PartyAvatar(row: row)
            VStack(alignment: .leading, spacing: dp(2)) {
                HStack(spacing: dp(6)) {
                    Text(verbatim: row.name).font(NuvioType.bodyLarge).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if row.isHost { tag("Host") }
                    if row.isYou { tag("You") }
                }
                Text(verbatim: partyStatusLabel(row)).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
            }
            Spacer(minLength: 0)
            if removable { Image("md_remove").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18)).foregroundStyle(colors.textTertiary) }
        }
        .padding(.horizontal, dp(14)).padding(.vertical, dp(8))
        if removable {
            Button(action: onRemove) {
                content.background(Capsule().fill(colors.background))
                    .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            }
            .buttonStyle(PlainNoChromeButtonStyle())
            .focused($focused)
            .reportsFocus(focused)
            .accessibilityIdentifier("party.member.\(row.name)")
        } else {
            content.accessibilityIdentifier("party.member.\(row.name)")
        }
    }

    private func tag(_ text: String) -> some View {
        Text(ui: text).font(NuvioType.labelSmall).foregroundStyle(colors.secondary)
            .padding(.horizontal, dp(6)).padding(.vertical, dp(1))
            .overlay(Capsule().stroke(colors.secondary.opacity(0.6), lineWidth: NuvioTokens.Stroke.hairline))
    }
}

/// Cancel first; the destructive button acts and closes.
struct PartyConfirmDialog: View {
    let title: String
    let message: String
    let confirm: String
    let dialogs: SettingsDialogs?
    var close: (() -> Void)? = nil
    let action: () -> Void

    var body: some View {
        NuvioDialog(title: title, subtitle: message) {
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: "Cancel", initialFocus: true) { dismiss() }
                SettingsDialogButton(title: confirm, destructive: true) { dismiss(); action() }
                    .accessibilityIdentifier("party.confirm")
            }
        }
    }

    private func dismiss() { if let close { close() } else { dialogs?.pop() } }
}

// MARK: - Player

/// The player's party line at the top: who is in the party and what is going on ("Waiting for Ana",
/// "Ana paused", "2:05 behind"). Shown with the controls, or by itself whenever there is something to say.
struct PartyPlayerPill: View {
    let view: TvPartyView
    let line: String?
    let message: String?
    let controlsVisible: Bool

    var body: some View {
        let text = message ?? line
        if view.inParty && (controlsVisible || text != nil) {
            HStack(spacing: dp(10)) {
                HStack(spacing: -dp(8)) {
                    ForEach(view.members.prefix(4), id: \.id) { row in PartyAvatar(row: row, size: dp(28)) }
                }
                if let text {
                    Text(verbatim: text).font(NuvioType.labelLargeSemi).foregroundStyle(Color.white).lineLimit(1)
                        .accessibilityIdentifier("player.party.line")
                }
            }
            .padding(.horizontal, dp(12)).padding(.vertical, dp(6))
            .navigationGlass(in: Capsule())
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.top, dp(28))
            .allowsHitTesting(false)
            .transition(.opacity)
        }
    }
}

/// The player's Watch party panel: start a party for this title, or the party you are in with the actions
/// that make sense from the player (switch the party here, go watch what the party watches, catch up, leave).
struct PartyPlayerPanel: View {
    let session: TvPlayerSession
    let view: TvPartyView
    let onClose: () -> Void
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var starting = false
    @State private var problem: String?
    @State private var confirmEnd = false

    var body: some View {
        HStack {
            Spacer()
            ZStack {
                VStack(alignment: .leading, spacing: dp(12)) {
                    Text(ui: view.inParty ? "Watch party" : "Watch together").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                    if view.inParty { inParty } else { start }
                    if let problem {
                        Text(verbatim: problem).font(NuvioType.bodyMedium).foregroundStyle(colors.error).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .disabled(confirmEnd)
                if confirmEnd {
                    PartyConfirmDialog(title: "End the watch party?", message: "Everyone will be disconnected from the party.",
                                       confirm: "End for everyone", dialogs: nil, close: { confirmEnd = false }) {
                        TvParty.shared.end(); onClose()
                    }
                    .scaleEffect(0.9)
                }
            }
            .padding(dp(24))
            .frame(width: dp(520))
            .frame(maxHeight: .infinity)
            .navigationGlass(in: UnevenRoundedRectangle(topLeadingRadius: dp(16), bottomLeadingRadius: dp(16)))
            .focusSection()
        }
        .ignoresSafeArea()
        .accessibilityIdentifier("player.party.panel")
    }

    @ViewBuilder
    private var start: some View {
        Text("Invite your friends. Everyone plays from their own sources and stays in sync.")
            .font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).fixedSize(horizontal: false, vertical: true)
        if !view.available {
            SettingsHelperText(text: "Watch parties aren't available right now.")
        } else if !view.canUse {
            SettingsHelperText(text: "Sign in to your Tuvora account to use watch parties.")
        } else {
            SettingsActionRow(title: starting ? L("Starting\u{2026}") : "Start watch party", showChevron: false, leadingIcon: "md_people") {
                guard !starting else { return }
                starting = true
                Task {
                    let failure = try? await TvParty.shared.host(session: session)
                    starting = false
                    problem = failure ?? nil
                }
            }
            .accessibilityIdentifier("player.party.start")
            SettingsHelperText(text: "Watch parties are experimental and may not work as expected.")
        }
    }

    @ViewBuilder
    private var inParty: some View {
        HStack(spacing: dp(14)) {
            VStack(alignment: .leading, spacing: dp(4)) {
                Text("Party code").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                Text(verbatim: view.code).font(NuvioType.inter(30, .bold)).tracking(dp(2)).foregroundStyle(colors.textPrimary)
                    .accessibilityIdentifier("player.party.code")
                Text(verbatim: String(format: L("%1$d of %2$d seats"), view.seatsUsed, view.seatsMax))
                    .font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            }
            Spacer(minLength: 0)
            if let image = QrCode.image(for: view.inviteLink) {
                Image(decorative: image, scale: 1).interpolation(.none).resizable()
                    .padding(dp(5)).frame(width: dp(88), height: dp(88))
                    .background(RoundedRectangle(cornerRadius: dp(8)).fill(Color.white))
                    .accessibilityLabel("Party invite QR code")
            }
        }
        ScrollView {
            VStack(alignment: .leading, spacing: dp(8)) {
                ForEach(view.members, id: \.id) { row in
                    HStack(spacing: dp(10)) {
                        PartyAvatar(row: row, size: dp(28))
                        Text(verbatim: row.name).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                        Spacer(minLength: 0)
                        Text(verbatim: partyStatusLabel(row)).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
                    }
                }
            }
        }
        .frame(maxHeight: dp(150))
        let here = TvParty.shared.playsSessionTitle(session: session)
        if !here, view.isHost {
            SettingsActionRow(title: String(format: L("Switch the party to %@"), session.title), showChevron: false, leadingIcon: "md_sync") {
                TvParty.shared.switchTo(session: session); onClose()
            }
            .accessibilityIdentifier("player.party.switch")
        } else if !here, let type = view.mediaType, let id = view.mediaId {
            SettingsActionRow(title: "Watch with the party", subtitle: view.mediaTitle.map { String(format: L("The party is watching %@"), $0) },
                              showChevron: false, leadingIcon: "md_play_arrow") {
                Task {
                    guard let meta = try? await MetaDetailsRepository.shared.fetch(type: type, id: id, cacheResult: true) else { return }
                    playback.openSources(meta: meta, video: meta.videos.first { $0.id == view.mediaVideoId })
                }
            }
            .accessibilityIdentifier("player.party.watch")
        }
        if session.party.isBehind {
            SettingsActionRow(title: "Catch up", showChevron: false, leadingIcon: "md_fast_forward") {
                session.party.catchUp(); onClose()
            }
            .accessibilityIdentifier("player.party.catchUp")
        }
        if view.canToggleHostOnly {
            SettingsToggleRow(title: "Only I can control playback", subtitle: "Others can still pause or rewind for themselves.",
                              isOn: view.hostOnly) { TvParty.shared.setHostOnly(enabled: !view.hostOnly) }
        } else if view.hostOnly {
            SettingsHelperText(text: "Only the host can control playback")
        }
        HStack(spacing: dp(10)) {
            SettingsDialogButton(title: "Leave party") { TvParty.shared.leave(); onClose() }
                .accessibilityIdentifier("player.party.leave")
            if view.canEnd {
                SettingsDialogButton(title: "End for everyone", destructive: true) { confirmEnd = true }
            }
        }
    }
}
