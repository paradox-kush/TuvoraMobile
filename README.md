<div align="center">

  <img src="composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png" alt="Tuvora" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    One media app for your phone, tablet and Apple TV.
    <br />
    IPTV • Live TV guide • Sports Centre • Your own media servers • One account, every screen
  </p>

</div>

## About

Tuvora is a media player that works the way you already watch: add the sources you have once, and they follow you to every screen you sign in on. This repository holds the **Android**, **iPhone/iPad** and **Apple TV** apps. They share one Kotlin Multiplatform codebase, with native entry points per platform.

Tuvora also ships for [Android TV](https://github.com/paradox-kush/TuvoraTV) and [desktop](https://github.com/paradox-kush/TuvoraDesktop) (Windows, macOS, Linux), and has a web dashboard at [tuvora.co](https://tuvora.co).

Tuvora is built on Nuvio by NuvioMedia. See [Credits](#credits).

## What's inside

- **IPTV**: Xtream Codes, Stalker portal and M3U (URL or file) playlists, with Live TV, VOD and series.
- **Live TV guide** with EPG, including matching external guide feeds to your channels.
- **Sports Centre**: fixtures with automatic channel matching, game alerts you control, and an option to hide scores until you choose to look.
- **Media servers**: Jellyfin and Emby libraries sit next to your other sources; Plex on the phone app.
- **Account sync**: one self-hosted account syncs profiles, library, watch progress, watched items, add-ons and IPTV playlists across devices.
- **Episode alerts and a customisable Home**: alerts with quiet hours, a daily cap and a switch per show; every Home section can be moved or hidden.

## Installation

Get the latest build for your device from [tuvora.co](https://tuvora.co), or download it directly:

- **Android**: the latest APK is at [tuvora.co/apk](https://tuvora.co/apk) and on [GitHub Releases](https://github.com/paradox-kush/TuvoraMobile/releases/latest).
- **iPhone, iPad and Apple TV**: distributed through TestFlight. Join from [tuvora.co](https://tuvora.co).

## Development

Prerequisites: JDK 17, the Android SDK, and (for iOS and Apple TV) Xcode on macOS.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17      # adjust to where your JDK 17 lives
export ANDROID_HOME=/path/to/android-sdk

git clone https://github.com/paradox-kush/TuvoraMobile.git
cd TuvoraMobile
./scripts/run-mobile.sh android
# or
./scripts/run-mobile.sh ios
```

### Project structure

- `composeApp/` is the shared Kotlin Multiplatform and Compose Multiplatform code.
  - `src/commonMain/` shared UI, features, repositories and platform-agnostic logic.
  - `src/androidMain/` Android-specific integrations.
  - `src/iosMain/` iOS-specific integrations.
- `androidApp/` is the Android application module.
- `iosApp/` is the native Xcode project and iOS entry point.
- `tvosCore/` compiles the shared code for tvOS, and `tvosApp/` is the SwiftUI Apple TV app.
- `ARCHITECTURE.md` describes the module rules that the build enforces. Read it before adding a feature.

### Build and test

```bash
./gradlew :composeApp:compileAndroidMain               # Android
./gradlew :composeApp:compileKotlinIosSimulatorArm64   # iOS
./gradlew :tvosCore:compileKotlinTvosSimulatorArm64    # Apple TV shared code

./gradlew :composeApp:testAndroidHostTest              # unit tests, JVM
./gradlew :composeApp:iosSimulatorArm64Test            # unit tests, Kotlin/Native
./gradlew :tvosCore:tvosSimulatorArm64Test             # Apple TV shared code tests
```

Both test runners must pass: a change can be green on the JVM and still fail on Kotlin/Native. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

Versioning is driven from `iosApp/Configuration/Version.xcconfig`, the shared source of truth for the iOS and Android builds.

## Legal

Tuvora is a client-side interface for browsing metadata and playing media from sources you add yourself. It is intended for content you own or are otherwise authorized to access. Tuvora does not host, store or distribute any media content, and is not affiliated with any third-party source, catalog or content provider.

Read the full [Terms of Use](https://tuvora.co/terms) and [Privacy Policy](https://tuvora.co/privacy).

## Built with

- Kotlin Multiplatform and Compose Multiplatform
- AndroidX Media3 and libmpv for playback
- SwiftUI and AVFoundation on Apple platforms
- Supabase for account sync

## Credits

Tuvora is built on [Nuvio](https://github.com/NuvioMedia/NuvioMobile) by NuvioMedia. All upstream attribution and licenses are preserved.

## License

Tuvora Mobile is licensed under the [GNU General Public License v3.0](LICENSE).

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/paradox-kush/TuvoraMobile.svg?style=for-the-badge
[contributors-url]: https://github.com/paradox-kush/TuvoraMobile/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/paradox-kush/TuvoraMobile.svg?style=for-the-badge
[forks-url]: https://github.com/paradox-kush/TuvoraMobile/network/members
[stars-shield]: https://img.shields.io/github/stars/paradox-kush/TuvoraMobile.svg?style=for-the-badge
[stars-url]: https://github.com/paradox-kush/TuvoraMobile/stargazers
[issues-shield]: https://img.shields.io/github/issues/paradox-kush/TuvoraMobile.svg?style=for-the-badge
[issues-url]: https://github.com/paradox-kush/TuvoraMobile/issues
[license-shield]: https://img.shields.io/github/license/paradox-kush/TuvoraMobile.svg?style=for-the-badge
[license-url]: https://github.com/paradox-kush/TuvoraMobile/blob/main/LICENSE
