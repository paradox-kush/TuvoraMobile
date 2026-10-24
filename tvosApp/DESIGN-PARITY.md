# Apple TV UI — design parity reference

The Apple TV app **looks like Tuvora's Android TV app (NuvioTV) and behaves like tvOS**.
Read this before building or changing any screen in `tvosApp/`.

## 1. Source of truth
- **Look:** NuvioTV (`/Users/kush-mac/Documents/projects/nuvio/NuvioTV/app/src/main/java/com/nuvio/tv/ui/…`).
  Translate its layout/metrics/states screen by screen. NuvioTV is laid out on a 960×540 dp canvas;
  Apple TV is 1920×1080 pt → every dp/sp goes through `dp()` (×2). Tokens are already translated in
  `TuvoraTV/Sources/UI/NuvioTheme.swift` (palette, spacing, radii, strokes, motion, Inter type scale) —
  use `@Environment(\.nuvio) var colors`, `NuvioType.*`, `NuvioTokens.*`. Never hardcode a colour.
- **Components:** reuse `UI/NuvioComponents.swift` (HubChip, NuvioPosterCard, NuvioShimmer,
  NuvioShelfHeader, SeeAllButton, NuvioStateMessage, ImdbBadge), `UI/NuvioDialogs.swift`
  (NuvioDialog, SettingsActionRow), `Screens/TitleDetailsScreen.swift` (PlayPill, CircleIconButton,
  DetailBackdrop). Add a new shared component there only when two screens need it.
- **Strings:** use NuvioTV's English wording (`NuvioTV/app/src/main/res/values/strings.xml`) — never invent copy.
- **Assets:** `TuvoraTV/Assets.xcassets` (NuvioTV raw SVGs, wordmark, Material icons `md_*`). New
  Material icons: fetch `https://raw.githubusercontent.com/google/material-design-icons/master/src/<cat>/<name>/materialicons/24px.svg`.

## 2. tvOS behaviour (Apple HIG, verified 2026-09-28)
- Safe area: 80 pt sides, 60 pt top/bottom. Text never below 23 pt (`NuvioType.inter` clamps).
- Focus: every focusable shows NuvioTV's focus treatment (ring/fill per component) — focus must be
  unmistakable. Artwork cards also get `.hoverEffect(.highlight)`; shelves use `.scrollClipDisabled()`
  and `.focusSection()`. Every focusable calls `.reportsFocus(focused)` (the shell uses it to detect the
  content's left edge and open the sidebar).
- Menu/Back goes to the parent (the shell maps Menu at a section root to "open sidebar"). Use
  `.onExitCommand` only to close an overlay/panel you opened.
- **Liquid Glass only on the navigation/control layer** (sidebar, player controls, panels, dialogs),
  never on content. Use `.navigationGlass(in: shape)` (glass on tvOS 26, material fallback).
- Text entry: native tvOS keyboard (`TextField`/`SecureField`/`.searchable`), never a custom keyboard.
- Buttons use `.buttonStyle(PlainNoChromeButtonStyle())` + our own focus visuals (no default tvOS chrome).

## 3. NuvioTV spec digest (dp; ×2 in code)
- Sidebar: see `Screens/MainShell.swift` (Modern floating glass pill, NuvioTV ModernSidebarBlurPanel).
- Shelves: header titleMedium SemiBold at 52 gutter, 14 below; row gap 24; item gap 12.
- Poster pref 126×189 (radius 12). Hub portrait ×0.9072; Modern portrait W×0.84×1.08 / H×0.9072.
- Settings (CLASSIC): screen padding 32; workspace BackgroundElevated radius 28 + 1dp Border, padding 20;
  220dp category rail + 16 gap + detail pane. Rail button 56 tall pill: selected BackgroundCard + 1dp
  ring + 3×16 Secondary tick; focused 2dp ring; 18 icon; titleMedium. SettingsActionRow: see component.
  Detail header headlineMedium SemiBold + 28×3 Secondary bar. Group card BackgroundCard radius 18 pad 14.
- NuvioDialog: 520 wide, BackgroundElevated, radius 16, 1dp Border, padding 24.
- Profile selection (`ui/screens/profile/ProfileSelectionScreen.kt`): bg vertical gradient
  lerp(#1E1E1E, avatar,.3) → lerp(#121212, avatar,.14)@.42 → #121212 + left wash avatar 26%→8%@.45→0@.72,
  520 ms; wordmark 44; 28 gap; "Who's watching?" 44sp Bold; subtitle 18sp Medium TextSecondary; cards
  152 wide gap 28; avatar 96→102 in ring 114→122, ring 1→3dp in the profile theme focus colour, scale
  1.04 / 210 ms; name 17sp; PIN badge 26 circle #FFB300.
- PIN: boxes 118 gap 14 radius 2; idle hairline white 72%; active 2dp FocusRing; filled white 7% + 16 dot;
  error #E35D5D on #311818 76%. Strings profile_pin_* in NuvioTV strings.xml.
- QR sign-in (`ui/screens/account/AuthQrSignInScreen.kt`): black bg; left wordmark 60, headline 40/45sp,
  body 17/26 #969CA3; right pane 460 wide white 2.2% + 1dp white 7% border; QR 206 on white radius 8;
  buttons radius 16 white 5% + 9% border; primary white/black, focused #E9DFFF.
- Search/Library/Sports: follow the NuvioTV screens (`ui/screens/search`, `ui/screens/library`,
  `ui/screens/radar/SportsHubScreen.kt`); LIVE chip = #E4572E 16% fill, 50% hairline, 6 dot, labelSmall Bold.

## 4. Kotlin access
Swift sees only PUBLIC Kotlin. Most repositories are public (`SearchRepository`, `LibraryRepository`,
`PlayerSettingsRepository`, `XtreamRepository`, `AddonRepository`, `ProfileRepository`, radar repos…).
For internal logic add a small public facade in `tvosCore/src/tvosMain/kotlin/com/tuvora/tvos/screens/`
(same compilation module as the shared code, so internals are reachable) — see TvHome.kt / TvTitle.kt.
Pure decisions go in `tvosCore/src/commonMain/.../tvos/…` with tests in commonTest.


## 5. Non-player parity, October 2026

Native tvOS now exposes device-source choices and saved setup recovery, guide channel search,
synced custom guide categories, collection/folder authoring, Home row controls and privacy consent.
These screens reuse the shared repositories and policies; playback remains owned by the native
player. Device-source invalidation has one process owner, with refresh on foreground rather than a
new polling loop. The existing backend setup platform is `tv`.

Dialogs must instantiate input content once. Duplicating a TextField inside ViewThatFits candidates
can leave the hidden field owning keyboard focus; NuvioDialog measures one scrollable content tree.

`AndroidParityTests` exercises local source persistence after relaunch, guide search keyboard and
Back behavior, custom category reachability, collection/folder save, Home settings dismissal and
privacy reachability. The guide tests require `python3 tvosApp/Scripts/parity-guide-fixture.py`
from the repository root on loopback port 18124. Use a disposable simulator: these acceptance
checks intentionally create local guest data. No production source credentials are needed.

The follow-up native pass adds TMDB presets/discovery and all 18 shared filter fields, source sorting,
catalog genres, collection backdrop editing, title section visibility, overall/episode rating controls,
list or horizontal episodes, spoiler protection and solid custom accent authoring. Filter edits are a
local draft until Apply; collection edits remain a draft until Save. Invalid input preserves the last
valid value. Genre lists load on demand, with explicit retry; no recurring fetch was added.

Episode cards derive progress/completion from WatchProgressRepository and WatchedRepository rather
than a placeholder fraction. Unwatched rating protection also applies to the ratings section. Custom
colors use the shared solid-color rule and shared text contrast; native ring rendering supplies a
contrasting focus cue for very dark custom accents. Toggle rows expose On/Off to accessibility.

Android APK updates, Compose rendering diagnostics and RGB565 image preferences have no native
counterpart. App Store content-discovery restrictions still apply. Native tvOS focus/navigation and
its existing player remain intentional platform differences; this is functional parity for the
identified missing workflows, not a byte-for-byte Android layout or a claim about untested hardware.

The additional UI checks validate rejected input, a saved custom color after relaunch, saved episode
preferences rendered on a synthetic local title, and a discovery filter retained in a saved folder.
The synthetic title entry point is DEBUG + simulator only; it does not fetch metadata or play media.
Filter validation and progress/completion rules have native core tests; dark-ring contrast has Swift
unit tests. See the test logs before treating a run as validated.

API references: [TMDB discovery](https://developer.themoviedb.org/reference/discover-movie) and
[Apple focus](https://developer.apple.com/documentation/swiftui/focusstate/).
