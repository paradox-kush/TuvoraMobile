package com.tuvora.tvos.screens

import com.nuvio.app.core.rec.RecEventSettings
import com.nuvio.app.features.settings.SentrySettingsRepository
import kotlinx.coroutines.flow.StateFlow

/** Exposes the same persisted consent and queue-purge behavior used by the other clients. */
object TvPrivacySettings {
    val diagnostics: StateFlow<Boolean> get() {
        SentrySettingsRepository.ensureLoaded()
        return SentrySettingsRepository.enabled
    }
    val recommendations: StateFlow<Boolean> get() = RecEventSettings.enabled
    fun setDiagnostics(enabled: Boolean) = SentrySettingsRepository.setEnabled(enabled)
    fun setRecommendations(enabled: Boolean) = RecEventSettings.setEnabled(enabled)
}
