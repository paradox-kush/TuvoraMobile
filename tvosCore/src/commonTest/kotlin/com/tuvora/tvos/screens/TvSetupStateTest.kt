package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.AccountKind
import com.nuvio.app.features.iptv.ProviderSupport
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.SetupCodeOutcome
import com.nuvio.app.features.iptv.SetupCodeProblem
import com.nuvio.app.features.iptv.SetupCodeUiState
import com.nuvio.app.features.iptv.SetupCompletion
import com.nuvio.app.features.iptv.SetupPreview
import com.nuvio.app.features.iptv.SetupPreviewPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Apple TV code screen shows for each state of the shared setup-code controller (contract sections 3, 8). */
class TvSetupStateTest {

    private val profiles = listOf(TvSetupProfile(1, "Main"), TvSetupProfile(2, "Kids"))
    private val preview = SetupPreview(
        providerName = "Starshare", support = ProviderSupport(telegram = "starshare_help", email = "help@starshare.example.com"),
        packageName = "Gold",
        playlists = listOf(
            SetupPreviewPlaylist("Starshare Live", SOURCE_TYPE_XTREAM), SetupPreviewPlaylist("Movies", SOURCE_TYPE_M3U_URL),
            SetupPreviewPlaylist("Box", SOURCE_TYPE_STALKER), SetupPreviewPlaylist("Other", "weird"),
        ),
        addons = listOf("Cinemeta"),
    )

    private fun build(
        ui: SetupCodeUiState = SetupCodeUiState(),
        kind: AccountKind = AccountKind.REAL,
        label: String? = "me@example.com",
        showAddons: Boolean = true,
        activeProfile: Int = 1,
    ) = TvSetupStateBuilder.build(ui, kind, label, profiles, activeProfile, showAddons)

    @Test
    fun `a fresh screen is the entry phase with twelve empty boxes and nothing to continue with`() {
        val s = build()
        assertEquals(TvSetupPhase.ENTRY, s.phase)
        assertEquals(List(12) { "" }, s.boxes)
        assertFalse(s.canContinue)
        assertNull(s.problem)
        assertEquals("me@example.com", s.accountLabel)
    }

    @Test
    fun `a typed code fills boxes and a complete one can continue`() {
        val s = build(SetupCodeUiState(typed = "TUV-ABCD-EFGH-JKMN"))
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "M", "N"), s.boxes)
        assertTrue(s.canContinue)
    }

    @Test
    fun `a problem found in what was typed shows the agreed sentence`() {
        assertEquals(
            "A code has 12 characters. Check it against the one your provider sent.",
            build(SetupCodeUiState(typed = "TUV-ABCD", typedProblem = SetupCodeProblem.WRONG_LENGTH)).problem,
        )
        assertEquals(
            "That doesn't look like a setup code. Codes use letters and the numbers 2-9 only.",
            build(SetupCodeUiState(typed = "TUV-AB0D", typedProblem = SetupCodeProblem.BAD_CHARACTERS)).problem,
        )
    }

    @Test
    fun `checking the code is its own phase`() {
        assertEquals(TvSetupPhase.CHECKING, build(SetupCodeUiState(previewLoad = com.nuvio.app.features.iptv.BoundedLoad.begin(com.nuvio.app.features.iptv.LoadSurface.SETTINGS))).phase)
    }

    @Test
    fun `a ready preview names the provider the playlists and the profile it will go to`() {
        val s = build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Ready(preview), selectedProfileIndex = 2))
        assertEquals(TvSetupPhase.PREVIEW, s.phase)
        assertEquals("Starshare", s.providerName)
        assertEquals("Gold", s.packageName)
        assertEquals(
            listOf("Xtream account", "M3U playlist", "Stalker portal", "Playlist"),
            s.playlists.map { it.typeLabel },
        )
        assertEquals(2, s.selectedProfile)
        assertEquals(profiles, s.profiles)
        assertEquals(listOf("Cinemeta"), s.addons)
    }

    @Test
    fun `store builds do not show the add-ons line`() {
        val s = build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Ready(preview)), showAddons = false)
        assertEquals(emptyList(), s.addons)
    }

    @Test
    fun `the profile defaults to the active one`() {
        assertEquals(2, build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Ready(preview)), activeProfile = 2).selectedProfile)
    }

    @Test
    fun `adding is its own phase`() {
        assertEquals(TvSetupPhase.ADDING, build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Ready(preview), redeeming = true)).phase)
    }

    @Test
    fun `an unusable code reads neutrally and an expired one offers the provider contacts`() {
        val unusable = build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Unusable))
        assertEquals(TvSetupPhase.ENTRY, unusable.phase)
        assertEquals(
            "This code can't be used. If you've already used it, the playlist is in your account. Otherwise ask your provider for a new code.",
            unusable.problem,
        )
        val expired = build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Expired(preview.support)))
        assertEquals("This code has expired. Ask your provider for a new one.", expired.problem)
        assertEquals(listOf("Telegram", "Email"), expired.problemContacts.map { it.label })
        assertEquals(listOf("https://t.me/starshare_help", "mailto:help@starshare.example.com"), expired.problemContacts.map { it.url })
    }

    @Test
    fun `network and rate limit use their own words`() {
        assertEquals("We couldn't reach Tuvora. Check your connection and try again.", build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Network)).problem)
        assertEquals("Too many tries. Wait a little while and try again.", build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.RateLimited(30))).problem)
    }

    @Test
    fun `a redeem refused on the preview keeps the preview and shows why`() {
        val s = build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Ready(preview), redeemRefusal = SetupCodeOutcome.ProfileGone))
        assertEquals(TvSetupPhase.PREVIEW, s.phase)
        assertEquals("That profile no longer exists. Pick another.", s.problem)
    }

    @Test
    fun `without a real account a code still works and adds to this device`() {
        for (kind in listOf(AccountKind.GUEST, AccountKind.SIGNED_OUT)) {
            val entry = build(kind = kind, label = null)
            assertTrue(entry.onDevice, "$kind")
            assertNull(entry.problem, "no sign-in wall in front of the keypad")
            assertNull(entry.watchedProfile, "no phone redeem to wait for without an account")
            val ready = build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Ready(preview)), kind = kind, label = null)
            assertEquals(TvSetupPhase.PREVIEW, ready.phase)
            assertEquals("Sign in any time to keep these in your Tuvora account.", ready.deviceNote)
            assertEquals(emptyList(), ready.profiles, "nothing to pick: it goes to this device")
            assertNull(ready.accountLabel)
        }
        val real = build(kind = AccountKind.REAL)
        assertFalse(real.onDevice)
        assertNull(real.deviceNote)
        assertEquals(profiles, real.profiles)
    }

    @Test
    fun `a device redeem says it was added to this Apple TV`() {
        val done = SetupCompletion("Starshare", 1, added = 1, updated = 0, alreadyRedeemed = false, openPlaylistKey = "k9", onDevice = true)
        assertEquals("Starshare added your playlist to this Apple TV", build(SetupCodeUiState(completed = done), kind = AccountKind.SIGNED_OUT).doneText)
    }

    @Test
    fun `a lapsed session on an account redeem asks to sign in again`() {
        val s = build(SetupCodeUiState(previewOutcome = SetupCodeOutcome.Ready(preview), redeemRefusal = SetupCodeOutcome.NeedsSignIn))
        assertEquals("Your Tuvora session ended. Sign in again on this Apple TV to add this setup.", s.problem)
    }

    @Test
    fun `a finished redeem says who added what and which playlist to open`() {
        val done = SetupCompletion("Starshare", 1, added = 1, updated = 0, alreadyRedeemed = false, openPlaylistKey = "k9")
        val s = build(SetupCodeUiState(completed = done))
        assertEquals(TvSetupPhase.DONE, s.phase)
        assertEquals("Starshare added your playlist", s.doneText)
        assertEquals("k9", s.openPlaylistKey)
    }

    @Test
    fun `an already redeemed code and a login the provider has not filled in say so`() {
        assertEquals(
            "Your playlist from Starshare is already in your account",
            build(SetupCodeUiState(completed = SetupCompletion("Starshare", 1, 0, 0, alreadyRedeemed = true, openPlaylistKey = null))).doneText,
        )
        assertEquals(
            "Starshare has not filled in your login yet. Ask them to: your playlist is added as soon as they do.",
            build(SetupCodeUiState(completed = SetupCompletion("Starshare", 1, 0, 0, false, null, skippedReasons = listOf("missing_login")))).doneText,
        )
        assertEquals(
            "Starshare's address for this playlist isn't valid. Ask them to check it.",
            build(SetupCodeUiState(completed = SetupCompletion("Starshare", 1, 0, 0, false, null, skippedReasons = listOf("invalid_url")))).doneText,
        )
    }

    @Test
    fun `a playlist added to another profile names that profile`() {
        val done = SetupCompletion("Starshare", 2, added = 1, updated = 0, alreadyRedeemed = false, openPlaylistKey = null)
        assertEquals("Starshare added your playlist to Kids", build(SetupCodeUiState(completed = done), activeProfile = 1).doneText)
    }

    @Test
    fun `the screen names the profile a phone redeem is watched for`() {
        assertEquals("Kids", build(activeProfile = 2).watchedProfile)
        assertEquals("Main", build(activeProfile = 1).watchedProfile)
    }

    @Test
    fun `the wait snapshot prefers the server's list and falls back to what the device already knows`() {
        assertEquals(setOf("a", "b", "local"), TvWaitSnapshot.of(serverKeys = setOf("a", "b"), cachedKeys = setOf("old"), localIds = setOf("local")))
        assertEquals(setOf("old", "local"), TvWaitSnapshot.of(serverKeys = null, cachedKeys = setOf("old"), localIds = setOf("local")))
    }
}
