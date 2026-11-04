package com.nuvio.app.core.contracts

/**
 * Firewall port for provider setup codes (Step 2). The shell (App/MainAppContent, the deep-link bridge)
 * must not name the IPTV subsystem, so it hands a code that arrived from a link — or the moment a person
 * who went to sign in is back — to whichever feature registered here. No-op default (null) when the
 * feature is absent: such a link is simply ignored.
 *
 * The code is NEVER passed as a route argument or persisted: [acceptLinkedCode] keeps it in memory only
 * (30 minutes), and the preview page reads it from there.
 */
internal interface SetupCodeEntry {
    /**
     * A code (or a link containing one) arrived from outside the app. Holds it in memory and returns true
     * when it was a code. Needs no account (B205): the preview opens for everyone, and a person without a real
     * account adds the playlists to this device. [takeResumeAfterSignIn] stays for a session that lapsed mid-redeem.
     */
    fun acceptLinkedCode(link: String): Boolean

    /**
     * A link arrived while there is no shell to open it in (the profile picker is showing). Keeps the code in
     * memory only (same 30 minutes) with no routing; the shell calls [acceptHeldCode] once it is on screen.
     */
    fun holdLinkedCode(link: String): Boolean

    /** True while a code is held (and still inside its 30 minutes). */
    fun hasHeldCode(): Boolean

    /** The shell appeared: continue a held link as if it had just arrived. False when nothing is held (any more). */
    fun acceptHeldCode(): Boolean

    /** True once (then false) after sign-in completed with a code held: the shell should open the preview. */
    fun takeResumeAfterSignIn(): Boolean

    /** Prepares the Add Playlist page to open on its Code chip (the empty IPTV screen's secondary button). */
    fun prepareCodeEntryPage()
}

internal object SetupCodeEntryAccess {
    private var entry: SetupCodeEntry? = null

    fun register(e: SetupCodeEntry) {
        entry = e
    }

    /** Null until the IPTV feature registers — the no-op default. */
    fun current(): SetupCodeEntry? = entry

    fun resetForTest() {
        entry = null
    }
}
