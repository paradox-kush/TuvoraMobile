package com.nuvio.app.core.contracts

import kotlinx.coroutines.flow.StateFlow

/*
 * One per-show switch drives both the OS episode alert (features/notifications) and the Calendar's bell
 * (features/calendar, fork-only). The firewall keeps the two features from naming each other, so each side meets the
 * other here: Notifications owns the switch and exposes it as [ShowAlertSwitch]; the Calendar owns the bell and
 * offers it back as [ShowAlertBellMirror]. With nothing registered both are no-ops.
 */

/** What the Calendar UI reads and writes about a show's alert. Types are the app's ("series") and content ids. */
interface ShowAlertSwitch {
    /** Bumped whenever any show's alert state, the master switch or the permission state changes. */
    val revision: StateFlow<Long>

    /** The show's own switch, whether or not the master switch is on (what a bell row shows). */
    fun isShowOn(contentType: String, contentId: String): Boolean

    /** Alerts will actually go out for the show: master on, permission granted, show on (what the Home chip shows). */
    fun isDelivering(contentType: String, contentId: String): Boolean

    /** The OS notification permission was refused or revoked: controls say so instead of acting. */
    fun isPermissionDenied(): Boolean

    /** Flips the show's switch and its bell. Never asks for the OS permission. */
    fun set(contentType: String, contentId: String, title: String?, on: Boolean)

    /** Turns the show on and, if the master switch is off, asks for the OS permission now and turns it on. */
    fun request(contentType: String, contentId: String, title: String?)

    fun openSystemNotificationSettings()
}

/** The Calendar's side: keeps its bell equal to the show's alert switch. Must not call back into [ShowAlertSwitch]. */
interface ShowAlertBellMirror {
    fun mirrorBell(contentType: String, contentId: String, on: Boolean)
}

object ShowAlertSwitchAccess {
    private var switch: ShowAlertSwitch? = null
    fun register(s: ShowAlertSwitch) { switch = s }
    fun current(): ShowAlertSwitch? = switch
    fun resetForTest() { switch = null }
}

object ShowAlertBellMirrorAccess {
    private var mirror: ShowAlertBellMirror? = null
    fun register(m: ShowAlertBellMirror) { mirror = m }
    fun current(): ShowAlertBellMirror? = mirror
    fun resetForTest() { mirror = null }
}
