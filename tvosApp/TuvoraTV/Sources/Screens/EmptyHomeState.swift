import SwiftUI
import TuvoraCore

/// What the empty Home offers, by situation (B193). Pure, so the choice is unit-testable.
enum EmptyHomeAction: Equatable {
    case retry, openIptv, setUpSources

    static func choose(hint: TvEmptyHomeHint, hasError: Bool) -> EmptyHomeAction {
        if hasError { return .retry }
        return hint == .playlistInIptvTab ? .openIptv : .setUpSources
    }
    var title: String {
        switch self {
        case .retry: return "Retry"
        case .openIptv: return "Open IPTV"
        case .setUpSources: return "Set up sources"
        }
    }
}

/// How long Home must stay empty before the empty state (and its focus-grabbing action) appears. A viewer who
/// HAS content must never see the action flash and take focus while rows are still arriving: the rows, the
/// continue-watching list and the collections come from separate streams that can lag one another.
enum EmptyHomeSettle {
    static let nanoseconds: UInt64 = 1_500_000_000
}

/// Simulator hook `-smokeEmptyHome` (DEBUG): Home shows as a fresh install with no sources does, whatever the
/// simulator profile holds (EmptyHomeFocusTests, B193). Never compiled into a release build.
enum EmptyHomeSmoke {
    static var enabled: Bool {
        #if DEBUG
        return AppArguments.list.contains("-smokeEmptyHome")
        #else
        return false
        #endif
    }
}

/// The empty-Home message with ONE focusable action, so a fresh install with no sources never leaves the remote
/// with nothing to focus (B193). `candidate` is the caller's "nothing to show and nothing loading"; the state
/// appears only after it has held for `EmptyHomeSettle`. `hasError` is a load error or failed rows: the action is Retry.
struct EmptyHomeState: View {
    let candidate: Bool
    let errorMessage: String?
    var failedRows = false
    @State private var settled = false

    var body: some View {
        // The settle task rides on an always-present (zero-height) view: a task on a Group whose only child is the
        // conditional below would never start while that child is absent.
        ZStack {
            Color.clear.frame(height: 0)
            if candidate && settled {
                // The smoke hook must not depend on the simulator profile's playlists.
                let hint = EmptyHomeSmoke.enabled ? TvEmptyHomeHint.addPlaylist : TvHome.shared.emptyHomeHint()
                let action = EmptyHomeAction.choose(hint: hint, hasError: !EmptyHomeSmoke.enabled && (errorMessage != nil || failedRows))
                NuvioStateMessage(title: StoreCopy.emptyHomeTitle(hint),
                                  message: errorMessage ?? StoreCopy.emptyHomeMessage(hint),
                                  actionTitle: action.title, actionId: "home.empty.action") {
                    switch action {
                    case .retry: TvHome.shared.retryFailed()
                    case .openIptv: DeepLinkCenter.shared.openIptvTab = true
                    case .setUpSources: DeepLinkCenter.shared.openSetupCode = true
                    }
                }
                .frame(height: dp(200))
            }
        }
        .task(id: candidate) {
            guard candidate else { settled = false; return }
            try? await Task.sleep(nanoseconds: EmptyHomeSettle.nanoseconds)
            if !Task.isCancelled { settled = true }
        }
    }
}
