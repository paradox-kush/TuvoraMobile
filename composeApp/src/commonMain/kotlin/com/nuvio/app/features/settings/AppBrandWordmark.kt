package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.rememberResourceEnvironment

// Tuvora pins the brand wordmark to a single Tuvora asset across every theme/icon. Upstream selects a
// per-theme Nuvio wordmark here; the fork renders only the Tuvora wordmark so no Nuvio branding ever
// appears (the cosmetic theme/icon system still works everywhere else). This stays the one brand
// chokepoint — every caller (login, splash, member badge) shows Tuvora. The decoded-bitmap cache and
// the splash preload are upstream's (sync 16), pinned to the same resource.
private val TuvoraWordmark: DrawableResource get() = Res.drawable.app_logo_wordmark

@Composable
internal fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: AppIconOption? = null,
) {
    val resource = TuvoraWordmark
    val environment = rememberResourceEnvironment()
    val bitmap by produceState(AppBrandWordmarkCache[resource], resource, environment) {
        if (value == null) value = AppBrandWordmarkCache.load(resource, environment)
    }
    val loaded = bitmap
    val painter = if (loaded != null) {
        remember(loaded) { BitmapPainter(loaded) }
    } else {
        painterResource(resource)
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun PreloadAppBrandWordmark() {
    val resource = TuvoraWordmark
    val environment = rememberResourceEnvironment()
    LaunchedEffect(resource, environment) {
        AppBrandWordmarkCache.load(resource, environment)
    }
}

private object AppBrandWordmarkCache {
    private val bitmaps = MutableStateFlow<Map<DrawableResource, ImageBitmap>>(emptyMap())

    operator fun get(resource: DrawableResource): ImageBitmap? = bitmaps.value[resource]

    suspend fun load(resource: DrawableResource, environment: ResourceEnvironment): ImageBitmap {
        get(resource)?.let { return it }
        val bitmap = withContext(Dispatchers.Default) {
            getDrawableResourceBytes(environment, resource).decodeToImageBitmap()
        }
        bitmaps.update { cached -> cached + (resource to bitmap) }
        return bitmap
    }
}
