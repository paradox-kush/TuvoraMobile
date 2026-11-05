package com.nuvio.app.arch

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Architecture enforcement (rules doc Rule 6) — the mechanism that makes the design stick. JVM-only:
 * Konsist is a static source scan, so it runs once on the host test set, not per platform.
 *
 * Fork side = UPSTREAM-ABSENT paths (verified via `git cat-file -e origin/cmp-rewrite:<path>`), NOT a
 * features/ name list: fork-only code also lives under core/{analytics,diag,memory,rec} and as a few
 * files inside shared dirs. RE-VERIFY the fork set at every upstream sync.
 *
 * Baseline-and-ratchet: ~200 pre-existing objects + 26 crossing files cannot be greened today, so the
 * baseline in [ArchBaseline] freezes them; the test fails only on NEW violations and the baseline only
 * shrinks (each seam deletes its entries). A wrong fork set bakes wrong entries into the baseline —
 * and baseline entries are forever — which is why the set is computed, not guessed.
 */
class ArchitectureTest {

    // Only THIS checkout's sources. Nested worktrees (wt/…) hold their own copy of composeApp/src and
    // must not be scanned. scopeFromProject/Module/SourceSet parse EVERY .kt under the project root
    // before filtering (Konsist 0.17 KoFileDeclarationProvider), so 60+ nested worktrees (~140k files)
    // ran the test out of heap and wedged it. scopeFromDirectory walks only the given directory,
    // relative to the nearest project root — this checkout's, also when the checkout itself is a wt/.
    private val files: List<Pair<String, String>> =
        Konsist.scopeFromDirectory("composeApp/src").files.map { it.path to it.text }

    // --- fork-side definition (upstream absence, not directory naming) ---
    private val forkPaths = listOf(
        "/features/radar/", "/features/iptv/", "/features/epg/", "/features/livetv/", "/features/dev/", "/features/recordings/",
        "/features/announcements/",
        "/core/analytics/", "/core/diag/", "/core/memory/", "/core/rec/",
    )
    private val forkFiles = listOf("ImmersivePlaybackGate.kt")
    private fun isForkFile(path: String) =
        forkPaths.any { path.contains(it) } || forkFiles.any { path.endsWith("/$it") }
    private fun isWiringFile(path: String) =
        path.endsWith("/com/nuvio/app/FeatureWiring.kt") ||
        // Logic-port half of the composition root, split out so Apple TV (:tvosCore) shares the list.
        path.endsWith("/com/nuvio/app/FeatureContributions.kt") ||
        path.endsWith("/com/nuvio/app/AndroidFeatureWiring.kt")

    // fork FEATURE refs (R2b) + fork-only core SUBSYSTEM refs (R2d — rec+memory get ports;
    // analytics+diag are DELIBERATELY EXEMPT: cross-cutting telemetry, accepted as thin diff).
    private val forkRef = Regex("""\bcom\.nuvio\.app\.features\.(radar|iptv|epg|livetv|dev|announcements|recordings)\.""")
    private val forkCoreRef = Regex("""\bcom\.nuvio\.app\.core\.(rec|memory)\.""")

    // Strip block + WHOLE-LINE // comments only. A naive //.* eats the // in "https://…" literals and
    // silently disables enforcement for that line (an invisible false NEGATIVE).
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "")

    private fun rel(path: String) = path.substringAfter("/composeApp/src/")

    @Test
    fun `the scan covers this checkout's sources`() {
        // Guard against a vacuous pass: every rule below iterates [files].
        assertTrue(files.size > 200, "scanned only ${files.size} source files")
        assertTrue(files.any { (p, _) -> isWiringFile(p) }, "composition root not in the scan")
    }

    @Test
    fun `the fork's own media-server feature stays deleted (Jellyfin and Emby live in features-servers)`() {
        // 13th upstream sync: Jellyfin/Emby is upstream's features/servers on every platform. A second
        // implementation under features/mediaserver would fork the product again, so its return is a red build.
        assertTrue(files.none { (p, _) -> p.contains("/features/mediaserver/") }, "features/mediaserver is back")
        assertTrue(!forkRef.containsMatchIn("com.nuvio.app.core.contracts.OwnSourcePolicy"), "neutral ports stay reachable")
    }

    @Test
    fun `non-fork code never references a fork feature or fork-only core subsystem (R2b + R2d)`() {
        val violations = files
            .filter { (p, _) -> !isForkFile(p) && !isWiringFile(p) }
            .filter { (_, text) ->
                val code = stripComments(text)
                forkRef.containsMatchIn(code) || forkCoreRef.containsMatchIn(code)
            }
            .map { (p, _) -> rel(p) }
            .filterNot { it in ArchBaseline.crossings }
            .sorted()
        assertTrue(
            violations.isEmpty(),
            "NEW firewall crossing(s) — cross via an extension point (design §7), not a direct reference:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `features are reached only through their api package (R2a)`() {
        val internalRef = Regex("""\bcom\.nuvio\.app\.features\.([a-z]+)\.internal\.""")
        val violations = files
            .filter { (p, text) ->
                internalRef.findAll(stripComments(text)).any { m ->
                    m.groupValues[1] != "common" && "/features/${m.groupValues[1]}/" !in p
                }
            }
            .map { (p, _) -> rel(p) }
            .filterNot { it in ArchBaseline.crossings }
            .sorted()
        assertTrue(
            violations.isEmpty(),
            "cross-feature internal access — go through the feature's api package:\n" +
                violations.joinToString("\n"),
        )
    }

    /**
     * R7 — every IPTV / Live TV loading state has a deadline and a terminal outcome (repo-root CLAUDE.md).
     * "Live TV spins forever" was fixed path by path three times and came back each time, because any code
     * could switch a loading flag on with nothing guaranteeing it ever switched off. So a loading state is
     * entered only through BoundedLoad (deadline + Loaded/Empty/Failed, failure never "empty"): this forbids
     * a raw Boolean loading flag set to true (or declared defaulting to true), a `loading by remember { mutableStateOf(true) }`, and building
     * a LoadStatus.Loading anywhere but BoundedLoad.kt. Main sources only; tests may build any state.
     * No baseline — there were no violations left when the rule landed, so there is nothing to grandfather.
     */
    @Test
    fun `IPTV and Live TV loading states are entered only through BoundedLoad (R7)`() {
        val rawFlagOn = Regex("""\b\w*[lL]oading\w*\s*=\s*true\b""")
        val rememberedOn = Regex("""\b\w*[lL]oading\w*\s+by\s+remember[^\n]*mutableStateOf\(\s*true\s*\)""")
        val builtLoading = Regex("""\bLoadStatus\.Loading\s*\(""")
        // A loading Boolean that STARTS true (`val loading: Boolean = true`) is the same switch, set at birth.
        val declaredOn = Regex("""\b\w*[lL]oading\w*\s*:\s*Boolean\s*=\s*true\b""")
        val mainSourceSet = Regex("""/composeApp/src/[A-Za-z]*Main/""")
        val violations = files
            .filter { (p, _) -> ("/features/iptv/" in p || "/features/livetv/" in p) && mainSourceSet.containsMatchIn(p) }
            .filterNot { (p, _) -> p.endsWith("/features/iptv/BoundedLoad.kt") }
            .flatMap { (p, text) ->
                stripComments(text).lines().withIndex()
                    .filter { (_, line) -> rawFlagOn.containsMatchIn(line) || rememberedOn.containsMatchIn(line) || builtLoading.containsMatchIn(line) || declaredOn.containsMatchIn(line) }
                    .map { (i, line) -> "${rel(p)}:${i + 1}: ${line.trim()}" }
            }
        assertTrue(
            violations.isEmpty(),
            "IPTV/Live TV loading state entered outside BoundedLoad — use BoundedLoad.begin/run so it always ends:\n" +
                violations.joinToString("\n"),
        )
    }
}
