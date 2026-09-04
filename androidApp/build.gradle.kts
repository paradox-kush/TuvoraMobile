import java.util.Properties

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
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.sentry.android.gradle)
    id("com.posthog.android") version "1.4.0"
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
fun envOrLocalProperty(key: String): String? =
    providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

// Env wins over local.properties so CI can sign different flavors with different
// keys in one job (full = release key, playstore = dedicated Play upload key).
val releaseStoreFile = envOrLocalProperty("NUVIO_RELEASE_STORE_FILE")
val releaseStorePassword = envOrLocalProperty("NUVIO_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = envOrLocalProperty("NUVIO_RELEASE_KEY_ALIAS")
val releaseKeyPassword = envOrLocalProperty("NUVIO_RELEASE_KEY_PASSWORD")
val releaseKeystore = releaseStoreFile?.let(rootProject::file)

val sentryAuthToken = envOrLocalProperty("SENTRY_AUTH_TOKEN")
val sentryOrg = envOrLocalProperty("SENTRY_ORG")
val sentryProject = envOrLocalProperty("SENTRY_PROJECT")
val sentryMappingUploadEnabled = sentryAuthToken != null && sentryOrg != null && sentryProject != null
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = providers.gradleProperty("versionNameOverride").orNull?.takeIf { it.isNotBlank() }
    ?: readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = providers.gradleProperty("versionCodeOverride").orNull?.toIntOrNull()
    ?: readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val buildsReleaseApks = requestedTaskNames.any {
    it.startsWith("assemble", ignoreCase = true) && it.endsWith("Release", ignoreCase = true)
}
// When the same invocation also builds an AAB (release.yml runs assemble + bundle together),
// ABI splits must be OFF or bundling fails with "Multiple shrunk-resources files". Mirror TV.
val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
// ARM-only ABI trim is expressed TWO ways depending on this flag, because AGP 9 forbids
// ndk.abiFilters and an enabled splits.abi filter in the same configuration:
//  - splits ENABLED  (assemble-only release) -> splits.abi.include restricts to ARM.
//  - splits DISABLED (the shipped assemble+bundle path, bundle-only, and debug) -> the
//    single fat APK / AAB would otherwise carry every compiled ABI, so ndk.abiFilters
//    restricts compilation to ARM instead. Exactly one of the two is active at a time.
val abiSplitsEnabled = buildsReleaseApks && !buildingAppBundle

android {
    namespace = "com.nuvio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.tuvora.mobile"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName

        // Compile native code for ARM only (see abiSplitsEnabled above). In the shipped
        // release path release.yml runs assemble+bundle together, so splits are OFF and
        // assembleFullRelease emits a SINGLE fat APK carrying every COMPILED ABI. Without
        // this filter that APK bundled libmpv + FFmpeg for all four ABIs (~105 MB) —
        // x86/x86_64 being pure dead weight, since every real phone is ARM. Restricting to
        // both ARM ABIs shrinks the shipped Tuvora-<ver>.apk (and the AAB) while still
        // installing on every ARM device, 64-bit and 32-bit, with no wrong-ABI brick. Per
        // Google's fallback ("if you can't ship x86, include both arm32 and arm64"), keeping
        // armeabi-v7a also covers x86 Chromebooks via arm32 translation. Only set when splits
        // are OFF — AGP 9 rejects abiFilters alongside an enabled splits.abi filter.
        if (!abiSplitsEnabled) {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }

    sourceSets.getByName("full") {
        manifest.srcFile("src/full/AndroidManifest.xml")
        jniLibs.directories.add("../composeApp/src/full/jniLibs")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    splits {
        abi {
            isEnable = abiSplitsEnabled
            reset()
            // ARM only — keep in sync with defaultConfig.ndk.abiFilters above. x86/x86_64 are
            // never compiled, so listing them here would only produce empty/dead split APKs.
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../composeApp/proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.nuviodebug.com")
    }
}

sentry {
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryMappingUploadEnabled)
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    autoUploadSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    sentryAuthToken?.let(authToken::set)
    sentryOrg?.let(org::set)
    sentryProject?.let(projectName::set)
    ignoredBuildTypes.set(setOf("debug"))
    autoInstallation {
        enabled.set(false)
    }
    tracingInstrumentation {
        enabled.set(false)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.appcompat)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
}
