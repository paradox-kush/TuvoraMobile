package com.nuvio.app

import android.content.Intent
import android.net.Uri
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * E2E 2026-10-16: with the app already running as MainActivity (no launcher alias in the task), a party link
 * started the launcher alias as a SECOND MainActivity on top; the hidden first instance's deep-link collector
 * consumed the link, so the visible one stayed on Home. A link must go to the activity that is already alive.
 */
class LinkHandoffPolicyTest {
    @Test
    fun `a running activity gets the link`() {
        assertEquals("com.nuvio.app.MainActivity", LinkHandoffPolicy.target(listOf("com.nuvio.app.MainActivity"), "com.nuvio.app.launcher.AppIconDefault"))
    }

    @Test
    fun `the top one when several are alive`() {
        assertEquals(
            "com.nuvio.app.launcher.AppIconArcticBlue",
            LinkHandoffPolicy.target(listOf("com.nuvio.app.MainActivity", "com.nuvio.app.launcher.AppIconArcticBlue"), "com.nuvio.app.launcher.AppIconDefault"),
        )
    }

    @Test
    fun `cold start uses the launcher icon`() {
        assertEquals("com.nuvio.app.launcher.AppIconDefault", LinkHandoffPolicy.target(emptyList(), "com.nuvio.app.launcher.AppIconDefault"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkActivityHandoffTest {
    @After fun tearDown() = LiveMainActivities.destroyed("com.nuvio.app.MainActivity")

    @Test
    fun `a warm link reaches the running activity with the link attached`() {
        LiveMainActivities.created("com.nuvio.app.MainActivity")
        Robolectric.buildActivity(LinkActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse("tuvora://party/E23TPG"))).create()
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("com.nuvio.app.MainActivity", started.component?.className)
        assertEquals("tuvora://party/E23TPG", started.getStringExtra(LinkActivity.EXTRA_LINK))
        assertEquals(true, started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0, "delivered as onNewIntent")
    }
}
