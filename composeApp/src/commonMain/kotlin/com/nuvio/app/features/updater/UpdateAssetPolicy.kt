package com.nuvio.app.features.updater

/**
 * Decides which release APKs this device may install, best first (B207). Mirrors NuvioTV's
 * `UpdateAssetPolicy`: matching ABI splits in device order, then the universal APK, then any APK that
 * names no ABI; a split for an ABI the device cannot use is never returned (never downloaded).
 */
internal object UpdateAssetPolicy {

    private val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * A 32-bit app process cannot load 64-bit libraries even when the CPU's abilist advertises
     * arm64-v8a (64-bit kernel with 32-bit userland), so those entries are dropped.
     */
    fun usableAbis(supportedAbis: List<String>, is64BitProcess: Boolean): List<String> =
        supportedAbis.filter { is64BitProcess || !it.contains("64") }

    fun rank(assets: List<GitHubAssetDto>, supportedAbis: List<String>): List<GitHubAssetDto> {
        val apks = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType == "application/vnd.android.package-archive"
        }
        val ranked = LinkedHashSet<GitHubAssetDto>()
        for (abi in supportedAbis) {
            apks.filter { mentions(it.name, abi) }.forEach { ranked += it }
        }
        apks.filter { it.name.contains("universal", ignoreCase = true) && mentionsNoAbi(it.name) }
            .forEach { ranked += it }
        apks.filter { mentionsNoAbi(it.name) }.forEach { ranked += it }
        return ranked.toList()
    }

    private fun mentionsNoAbi(name: String) = knownAbis.none { mentions(name, it) }

    // "x86" must not match inside "x86_64".
    private fun mentions(name: String, abi: String): Boolean =
        Regex("(?<![a-z0-9])${Regex.escape(abi)}(?![a-z0-9_])", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
