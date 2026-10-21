package com.nuvio.app.features.addons

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String {
    val installation = kotlinx.coroutines.currentCoroutineContext()[com.nuvio.app.core.sources.AddonInstallationRequest]?.permitsManifest(url) == true
    if (!installation) com.nuvio.app.core.sources.DeviceSources.guardAddon(url)
    val epoch = com.nuvio.app.core.sources.DeviceSources.epoch
    val result = if (forceRefresh) {
        httpGetTextWithHeaders(
            url = url,
            headers = mapOf("Cache-Control" to "no-cache"),
        )
    } else {
        httpGetText(url)
    }

    if (!installation) com.nuvio.app.core.sources.DeviceSources.guardAddon(url)
    if (epoch != com.nuvio.app.core.sources.DeviceSources.epoch) throw kotlinx.coroutines.CancellationException("Source choices changed")
    return result
}
