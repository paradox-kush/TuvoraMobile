package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The setup-code flow's decisions, on fakes: no network, no UI, no Compose. */
class SetupCodeControllerTest {

    private class FakeApi : ProviderSetupApi {
        var previewCalls = 0
        var redeemCalls = 0
        val redeemed = mutableListOf<Pair<String, Int>>()
        val skipAddonsSeen = mutableListOf<Boolean>()
        var previewResult: SetupCodeOutcome = ready("Acme TV")
        /** A per-code answer, overriding [previewResult]; and a gate that holds a code's preview in flight. */
        var previewFor: ((String) -> SetupCodeOutcome)? = null
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var redeemResult: RedeemResult = RedeemResult.Redeemed(
            RedeemSummary(2, 1, 0, 0, listOf(RedeemedPlaylist("key-1", "Live", "added")), 1, 0),
        )
        override suspend fun preview(code: String): SetupCodeOutcome {
            previewCalls++
            gates[code]?.await()
            return previewFor?.invoke(code) ?: previewResult
        }
        override suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean): RedeemResult {
            redeemCalls++; redeemed += code to profileIndex; skipAddonsSeen += skipAddons; return redeemResult
        }
        var deviceCalls = 0
        /** (code, claimToken) of every device redeem. */
        val deviceRedeems = mutableListOf<Pair<String, String>>()
        var deviceResult: () -> DeviceRedeemResult = {
            DeviceRedeemResult.Redeemed(
                DeviceRedeemSummary(
                    "Acme TV", false,
                    listOf(DevicePlaylist("key-1", "Live", "xtream", baseUrl = "http://p.example.com", username = "u", password = "p")),
                ),
            )
        }
        override suspend fun deviceRedeem(code: String, claimToken: String): DeviceRedeemResult {
            deviceCalls++; deviceRedeems += code to claimToken; return deviceResult()
        }
        override suspend fun claimDeviceSetup(claimToken: String, profileIndex: Int, skipAddons: Boolean): DeviceClaimResult = error("not used")
        override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> = error("not used")
        override suspend fun detach(profileId: Int, playlistKey: String): Boolean = error("not used")

        companion object {
            fun ready(provider: String) = SetupCodeOutcome.Ready(
                SetupPreview(provider, ProviderSupport(telegram = "acme_tv"), "Gold", listOf(SetupPreviewPlaylist("Live", "xtream")), listOf("Cinemeta")),
            )
        }
    }

    private val api = FakeApi()
    private var now = 0L
    private val holder = SetupCodeHolder(clock = { now })
    private var kind = AccountKind.REAL
    private var activeProfile = 1
    private var signInRequests = 0
    private val pulls = mutableListOf<Int>()
    private val refreshes = mutableListOf<Int>()
    private val events = mutableListOf<Pair<String, Map<String, Any>>>()
    private var localKeys = setOf("key-1")
    private var existingProfiles = (1..9).toSet()
    private var skipAddons = false
    private val stored = mutableListOf<List<XtreamAccount>>()
    private var localAddResult: (List<XtreamAccount>) -> Int = { it.size }
    private var rawClaims: String? = null
    private val claims = PendingDeviceClaimStore(load = { rawClaims }, save = { rawClaims = it })
    private var tokens = 0

    private fun controller() = SetupCodeController(
        api = { api }, holder = holder, accountKind = { kind }, activeProfileIndex = { activeProfile },
        profileExists = { it in existingProfiles }, localAccountKeys = { localKeys }, skipAddons = { skipAddons },
        pullPlaylists = { pulls += it }, refreshManaged = { refreshes += it },
        requestSignIn = { signInRequests++ }, telemetry = ProviderSetupTelemetry,
        addLocalPlaylists = { stored += it; localAddResult(it) }, claims = claims,
        newClaimToken = { (++tokens).toString().padStart(64, '0') }, nowMs = { 1234L },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @BeforeTest
    fun setUp() {
        ProviderSetupTelemetry.capture = { name, props -> events += name to props }
    }

    @AfterTest
    fun tearDown() {
        ProviderSetupTelemetry.capture = com.nuvio.app.core.analytics.AnalyticsSink::capture
    }

    @Test
    fun `typing groups the code as it is entered`() {
        val c = controller()
        c.onTyped("abcdefg")
        assertEquals("TUV-ABCD-EFG", c.state.value.typed)
    }

    @Test
    fun `pasting a link or a message takes the code out of it`() {
        val c = controller()
        c.onPasted("Open https://tuvora.co/s/TUV-ABCD-EFGH-JKMN then sign in")
        assertEquals("TUV-ABCD-EFGH-JKMN", c.state.value.typed)
        c.onPasted("abcdefghjkmn")
        assertEquals("TUV-ABCD-EFGH-JKMN", c.state.value.typed)
    }

    @Test
    fun `continue with a bad code is rejected with its problem and holds nothing`() {
        val c = controller()
        c.onTyped("ABCD-0000")
        assertEquals(ContinueResult.REJECTED, c.onContinue())
        assertEquals(SetupCodeProblem.BAD_CHARACTERS, c.state.value.typedProblem)
        assertFalse(holder.hasCode())
        assertEquals(0, api.previewCalls)
    }

    @Test
    fun `continue with a good code holds it in memory and opens the preview`() {
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        assertEquals(ContinueResult.OPEN_PREVIEW, c.onContinue())
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(0, signInRequests)
    }

    @Test
    fun `a linked code is held and and a link that is not a code is ignored`() {
        val c = controller()
        assertTrue(c.acceptLinkedCode("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN"))
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertFalse(c.acceptLinkedCode("https://tuvora.co/s/nope"))
    }

    @Test
    fun `the preview loads once for the held code and defaults to the active profile`() {
        activeProfile = 3
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.loadPreview()
        assertEquals(1, api.previewCalls, "a Ready preview is not fetched again")
        assertEquals("Acme TV", c.state.value.preview?.providerName)
        assertEquals(3, c.state.value.selectedProfileIndex)
        assertEquals(listOf("setup_code_preview" to mapOf<String, Any>("outcome" to "ready")), events)
    }

    @Test
    fun `a dead code is forgotten`() {
        val c = controller()
        for (outcome in listOf(SetupCodeOutcome.Unusable, SetupCodeOutcome.Expired(ProviderSupport.NONE))) {
            holder.set("ABCDEFGHJKMN")
            api.previewResult = outcome
            c.loadPreview(force = true)
            assertEquals(outcome, c.state.value.previewOutcome)
            assertNull(holder.peek(), "$outcome")
        }
    }

    @Test
    fun `a network or rate limit problem keeps the code for a retry`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        api.previewResult = SetupCodeOutcome.Network
        c.loadPreview()
        assertEquals("ABCDEFGHJKMN", holder.peek())
        api.previewResult = api.previewResult.let { SetupCodeOutcome.Ready(SetupPreview("Acme TV", ProviderSupport.NONE, "", emptyList(), emptyList())) }
        c.loadPreview(force = true)
        assertTrue(c.state.value.preview != null)
    }

    @Test
    fun `no preview is asked for when nothing is held`() {
        val c = controller()
        c.loadPreview()
        assertEquals(0, api.previewCalls)
        assertEquals(SetupCodeOutcome.Problem(SetupCodeProblem.EMPTY), c.state.value.previewOutcome)
    }

    @Test
    fun `confirm redeems into the chosen profile then pulls once for it and clears the code`() {
        activeProfile = 2
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.confirm()
        assertEquals(listOf("ABCDEFGHJKMN" to 2), api.redeemed)
        assertEquals(listOf(2), pulls, "exactly one pull, for the redeemed profile")
        assertEquals(emptyList(), refreshes)
        assertNull(holder.peek(), "cleared on success")
        val done = c.state.value.completed!!
        assertEquals("Acme TV", done.providerName)
        assertEquals("key-1", done.openPlaylistKey)
        assertEquals(listOf("setup_code_preview", "setup_code_redeemed"), events.map { it.first })
        assertEquals(mapOf<String, Any>("added" to 1, "updated" to 0, "outcome" to "redeemed"), events.last().second)
    }

    @Test
    fun `a redeem into another profile refreshes its managed map and does not pull`() {
        activeProfile = 1
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.selectProfile(3)
        c.confirm()
        assertEquals(listOf("ABCDEFGHJKMN" to 3), api.redeemed)
        assertEquals(emptyList(), pulls)
        assertEquals(listOf(3), refreshes)
        assertNull(c.state.value.completed?.openPlaylistKey, "its playlists are not on this device yet")
    }

    @Test
    fun `no analytics event carries the code`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.confirm()
        for ((name, props) in events) {
            assertFalse(props.values.any { it.toString().contains("ABCD") }, "$name must not carry the code: $props")
        }
    }

    // ---- B205: a code works with no account -------------------------------------------------------------

    @Test
    fun `signed-out continue goes to the preview and never to sign-in`() {
        for (who in listOf(AccountKind.SIGNED_OUT, AccountKind.GUEST)) {
            kind = who
            val c = controller()
            c.onTyped("TUV-ABCD-EFGH-JKMN")
            assertEquals(ContinueResult.OPEN_PREVIEW, c.onContinue(), "$who")
            assertEquals("ABCDEFGHJKMN", holder.peek())
            assertEquals(0, signInRequests, "$who")
            c.cancel()
        }
    }

    @Test
    fun `a linked code without a real account is held for the preview without a sign-in detour`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        assertTrue(c.acceptLinkedCode("TUV-ABCD-EFGH-JKMN"))
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(0, signInRequests)
        assertFalse(c.takeResumeAfterSignIn(), "there is no sign-in to come back from")
    }

    @Test
    fun `signed-out preview loads the same way as signed-in`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        assertEquals("Acme TV", c.state.value.preview?.providerName)
        assertEquals(1, api.previewCalls)
    }

    private fun addOnDevice(c: SetupCodeController) {
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        c.confirm()
    }

    @Test
    fun `signed-out add redeems to the device with a 64 hex token and stores the playlists and a pending claim`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        addOnDevice(c)
        assertEquals(0, api.redeemCalls, "the account RPC is never used without an account")
        val (code, token) = api.deviceRedeems.single()
        assertEquals("ABCDEFGHJKMN", code)
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(listOf("key-1"), stored.single().map { it.id }, "stored like a pulled row, keyed by playlist_key")
        val claim = claims.list().single()
        assertEquals(token, claim.token)
        assertEquals("Acme TV", claim.providerName)
        assertEquals(1234L, claim.createdAtMs)
        assertEquals(listOf("key-1"), claim.playlists.map { it.playlistKey })
        assertEquals(emptyList(), pulls, "there is no account to pull from")
        assertNull(holder.peek(), "the code is spent")
        val done = c.state.value.completed!!
        assertEquals(CompletionKind.ADDED_ON_DEVICE, done.outcome)
        assertEquals("key-1", done.openPlaylistKey)
        assertTrue(done.onDevice)
    }

    @Test
    fun `a guest adds to the device the same way`() {
        kind = AccountKind.GUEST
        addOnDevice(controller())
        assertEquals(1, api.deviceCalls)
        assertEquals(1, claims.list().size)
    }

    @Test
    fun `a network failure keeps the code and the retry reuses the same token`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        api.deviceResult = { DeviceRedeemResult.Refused(SetupCodeOutcome.Network) }
        addOnDevice(c)
        assertEquals(SetupCodeOutcome.Network, c.state.value.redeemRefusal, "a failure is never shown as success")
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(emptyList(), claims.list(), "nothing was stored")
        assertEquals(emptyList(), stored)
        assertNull(c.state.value.completed)
        // The server redeemed it anyway; the retry gets the playlists back as already_redeemed.
        api.deviceResult = {
            DeviceRedeemResult.Redeemed(DeviceRedeemSummary("Acme TV", true, listOf(DevicePlaylist("key-1", "Live", "xtream", baseUrl = "http://p.example.com", username = "u", password = "p"))))
        }
        c.confirm()
        assertEquals(2, api.deviceCalls)
        assertEquals(api.deviceRedeems[0].second, api.deviceRedeems[1].second, "same token on retry")
        assertEquals(1, claims.list().size)
        assertEquals(CompletionKind.ADDED_ON_DEVICE, c.state.value.completed?.outcome)
    }

    @Test
    fun `a new code gets a new token`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        api.deviceResult = { DeviceRedeemResult.Refused(SetupCodeOutcome.Network) }
        addOnDevice(c)
        c.cancel()
        holder.set("PQRSTUVWXYZ2")
        c.loadPreview()
        c.confirm()
        assertTrue(api.deviceRedeems[0].second != api.deviceRedeems[1].second)
    }

    @Test
    fun `a dead code on the device route is forgotten`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        api.deviceResult = { DeviceRedeemResult.Refused(SetupCodeOutcome.Unusable) }
        addOnDevice(c)
        assertEquals(SetupCodeOutcome.Unusable, c.state.value.redeemRefusal)
        assertNull(holder.peek())
        assertEquals(emptyList(), claims.list())
    }

    @Test
    fun `a failed local write after the redeem is an error and keeps the token for a retry`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        localAddResult = { -1 }
        addOnDevice(c)
        assertEquals(SetupCodeOutcome.Network, c.state.value.redeemRefusal)
        assertNull(c.state.value.completed)
        assertEquals("ABCDEFGHJKMN", holder.peek())
        localAddResult = { it.size }
        c.confirm()
        assertEquals(api.deviceRedeems[0].second, api.deviceRedeems[1].second)
        assertEquals(CompletionKind.ADDED_ON_DEVICE, c.state.value.completed?.outcome)
    }

    @Test
    fun `playlists the provider has not filled in are reported not added and store nothing`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        api.deviceResult = {
            DeviceRedeemResult.Redeemed(DeviceRedeemSummary("Acme TV", false, listOf(DevicePlaylist("k", "L", "xtream", action = "skipped", reason = "missing_login"))))
        }
        addOnDevice(c)
        assertEquals(emptyList(), stored)
        assertEquals(emptyList(), claims.list(), "no logins, no claim")
        assertEquals(CompletionKind.NOTHING_MISSING_LOGIN, c.state.value.completed?.outcome)
    }

    @Test
    fun `no analytics event for a device redeem carries the code or the token`() {
        kind = AccountKind.SIGNED_OUT
        addOnDevice(controller())
        val token = api.deviceRedeems.single().second
        for ((name, props) in events) {
            assertFalse(props.values.any { it.toString().contains("ABCD") || it.toString().contains(token) }, "$name: $props")
        }
        assertEquals("device_redeemed", events.last().second["outcome"])
    }

    @Test
    fun `an expired redeem still offers the contacts the preview showed and forgets the code`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.Expired(ProviderSupport.NONE))
        c.confirm()
        val refusal = c.state.value.redeemRefusal as SetupCodeOutcome.Expired
        assertEquals(ProviderSupport(telegram = "acme_tv"), refusal.support)
        assertNull(holder.peek())
        assertFalse(c.state.value.redeeming)
    }

    @Test
    fun `a profile that vanished or a network problem keeps the code`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.ProfileGone)
        c.confirm()
        assertEquals(SetupCodeOutcome.ProfileGone, c.state.value.redeemRefusal)
        assertEquals("ABCDEFGHJKMN", holder.peek())
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.Network)
        c.confirm()
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(0, pulls.size)
    }

    @Test
    fun `cancel forgets everything`() {
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        c.onContinue()
        c.loadPreview()
        c.cancel()
        assertNull(holder.peek())
        assertEquals(SetupCodeUiState(), c.state.value)
    }

    private fun completion(added: Int = 0, updated: Int = 0, already: Boolean = false, skipped: List<String> = emptyList(), unchanged: Int = 0) =
        SetupCompletion("Acme TV", 1, added, updated, already, null, skipped, unchanged)

    @Test
    fun `a redeem that added nothing never says it added a playlist`() {
        assertEquals(CompletionKind.ADDED, completion(added = 1).outcome)
        assertEquals(CompletionKind.ADDED, completion(updated = 2, skipped = listOf("missing_login")).outcome)
        assertEquals(CompletionKind.ALREADY_IN_ACCOUNT, completion(already = true).outcome)
        assertEquals(CompletionKind.NOTHING_MISSING_LOGIN, completion(skipped = listOf("missing_login")).outcome)
        assertEquals(CompletionKind.NOTHING_INVALID_URL, completion(skipped = listOf("invalid_url")).outcome)
        assertEquals(CompletionKind.NOTHING_MISSING_LOGIN, completion(skipped = listOf("invalid_url", "missing_login")).outcome)
        // Code review M6: nothing added, nothing changed, nothing skipped is NOT "added your playlist" either.
        assertEquals(CompletionKind.NOTHING_ADDED, completion().outcome)
        // A second code for a playlist this account already holds: still pulled and opened, "already in your account".
        assertEquals(CompletionKind.ALREADY_IN_ACCOUNT, completion(unchanged = 2).outcome)
        assertEquals(CompletionKind.ADDED, completion(added = 1, unchanged = 1).outcome)
    }

    private fun done(profile: Int, key: String? = "key-1") = SetupCompletion("Acme TV", profile, 1, 0, false, key)

    @Test
    fun `every successful redeem ends on the new playlist's details when it is on this device`() {
        assertEquals(SetupCompletionPlan(pops = 2, openDetailsKey = "key-1"), SetupCompletionNavigation.plan(done(1), fromAddPage = true))
        assertEquals(SetupCompletionPlan(pops = 1, openDetailsKey = "key-1"), SetupCompletionNavigation.plan(done(1), fromAddPage = false))
    }

    @Test
    fun `a redeem into another profile ends on the IPTV list because its details cannot open here`() {
        val plan = SetupCompletionNavigation.plan(done(3, key = null), fromAddPage = true)
        assertEquals(SetupCompletionPlan(pops = 2, openDetailsKey = null), plan)
        assertEquals(1, SetupCompletionNavigation.plan(done(3, key = null), fromAddPage = false).pops)
    }

    @Test
    fun `the controller hands the plan a key only for the active profile`() {
        activeProfile = 1
        val c = controller()
        holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.selectProfile(3); c.confirm()
        val other = c.state.value.completed!!
        assertNull(SetupCompletionNavigation.plan(other, fromAddPage = true).openDetailsKey)
        c.finish()
        holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.selectProfile(1); c.confirm()
        assertEquals("key-1", SetupCompletionNavigation.plan(c.state.value.completed!!, fromAddPage = true).openDetailsKey)
    }

    // ---- security M1: the preview shown is always for the code that confirm() redeems ----------------

    @Test
    fun `a late preview for the previous code is discarded and never shown for the code now held`() {
        val c = controller()
        api.previewFor = { code -> FakeApi.ready(if (code == "ABCDEFGHJKMN") "Provider A" else "Provider B") }
        api.gates["ABCDEFGHJKMN"] = CompletableDeferred()          // X is slow
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        c.onContinue()
        c.loadPreview()                                              // X in flight
        assertTrue(c.state.value.previewLoading)
        assertTrue(c.acceptLinkedCode("TUV-PQRS-TUVW-XYZ2"))        // a link for Y arrives meanwhile
        c.loadPreview()                                              // Y
        api.gates.getValue("ABCDEFGHJKMN").complete(Unit)            // X answers late
        assertEquals("Provider B", c.state.value.preview?.providerName, "the screen shows Y's provider")
        c.confirm()
        assertEquals(listOf("PQRSTUVWXYZ2" to 1), api.redeemed, "and Y is what is redeemed")
        assertEquals("Provider B", c.state.value.completed?.providerName, "the toast names Y's provider")
    }

    @Test
    fun `typing a second code while the first preview is in flight shows only the second`() {
        val c = controller()
        api.previewFor = { code -> FakeApi.ready(if (code == "ABCDEFGHJKMN") "Provider A" else "Provider B") }
        api.gates["ABCDEFGHJKMN"] = CompletableDeferred()
        c.onTyped("TUV-ABCD-EFGH-JKMN"); c.onContinue(); c.loadPreview()
        c.onTyped("TUV-PQRS-TUVW-XYZ2"); c.onContinue(); c.loadPreview()
        api.gates.getValue("ABCDEFGHJKMN").complete(Unit)
        assertEquals("Provider B", c.state.value.preview?.providerName)
        assertEquals(2, api.previewCalls)
    }

    @Test
    fun `confirm refuses a preview that was fetched for a different code and loads the right one`() {
        val c = controller()
        api.previewFor = { code -> FakeApi.ready(if (code == "ABCDEFGHJKMN") "Provider A" else "Provider B") }
        holder.set("ABCDEFGHJKMN")
        c.loadPreview()
        holder.set("PQRSTUVWXYZ2")                                   // the held code changed under the screen
        c.confirm()
        assertEquals(0, api.redeemCalls, "never redeems a code the person was not shown")
        assertEquals("Provider B", c.state.value.preview?.providerName)
        c.confirm()
        assertEquals(listOf("PQRSTUVWXYZ2" to 1), api.redeemed)
    }

    @Test
    fun `every new held code bumps the generation the preview effect is keyed on`() {
        val c = controller()
        val g0 = c.state.value.holdGeneration
        c.acceptLinkedCode("TUV-ABCD-EFGH-JKMN")
        val g1 = c.state.value.holdGeneration
        assertTrue(g1 != g0, "a link while the preview page is open must restart its effect")
        c.loadPreview()
        assertTrue(c.state.value.previewOutcome is SetupCodeOutcome.Ready)
        c.acceptLinkedCode("TUV-PQRS-TUVW-XYZ2")
        assertTrue(c.state.value.holdGeneration != g1)
        assertNull(c.state.value.previewOutcome, "the old preview is gone, not stuck on screen")
        assertFalse(c.state.value.previewLoading, "and nothing waits on a cancelled request")
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        val g2 = c.state.value.holdGeneration
        c.onContinue()
        assertTrue(c.state.value.holdGeneration != g2)
    }

    // ---- code review M2: a vanished profile can be recovered from -------------------------------------

    @Test
    fun `after the profile vanished the next confirm redeems into the profile that exists`() {
        activeProfile = 1
        val c = controller()
        holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.selectProfile(3)
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.ProfileGone)
        c.confirm()
        assertEquals(SetupCodeOutcome.ProfileGone, c.state.value.redeemRefusal)
        assertNull(c.state.value.selectedProfileIndex, "falls back to the active profile instead of the dead one")
        api.redeemResult = RedeemResult.Redeemed(RedeemSummary(1, 1, 0, 0, listOf(RedeemedPlaylist("key-1", "Live", "added")), 0, 0))
        c.confirm()
        assertEquals(listOf("ABCDEFGHJKMN" to 3, "ABCDEFGHJKMN" to 1), api.redeemed)
    }

    @Test
    fun `a chosen profile that no longer exists is not redeemed into`() {
        activeProfile = 1
        val c = controller()
        holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.selectProfile(3)
        existingProfiles = setOf(1)
        c.confirm()
        assertEquals(listOf("ABCDEFGHJKMN" to 1), api.redeemed, "validated with profileExists first")
    }

    // ---- security L7 / code review L10: nothing about a dead code lingers ------------------------------

    @Test
    fun `typed text is cleared whenever the held code is dropped`() {
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN"); c.onContinue()
        api.previewResult = SetupCodeOutcome.Unusable
        c.loadPreview()
        assertEquals("", c.state.value.typed, "a dead code is not left in the field")
        // redeem-time
        c.onTyped("TUV-ABCD-EFGH-JKMN"); c.onContinue()
        api.previewResult = FakeApi.ready("Acme TV")
        c.loadPreview(force = true)
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.Unusable)
        c.confirm()
        assertEquals("", c.state.value.typed)
        // expiry of the held code
        c.onTyped("TUV-ABCD-EFGH-JKMN"); c.onContinue()
        now += SetupCodeHolder.TTL_MS
        c.loadPreview(force = true)
        assertEquals("", c.state.value.typed, "the 30 minute bound applies to the field too")
    }

    @Test
    fun `the controller state never prints the code`() {
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN")
        assertFalse(c.state.value.toString().contains("ABCD"), c.state.value.toString())
        assertFalse(c.state.value.toString().contains("JKMN"))
    }

    @Test
    fun `re-running the preview effect after a dead code keeps the real outcome`() {
        val c = controller()
        c.onTyped("TUV-ABCD-EFGH-JKMN"); c.onContinue()
        api.previewResult = SetupCodeOutcome.Expired(ProviderSupport(telegram = "acme_tv"))
        c.loadPreview()
        assertNull(holder.peek())
        c.loadPreview()                                              // the effect runs again after a recomposition
        assertTrue(c.state.value.previewOutcome is SetupCodeOutcome.Expired, "not rewritten to the empty-code problem")
    }

    // ---- code review M3 (controller half): a transient failure never costs the person their code ------

    @Test
    fun `a transient redeem failure reads as a network problem and keeps the code`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN"); c.loadPreview()
        api.redeemResult = RedeemResult.Refused(SetupCodeOutcome.Network)
        c.confirm()
        assertEquals(SetupCodeOutcome.Network, c.state.value.redeemRefusal)
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(0, pulls.size)
    }

    // ---- security M6 / (a): store flavours send p_skip_addons ---------------------------------------------

    @Test
    fun `the redeem tells the server to skip add-ons exactly when add-ons are off in this build`() {
        val c = controller()
        holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.confirm()
        assertEquals(listOf(false), api.skipAddonsSeen)
        skipAddons = true
        c.finish(); holder.set("ABCDEFGHJKMN"); c.loadPreview(); c.confirm()
        assertEquals(listOf(false, true), api.skipAddonsSeen)
    }

    // ---- a link while the profile picker shows is held, never dropped -------------------------------------

    @Test
    fun `a held link is only kept in memory and routes nowhere until accepted`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        assertTrue(c.holdLinkedCode("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN"))
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertEquals(0, signInRequests, "no sign-in routing while the picker is showing")
        assertFalse(c.holdLinkedCode("https://tuvora.co/s/nope"), "a link that is not a code holds nothing")
    }

    @Test
    fun `accepting the held link after the shell appears behaves like a linked code`() {
        val c = controller()
        c.holdLinkedCode("TUV-ABCD-EFGH-JKMN")
        assertTrue(c.acceptHeldCode())
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertTrue(c.state.value.holdGeneration > 0, "the preview effect restarts for it")
    }

    @Test
    fun `a held link older than 30 minutes is not accepted`() {
        val c = controller()
        c.holdLinkedCode("TUV-ABCD-EFGH-JKMN")
        now += SetupCodeHolder.TTL_MS
        assertFalse(c.acceptHeldCode())
        assertFalse(c.hasHeldCode())
    }

    @Test
    fun `a signed-out person whose link was held opens the preview with no sign-in when it is accepted`() {
        kind = AccountKind.SIGNED_OUT
        val c = controller()
        c.holdLinkedCode("TUV-ABCD-EFGH-JKMN")
        assertEquals(0, signInRequests)
        assertTrue(c.acceptHeldCode())
        assertEquals(0, signInRequests)
    }
}
