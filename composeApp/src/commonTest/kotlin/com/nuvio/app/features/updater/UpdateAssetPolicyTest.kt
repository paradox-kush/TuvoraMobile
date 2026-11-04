package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateAssetPolicyTest {
    private fun apk(name: String) = GitHubAssetDto(name = name, browserDownloadUrl = "https://x/$name", size = 1L)

    private val release = listOf(
        apk("T-1.0-arm64-v8a.apk"),
        apk("T-1.0-armeabi-v7a.apk"),
        apk("T-1.0.apk"),
        apk("T-1.0.aab"),
    )

    private fun names(assets: List<GitHubAssetDto>, abis: List<String>, is64: Boolean) =
        UpdateAssetPolicy.rank(assets, UpdateAssetPolicy.usableAbis(abis, is64)).map { it.name }

    @Test
    fun `64-bit device gets arm64 then v7a then universal`() {
        assertEquals(
            listOf("T-1.0-arm64-v8a.apk", "T-1.0-armeabi-v7a.apk", "T-1.0.apk"),
            names(release, listOf("arm64-v8a", "armeabi-v7a"), true),
        )
    }

    @Test
    fun `32-bit-only device never gets arm64`() {
        assertEquals(
            listOf("T-1.0-armeabi-v7a.apk", "T-1.0.apk"),
            names(release, listOf("armeabi-v7a", "armeabi"), false),
        )
    }

    @Test
    fun `64-bit kernel with 32-bit userland lists arm64 but never gets it`() {
        val ranked = names(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"), false)
        assertEquals(listOf("T-1.0-armeabi-v7a.apk", "T-1.0.apk"), ranked)
        assertTrue(ranked.none { it.contains("arm64") })
    }

    @Test
    fun `missing matching split falls back to universal`() {
        assertEquals(
            listOf("T-1.0.apk"),
            names(release.filterNot { it.name.contains("armeabi") }, listOf("armeabi-v7a"), false),
        )
    }

    @Test
    fun `only universal present`() {
        assertEquals(listOf("T-1.0.apk"), names(listOf(apk("T-1.0.apk")), listOf("arm64-v8a"), true))
    }

    @Test
    fun `wrong-ABI-only release yields nothing to download`() {
        assertEquals(emptyList(), names(listOf(apk("T-arm64-v8a.apk")), listOf("armeabi-v7a"), false))
        assertEquals(emptyList(), names(emptyList(), listOf("arm64-v8a"), true))
    }

    @Test
    fun `x86 does not match x86_64`() {
        val assets = listOf(apk("T-x86_64.apk"), apk("T-x86.apk"))
        assertEquals(listOf("T-x86.apk"), names(assets, listOf("x86"), false))
    }
}
