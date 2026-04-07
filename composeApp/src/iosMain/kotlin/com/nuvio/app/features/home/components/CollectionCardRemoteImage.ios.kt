package com.nuvio.app.features.home.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import platform.CoreGraphics.CGImageRef
import platform.CoreFoundation.CFDataCreate
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode
import platform.CoreGraphics.CGImageRelease
import kotlinx.cinterop.usePinned

private val gifHttpClient = HttpClient(Darwin)
private val gifDecodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private const val MaxCachedGifImages = 12
private val gifImageCache = mutableMapOf<String, UIImage>()
private val gifImageCacheOrder = mutableListOf<String>()
private val gifImageInFlight = mutableMapOf<String, Deferred<UIImage?>>()

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    if (!animateIfPossible) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    var gifImage by remember(imageUrl) { mutableStateOf(cachedGifImage(imageUrl)) }

    LaunchedEffect(imageUrl) {
        gifImage = loadGifImage(imageUrl)
    }

    UIKitView(
        modifier = modifier,
        factory = {
            UIImageView().apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                clipsToBounds = true
                userInteractionEnabled = false
                image = gifImage
                tag = imageUrl.hashCode().toLong()
            }
        },
        update = { imageView ->
            if (imageView.tag != imageUrl.hashCode().toLong()) {
                imageView.tag = imageUrl.hashCode().toLong()
            }
            imageView.image = gifImage
        },
    )
}

private fun cachedGifImage(imageUrl: String): UIImage? {
    val image = gifImageCache[imageUrl] ?: return null
    gifImageCacheOrder.remove(imageUrl)
    gifImageCacheOrder.add(imageUrl)
    return image
}

private fun storeGifImage(imageUrl: String, image: UIImage) {
    gifImageCache[imageUrl] = image
    gifImageCacheOrder.remove(imageUrl)
    gifImageCacheOrder.add(imageUrl)

    while (gifImageCacheOrder.size > MaxCachedGifImages) {
        val eldestKey = gifImageCacheOrder.removeFirstOrNull() ?: break
        gifImageCache.remove(eldestKey)
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun loadGifImage(imageUrl: String): UIImage? {
    cachedGifImage(imageUrl)?.let { return it }

    val request = gifImageInFlight[imageUrl] ?: gifDecodeScope.async {
        runCatching {
            val bytes = gifHttpClient.get(imageUrl).body<ByteArray>()
            bytes
                .takeIf { it.isNotEmpty() }
                ?.toCFData()
                ?.let { UIImage.gifImageWithData(it) }
        }.getOrNull()
    }.also { gifImageInFlight[imageUrl] = it }

    val image = try {
        request.await()
    } finally {
        if (gifImageInFlight[imageUrl] === request) {
            gifImageInFlight.remove(imageUrl)
        }
    }

    if (image != null) {
        storeGifImage(imageUrl, image)
    }

    return image
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData() =
    usePinned { pinned ->
        CFDataCreate(
            allocator = null,
            bytes = pinned.addressOf(0).reinterpret(),
            length = size.toLong(),
        )
    }

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.Companion.gifImageWithData(data: kotlinx.cinterop.CPointer<cnames.structs.__CFData>?): UIImage? {
    return runCatching {
        val source = data?.let { CGImageSourceCreateWithData(it, null) } ?: return null
        val count = CGImageSourceGetCount(source).toInt()
        val frames = mutableListOf<UIImage>()

        for (index in 0 until count) {
            val imageRef: CGImageRef = CGImageSourceCreateImageAtIndex(source, index.toULong(), null) ?: continue
            try {
                frames.add(UIImage.imageWithCGImage(imageRef))
            } finally {
                CGImageRelease(imageRef)
            }
        }

        if (frames.isEmpty()) return null

        val durationSeconds = (count * 0.1).coerceAtLeast(0.1)
        UIImage.animatedImageWithImages(frames, durationSeconds)
    }.getOrNull()
}