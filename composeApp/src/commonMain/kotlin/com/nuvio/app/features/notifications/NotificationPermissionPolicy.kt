package com.nuvio.app.features.notifications

/** What the Notifications page shows about the OS permission. */
internal data class NotificationPermissionView(
    val granted: Boolean,
    val denied: Boolean,
    val errorMessage: String?,
)

/**
 * Reconciles the OS permission with the page state. The permission can change outside the app (system settings),
 * so this runs every time the page resumes, and a message that only said "disabled" must go when it is granted again.
 */
internal object NotificationPermissionPolicy {
    fun reconcile(
        isEnabled: Boolean,
        granted: Boolean,
        currentError: String?,
        disabledMessage: String,
    ): NotificationPermissionView = NotificationPermissionView(
        granted = granted,
        denied = isEnabled && !granted,
        errorMessage = when {
            isEnabled && !granted -> disabledMessage
            granted && currentError == disabledMessage -> null
            else -> currentError
        },
    )

    /** A refresh on resume reschedules only when alerts are on and the permission just came back. */
    fun shouldReschedule(wasGranted: Boolean, nowGranted: Boolean, isEnabled: Boolean): Boolean =
        isEnabled && nowGranted && !wasGranted
}
