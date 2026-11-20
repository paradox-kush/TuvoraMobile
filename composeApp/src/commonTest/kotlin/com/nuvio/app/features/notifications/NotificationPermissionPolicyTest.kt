package com.nuvio.app.features.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationPermissionPolicyTest {
    private val disabled = "System notifications are disabled"

    @Test
    fun `a re-granted permission clears the stale disabled message and the denied flag`() {
        val view = NotificationPermissionPolicy.reconcile(isEnabled = true, granted = true, currentError = disabled, disabledMessage = disabled)
        assertEquals(true, view.granted, "granted")
        assertEquals(false, view.denied, "denied")
        assertNull(view.errorMessage, "stale message cleared")
    }

    @Test
    fun `a revoked permission with alerts on shows the disabled message`() {
        val view = NotificationPermissionPolicy.reconcile(isEnabled = true, granted = false, currentError = null, disabledMessage = disabled)
        assertEquals(false, view.granted, "granted")
        assertEquals(true, view.denied, "denied")
        assertEquals(disabled, view.errorMessage, "message")
    }

    @Test
    fun `an unrelated error survives a permission refresh`() {
        val view = NotificationPermissionPolicy.reconcile(isEnabled = true, granted = true, currentError = "Could not send", disabledMessage = disabled)
        assertEquals("Could not send", view.errorMessage, "other error kept")
    }

    @Test
    fun `permission off with alerts off is not shown as denied`() {
        val view = NotificationPermissionPolicy.reconcile(isEnabled = false, granted = false, currentError = null, disabledMessage = disabled)
        assertEquals(false, view.denied, "denied")
        assertNull(view.errorMessage, "no message")
    }

    @Test
    fun `a refresh reschedules only when the permission came back`() {
        assertEquals(true, NotificationPermissionPolicy.shouldReschedule(wasGranted = false, nowGranted = true, isEnabled = true), "regranted")
        assertEquals(false, NotificationPermissionPolicy.shouldReschedule(wasGranted = true, nowGranted = true, isEnabled = true), "unchanged")
        assertEquals(false, NotificationPermissionPolicy.shouldReschedule(wasGranted = false, nowGranted = true, isEnabled = false), "alerts off")
    }
}
