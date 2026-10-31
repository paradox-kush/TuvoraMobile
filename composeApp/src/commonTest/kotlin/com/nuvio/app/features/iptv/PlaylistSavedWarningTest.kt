package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** UX116 — an edit saved with a rejected login says whose verdict it is, not that the check failed. */
class PlaylistSavedWarningTest {

    @Test
    fun `wrong login attributes the verdict to the provider`() {
        assertEquals(
            "Saved — the provider says: Wrong username or password",
            savedUnverifiedFallback(
                PlaylistSaveMessage.Known(PlaylistSaveError.WRONG_CREDENTIALS),
                "Wrong username or password",
            ),
        )
    }

    @Test
    fun `an unreachable provider keeps the could not be checked wording`() {
        assertEquals(
            "Saved, but the provider couldn't be checked: Couldn't reach the server",
            savedUnverifiedFallback(
                PlaylistSaveMessage.Known(PlaylistSaveError.UNREACHABLE),
                "Couldn't reach the server",
            ),
        )
    }

    @Test
    fun `an authored provider message keeps the could not be checked wording`() {
        assertEquals(
            "Saved, but the provider couldn't be checked: Account expired",
            savedUnverifiedFallback(PlaylistSaveMessage.Authored("Account expired"), "Account expired"),
        )
    }
}
