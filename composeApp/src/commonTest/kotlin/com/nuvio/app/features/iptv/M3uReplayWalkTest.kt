package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.CatchUpDialectWalk.FailureKind
import com.nuvio.app.features.iptv.CatchUpDialectWalk.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The M3U replay's candidate ladder, the form remembered per playlist, and the store that keeps it. */
class M3uReplayWalkTest {

    private class Memory : M3uReplayWalk.FormMemory {
        val forms = HashMap<String, String>()
        var writes = 0
        override fun recallM3uForm(accountId: String) = forms[accountId]
        override fun rememberM3uForm(accountId: String, form: String) { forms[accountId] = form; writes++ }
    }

    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val start = now - 3 * 60 * minute          // a show that ended two hours ago
    private val end = start + 60 * minute
    private val xtreamLive = "http://h.example:8080/live/user/pass/123.ts"
    private val xtream = M3uCatchUpInfo(M3uCatchUpType.XTREAM, null, 3)

    private fun request(
        account: String = "pl-1",
        info: M3uCatchUpInfo = xtream,
        live: String = xtreamLive,
        offsetMs: Long = 0L,
    ) = M3uReplayWalk.Request(account, info, live, start, end, now, offsetMs)

    private fun walk(memory: Memory = Memory()) = M3uReplayWalk(memory)

    /** Every URL the walk offers for [request], in order, failing each with a transport error. */
    private fun order(walk: M3uReplayWalk, request: M3uReplayWalk.Request): List<String> {
        val begun = walk.begin(request)!!
        val seen = mutableListOf(begun.attempt.url)
        var step: Step = Step.Next(begun.attempt)
        while (true) {
            step = walk.onFailure((step as Step.Next).attempt.token, FailureKind.TRANSPORT)
            if (step !is Step.Next) break
            seen += step.attempt.url
        }
        return seen
    }

    @Test
    fun `the playlist's own link leads and the alternatives follow in order`() {
        val urls = order(walk(), request())
        assertTrue(urls.first().contains("/timeshift/user/pass/"), "built from the playlist style: ${urls.first()}")
        assertTrue(urls.first().endsWith("/123.ts"))
        assertEquals(urls.first().replace(".ts", ".m3u8"), urls[1], "the other container is the first fallback")
        assertTrue(urls.any { "timeshift.php" in it }, "the older php form is on the ladder")
        assertEquals(urls.size, urls.toSet().size, "no link is tried twice")
    }

    @Test
    fun `the window asked for runs past the programme into what follows`() {
        val begun = walk().begin(request())!!
        assertEquals(
            CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(start, end), now),
            begun.windowEndMs,
        )
        assertTrue(begun.windowEndMs > end)
        // 60 min show + what has aired after it (up to 30 s short of now): 179 whole minutes from the start.
        assertTrue(begun.attempt.url.contains("/timeshift/user/pass/179/"), begun.attempt.url)
    }

    @Test
    fun `a style that cannot be built is unavailable not a guess`() {
        assertNull(walk().begin(request(info = M3uCatchUpInfo(M3uCatchUpType.APPEND, null, 3))))
        assertNull(walk().begin(request(live = "")))
        assertNull(walk().begin(request().copy(programmeEndMs = start)))
    }

    @Test
    fun `the form that played is remembered per playlist and leads next time`() {
        val memory = Memory()
        val w = walk(memory)
        val hlsLive = "http://h.example:8080/live/user/pass/123.m3u8"
        val first = w.begin(request(live = hlsLive))!!
        assertTrue(first.attempt.url.endsWith(".m3u8"), "the playlist's own container leads: ${first.attempt.url}")
        val second = (w.onFailure(first.attempt.token, FailureKind.TRANSPORT) as Step.Next).attempt
        assertTrue(second.url.endsWith(".ts"))
        assertEquals(Step.Done, w.onSuccess(second.token))
        assertEquals("ts", memory.forms["pl-1"], "the TS link won on this playlist")

        val again = w.begin(request(live = hlsLive))!!
        assertTrue(again.attempt.url.endsWith(".ts"), "next replay on this playlist goes straight to TS: ${again.attempt.url}")

        val other = w.begin(request(account = "pl-2", live = hlsLive))!!
        assertTrue(other.attempt.url.endsWith(".m3u8"), "another playlist is not led by pl-1's winner")
        assertNull(memory.forms["pl-2"])
    }

    @Test
    fun `a decode failure ends the walk and nothing is remembered`() {
        val memory = Memory()
        val w = walk(memory)
        val first = w.begin(request())!!
        assertEquals(Step.Unavailable, w.onFailure(first.attempt.token, FailureKind.DECODE))
        assertEquals(0, memory.writes)
        assertEquals(Step.Stale, w.onSuccess(first.attempt.token), "a late answer from the dead walk is ignored")
    }

    @Test
    fun `exhausting the ladder ends in unavailable`() {
        val w = walk()
        val urls = order(w, request())
        assertTrue(urls.size >= 3)
        val last = w.begin(request())!!
        var step: Step = Step.Next(last.attempt)
        repeat(urls.size - 1) { step = w.onFailure((step as Step.Next).attempt.token, FailureKind.TRANSPORT) }
        assertEquals(Step.Unavailable, w.onFailure((step as Step.Next).attempt.token, FailureKind.TRANSPORT))
    }

    @Test
    fun `a stale token is ignored and a new replay supersedes the old one`() {
        val w = walk()
        val a = w.begin(request())!!
        val b = w.begin(request())!!
        assertNotEquals(a.attempt.token, b.attempt.token)
        assertEquals(Step.Stale, w.onFailure(a.attempt.token, FailureKind.TRANSPORT))
        assertIs<Step.Next>(w.onFailure(b.attempt.token, FailureKind.TRANSPORT))
    }

    @Test
    fun `the playlist correction offsets the start stamp`() {
        val utc = walk().begin(request(offsetMs = 0L))!!.attempt.url
        val shifted = walk().begin(request(offsetMs = 5 * 60 * minute))!!.attempt.url
        assertNotEquals(utc, shifted)
    }

    @Test
    fun `restarting at the playhead builds a fresh window from there`() {
        val w = walk()
        val first = w.begin(request())!!
        val at = start + 150 * minute
        val restarted = w.restartAt(first.attempt.token, at, now)!!
        assertNotEquals(first.attempt.token, restarted.attempt.token)
        assertEquals(
            CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(at, at + 1), now),
            restarted.windowEndMs,
        )
        assertNull(w.restartAt(first.attempt.token, at, now), "the old token no longer names a replay")
    }

    @Test
    fun `a played replay can still restart at the playhead and no longer walks on failure`() {
        val memory = Memory()
        val w = walk(memory)
        val first = w.begin(request())!!
        assertEquals(Step.Done, w.onSuccess(first.attempt.token))
        assertEquals(Step.Stale, w.onSuccess(first.attempt.token), "the proof is recorded once")
        assertEquals(1, memory.writes)
        assertEquals(Step.Stale, w.onFailure(first.attempt.token, FailureKind.TRANSPORT), "a played replay never walks to another link")
        assertNotNull(w.restartAt(first.attempt.token, start + 150 * minute, now), "the ask outlives the proof")
    }

    // --- the store ---------------------------------------------------------------------------

    private class FakeAccounts(var account: XtreamAccount) {
        val store = CatchUpWinnerStore(
            accountOf = { id -> account.takeIf { it.id == id } },
            update = { id, edit -> if (account.id == id) account = edit(account) },
        )
    }

    private fun m3uAccount() = XtreamAccount(
        id = "pl-1", name = "P", baseUrl = "", username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL,
    )

    @Test
    fun `the store keeps an m3u form under its own key and never reads it as an xtream dialect`() {
        val a = FakeAccounts(m3uAccount())
        assertNull(a.store.recallM3uForm("pl-1"))
        a.store.rememberM3uForm("pl-1", "hls+clock")
        assertEquals("hls+clock", a.store.recallM3uForm("pl-1"))
        assertNull(a.store.recall("pl-1"), "an M3U form is not an Xtream dialect")
        assertNull(a.store.recallM3uForm("other"), "another playlist has no proof")
    }

    @Test
    fun `re-proving the same form does not write the account again`() {
        var updates = 0
        var account = m3uAccount()
        val store = CatchUpWinnerStore(
            accountOf = { account },
            update = { _, edit -> updates++; account = edit(account) },
        )
        store.rememberM3uForm("pl-1", "ts")
        store.rememberM3uForm("pl-1", "ts")
        assertEquals(1, updates)
    }

    @Test
    fun `an xtream proof is not read as an m3u form`() {
        val a = FakeAccounts(m3uAccount())
        a.store.remember("pl-1", CatchUpDialectWalk.StoredWinner("unknown", CatchUpDialectWalk.Dialect.PATH_TS))
        assertNull(a.store.recallM3uForm("pl-1"))
    }

    @Test
    fun `the walk writes through the store to the playlist`() {
        val a = FakeAccounts(m3uAccount())
        val w = M3uReplayWalk(a.store)
        val begun = w.begin(request())!!
        w.onSuccess(begun.attempt.token)
        assertEquals("ts", a.store.recallM3uForm("pl-1"))
    }
}
