package com.nuvio.app.features.mediaserver.internal.live

import com.nuvio.app.core.contracts.PlaybackPlayMethod
import com.nuvio.app.core.contracts.PlaybackSessionState
import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.MediaServerAccounts
import com.nuvio.app.features.mediaserver.internal.SignInResult
import com.nuvio.app.features.mediaserver.internal.FakeEntriesPersistence
import com.nuvio.app.features.mediaserver.internal.FakeSecureTokenStore
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.app.features.mediaserver.internal.store.MediaServerCredentialStore
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import com.nuvio.app.features.mediaserver.internal.client.AuthorityRoutingHttp
import com.nuvio.app.features.mediaserver.internal.client.DiscoveryResult
import com.nuvio.app.features.mediaserver.internal.client.HealthStatus
import com.nuvio.app.features.mediaserver.internal.client.ItemsQuery
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.mediaserver.internal.client.QuickConnectOutcome
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.source.MediaServerItemMapper
import com.nuvio.app.features.mediaserver.internal.source.MediaServerItemRegistry
import com.nuvio.app.features.mediaserver.internal.source.MediaServerPlaybackSessions
import com.nuvio.app.features.mediaserver.internal.source.MediaServerSessionReporter
import com.nuvio.app.features.mediaserver.internal.source.MediaServerStreamSourceProvider
import com.nuvio.app.features.mediaserver.internal.store.MediaServerTrustStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LIVE end-to-end against a real local server, on the Android OkHttp client (the same Ktor/OkHttp stack the app
 * ships). Skipped unless the environment names one:
 *
 *   TUVORA_MS_E2E_JELLYFIN=http://localhost:18096|<user>|<password>   (docker jellyfin/jellyfin:12.x)
 *   TUVORA_MS_E2E_EMBY=http://localhost:18097|<user>|<password>       (docker emby/embyserver)
 *
 * No credentials live in the repository: the throwaway user of a throwaway container is passed by the environment.
 * Library expected (generated ffmpeg clips): "Test Movie (2024)" (15 s), "SIGNAL: The Movie ..." / "Long Movie (2022)" (7 min).
 */
class MediaServerLiveE2ETest {
    private class Target(val url: String, val user: String, val password: String)

    private fun target(env: String): Target? {
        val raw = System.getenv(env)
        if (raw.isNullOrBlank()) {
            check(System.getenv("TUVORA_MS_E2E_REQUIRED") != "true") { "Required live target is missing: $env" }
            org.junit.Assume.assumeTrue("Live target not configured: $env", false)
            return null
        }
        val parts = raw.split('|')
        require(parts.size == 3 && parts.all { it.isNotBlank() }) { "Invalid live target configuration: $env" }
        return Target(parts[0], parts[1], parts[2])
    }

    /** The production wiring (real HTTP stack, in-memory secure store/persistence) as a separate "install". */
    private class LiveRig {
        val secure = FakeSecureTokenStore()
        val credentials = MediaServerCredentialStore(secure)
        var nowMs: Long = System.currentTimeMillis()
        val store = MediaServerEntryStore(FakeEntriesPersistence(), { null }, { 1 }, { _, _ -> })
        val services = MediaServerServices(
            http = AuthorityRoutingHttp(MediaServerTrust(MediaServerTrustStore({ null }, { }, { }))),
            credentials = credentials,
            identityProvider = { MediaBrowserClientIdentity("Tuvora", "E2E", credentials.deviceId(), "1.0.0") },
            nowMs = { nowMs },
        )
    }

    private fun realRig() = LiveRig()

    private fun httpStatus(url: String, headers: Map<String, String> = emptyMap(), range: String? = "bytes=0-99", method: String = "GET"): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        range?.let { c.setRequestProperty("Range", it) }
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        return try { c.responseCode } finally { c.disconnect() }
    }

    @Test
    fun jellyfinEndToEnd() {
        val t = target("TUVORA_MS_E2E_JELLYFIN") ?: return
        runBlocking {
            val rig = realRig()
            val services = rig.services
            val accounts = MediaServerAccounts(rig.store, services, listOf(1))

            // discovery of what a person would type
            val hostPort = t.url.removePrefix("http://")
            val found = assertIs<DiscoveryResult.Found>(services.discover(hostPort, MediaServerType.JELLYFIN))
            assertEquals(t.url, found.baseUrl)
            assertEquals(MediaServerType.JELLYFIN, found.type)
            assertFalse(found.typeMismatch)
            val api = services.authApi(found.baseUrl, found.type)
            assertTrue(api.quickConnectEnabled())

            // wrong password -> 401, nothing stored
            val bad = kotlin.runCatching { api.authenticateByName(t.user, "definitely-wrong") }.exceptionOrNull()
            assertTrue((bad as MediaServerException.Http).isUnauthorized)

            // password sign-in -> add the server
            val session = api.authenticateByName(t.user, t.password)
            val added = assertIs<SignInResult.Success>(accounts.addServer(found.type, found.baseUrl, found.info, session, displayName = null))
            val entry = added.entry
            assertEquals("jellyfin|${found.info.machineId}|${session.userId}", entry.key)
            assertTrue(services.isSignedIn(entry))
            assertEquals(HealthStatus.ONLINE, services.health(entry))
            val client = assertNotNull(services.clientFor(entry))

            // browse
            val views = client.views()
            val movieLib = views.first { it.collectionType == "movies" }
            val movies = client.items(ItemsQuery(parentId = movieLib.id, includeItemTypes = listOf("Movie"), fields = "Overview,ProviderIds,MediaSources", limit = 20)).items
            assertTrue(movies.size >= 2, "expected the generated library, got ${movies.map { it.name }}")
            val short = movies.first { it.name?.startsWith("Test Movie") == true }
            val long = movies.first { (it.runTimeTicks ?: 0) > 3_000_000_000L }
            assertNotNull(MediaServerItemMapper.preview(entry, short))
            assertTrue(client.search("Test", 10).isNotEmpty())
            val series = client.items(ItemsQuery(includeItemTypes = listOf("Series"), limit = 5)).items.first()
            val eps = client.episodes(series.id!!)
            assertTrue(eps.isNotEmpty() && client.seasons(series.id!!).isNotEmpty())

            // playback: deferred -> minted static URL, played anonymously (Jellyfin's direct stream needs no token)
            MediaServerItemMapper.registered(entry, client.item(long.id!!, "MediaSources")!!)?.let(MediaServerItemRegistry::register)
            val provider = MediaServerStreamSourceProvider(rig.store, services)
            val videoId = MediaServerIds.contentId(entry, MediaServerIds.Kind.MOVIE, long.id!!)
            val stream = assertNotNull(provider.directStreamItem(videoId))
            assertTrue(stream.url!!.startsWith("ms-deferred:") && !stream.url!!.contains(session.accessToken))
            val minted = assertNotNull(provider.resolveDeferredUrl(stream.url!!, forceMint = false))
            assertFalse(minted.contains(session.accessToken), "direct play URL is tokenless: $minted")
            assertEquals(206, httpStatus(minted), "static stream plays with NO token")
            assertEquals(PlaybackPlayMethod.DIRECT_PLAY, MediaServerPlaybackSessions.latestFor(entry.serverKey)!!.playMethod)

            // the fallback transcode comes with the server's own ApiKey and plays
            val transcode = assertNotNull(provider.resolveDeferredUrl(stream.url!!, forceMint = true))
            assertTrue(transcode.contains("master.m3u8") && transcode.contains("ApiKey="), transcode)
            assertEquals(200, httpStatus(transcode, range = null))
            assertEquals(401, httpStatus(transcode.substringBefore("ApiKey=") + "ApiKey=wrong", range = null), "the server enforces its own token on the transcode route")

            // progress reporting: stop at ~38% -> the server keeps the position and lists it under resume; the reporter drives the real endpoints
            MediaServerPlaybackSessions.record(MediaServerPlaybackSessions.Session(entry.serverKey, long.id!!, long.mediaSources.first().id, "e2e-session", PlaybackPlayMethod.DIRECT_PLAY))
            val reporter = MediaServerSessionReporter(rig.store, services, { rig.nowMs })
            val duration = (long.runTimeTicks!! / 10_000)
            fun state(pos: Long) = PlaybackSessionState(videoId, videoId, "ms", pos, duration)
            reporter.onStart(state(0))
            rig.nowMs += 12_000
            reporter.onProgress(state(60_000), paused = false)
            rig.nowMs += 1_000
            reporter.onProgress(state(60_000), paused = true)
            val stopAt = duration * 38 / 100
            reporter.onStop(state(stopAt))
            val after = client.item(long.id!!)!!
            assertEquals(false, after.userData?.played)
            val serverPos = after.userData!!.playbackPositionTicks / 10_000
            assertTrue(kotlin.math.abs(serverPos - stopAt) < 2_000, "server position $serverPos vs $stopAt")
            val shelves = client.homeShelves(setOf(MediaServerHomeRow.CONTINUE_WATCHING), 20, "Overview")
            assertTrue(shelves.continueWatching.any { it.id == long.id }, "resumable on the server")

            // finishing at 96%: the server marks played itself from the Stopped report; no explicit mark
            val r2 = MediaServerSessionReporter(rig.store, services, { rig.nowMs })
            r2.onStart(state(0)); r2.onStop(state(duration * 96 / 100))
            val finished = client.item(long.id!!)!!
            assertEquals(true, finished.userData?.played, "the server marked it played from the Stopped report alone")
            client.setPlayed(long.id!!, false)
            assertEquals(false, client.item(long.id!!)!!.userData?.played)

            // Quick Connect, "approve from Tuvora on your phone": a SECOND install (own device id) shows a code; THIS session approves it
            val other = realRig()
            val shown = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val flow = async { other.services.signInWithQuickConnect(found.baseUrl, found.type) { req, _ -> shown.set(req.code) } }
            var waited = 0
            while (shown.get() == null && waited++ < 100) delay(100)
            val code = assertNotNull(shown.get(), "the second device showed a code")
            client.authorizeQuickConnect(code) // "approve from Tuvora on your phone": THIS session authorises the OTHER install's code
            val outcome = flow.await()
            val signedIn = assertIs<QuickConnectOutcome.SignedIn>(outcome)
            assertEquals(session.userId, signedIn.session.userId)
            assertTrue(signedIn.session.accessToken != session.accessToken, "tokens are device-bound: the phone's token is NOT shared")
            val otherEntry = assertIs<SignInResult.Success>(
                MediaServerAccounts(other.store, other.services, listOf(1)).addServer(found.type, found.baseUrl, found.info, signedIn.session, "Phone-approved"),
            ).entry
            assertEquals(HealthStatus.ONLINE, other.services.health(otherEntry))
            assertEquals(HealthStatus.ONLINE, services.health(entry), "the approving device's own session is untouched")

            // revocation: a server-side logout is detected by an AUTHENTICATED call and drops only that device's session
            val logout = other.services.http.execute(
                com.nuvio.app.features.mediaserver.internal.client.MediaServerRequest(
                    "POST", "${found.baseUrl}/Sessions/Logout",
                    mapOf("Authorization" to com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserAuth.authorization(other.services.identity(), signedIn.session.accessToken)),
                ),
            )
            assertTrue(logout.isSuccess)
            assertEquals(HealthStatus.AUTH_ERROR, other.services.health(otherEntry))
            assertFalse(other.services.isSignedIn(otherEntry))
            assertEquals(HealthStatus.ONLINE, services.health(entry))
        }
    }

    @Test
    fun embyEndToEnd() {
        val t = target("TUVORA_MS_E2E_EMBY") ?: return
        runBlocking {
            val rig = realRig()
            val services = rig.services
            val accounts = MediaServerAccounts(rig.store, services, listOf(1))
            val found = assertIs<DiscoveryResult.Found>(services.discover(t.url.removePrefix("http://"), MediaServerType.EMBY))
            assertEquals(MediaServerType.EMBY, found.type)
            val api = services.authApi(found.baseUrl, found.type)
            assertFalse(api.quickConnectEnabled(), "Emby has no Quick Connect")
            val session = api.authenticateByName(t.user, t.password)
            val entry = (accounts.addServer(found.type, found.baseUrl, found.info, session, null) as SignInResult.Success).entry
            assertEquals(HealthStatus.ONLINE, services.health(entry))
            val client = assertNotNull(services.clientFor(entry))
            val views = client.views()
            assertTrue(views.isNotEmpty())
            val movies = client.items(ItemsQuery(includeItemTypes = listOf("Movie"), fields = "Overview,ProviderIds", limit = 20)).items
            assertTrue(movies.isNotEmpty() && movies.all { it.id!!.all(Char::isDigit) })
            val movie = movies.first()
            val detail = client.item(movie.id!!, "MediaSources")!!
            assertTrue(detail.mediaSources.first().id!!.startsWith("mediasource_"))
            MediaServerItemMapper.registered(entry, detail)?.let(MediaServerItemRegistry::register)
            val provider = MediaServerStreamSourceProvider(rig.store, services)
            val videoId = MediaServerIds.contentId(entry, MediaServerIds.Kind.MOVIE, movie.id!!)
            val item = assertNotNull(provider.directStreamItem(videoId))
            val headers = assertNotNull(item.behaviorHints.proxyHeaders?.request)
            val minted = assertNotNull(provider.resolveDeferredUrl(item.url!!, false))
            assertFalse(minted.contains(session.accessToken), "no token in the URL: $minted")
            assertEquals(401, httpStatus(minted), "Emby's stream route is not anonymous")
            assertEquals(206, httpStatus(minted, headers), "...but the X-Emby-Token header from the stream item plays it")
            assertTrue(client.search("Movie", 10).isNotEmpty() || client.search("Test", 10).isNotEmpty())
            // reports need a PlaySessionId on Emby: the client always sends one
            val reporter = MediaServerSessionReporter(rig.store, services, { rig.nowMs })
            val duration = ((detail.runTimeTicks ?: 600_000_000L) / 10_000)
            fun state(pos: Long) = PlaybackSessionState(videoId, videoId, "ms", pos, duration)
            reporter.onStart(state(0)); rig.nowMs += 12_000; reporter.onProgress(state(5_000), false); reporter.onStop(state(5_000))
            assertTrue(services.isSignedIn(entry), "no report was rejected as unauthorised")
            val shelves = client.homeShelves(setOf(MediaServerHomeRow.RECENTLY_ADDED, MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.CONTINUE_WATCHING), 20, "Overview")
            assertTrue(shelves.recentlyAdded.isNotEmpty())
        }
    }
}
