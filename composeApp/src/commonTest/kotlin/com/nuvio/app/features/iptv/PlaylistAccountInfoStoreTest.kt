package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One panel question per playlist per freshness window; never for a playlist with no panel. */
class PlaylistAccountInfoStoreTest {
    private var now = 1_000_000L
    private var calls = 0
    private val info = XtreamAccountInfo(status = "Active", isTrial = false, expiresAtEpochSec = 99, maxConnections = 1, activeConnections = 0)
    private var answer: XtreamAccountInfo? = info
    private val store = PlaylistAccountInfoStore(fetch = { calls++; answer }, clock = { now })
    private val xtream = XtreamAccount(id = "k1", name = "A", baseUrl = "http://a.example.com", username = "u", password = "p")

    @Test
    fun `a fresh answer is reused without asking again`() = runBlocking {
        assertEquals(info, store.infoFor(xtream))
        assertEquals(info, store.infoFor(xtream))
        assertEquals(1, calls)
        now += AccountInfoFreshnessPolicy.TTL_MS - 1
        store.infoFor(xtream)
        assertEquals(1, calls)
    }

    @Test
    fun `a stale answer is asked for again`() = runBlocking {
        store.infoFor(xtream)
        now += AccountInfoFreshnessPolicy.TTL_MS
        store.infoFor(xtream)
        assertEquals(2, calls)
    }

    @Test
    fun `an M3U playlist has no panel to ask`() = runBlocking {
        assertNull(store.infoFor(xtream.copy(sourceType = SOURCE_TYPE_M3U_URL)))
        assertEquals(0, calls)
    }

    @Test
    fun `an unreachable panel keeps the last known answer`() = runBlocking {
        store.infoFor(xtream)
        now += AccountInfoFreshnessPolicy.TTL_MS
        answer = null
        assertEquals(info, store.infoFor(xtream), "still shows what the panel last said")
        assertEquals(info, store.known.value["k1"])
    }

    @Test
    fun `freshness table`() {
        assertTrue(AccountInfoFreshnessPolicy.isFresh(0, 1))
        assertFalse(AccountInfoFreshnessPolicy.isFresh(0, AccountInfoFreshnessPolicy.TTL_MS))
        assertFalse(AccountInfoFreshnessPolicy.isFresh(10, 5), "a clock that went backwards is not fresh")
    }

    // ---- code review M4: a failure is remembered for seconds, not hours --------------------------------

    @Test
    fun `a failed check is retried after a short wait and not after six hours`() = runBlocking {
        answer = null
        assertNull(store.infoFor(xtream))
        assertEquals(1, calls)
        now += AccountInfoFreshnessPolicy.FAILURE_TTL_MS - 1
        assertNull(store.infoFor(xtream))
        assertEquals(1, calls, "inside the short window nothing is re-asked (no hammering a dead panel)")
        now += 1
        answer = info
        assertEquals(info, store.infoFor(xtream), "a minute later the panel is asked again and the answer shows")
        assertEquals(2, calls)
        assertTrue(AccountInfoFreshnessPolicy.FAILURE_TTL_MS in 30_000L..120_000L)
    }

    @Test
    fun `a failure does not erase what the panel last said`() = runBlocking {
        store.infoFor(xtream)
        now += AccountInfoFreshnessPolicy.TTL_MS
        answer = null
        store.infoFor(xtream)
        assertEquals(info, store.cached("k1"))
    }

    @Test
    fun `freshness table for failures`() {
        assertTrue(AccountInfoFreshnessPolicy.isFresh(0, 1, succeeded = false))
        assertFalse(AccountInfoFreshnessPolicy.isFresh(0, AccountInfoFreshnessPolicy.FAILURE_TTL_MS, succeeded = false))
        assertTrue(AccountInfoFreshnessPolicy.isFresh(0, AccountInfoFreshnessPolicy.FAILURE_TTL_MS, succeeded = true))
    }

    // --- B171: the details page's one lookup is an inspection; a cancellation is never a cached failure ---

    @Test
    fun `an inspection fetch runs as read-only inspection and the list's fetch does not`() = runBlocking {
        val seen = mutableListOf<com.nuvio.app.core.sources.DeviceSourceAccess?>()
        val s = PlaylistAccountInfoStore(
            fetch = { seen += kotlin.coroutines.coroutineContext[com.nuvio.app.core.sources.DeviceSourceAccessElement]?.access; info },
            clock = { now },
        )
        s.infoFor(xtream)
        s.infoFor(xtream, force = true, inspect = true)
        assertEquals(listOf(null, com.nuvio.app.core.sources.DeviceSourceAccess.INSPECT), seen)
    }

    @Test
    fun `a forced inspection bypasses the six hour freshness window`() = runBlocking {
        store.infoFor(xtream)
        store.infoFor(xtream, force = true, inspect = true)
        assertEquals(2, calls)
    }

    @Test
    fun `a cancelled fetch propagates and is not remembered as a failed check`() = runBlocking {
        val s = PlaylistAccountInfoStore(fetch = { throw kotlinx.coroutines.CancellationException("scope gone") }, clock = { now })
        val thrown = runCatching { s.infoFor(xtream) }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
        assertNull(s.cached("k1"))
        answer = info
        assertEquals(info, store.infoFor(xtream), "no 45 s failure entry was left behind")
    }

    // --- review fixes: the DEFAULT ask, and the list loop -------------------------------------------

    private val refusal = com.nuvio.app.core.sources.SourceDisabledOnDeviceException("iptv", "ready")

    @Test
    fun `the default ask turns a device refusal into no answer instead of throwing`() = runBlocking {
        assertNull(PlaylistAccountInfoStore.fetchAccountInfo(xtream) { Result.failure(refusal) })
        assertEquals(info, PlaylistAccountInfoStore.fetchAccountInfo(xtream) { Result.success(info) })
    }

    @Test
    fun `the default ask still rethrows a real cancellation`() = runBlocking {
        val thrown = runCatching {
            PlaylistAccountInfoStore.fetchAccountInfo(xtream) { Result.failure(kotlinx.coroutines.CancellationException("scope gone")) }
        }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException && thrown !is com.nuvio.app.core.sources.SourceDisabledOnDeviceException)
    }

    @Test
    fun `a refusal for one playlist does not end the list pass for the rest`() = runBlocking {
        val off = xtream.copy(id = "off")
        val on = xtream.copy(id = "on")
        val asked = mutableListOf<String>()
        val s = PlaylistAccountInfoStore(
            fetch = { acc -> asked += acc.id; PlaylistAccountInfoStore.fetchAccountInfo(acc) { a -> if (a.id == "off") Result.failure(refusal) else Result.success(info) } },
            clock = { now },
        )
        s.refreshManaged(listOf(off, on), setOf("off", "on"), availableHere = { true })
        assertEquals(listOf("off", "on"), asked)
        assertEquals(info, s.cached("on"))
    }

    @Test
    fun `the list pass never asks a playlist turned off on this device`() = runBlocking {
        val asked = mutableListOf<String>()
        val s = PlaylistAccountInfoStore(fetch = { acc -> asked += acc.id; info }, clock = { now })
        s.refreshManaged(listOf(xtream.copy(id = "off"), xtream.copy(id = "on"), xtream.copy(id = "unmanaged")), setOf("off", "on"), availableHere = { it.id != "off" })
        assertEquals(listOf("on"), asked)
    }

    @Test
    fun `details inspection plan keeps the cache for turned-on playlists and never touches an off portal`() {
        val p = DetailsInspectionPolicy
        assertEquals(DetailsInspectionPolicy.Plan.NORMAL, p.plan(SOURCE_TYPE_XTREAM, availableHere = true))
        assertEquals(DetailsInspectionPolicy.Plan.INSPECT, p.plan(SOURCE_TYPE_XTREAM, availableHere = false))
        assertEquals(DetailsInspectionPolicy.Plan.SKIP, p.plan(SOURCE_TYPE_STALKER, availableHere = false))
        assertEquals(DetailsInspectionPolicy.Plan.NORMAL, p.plan(SOURCE_TYPE_STALKER, availableHere = true))
    }
}
