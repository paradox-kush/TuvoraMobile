package com.nuvio.app.features.mediaserver.internal.live

import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.FakeEntriesPersistence
import com.nuvio.app.features.mediaserver.internal.FakeSecureTokenStore
import com.nuvio.app.features.mediaserver.internal.MediaServerAccounts
import com.nuvio.app.features.mediaserver.internal.SignInResult
import com.nuvio.app.features.mediaserver.internal.client.AuthorityRoutingHttp
import com.nuvio.app.features.mediaserver.internal.client.DiscoveryResult
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.ExternalIds
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.TitleFacts
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.source.MediaServerMatchLane
import com.nuvio.app.features.mediaserver.internal.source.MediaServerPlaybackSessions
import com.nuvio.app.features.mediaserver.internal.source.MediaServerStreamSourceProvider
import com.nuvio.app.features.mediaserver.internal.store.MediaServerCredentialStore
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import com.nuvio.app.features.mediaserver.internal.store.MediaServerTrustStore
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LIVE: a server whose library is filled by add-ons (Remux, or Jellyfin + Gelato) answers ONE title with SEVERAL
 * versions - one per add-on result. The title page must offer every one, and picking a version must play THAT
 * version (the mint step resolves the picked MediaSourceId, never "the server's first"). Skipped unless set:
 *
 *   TUVORA_MS_E2E_ONDEMAND=http://localhost:18108|<user>|<password>             (standalone Jellyfin-API server)
 *   TUVORA_MS_E2E_AGGREGATOR=http://localhost:18109/jellyfin|<config uuid>|<password>  (an add-on aggregator's Jellyfin endpoint)
 *
 * Library expected: research/virtual-library-testbed mock add-on, "Many Streams Movie" (tt0000011, 2012) with six
 * distinct results. The mock's log shows which upstream file (`?v=1`..`?v=6`) each pick fetched.
 */
class MediaServerMultiStreamLiveTest {
    @Test
    fun aStandaloneOnDemandServerOffersEveryResultAndPlaysThePickedOne() = run("TUVORA_MS_E2E_ONDEMAND")

    @Test
    fun anAddOnAggregatorsJellyfinEndpointOffersEveryResultAndPlaysThePickedOne() = run("TUVORA_MS_E2E_AGGREGATOR")

    /** The aggregator with "resolve on open" off: the item page carries only placeholders, the versions come from PlaybackInfo. */
    @Test
    fun anAggregatorThatResolvesOnlyOnPlayStillOffersEveryResult() =
        run("TUVORA_MS_E2E_AGGREGATOR_LAZY", imdb = "tt0000005", title = "Multi Version Movie", year = 2022, expected = 3)

    private fun run(env: String, imdb: String = "tt0000011", title: String = "Many Streams Movie", year: Int = 2012, expected: Int = 6) {
        val raw = System.getenv(env)
        if (raw.isNullOrBlank()) {
            org.junit.Assume.assumeTrue("Live target not configured: $env", false)
            return
        }
        val (url, user, password) = raw.split('|').also { require(it.size == 3) { "Invalid $env" } }
        runBlocking {
            val credentials = MediaServerCredentialStore(FakeSecureTokenStore())
            val store = MediaServerEntryStore(FakeEntriesPersistence(), { null }, { 1 }, { _, _ -> })
            val services = MediaServerServices(
                http = AuthorityRoutingHttp(MediaServerTrust(MediaServerTrustStore({ null }, { }, { }))),
                credentials = credentials,
                identityProvider = { MediaBrowserClientIdentity("Tuvora", "E2E", credentials.deviceId(), "1.0.0") },
                nowMs = { System.currentTimeMillis() },
            )
            val found = assertIs<DiscoveryResult.Found>(services.discover(url, MediaServerType.JELLYFIN))
            val session = services.authApi(found.baseUrl, found.type).authenticateByName(user, password)
            val entry = assertIs<SignInResult.Success>(
                MediaServerAccounts(store, services, listOf(1)).addServer(found.type, found.baseUrl, found.info, session, displayName = null),
            ).entry

            val facts = TitleFacts(ExternalIds(imdb = imdb), primary = title, year = year)
            val lane = MediaServerMatchLane(store, services, { System.currentTimeMillis() }, { _, _ -> facts })
            val streams = lane.streams(MediaServerIds.matchGroupId(entry.serverKey), "movie", imdb, null, null)
            println("offered: " + streams.map { it.name })
            assertEquals(expected, streams.size, "one stream per add-on result: ${streams.map { it.name }}")
            assertEquals(expected, streams.map { it.url }.toSet().size, "each version is its own deferred pick")

            val provider = MediaServerStreamSourceProvider(store, services, lane)
            val playedSources = streams.mapIndexed { i, stream ->
                // a person picks one version at a time; an aggregator rate-limits stream requests per address (~1 s)
                if (i > 0) kotlinx.coroutines.delay(1_500)
                val minted = assertNotNull(provider.resolveDeferredUrl(stream.url!!, forceMint = false), "mint ${stream.name}")
                val picked = stream.url!!.substringAfterLast('|')
                val played = assertNotNull(assertNotNull(MediaServerPlaybackSessions.latestFor(entry.serverKey)).mediaSourceId)
                assertTrue(played.equals(picked, ignoreCase = true), "picked ${stream.name} ($picked) but the server session plays $played")
                val status = fetchStatus(minted)
                assertTrue(status == 200 || status == 206, "${stream.name} plays: HTTP $status")
                println("played ${stream.name} -> $played")
                played.lowercase()
            }
            assertEquals(expected, playedSources.toSet().size, "every pick played a different version")
        }
    }

    /** GET the first bytes, following a server's redirect to the add-on's link by hand: the test add-on serves media at the
     *  address the containers know it by (`host.docker.internal`), which this JVM reaches on localhost. */
    private fun fetchStatus(url: String, hops: Int = 3): Int {
        val c = URL(url.replace("host.docker.internal", "localhost")).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("Range", "bytes=0-99")
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        return try {
            val code = c.responseCode
            val location = c.getHeaderField("Location")
            if (code in 300..399 && location != null && hops > 0) fetchStatus(URL(URL(url), location).toString(), hops - 1) else code
        } finally { c.disconnect() }
    }
}
