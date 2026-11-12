package com.nuvio.app.core.ui

import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.size.Precision
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTierPolicy
import com.nuvio.app.addSharedImageComponents
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.network.NetworkErrorInterceptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(context: PlatformContext): ImageLoader.Builder =
    components {
        // This `components {}` REPLACES the registry App.kt built (Coil keeps only the last one set), so the shared
        // components (poster fallback, SVG, pinned-server artwork) are added here again, before the network fetcher.
        addSharedImageComponents()
        // Explicit fetcher so image loads share the app's IPv4-first DNS ordering — badge/poster
        // CDNs advertise AAAA records that dead-end on broken-IPv6 routes (same fix as TV's
        // image loader; the service-loaded default fetcher uses a stock client without it).
        add(
            KtorNetworkFetcherFactory(
                HttpClient(OkHttp) {
                    engine {
                        preconfigured = OkHttpClient.Builder()
                            .dns(IPv4FirstDns())
                            // upstream d534d14e9: an SSL setup RuntimeException becomes a failed load, not a crash
                            .addInterceptor(NetworkErrorInterceptor())
                            .followRedirects(true)
                            .build()
                    }
                }
            )
        )
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }
        // ⚠️ This cap is a GRAPHICS-memory budget, not a heap one — the original reasoning here
        // ("poster bitmaps share the ~256MB heap with ExoPlayer's media buffer") was wrong on any
        // device since API 26. Coil's `allowHardware` defaults to TRUE, so decoded posters are
        // Bitmap.Config.HARDWARE, which lives in gralloc/EGL memory and is counted under
        // `summary.graphics`, NOT the Java heap. Confirmed by telemetry on 2026-08-16: at process
        // death graphics was 154-192MB against a Java heap of 52-71MB nowhere near its ceiling
        // (research/graphics-memory.md).
        //
        // The cap still works, it just bounds a different pool than intended — and it is sized from
        // `memoryClass`, which is a HEAP figure. Re-basing it on something graphics-shaped (screen
        // size matters: the window's own triple buffer is ~30MB at 1080p and ~54MB at 1440p) is
        // deliberately NOT done here, because the right numbers need the per-pool measurement in
        // research/graphics-memory.md §6. Guessing again would repeat the original mistake.
        //
        // UNVERIFIED: whether `allowRgb565(true)` below does anything once the hardware-bitmap path
        // wins the config decision. Coil's docs do not state the precedence. Settle it before
        // relying on RGB565 for any sizing argument.
        .memoryCache {
            val memory = MemoryPortAccess.current()
            val cap = MemoryTierPolicy.imageMemoryCacheBytes(memory.baseTier())
            val cache = MemoryCache.Builder().maxSizeBytes(cap).build()
            memory.registerBudget("image_memory_cache", cap, priority = 0) { cache.clear() }
            cache
        }
        .allowRgb565(true)
        .precision(Precision.INEXACT)

// The Application implements SingletonImageLoader.Factory (NuvioApplication) so the loader exists
// before Coil's first request; the composable factory in App.kt is skipped on Android.
internal actual val platformProvidesImageLoader: Boolean = true
