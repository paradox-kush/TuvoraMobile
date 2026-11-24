package com.tuvora.tvos.app

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.NuvioProfile
import kotlin.test.Test
import kotlin.test.assertEquals

// Apple TV's startup gate must make the same choices as the phone's AppGate.kt (upstream, Compose):
// which screen shows and which profile opens without asking. Cases mirror AppGate.kt:296-372.
class TvGatePolicyTest {
    private val alice = NuvioProfile(profileIndex = 1, name = "Alice")
    private val bob = NuvioProfile(profileIndex = 2, name = "Bob")
    private val kidsWithPin = NuvioProfile(profileIndex = 3, name = "Kids", pinEnabled = true)
    private val signedIn = AuthState.Authenticated(userId = "u1", email = "a@example.test", isAnonymous = false)

    private fun input(
        auth: AuthState = signedIn,
        profiles: List<NuvioProfile> = emptyList(),
        current: TvGateScreen = TvGateScreen.Loading,
        online: Boolean = true,
        rememberLast: Boolean = false,
        everSelected: Boolean = false,
        activeProfileIndex: Int = 1,
    ) = TvGateInput(auth, profiles, current, online, rememberLast, everSelected, activeProfileIndex)

    @Test
    fun `a picker the viewer opened stays up on cached profiles with no session`() {
        val opened = input(auth = AuthState.Unauthenticated, profiles = listOf(alice), current = TvGateScreen.ProfilePicker)
            .copy(userOpenedPicker = true)
        assertEquals(TvGateDecision.Stay, TvGatePolicy.decide(opened), "Manage Profiles must not bounce back to the app")
        assertEquals(TvGateDecision.Stay, TvGatePolicy.decide(opened.copy(auth = AuthState.Loading)), "session still restoring")
        // The startup picker (not opened by the viewer) still auto-skips a single unlocked profile.
        assertEquals(TvGateDecision.SwitchTo(alice, sync = false), TvGatePolicy.decide(opened.copy(userOpenedPicker = false)))
    }

    @Test
    fun `signed in with one unlocked profile opens it and syncs`() {
        assertEquals(TvGateDecision.SwitchTo(alice, sync = true), TvGatePolicy.decide(input(profiles = listOf(alice))))
    }

    @Test
    fun `a single profile with a PIN asks first`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.ProfilePicker), TvGatePolicy.decide(input(profiles = listOf(kidsWithPin))))
    }

    @Test
    fun `several profiles show the picker`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.ProfilePicker), TvGatePolicy.decide(input(profiles = listOf(alice, bob))))
    }

    @Test
    fun `remember last profile reopens it without asking`() {
        val decision = TvGatePolicy.decide(
            input(profiles = listOf(alice, bob), rememberLast = true, everSelected = true, activeProfileIndex = 2),
        )
        assertEquals(TvGateDecision.SwitchTo(bob, sync = true), decision)
    }

    @Test
    fun `remember last never skips a PIN`() {
        val decision = TvGatePolicy.decide(
            input(profiles = listOf(alice, kidsWithPin), rememberLast = true, everSelected = true, activeProfileIndex = 3),
        )
        assertEquals(TvGateDecision.Show(TvGateScreen.ProfilePicker), decision)
    }

    @Test
    fun `signed out with no cached profiles shows sign in`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.SignIn), TvGatePolicy.decide(input(auth = AuthState.Unauthenticated)))
    }

    @Test
    fun `signed out and offline with cached profiles opens them without syncing`() {
        val decision = TvGatePolicy.decide(
            input(auth = AuthState.Unauthenticated, profiles = listOf(alice), current = TvGateScreen.SignIn, online = false),
        )
        assertEquals(TvGateDecision.SwitchTo(alice, sync = false), decision)
    }

    @Test
    fun `signed out and online on the sign in screen stays there`() {
        val decision = TvGatePolicy.decide(
            input(auth = AuthState.Unauthenticated, profiles = listOf(alice), current = TvGateScreen.SignIn, online = true),
        )
        assertEquals(TvGateDecision.Show(TvGateScreen.SignIn), decision)
    }

    @Test
    fun `still loading with cached profiles opens them without syncing`() {
        val decision = TvGatePolicy.decide(input(auth = AuthState.Loading, profiles = listOf(alice)))
        assertEquals(TvGateDecision.SwitchTo(alice, sync = false), decision)
    }

    @Test
    fun `still loading with nothing cached waits`() {
        assertEquals(TvGateDecision.Show(TvGateScreen.Loading), TvGatePolicy.decide(input(auth = AuthState.Loading)))
    }

    @Test
    fun `an authenticated update while already in the app changes nothing`() {
        assertEquals(TvGateDecision.Stay, TvGatePolicy.decide(input(profiles = listOf(alice), current = TvGateScreen.Main)))
    }

    @Test
    fun `a switch in progress is never interrupted`() {
        val decision = TvGatePolicy.decide(input(profiles = listOf(alice, bob), current = TvGateScreen.Switching))
        assertEquals(TvGateDecision.Stay, decision)
    }

    @Test
    fun `profiles arriving while the picker auto-skips open the only profile`() {
        val decision = TvGatePolicy.decide(input(profiles = listOf(alice), current = TvGateScreen.ProfilePicker))
        assertEquals(TvGateDecision.SwitchTo(alice, sync = true), decision)
    }

    @Test
    fun `a picker the user opened on purpose is not auto-skipped`() {
        val decision = TvGatePolicy.decide(
            input(profiles = listOf(alice), current = TvGateScreen.ProfilePicker).copy(userOpenedPicker = true),
        )
        assertEquals(TvGateDecision.Stay, decision)
    }

    @Test
    fun `a sign-in still restoring never sends an open app back to the picker`() {
        // Simulator 2026-09-28: picked a cached profile while the session was still restoring; the
        // gate reached Main and was re-evaluated on the screen change, bouncing Main -> picker -> Main.
        val twoProfiles = listOf(alice, bob)
        assertEquals(TvGateDecision.Stay, TvGatePolicy.decide(input(auth = AuthState.Loading, profiles = twoProfiles, current = TvGateScreen.Main)))
        assertEquals(TvGateDecision.Stay, TvGatePolicy.decide(input(auth = AuthState.Unauthenticated, profiles = twoProfiles, current = TvGateScreen.Main)))
    }

    // Bug 2026-09-29: Settings -> Manage Profiles had no way back. Leaving a picker the viewer opened
    // over a running profile returns straight to the app - no profile switch and no re-sync.
    @Test
    fun `leaving a picker opened over a live profile returns to the app`() {
        assertEquals(TvGateScreen.Main, TvGatePolicy.leavePicker(userOpenedPicker = true, profileLive = true))
    }

    @Test
    fun `the startup picker has nothing to go back to`() {
        assertEquals(null, TvGatePolicy.leavePicker(userOpenedPicker = false, profileLive = true))
        assertEquals(null, TvGatePolicy.leavePicker(userOpenedPicker = true, profileLive = false))
    }

    // In-app profile switch (sidebar profile item): re-picking the profile already open just closes
    // the picker - no switch, no re-sync (that re-switch once left Home empty).
    @Test
    fun `picking the profile already open closes the switcher`() {
        assertEquals(TvPickAction.Close, TvGatePolicy.onPick(pickedIndex = 1, activeIndex = 1, profileLive = true, userOpenedPicker = true))
    }

    @Test
    fun `picking another profile switches`() {
        assertEquals(TvPickAction.Switch, TvGatePolicy.onPick(pickedIndex = 2, activeIndex = 1, profileLive = true, userOpenedPicker = true))
    }

    @Test
    fun `the startup pick always switches`() {
        assertEquals(TvPickAction.Switch, TvGatePolicy.onPick(pickedIndex = 1, activeIndex = 1, profileLive = false, userOpenedPicker = false))
        assertEquals(TvPickAction.Switch, TvGatePolicy.onPick(pickedIndex = 1, activeIndex = null, profileLive = true, userOpenedPicker = true))
    }
}
