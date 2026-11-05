# Architecture rules (enforced)

New code follows the spatiotemporal-composability standard. Full spec:
`../research/tuvora-architecture-rules.md` and `../research/iptv-sports-decomposition.md`.
**These rules are enforced by `ArchitectureTest` (Konsist, JVM host test) — a violation is a red build,
not a code-review maybe.** Phase 0 (this commit) lands the machinery; seams S1–S11 land incrementally.

## The two disciplines
- **Invariant T — tracked disposal.** Any acquire (subscribe/register/open/cache) returns a `Disposable`;
  teardown composes them LIFO via `EffectScope` (`features/common/lifecycle`). Never a bare `unsubscribe()`
  a caller must remember. Effectful suspend-acquires use `EffectScope.acquire` (atomic with registration;
  cancellation is NOT revert). Engine-level I/O timeouts only — `withTimeout` is inert under NonCancellable.
- **Invariant S — declared contracts.** Feature code never names a foreign global `object`; it depends on
  an interface (port) provided at the root via `CompositionLocal`. Wiring lives in ONE file,
  `FeatureWiring.kt` (the single firewall exception). Registration is process-init
  (`NuvioApplication.onCreate`), never a `@Composable` body or `Activity.onCreate`.

## Definition of done (per feature/seam)
- [ ] One `api` package (ports + neutral value types); everything else `internal`.
- [ ] Names no foreign `object` — ports provided at the root. *(Konsist)*
- [ ] Cross-feature types are consumer-owned & neutral (never a foreign data type in a port signature).
- [ ] Every effect returns a `Disposable`, owned by an `EffectScope`/coroutine scope.
- [ ] `@Composable`s do no I/O and name no repository object; orchestration in the state holder. *(Konsist)*
- [ ] Preconditions are gates at the seam, not per-call-site checks.
- [ ] Firewall crossings go through an extension point, not a direct reference. *(Konsist)*
- [ ] Screens stay previewable via `PreviewFeatureWiring`.
- [ ] Arch test green; `ArchBaseline` shrank or held (never grew).
- [ ] Tests red-first, green on BOTH runners (`testAndroidHostTest` + `iosSimulatorArm64Test`).

## The firewall (merge safety)
The fork side is defined by **upstream absence** (`git cat-file -e origin/cmp-rewrite:<path>`), NOT
directory naming: `features/{radar,iptv,epg,livetv,dev,announcements,calendar}` plus `core/{analytics,diag,memory,rec}` and a
few fork-only files in shared dirs. Re-verify the set at every upstream sync. `ArchBaseline` freezes the
26 current crossings; each seam burns its entries down. Never add a baseline entry to silence a rule.
