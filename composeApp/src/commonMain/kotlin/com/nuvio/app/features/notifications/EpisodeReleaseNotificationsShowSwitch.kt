package com.nuvio.app.features.notifications

import com.nuvio.app.core.contracts.ShowAlertSwitch
import kotlinx.coroutines.flow.StateFlow

/** The notifications side of the shared per-show switch (see core/contracts/ShowAlerts.kt). */
internal object EpisodeReleaseNotificationsShowSwitch : ShowAlertSwitch {
    private val repository = EpisodeReleaseNotificationsRepository

    override val revision: StateFlow<Long> get() = repository.revision

    override fun isShowOn(contentType: String, contentId: String) = repository.isShowOn(contentType, contentId)

    override fun isDelivering(contentType: String, contentId: String) = repository.isDelivering(contentType, contentId)

    override fun isPermissionDenied() = repository.uiState.value.permissionDenied

    override fun set(contentType: String, contentId: String, title: String?, on: Boolean) =
        repository.setShowAlert(contentType, contentId, title, on)

    override fun request(contentType: String, contentId: String, title: String?) =
        repository.requestShowAlert(contentType, contentId, title)

    override fun openSystemNotificationSettings() = repository.openSystemNotificationSettings()
}
