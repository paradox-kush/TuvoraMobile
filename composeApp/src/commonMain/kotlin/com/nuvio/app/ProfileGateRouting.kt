package com.nuvio.app

/**
 * Pure routing decisions for "Switch profile" requests that cross Compose instances.
 *
 * On iOS the app is several Compose hosts at once: the Home host owns the profile gate, while the
 * Settings cover (and pushed detail screens) are separate hosts that bypass the gate. A tap in a
 * bypassing host has to be routed to the gate-owning host; a tap that has no route must not be shown.
 */
internal object ProfileGateRouting {

    enum class SwitchRequestRoute {
        /** An in-process [AppGateController] reaches the gate directly. */
        GateController,

        /** The native shell's switcher controller reaches the gate-owning host. */
        NativeSwitcher,

        /** Nothing can service the request: the entry point must be hidden, never a dead tap. */
        None,
    }

    fun switchRequestRoute(hasGateController: Boolean, hasNativeSwitcher: Boolean): SwitchRequestRoute =
        when {
            hasGateController -> SwitchRequestRoute.GateController
            hasNativeSwitcher -> SwitchRequestRoute.NativeSwitcher
            else -> SwitchRequestRoute.None
        }

    /** Whether this host should collect native-switcher requests (manage / pick a profile). */
    fun listensForNativeSwitcher(
        bypassAppGate: Boolean,
        hasNativeSwitcher: Boolean,
    ): Boolean = hasNativeSwitcher && !bypassAppGate
}
