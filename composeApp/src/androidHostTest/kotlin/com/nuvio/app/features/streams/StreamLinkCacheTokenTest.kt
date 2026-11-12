package com.nuvio.app.features.streams

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamLinkCacheTokenTest {
    private val key = StreamLinkCacheRepository.contentKey(type = "movie", videoId = "tt123")

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_stream_link_cache", Context.MODE_PRIVATE).edit().clear().commit()
        StreamLinkCacheStorage.initialize(context)
        com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest()
        com.nuvio.app.core.contracts.OwnSourcePolicy.registerProviderIdPredicate("test-iptv") {
            it == "xtream" || it.startsWith("xtream-match:")
        }
    }

    @After
    fun resetRegistries() = com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest()

    /** Fork rule kept through upstream 3d70db683: a single-use Stalker link never enters the reuse cache. */
    @Test
    fun stalkerSingleUseLinkIsNeverCached() {
        StreamLinkCacheRepository.save(
            contentKey = key,
            url = "http://portal.example/play/live.php?mac=00:1A:79:00:00:01&stream=42&play_token=abc",
            streamName = "Channel",
            addonName = "IPTV",
            addonId = "xtream-match:stalker|http://portal.example|00:1A:79:00:00:01",
        )

        assertNull(StreamLinkCacheRepository.getValid(key, ONE_HOUR_MS))
    }

    @Test
    fun linkWithExpiringTokenIsKept() {
        val tokenUrl = "https://cdn.example.com/dld/file-id?token=abc&expires=1234"

        StreamLinkCacheRepository.save(
            contentKey = key,
            url = tokenUrl,
            streamName = "1080p",
            addonName = "Test",
            addonId = "addon:test",
        )

        assertEquals(tokenUrl, StreamLinkCacheRepository.getValid(key, ONE_HOUR_MS)?.url)
    }

    private companion object {
        const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
