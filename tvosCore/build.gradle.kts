import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// Apple TV shared core. Compiles composeApp's OWN source folders in place for tvOS, excluding every
// file that imports Compose UI (which has no tvOS build). No shared file moves, so upstream merges
// are unaffected; see the design doc "Tuvora for Apple TV — Design", section "Where the code lives".
//
// Compose resources has no tvOS build either, so this module also generates the `Res.string.*`
// accessors the shared logic calls (keys + English defaults) and the per-language string tables the
// Apple TV app bundles; `getString` resolves through those tables at runtime (tvosMain).

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    // Swift-friendly API for the Apple TV app: StateFlow as an observable, suspend as async.
    alias(libs.plugins.skie)
}

val composeAppSrc = rootProject.file("composeApp/src")
val composeResourcesDir = composeAppSrc.resolve("commonMain/composeResources")

// A file belongs to the shared logic layer unless it imports Compose UI or another UI-only library.
// `androidx.compose.ui.graphics.Color` alone is allowed: data models carry colours, and tvosCore
// provides that one type (src/commonMain/.../ComposeColor.kt).
val uiImport = Regex(
    "^import (androidx\\.compose\\.(ui|foundation|material|material3|animation)|org\\.jetbrains\\.compose\\.(ui|foundation|material)" +
        "|coil3|dev\\.chrisbanes|sh\\.calvin|com\\.kmpalette|androidx\\.navigation3|platform\\.(PhotosUI|MediaPlayer|UserNotifications)|com\\.dokar)[^\\n]*",
    RegexOption.MULTILINE,
)
// Library types logic uses that tvosCore supplies itself (src/commonMain/androidx/...).
val allowedUiImport = Regex("^import androidx\\.compose\\.ui\\.(graphics\\.(Color|lerp|luminance|toArgb)|text\\.intl\\.Locale)$")

// Files with no Compose-UI import that are still UI glue or iPhone-only, reviewed one by one. Anything
// here has no Apple TV role, or Apple TV supplies its own version (named in the comment).
val tvosExcludedCommon = listOf(
    // Composition root and navigation: Apple TV has TvAppGraph and SwiftUI navigation.
    "com/nuvio/app/FeatureWiring.kt", "com/nuvio/app/AppGateOverlay.kt", "com/nuvio/app/DownloadsDestinations.kt",
    "com/nuvio/app/navigation/PlayerChromePolicy.kt", "com/nuvio/app/navigation/RouteAnalyticsName.kt",
    // Compose UI slots and effects (use collectAsStateWithLifecycle / LazyList / composable hosts).
    "com/nuvio/app/features/announcements/api/Announcements.kt", "com/nuvio/app/features/radar/RadarHomeSportsSection.kt",
    "com/nuvio/app/core/rec/RecShelfTracking.kt", "com/nuvio/app/features/auth/AccountSessionPrompts.kt",
    "com/nuvio/app/features/library/TrackingMembershipRemovalConfirmation.kt",
    "com/nuvio/app/features/livetv/LiveTvOrientation.kt",
    // Phone/desktop long-press channel menu (sheet + toast); Apple TV has its own SwiftUI context menu.
    "com/nuvio/app/features/iptv/IptvLiveChannelMenu.kt",
    // Phone/desktop shell hook for setup links and the empty-screen button; it opens the Add Playlist page
    // (a Compose page object). Apple TV has its own setup-code screen and no deep links.
    "com/nuvio/app/features/iptv/SetupCodeEntryImpl.kt",
    // iOS-only SwiftUI tab bridge, and the announcements feature (not in Apple TV v1).
    "com/nuvio/app/features/announcements/internal/AnnouncementsRepository.kt", "com/nuvio/app/features/home/HomeEpisodeShuffle.kt",
    // In-app APK updater: App Store builds update through the store.
    "com/nuvio/app/features/updater/**",
    // Platform key: Apple TV supplies its own ("tvos") in tvosCore/src/commonMain/.../SyncPlatform.tvos.kt.
    "com/nuvio/app/core/sync/SyncPlatform.kt",
    // Trailer surface: Compose player host.
    "com/nuvio/app/features/trailer/TrailerPlaybackState.kt",
    // The Compose player runtime. Phase 1 extracts its decisions into PlayerSessionController, which tvOS uses.
    "com/nuvio/app/features/player/PlayerNextEpisodeAutoPlay.kt",
    "com/nuvio/app/features/player/PlayerScreenModalHosts.kt", "com/nuvio/app/features/player/ResumeLoadingUi.kt",
    "com/nuvio/app/features/player/PlayerScreenRuntime*.kt",
    // Compose player toast text (uses PlayerLayout's time formatter); Apple TV shows its own skip feedback.
    "com/nuvio/app/features/player/PlayerAutoSkipNotification.kt",
    // Compose settings page whose UI moved behind shared Chip/Settings* helpers; Apple TV settings are SwiftUI.
    "com/nuvio/app/features/settings/RatingsSettings.kt",
)
val tvosExcludedIos = listOf(
    // iPhone-only APIs; Apple TV supplies its own actual in tvosCore/src/tvosMain (named gaps).
    "com/nuvio/app/features/iptv/M3UFilePicker.ios.kt",              // UIDocumentPicker
    "com/nuvio/app/features/settings/AppIconPlatform.ios.kt",        // alternate app icons
    "com/nuvio/app/features/profiles/ProfileHoverHapticFeedback.ios.kt", // haptics
    "com/nuvio/app/features/livetv/LiveTvOrientation.ios.kt",        // device orientation
    "com/nuvio/app/features/updater/**",                              // in-app APK updater
    "com/nuvio/app/core/storage/AppleDataDirectory.ios.kt",          // tvOS writes only to Caches
)

fun uiFiles(sourceSet: String): List<String> {
    val root = composeAppSrc.resolve("$sourceSet/kotlin")
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { file -> uiImport.findAll(file.readText()).any { !allowedUiImport.matches(it.value) } }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
}

abstract class GenerateTvosResources : DefaultTask() {
    @get:InputDirectory
    abstract val resourcesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val kotlinOut: DirectoryProperty

    @get:OutputDirectory
    abstract val tablesOut: DirectoryProperty

    private fun unescape(raw: String): String = raw.trim()
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\@", "@")
        .replace("\\?", "?").replace("\\\\", "\\")

    private fun kotlinLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\$", "\\\$") + "\""

    private fun appleLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    /** values -> Base, values-pt-rBR -> pt-BR, values-in -> id (Apple's code for Indonesian). */
    private fun lproj(dir: String): String? {
        if (dir == "values") return "Base"
        val q = dir.removePrefix("values-")
        if (q == "in") return null // duplicate of values-id under the legacy Android code
        return q.replace("-r", "-")
    }

    private fun parse(dir: java.io.File): Pair<Map<String, String>, Map<String, Map<String, String>>> {
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        dir.listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strs = doc.getElementsByTagName("string")
            for (i in 0 until strs.length) {
                val e = strs.item(i) as Element
                strings[e.getAttribute("name")] = unescape(e.textContent)
            }
            val pls = doc.getElementsByTagName("plurals")
            for (i in 0 until pls.length) {
                val e = pls.item(i) as Element
                val items = e.getElementsByTagName("item")
                plurals[e.getAttribute("name")] = (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
            }
        }
        return strings to plurals
    }

    @TaskAction
    fun generate() {
        val root = resourcesDir.get().asFile
        val (strings, plurals) = parse(root.resolve("values"))
        val drawables = root.resolve("drawable").listFiles()?.map { it.nameWithoutExtension }?.sorted().orEmpty()
        val fonts = root.resolve("font").listFiles()?.map { it.nameWithoutExtension }?.sorted().orEmpty()

        val kt = StringBuilder()
        kt.appendLine("// GENERATED by :tvosCore:generateTvosResources from composeApp/src/commonMain/composeResources. Do not edit.")
        kt.appendLine("@file:Suppress(\"unused\", \"ObjectPropertyName\")")
        kt.appendLine("package nuvio.composeapp.generated.resources")
        kt.appendLine("import org.jetbrains.compose.resources.*")
        kt.appendLine("object Res { object string; object plurals; object drawable; object font }")
        strings.forEach { (k, v) -> kt.appendLine("val Res.string.`$k`: StringResource get() = StringResource(${kotlinLiteral(k)}, ${kotlinLiteral(v)})") }
        plurals.forEach { (k, q) ->
            kt.appendLine("val Res.plurals.`$k`: PluralStringResource get() = PluralStringResource(${kotlinLiteral(k)}, ${kotlinLiteral(q["one"] ?: q["other"].orEmpty())}, ${kotlinLiteral(q["other"].orEmpty())})")
        }
        // Compose's own generator turns '-' into '_' in accessor names (a file "a-b.xml" -> Res.drawable.a_b);
        // a hyphen would also be an invalid Objective-C name in the framework header.
        fun accessor(name: String) = name.replace('-', '_').replace('.', '_')
        drawables.forEach { kt.appendLine("val Res.drawable.`${accessor(it)}`: DrawableResource get() = DrawableResource(${kotlinLiteral(it)})") }
        fonts.forEach { kt.appendLine("val Res.font.`${accessor(it)}`: FontResource get() = FontResource(${kotlinLiteral(it)})") }
        val ktFile = kotlinOut.get().asFile.resolve("nuvio/composeapp/generated/resources/Res.tvos.kt")
        ktFile.parentFile.mkdirs()
        ktFile.writeText(kt.toString())

        val tables = tablesOut.get().asFile
        tables.deleteRecursively()
        root.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.sortedBy { it.name }.forEach { dir ->
            val lang = lproj(dir.name) ?: return@forEach
            val (localized, _) = parse(dir)
            val out = tables.resolve("$lang.lproj/Tuvora.strings")
            out.parentFile.mkdirs()
            out.writeText(buildString {
                appendLine("/* GENERATED from composeResources/${dir.name}. Do not edit. */")
                localized.forEach { (k, v) -> appendLine("${appleLiteral(k)} = ${appleLiteral(v)};") }
            })
        }
    }
}

val generatedKotlin = layout.buildDirectory.dir("generated/tvosResources/kotlin")
val generateTvosResources = tasks.register<GenerateTvosResources>("generateTvosResources") {
    resourcesDir.set(composeResourcesDir)
    kotlinOut.set(generatedKotlin)
    tablesOut.set(layout.buildDirectory.dir("generated/tvosResources/lproj"))
}

kotlin {
    listOf(tvosArm64(), tvosSimulatorArm64()).forEach { target ->
        // The framework the Apple TV Xcode project links (tvosApp/). Static, like the iOS app's.
        target.binaries.framework {
            baseName = "TuvoraCore"
            isStatic = true
        }
        // Same CommonCrypto binding the iOS build uses (Stalker MAC signing, PIN hashing, Simkl PKCE).
        target.compilations.getByName("main").cinterops.create("commoncrypto") {
            defFile(rootProject.file("composeApp/src/nativeInterop/cinterop/commoncrypto.def"))
            compilerOpts("-I${rootProject.file("composeApp/src/nativeInterop/cinterop")}")
        }
    }
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-opt-in=kotlin.time.ExperimentalTime",
            "-opt-in=kotlin.uuid.ExperimentalUuidApi",
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
            "-opt-in=kotlinx.cinterop.BetaInteropApi",
        )
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(composeAppSrc.resolve("commonMain/kotlin"))
            kotlin.exclude(uiFiles("commonMain") + tvosExcludedCommon)
            kotlin.srcDir(generateTvosResources.flatMap { it.kotlinOut })
            kotlin.srcDir(rootProject.file("composeApp/build/generated/runtime-config/kotlin"))
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kermit)
                implementation(libs.supabase.postgrest)
                implementation(libs.supabase.realtime)
                implementation(libs.supabase.auth)
                implementation(libs.supabase.functions)
                implementation(libs.supabase.storage)
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.framework)
                implementation(libs.androidx.savedstate)
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime:${libs.versions.androidx.lifecycle.get()}")
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.ksoup)
            }
        }
        commonTest { kotlin.srcDir(composeAppSrc.resolve("commonTest/kotlin/com/nuvio/app/features/mediaserver")) }
        commonTest.dependencies {
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
        }
        tvosMain {
            // iOS actuals compile for tvOS where they touch no iPhone-only UI (same import filter).
            kotlin.srcDir(composeAppSrc.resolve("iosMain/kotlin"))
            kotlin.srcDir(composeAppSrc.resolve("iosAppStore/kotlin"))
            kotlin.exclude(uiFiles("iosMain") + uiFiles("iosAppStore") + tvosExcludedIos)
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateTvosResources, ":composeApp:generateRuntimeConfigs")
}

