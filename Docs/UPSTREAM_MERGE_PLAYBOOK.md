# Upstream merge playbook (KMP: NuvioMobile / NuvioDesktop)

How Tuvora pulls upstream (**NuvioMedia**) changes into the forks without regressing the self-hosted
stack or shipping Nuvio branding. TV is a separate hand-port — same *intent*, its own steps.

**Golden rules**
- **MERGE, never rebase.** Fork `main` carries released tags + hundreds of fork commits; rebasing
  rewrites published history and detaches release tags.
- **Two things recur on every merge** and are the whole cost:
  1. The **firewall pays off**: upstream touches **zero** fork-feature files
     (`features/{iptv,epg,radar,livetv}`, `core/{memory,rec}`). All conflicts are in the shared spine.
     Post-merge, `ArchitectureTest` (Konsist) must stay green and `arch-baseline.txt` must not grow.
  2. The **Nuvio cosmetic/membership/theme/custom-server subsystem** (themes, app icons, member tiers,
     profile backgrounds, supporter card, self-hosted custom-server connections) is **KEPT but INERT**
     (decision 2026-08-20, reversing the earlier strip): its RPCs (`get_my_member_access`,
     `get_my_membership_overview`, `get_member_profile_*_catalog`) exist only on Nuvio's cloud — not on the
     self-hosted backend and not in `NuvioMedia/self-host` — so it resolves to free-tier/no-op at runtime.
     The ONE brand chokepoint is `AppBrandWordmark`, pinned to the fork's `app_logo_wordmark` (Tuvora) for
     every theme/icon; `customServerConnectionsEnabled`/`donation*Enabled` stay `false` in every flavor and
     the fork keeps its Marigold default theme (`ThemeSettingsRepository.selectedTheme`, no
     `selectedThemePreference`). Keeping it is measurably cheaper than stripping (TV: 0 vs 136 cascading
     errors) — see Phase 2.

**Tooling** (`scripts/upstream-merge/`):
- `strings_union.py BASE OURS THEIRS OUT` — 3-way key-level merge for any conflicted `strings.xml`
  (ours-on-brand, fork-change-wins when upstream did not touch the key, upstream-new keys appended,
  merge-introduced `Nuvio`→`Tuvora` sweep, duplicate-key check). `strings_sweep.py BASE MERGED` runs only
  the leak sweep on locales that auto-merged. Both skip legitimate attribution keys (`about_made_with`,
  `licenses_attributions*`, …).
- `resolve_hunks.py FILE ours|theirs|both|@replacement ...` — resolve conflict hunks in order without
  hand-editing markers.

---

## Phase 0 — Trial-merge to derisk (repos stay untouched)

```bash
SCRATCH=$(mktemp -d)
git worktree add --detach "$SCRATCH" main
( cd "$SCRATCH" && git merge --no-commit --no-ff origin/<upstream-branch> )   # mobile=cmp-rewrite, desktop=Dev
# conflict shape:
( cd "$SCRATCH" && git diff --name-only --diff-filter=U )
# firewall check — MUST be 0:
( cd "$SCRATCH" && git diff --name-only "$(git merge-base main origin/<up>)"..origin/<up> \
    -- 'composeApp/src/commonMain/kotlin/com/nuvio/app/features/'{radar,iptv,epg,livetv} \
       'composeApp/src/commonMain/kotlin/com/nuvio/app/core/'{memory,rec} | wc -l )
( cd "$SCRATCH" && git merge --abort ); git worktree remove --force "$SCRATCH"
```
Expect: ~40 conflicts, of which ~20-25 are binary launcher icons + a few string locales → ~12-28 real
code conflicts, plus ~60-75 silent both-touched auto-merges (where review time actually goes).

## Phase 1 — Merge + resolve conflicts

```bash
git checkout -b merge/upstream-YYYY-MM-DD main
git merge --no-ff --no-commit origin/<upstream-branch>
```

**Standing keep-ours clusters** (`git checkout --ours -- <file>`, then `git add`):
- **SyncBackend\*** (whole cluster) + **AppFeaturePolicy** (all flavor files) + **auth**
  (`AuthRepository`, TV `AuthManager`) + **SupabaseProvider** (whole file — it is a `holder` vs
  `cachedClient` **chimera trap**; per-hunk resolution splices incompatible halves).
- Self-hosted config where upstream swaps in `ServerConfigurationRepository`: keep ours
  (`SupabaseConfig.ANON_KEY`, `SupabaseProvider.selectedBackend`).
- The **launcher icons** + `README` + `Version.xcconfig` (keep our branding/versions).
- **String locales**: union (ours-on-collision, append upstream-new keys), then sweep merge-introduced
  Nuvio→Tuvora leaks. Fork translations are already clean, so `checkout --ours` is a safe fast path if
  you accept missing upstream translations for those locales.

**Union / graft patterns** (keep both sides): additive list entries (data-cleaner keys), additive enum
values (accent theme **colours** — brand-neutral), new settings fields (keep our default + add the new
field), a Composable that gained params on both sides (thread both).

**Drop upstream infra the fork lacks**: `DeviceSessionRegistration`, `MemberAccessRepository.*` calls,
provider-credential *seed* wiring (privacy call — stays OFF).

**Upstream artefacts that arrive WITHOUT a conflict marker — check after every merge:**
- `ls .github/workflows` — upstream adds workflows as new files (PR builds, `update-store-source.yml`
  firing on every published release). The fork keeps only `build.yml`, `release.yml` (and the manual
  `ios-test-build.yml`); `git rm` the rest.
- `grep -rn NuvioMedia composeApp/src` — the in-app updater (`AppUpdaterRepository.kt`) hard-codes
  upstream's release repo; keep `UPDATER_RELEASES_REPO` pointed at `paradox-kush`. Licence
  attributions are the only legitimate hits.
- Upstream's off-main mpv executor (`executeMpv {}`) can auto-merge into `PlayerEngine.android.kt`;
  the fork has one queue — rewrite to `ctl {}`.

**App shell (since 2026-09-27): the fork uses upstream's split shell** (App.kt → AppGate,
MainAppContent, MainTabsDestination, AppShellComponents, *Destination files, RootTabHost). Resolve
shell conflicts per hunk like any shared file — do NOT restore a monolithic App.kt. Fork-owned
pieces to keep when resolving: Iptv/Sports in `AppScreenTab` + both nav bars + `AppTabHost`,
`LiveTvRoute` (serializer, disposal, entry), ProfileSwitchController in `AppGate`, live-channel launch
+ realtime + IPTV refresh in `MainAppContent`, playback gate in `PlayerDestination`, Stalker mint in
`StreamDestination`. Also check `libs.versions.toml`: `material3` must be the version upstream pairs
with `composeMultiplatform` (a mismatch compiles but crashes every OutlinedTextField at runtime —
`Material3CompatibilityTest` guards it).

**mpv rule**: never reintroduce `mpv.*` on the main thread. Route seek/property writes through the
`ctl {}` queue; keep `snapshot()` reading the property shadow (`obs*`).

## Phase 2 — Keep the Nuvio cosmetic subsystem inert (do NOT strip)

Upstream-new cosmetic/membership files arrive with no conflict marker — leave them. Then:
- **Brand chokepoint**: `features/settings/AppBrandWordmark.kt` must render only
  `Res.drawable.app_logo_wordmark` (the fork's Tuvora asset) regardless of theme/icon. If upstream
  re-conflicts it, `git checkout main -- <file>` (or the sibling repo's `main` twin).
- **Theme default**: keep `ThemeSettingsRepository` `MARIGOLD` + `selectedTheme`; point any upstream
  `selectedThemePreference` consumer (e.g. `core/sync/ProfileSettingsSync.kt`) at `selectedTheme`;
  `AppearanceSettingsPage` lists `listOf(AppTheme.MARIGOLD) + AppTheme.entries…` un-gated (no
  `MemberAccessRepository`/`availableAppThemes` gating).
- **App.kt**: keep `MemberAccessRepository.refreshIfStale()`/`refresh()` (inert — RPC absent → free
  tier); drop `DeviceSessionRegistration.registerIfAuthenticated()` (dead RPC; the fork reports devices
  via `SyncDeviceReporter`).
- **AppFeaturePolicy**: every flavor actual gets `donationActionsEnabled=false`,
  `donationProgressEnabled=false` (iosAppStore: `true`, mirroring Mobile), `customServerConnectionsEnabled=false`,
  and keeps `supportersContributorsPageEnabled=false` + `debugBackendSwitcherEnabled`.
- **`ServerConnectionController`** (upstream custom-server, gated off): take the sibling repo's
  already-mapped version (`prepareForServerSwitch()`→`resetForSyncBackendChange()`,
  `SupabaseProvider.reset()`→`rebuildClient()`, `reinitialize()`→`initialize()`).
- **Strings**: the `community_membership_*`/`supporter_membership_*` keys are new → the union script
  rebrands them to Tuvora (they only render on the disabled Supporters page).
- **Desktop ← Mobile shortcut**: when Desktop's upstream catches up to a feature Mobile already reconciled
  (it lags `cmp-rewrite` by days), `git checkout mobile/main -- <file>` is the resolution for every
  conflicted file whose pre-merge desktop/mobile twins were identical; hand-resolve only the files with
  desktop-specific members (`AppFeaturePolicy*` — `downloadsEnabled`/`notificationsEnabled`/
  `externalPlayerSupported`; `ThemeSettingsRepository` — `desktopNavigationLayout`; `App.kt`).

## Phase 3 — Compile-drive the semantic breaks

```bash
./gradlew :composeApp:compileAndroidMain
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```
The textual merge misses semantic breaks; the compiler finds them. Recurring catalog:
- **`WatchedRepository` per-source refactor**: upstream stores items in a `WatchedItemsStore`
  (`read {…}` / `update {…}` over `nuvioItems`/`dirtyNuvioKeys`/…). Rewrite the fork's `migrateIdPrefix`
  + `pushPendingToServer` onto it (old names `itemsForSource` / `nuvioItemsByKey` /
  `nuvioDirtyWatchedKeys` / `persistNuvio()` are gone → use the store + `persist()`).
- **`AppFeaturePolicy`**: upstream added expect members (`donationActionsEnabled`,
  `donationProgressEnabled`, `customServerConnectionsEnabled`) → add them to every flavor **actual**
  (all `false`; fork uses SyncBackend + its own Donate row).
- **`ServerConnectionController`** (upstream custom-server, gated off): map its calls to fork methods —
  `prepareForServerSwitch()`→`resetForSyncBackendChange()`, `SupabaseProvider.reset()`→`rebuildClient()`,
  `reinitialize()`→`initialize()`.
- **`AddonPlatform.android`**: fork DoH `clientForDns` fell back to a removed `addonHttpClient` →
  `AddonHttpClientProvider.get()`. Keep the fork's `clientForDns(dnsProvider)` at the DoH call sites.
- **`PlayerEngine.android`**: `videoOutput` is the mpv **String** — pass `AndroidLibmpvVideoOutput.Gpu.mpvValue`.
- **HomeScreen**: upstream's `disintegrationRequest` dead-ends at the fork's `HomeContinueWatchingSplit`
  — drop it from that call, keep it on the upstream Upcoming row.
- Upstream's cosmetic tests (`AvatarAccessTest`, `AppIconOptionTest`, `MemberBrandWordmarkTest`, `ThemeAccessTest`,
  `ProfileBackgroundTest`) stay — they pass against the kept-inert subsystem.

## Phase 4 — Verify (house rule: BOTH runners + the gate)

```bash
./gradlew :composeApp:testAndroidHostTest         # JVM — includes ArchitectureTest (firewall gate)
./gradlew :composeApp:iosSimulatorArm64Test       # Native — a Kotlin/Native divergence can SIGABRT past 890 green JVM tests
./gradlew :androidApp:assembleFullDebug           # aapt/resources — catches manifest + drawable breakage
```
Arch gate must be green and `arch-baseline.txt` must not have grown.

## Phase 5 — Desktop, TV, backend, release

- **Desktop** (`NuvioDesktop`, upstream `origin/Dev`): same recipe (no strip); reuse Mobile's resolved twins via
  the local `mobile` remote (Phase 2). Cherry-pick fork increments from `mobile/main` (never the merge branch).
  Run desktop merges in a `git worktree` (`wt/merge-YYYY-MM-DD/<repo>`) — copy `local.properties` in and
  symlink `../nuvio-engine` next to the worktree (the iOS targets `check()` its xcframework).
- **TV** (`NuvioTV`, upstream `origin/dev`): separate codebase, hand-port the *intent*. It has no Konsist
  gate; its fork isolation is convention. **TV's `ui/screens/player/*` is fork-pinned** (taken wholesale from
  the fork since 2026-08-19; upstream's sidecar-subtitle controller API — `PlayerSidecarSubtitles.kt` /
  `PlayerSubtitleRtlFix.kt` — is NOT ported): keep `PlayerSidecarSubtitles.kt` deleted, keep the fork's
  player files for conflicts that need the sidecar API, and graft only self-contained upstream bits (pure new
  files such as `SubtitleMojibakeSanitizer`, the charset-decoding `downloadSubtitleBody`). Re-check
  `MainActivity` after every merge: IPTV + Sports `rootRoutes`/`drawerItems` and the
  `SidebarVisibilityPolicy` gate at both nav hosts.
  **The pin silently drops upstream robustness fixes (found 2026-10-25).** Taking `ui/screens/player/*`
  from the fork wholesale means every upstream fix to a file we pinned becomes an *ancestor* of `main`
  (git says "merged", `git log --grep` finds it) while its code never arrives. A 10-24 audit found ~15:
  the audio fallback ladder that rebuilt the player forever (#3988), the hardware-first video decoder
  filter (#3969), parallel chunk-buffer lease + off-heap (#3944/#3947 — `ParallelRangeDataSource.kt`
  was byte-identical to upstream's 2026-08-22 version, seven commits behind), mpv `eof-reached`/auto-retry
  (#3319/#3796/#4263), USB tunneling (#4276), background decoder release (#4282). Restored by
  `fix/restore-upstream-player-fixes`. So, at every TV sync, after resolving:
  1. List what the pin held back: `git log --oneline <old-merge-base>..origin/dev -- app/src/main/java/com/nuvio/tv/ui/screens/player app/src/test/java/com/nuvio/tv/ui/screens/player`.
  2. For each commit, record **ported / skipped (reason)** in `docs/UPSTREAM_SYNC_<date>.md`. "Unported feature"
     (sidecar subtitles, autosync, post-play) is a valid skip; a bug fix to a file we kept is not.
  3. Diff pinned files against upstream's: `git diff <merge-base> origin/dev -- <file>` empty for a file
     the fork never edited means the pin is only lag — take upstream's file.
  4. Tests are the tell: upstream tests present at the merge-base but missing from the fork
     (`comm -23` of the two `git ls-tree` listings) point at dropped code. Port them with the fix.
  The fork's own wiring guard is `RestoredUpstreamPlayerFixesWiringTest`; extend it per restored fix.
- **Backend parity**: upstream open-sourced its backend at **`NuvioMedia/self-host`**. For any feature
  gated only by a missing RPC (delta-sync, discovery-endpoint, provider-creds), port the migration from
  there into `nuvio-backend`, adapting to the fork's `*_core`-wrapper technique. Deploy backend BEFORE
  the app tags depend on it. (Provider-credential sync stays OFF — a privacy call, not a build one.)
- **Release**: `gh release list` EVERY repo before choosing a tag (versions get cut out-of-session).
  Tags trigger CI; mobile's tag auto-publishes the Play internal AAB.

See also the `nuvio-upstream-merge` memory (per-sync history + gotchas) and
`research/tuvora-architecture-rules.md` (the firewall the merge relies on).
