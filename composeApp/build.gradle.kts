import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.Properties

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @get:Input
    abstract val supabaseUrl: Property<String>

    @get:Input
    abstract val supabaseAnonKey: Property<String>

    @get:Input
    abstract val supabaseFallbackUrl: Property<String>

    /** Debug builds only: where the setup-code preview route lives (default blank = tuvora.co). */
    @get:Input
    abstract val providerWebUrl: Property<String>

    @get:Input
    abstract val nuvioSupabaseUrl: Property<String>

    @get:Input
    abstract val nuvioSupabaseAnonKey: Property<String>

    @get:Input
    abstract val syncBackendManifestUrl: Property<String>

    @get:Input
    abstract val sentryDsn: Property<String>

    @get:Input
    abstract val sentryEnvironment: Property<String>

    @get:Input
    abstract val tmdbApiKey: Property<String>

    @get:Input
    abstract val debugBuild: Property<Boolean>

    @get:Input
    abstract val realtimeSyncEnabled: Property<Boolean>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${supabaseUrl.get()}"
                |    const val ANON_KEY = "${supabaseAnonKey.get()}"
                |    const val FALLBACK_URL = "${supabaseFallbackUrl.get()}"
                |    const val PROVIDER_WEB_URL = "${providerWebUrl.get()}"
                |    const val NUVIO_URL = "${nuvioSupabaseUrl.get()}"
                |    const val NUVIO_ANON_KEY = "${nuvioSupabaseAnonKey.get()}"
                |}
                """.trimMargin()
            )
            resolve("SyncBackendBootstrapConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SyncBackendBootstrapConfig {
                |    const val SWITCH_MANIFEST_URL = "${syncBackendManifestUrl.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppBuildConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppBuildConfig {
                |    const val IS_DEBUG_BUILD = ${debugBuild.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/diagnostics").apply {
            mkdirs()
            resolve("SentryConfig.kt").writeText(
                """
                |package com.nuvio.app.core.diagnostics
                |
                |object SentryConfig {
                |    const val DSN = "${sentryDsn.get()}"
                |    const val ENVIRONMENT = "${sentryEnvironment.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/sync").apply {
            mkdirs()
            resolve("RealtimeSyncConfig.kt").writeText(
                """
                |package com.nuvio.app.core.sync
                |
                |object RealtimeSyncConfig {
                |    const val ENABLED = ${realtimeSyncEnabled.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb").apply {
            mkdirs()
            // Built-in TMDB key (upstream TmdbConfig.API_KEY); a personal key in settings overrides it.
            resolve("TmdbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.tmdb
                |
                |object TmdbConfig {
                |    const val API_KEY = "${tmdbApiKey.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/simkl").apply {
            mkdirs()
            resolve("SimklConfig.kt").writeText(
                """
                |package com.nuvio.app.features.simkl
                |
                |object SimklConfig {
                |    const val CLIENT_ID = "${props.getProperty("SIMKL_CLIENT_ID", "")}"
                |    const val REDIRECT_URI = "${props.getProperty("SIMKL_REDIRECT_URI", "nuvio://auth/simkl")}"
                |    const val APP_NAME = "${props.getProperty("SIMKL_APP_NAME", "tuvora")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/mdblist").apply {
            mkdirs()
            resolve("MdbListConfig.kt").writeText(
                """
                |package com.nuvio.app.features.mdblist
                |
                |object MdbListConfig {
                |    const val CLIENT_ID = "${props.getProperty("MDBLIST_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuvio.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${props.getProperty("PREMIUMIZE_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}" 
                |    const val SUPPORTERS_WALL_URL = "${props.getProperty("SUPPORTERS_WALL_URL", "https://nuvio.tv/api/supporters/wall")}"
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
// -PversionNameOverride / -PversionCodeOverride (from the release pipeline / git tag) win over the
// xcconfig, so AppVersionConfig.VERSION_NAME matches the released tag — the in-app updater compares
// against THIS constant, not the Android manifest versionName.
val releaseAppVersionName = providers.gradleProperty("versionNameOverride").orNull?.takeIf { it.isNotBlank() }
    ?: readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = providers.gradleProperty("versionCodeOverride").orNull?.toIntOrNull()
    ?: readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media"
val nuvioEngineAppleFramework = rootProject.file("../nuvio-engine/platform/apple/NuvioEngine.xcframework")
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val requestedAndroidDistributions = requestedGradleTasks.mapNotNull { taskName ->
    when {
        "playstore" in taskName -> "playstore"
        "full" in taskName -> "full"
        else -> null
    }
}.toSet()
require(requestedAndroidDistributions.size <= 1) {
    "Build Android full and playstore distributions separately, or set -Pnuvio.android.distribution=full|playstore."
}
val configuredAndroidDistribution = providers.gradleProperty("nuvio.android.distribution").orNull
    ?: supabaseProps.getProperty("NUVIO_ANDROID_DISTRIBUTION")
val isAmbiguousAndroidPackageTask = requestedGradleTasks.any { taskName ->
    taskName == "build" ||
        taskName.startsWith("assemble") ||
        taskName.startsWith("bundle")
} && requestedAndroidDistributions.isEmpty()
require(configuredAndroidDistribution != null || !isAmbiguousAndroidPackageTask) {
    "Set -Pnuvio.android.distribution=full|playstore for aggregate Android assemble/bundle tasks."
}
val androidDistribution = (
    configuredAndroidDistribution
        ?: requestedAndroidDistributions.singleOrNull()
        ?: "playstore"
    ).trim().lowercase()
require(androidDistribution == "playstore" || androidDistribution == "full") {
    "nuvio.android.distribution must be 'playstore' or 'full'."
}
val androidDistributionSourceDir = if (androidDistribution == "full") {
    "src/androidFull/kotlin"
} else {
    "src/androidPlaystore/kotlin"
}
val runtimeLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun runtimeConfigValue(key: String, fallback: String = ""): String =
    runtimeLocalProperties.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback

fun booleanConfigValue(key: String): Boolean? {
    val rawValue = runtimeLocalProperties.getProperty(key)
        ?: providers.environmentVariable(key).orNull
        ?: providers.gradleProperty(key).orNull
    return rawValue
        ?.trim()
        ?.lowercase()
        ?.let { value ->
            when (value) {
                "1", "true", "yes", "y", "debug" -> true
                "0", "false", "no", "n", "release" -> false
                else -> null
            }
        }
}

val xcodeConfiguration = providers.environmentVariable("CONFIGURATION").orNull
    ?.trim()
    ?.lowercase()
val kotlinFrameworkBuildType = providers.environmentVariable("KOTLIN_FRAMEWORK_BUILD_TYPE").orNull
    ?.trim()
    ?.lowercase()
val inferredDebugBuild = requestedGradleTasks.any { "debug" in it } ||
    xcodeConfiguration == "debug" ||
    kotlinFrameworkBuildType == "debug"
val isDebugBuild = booleanConfigValue("NUVIO_DEBUG_BUILD")
    ?: booleanConfigValue("nuvio.debugBuild")
    ?: inferredDebugBuild

fun runtimeConfigBoolean(key: String, default: Boolean): Boolean =
    when (runtimeConfigValue(key).lowercase()) {
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off" -> false
        else -> default
    }

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
    // Fork: SUPABASE_* = the self-hosted sync backend, NUVIO_* = upstream cloud (kept for
    // their features); fallback applies to the fork backend when configured.
    supabaseUrl.set(runtimeConfigValue("SUPABASE_URL"))
    supabaseAnonKey.set(runtimeConfigValue("SUPABASE_ANON_KEY"))
    supabaseFallbackUrl.set(runtimeConfigValue("SUPABASE_FALLBACK_URL"))
    providerWebUrl.set(runtimeConfigValue("PROVIDER_WEB_URL"))
    nuvioSupabaseUrl.set(runtimeConfigValue("NUVIO_SUPABASE_URL"))
    nuvioSupabaseAnonKey.set(runtimeConfigValue("NUVIO_SUPABASE_ANON_KEY"))
    syncBackendManifestUrl.set(runtimeConfigValue("SYNC_BACKEND_MANIFEST_URL"))
    tmdbApiKey.set(runtimeConfigValue("TMDB_API_KEY"))
    realtimeSyncEnabled.set(runtimeConfigBoolean("NUVIO_REALTIME_SYNC_ENABLED", true))
    debugBuild.set(isDebugBuild)
    sentryDsn.set(runtimeConfigValue("SENTRY_DSN"))
    tmdbApiKey.set(runtimeConfigValue("TMDB_API_KEY"))
    sentryEnvironment.set(
        when {
            requestedGradleTasks.any { "benchmark" in it } -> "benchmark"
            requestedGradleTasks.any { "debug" in it } -> "debug"
            else -> "production"
        }
    )
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    android {
        namespace = "com.nuvio.app"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        // JVM-side run of commonTest (fast local + ubuntu CI; the iOS twin is iosSimulatorArm64Test)
        withHostTest { isIncludeAndroidResources = true }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        val nuvioEngineSlice = if (iosTarget.name == "iosArm64") {
            "ios-arm64"
        } else {
            "ios-arm64_x86_64-simulator"
        }
        val nuvioEngineSliceDirectory = nuvioEngineAppleFramework.resolve(nuvioEngineSlice)
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
                create("appicon") {
                    defFile(project.file("src/nativeInterop/cinterop/appicon.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
                if (iosDistribution == "full") {
                    check(nuvioEngineSliceDirectory.resolve("libCNuvioEngine.a").isFile) {
                        "Build the local Nuvio Engine Apple XCFramework before compiling iOS Full."
                    }
                    create("nuvioengine") {
                        defFile(project.file("src/nativeInterop/cinterop/nuvioengine.def"))
                        compilerOpts("-I${nuvioEngineSliceDirectory.resolve("Headers").absolutePath}")
                        extraOpts("-libraryPath", nuvioEngineSliceDirectory.absolutePath)
                    }
                }
                configureEach {
                    extraOpts("-Xccall-mode", "direct")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                // BundledSQLiteDriver: SQLite compiled into the framework, so the match-index
                // db needs no system libsqlite3 link in the Xcode app (NativeSQLiteDriver's
                // -lsqlite3 doesn't reliably propagate from a static framework to the app link).
                implementation(libs.androidx.sqlite.bundled)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
            if (iosDistribution == "full") {
                linkerOpts(
                    "-lc++",
                    "-framework", "Security",
                    "-framework", "SystemConfiguration",
                    "-framework", "CoreFoundation",
                )
            }
        }

        if (iosTarget.name == "iosSimulatorArm64") {
            val testEntitlements = project.file("src/iosTest/resources/keychain-test.entitlements")
            iosTarget.binaries.withType<TestExecutable>().configureEach {
                linkerOpts("-sectcreate", "__TEXT", "__entitlements", testEntitlements.absolutePath)
                linkTaskProvider.configure { inputs.file(testEntitlements) }
            }
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        androidMain {
            kotlin.srcDir(project.file(androidDistributionSourceDir))
            if (androidDistribution == "full") {
                kotlin.srcDir(fullCommonSourceDir)
            }

            dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.appcompat)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.splashscreen)
                implementation(libs.androidx.work.runtime)
                implementation(libs.posthog.android)
                implementation(libs.coil.gif)
                implementation("androidx.recyclerview:recyclerview:1.4.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                // Per-playlist DNS-over-HTTPS for IPTV (P3) — resolves panel hosts over an
                // encrypted DoH endpoint on Android; iOS ignores the dnsProvider setting.
                implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
                implementation("com.google.code.gson:gson:2.11.0")
                implementation("io.github.peerless2012:ass-media:0.5.1")
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sentry.android)
                // media3 core, common and hls come from the fork AARs (libs/lib-exoplayer-,
                // lib-common-, lib-exoplayer-hls-release.aar via the lib-*.aar fileTree), NOT the
                // stock modules (all three excluded in configurations.all): mixing a stock module
                // with the fork core crashes on the ExoPlayer live path (getBandwidthMeter /
                // NuvioEngineConfig skews — see the configurations.all note). dash/smoothstreaming/
                // rtsp/session/container/extractor/datasource below stay stock and resolve against
                // the fork common (a superset of stock 1.11.0 common).
                implementation(libs.androidx.media3.exoplayer.dash)
                implementation(libs.androidx.media3.exoplayer.smoothstreaming)
                implementation(libs.androidx.media3.exoplayer.rtsp)
                implementation(libs.androidx.media3.datasource)
                implementation(libs.androidx.media3.datasource.okhttp)
                implementation(libs.androidx.media3.decoder)
                implementation(libs.androidx.media3.session)
                implementation(libs.androidx.media3.common)
                implementation(libs.androidx.media3.container)
                implementation(libs.androidx.media3.extractor)
                // Guava (ImmutableList etc.) is a compile dependency of media3-common. It used to
                // arrive transitively, but the fork common AAR (a flat file) carries no POM, so
                // excluding stock media3-common above dropped it. Re-declare it at the exact version +
                // exclusions media3-common 1.11.0 used, so nothing else on the classpath shifts.
                implementation("com.google.guava:guava:33.3.1-android") {
                    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
                    exclude(group = "org.checkerframework", module = "checker-compat-qual")
                    exclude(group = "com.google.code.findbugs", module = "jsr305")
                    exclude(group = "org.codehaus.mojo", module = "animal-sniffer-annotations")
                    exclude(group = "org.checkerframework", module = "checker-qual")
                    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
                }
                implementation(libs.mpv.android.lib)
                implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
                if (androidDistribution == "full") {
                    implementation(files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
                    implementation(libs.ksoup)
                }
            }
        }
        val androidHostTest by getting {
            dependencies {
                implementation("org.robolectric:robolectric:4.16")
                implementation("androidx.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
                implementation("androidx.compose.ui:ui-test-manifest:${libs.versions.composeMultiplatform.get()}")
                implementation("androidx.work:work-testing:${libs.versions.androidx.work.get()}")
                implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
            }
            if (androidDistribution == "full") {
                kotlin.srcDir(project.file("src/androidFullHostTest/kotlin"))
            }
        }
        commonMain.dependencies {
            implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-cache-control:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-svg:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialRipple)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.savedstate)
            implementation(libs.androidx.savedstate.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kmpalette.core)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.realtime)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.supabase.storage)
            implementation(libs.reorderable)
            // TMDB->Xtream match index: framework artifact resolves to AndroidSQLiteDriver
            // on Android and NativeSQLiteDriver (system libsqlite3) on iOS — no bundled binary
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.framework)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
            // Media-server (Jellyfin/Emby) client tests run real Ktor requests against canned responses.
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        // Host unit tests run on the JVM with no Android Context, so the framework SQLite driver
        // can't open. Tests that exercise IptvContentDb install the bundled driver in-memory via
        // IptvContentDbDriver.openForTests.
        getByName("androidHostTest").dependencies {
            // The -jvm artifact explicitly: the default resolves the ANDROID variant, whose
            // native sqliteJni .so isn't loadable on a desktop-JVM host test.
            implementation("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.androidx.sqlite.get()}")
            // Architecture test (Rule 6): static structure check, JVM-only (Konsist is JVM).
            implementation(libs.konsist)
        }
    }
}

configurations.matching { it.name == "iosMainImplementation" }.configureEach {
    project.dependencies.add(name, libs.ktor.client.darwin)
}

configurations.all {
    // The custom Nuvio engine fork replaces media3 core + common + hls (see libs/lib-*-release.aar,
    // the matched 1.11.0 rebuild in media3-engine/out/1.11.0). Two skews crash the app if a stock
    // module is mixed with the fork core, BOTH device-confirmed on an S24 Ultra (xsc.loruhon.com) on
    // the ExoPlayer live path:
    //  1. stock media3-exoplayer-hls calls getBandwidthMeter() on the core's BaseMediaSource, which
    //     the fork core does not expose -> NoSuchMethodError on every HLS prepare (2026-08-22).
    //  2. the fork core's DefaultAllocator reads androidx.media3.common.NuvioEngineConfig (a fork
    //     addition), which stock media3-common lacks -> NoClassDefFoundError building the ExoPlayer
    //     LoadControl, i.e. before playback even starts (2026-08-23). The nuvio-engine AAR ships a
    //     same-named class under com.nuvio.engine, NOT the androidx.media3.common package the core
    //     references, so it does not satisfy the link.
    // Exclude the stock core/common/hls modules and use the fork's own AARs (libs/lib-exoplayer-,
    // lib-common-, lib-exoplayer-hls-release.aar), which are built as a matched set against each
    // other. media3-ui is excluded because the fork does not ship it and Compose uses none of it.
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
    exclude(group = "androidx.media3", module = "media3-common")
    exclude(group = "androidx.media3", module = "media3-ui")
}

// JVM tests run in their own process: daemon heap settings do not size this worker.
// JDK 21 supports the Android SDK used by Robolectric; app bytecode targets stay unchanged.
val testJavaLauncher = extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    javaLauncher.set(testJavaLauncher)
    maxHeapSize = "4g"
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
