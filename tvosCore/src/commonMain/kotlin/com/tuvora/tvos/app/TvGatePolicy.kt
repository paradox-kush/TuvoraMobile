package com.tuvora.tvos.app

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.NuvioProfile

/**
 * Apple TV's startup gate: which screen shows, and which profile opens without asking.
 *
 * Mirrors the phone's AppGate.kt (upstream, Compose-bound, excluded from :tvosCore) rule for rule, so
 * a household sees the same behaviour on both. Pure, so it is tested without auth, storage or UI;
 * TvAppLifecycle feeds it the live state and carries out the decision.
 */
enum class TvGateScreen { Loading, SignIn, ProfilePicker, Switching, Main }

data class TvGateInput(
    val auth: AuthState,
    val profiles: List<NuvioProfile>,
    val current: TvGateScreen,
    val online: Boolean,
    val rememberLastProfileEnabled: Boolean,
    val hasEverSelectedProfile: Boolean,
    val activeProfileIndex: Int,
    /** The user chose "switch profile": the picker must stay up even with a single profile. */
    val userOpenedPicker: Boolean = false,
)

sealed interface TvGateDecision {
    data object Stay : TvGateDecision
    data class Show(val screen: TvGateScreen) : TvGateDecision
    data class SwitchTo(val profile: NuvioProfile, val sync: Boolean) : TvGateDecision
}

/** What choosing a profile on the picker does. */
enum class TvPickAction { Switch, Close }

object TvGatePolicy {

    /**
     * A pick on a picker opened over a live profile that names that same profile only closes the
     * picker; anything else (another profile, or the startup pick) switches.
     */
    fun onPick(pickedIndex: Int, activeIndex: Int?, profileLive: Boolean, userOpenedPicker: Boolean): TvPickAction =
        if (userOpenedPicker && profileLive && activeIndex == pickedIndex) TvPickAction.Close else TvPickAction.Switch

    /**
     * Back/Menu (or Done) on the profile picker. A picker the viewer opened from Settings over a
     * running profile closes straight back to the app - no switch, no re-sync. The startup picker
     * (or one reached with no live profile) has nothing behind it: null.
     */
    fun leavePicker(userOpenedPicker: Boolean, profileLive: Boolean): TvGateScreen? =
        if (userOpenedPicker && profileLive) TvGateScreen.Main else null

    fun decide(input: TvGateInput): TvGateDecision {
        if (input.current == TvGateScreen.Switching) return TvGateDecision.Stay
        // A picker the viewer opened on purpose (Manage Profiles, the sidebar's profile item) stays up whatever the
        // session is doing: a restoring or lapsed session on cached profiles must not auto-skip it back to the app.
        if (input.userOpenedPicker && input.current == TvGateScreen.ProfilePicker) return TvGateDecision.Stay

        val authenticated = input.auth is AuthState.Authenticated
        val cachedAccess = input.profiles.isNotEmpty() && !authenticated

        // Once the app is open on cached profiles, a session that is still restoring (or lapsed while
        // offline) never sends the viewer back to the picker. The phone gets this implicitly: its
        // AppGate effect is not re-run by a screen change, while this policy is.
        if (cachedAccess && input.current == TvGateScreen.Main) return TvGateDecision.Stay

        return when (input.auth) {
            AuthState.Loading ->
                if (cachedAccess) enterProfileGate(input, sync = false) else TvGateDecision.Show(TvGateScreen.Loading)

            AuthState.Unauthenticated ->
                // AppGate.kt: cached profiles stay usable offline, or when the user is not on sign-in.
                if (cachedAccess && (!input.online || input.current != TvGateScreen.SignIn)) {
                    enterProfileGate(input, sync = false)
                } else {
                    TvGateDecision.Show(TvGateScreen.SignIn)
                }

            is AuthState.Authenticated -> when (input.current) {
                TvGateScreen.Loading, TvGateScreen.SignIn -> enterProfileGate(input, sync = true)
                // Profiles can arrive after the picker went up (pull after sign-in): auto-skip then too.
                TvGateScreen.ProfilePicker ->
                    if (input.userOpenedPicker) TvGateDecision.Stay else autoSkip(input, sync = true) ?: TvGateDecision.Stay
                else -> TvGateDecision.Stay
            }
        }
    }

    private fun enterProfileGate(input: TvGateInput, sync: Boolean): TvGateDecision {
        if (input.profiles.isEmpty()) return TvGateDecision.Show(TvGateScreen.ProfilePicker)
        return autoSkip(input, sync) ?: TvGateDecision.Show(TvGateScreen.ProfilePicker)
    }

    /** A profile that opens without the picker: the remembered one, or the only one; never past a PIN. */
    private fun autoSkip(input: TvGateInput, sync: Boolean): TvGateDecision? {
        if (input.rememberLastProfileEnabled && input.hasEverSelectedProfile) {
            input.profiles
                .find { it.profileIndex == input.activeProfileIndex }
                ?.takeUnless { it.pinEnabled }
                ?.let { return TvGateDecision.SwitchTo(it, sync) }
        }
        val only = input.profiles.singleOrNull() ?: return null
        return if (only.pinEnabled) null else TvGateDecision.SwitchTo(only, sync)
    }
}
