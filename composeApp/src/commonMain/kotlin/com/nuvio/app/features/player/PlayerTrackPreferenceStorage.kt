package com.nuvio.app.features.player

data class PersistedPlayerTrackPreference(
    val subtitleType: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleName: String? = null,
    val subtitleTrackId: String? = null,
    val addonSubtitleId: String? = null,
    val addonSubtitleUrl: String? = null,
    val addonSubtitleAddonName: String? = null,
    val audioLanguage: String? = null,
    val audioName: String? = null,
    val audioTrackId: String? = null,
    val subtitleIsForced: Boolean? = null,
    /** F37: the series' last in-player resize mode ([PlayerResizeMode] name); null = global default. */
    val resizeMode: String? = null,
    /** F36: the series' manual zoom ([VideoZoom]); all null = no zoom. */
    val zoomScaleX: Float? = null,
    val zoomScaleY: Float? = null,
    val zoomPanX: Float? = null,
    val zoomPanY: Float? = null,
)

object PersistedSubtitleSelectionType {
    const val INTERNAL = "INTERNAL"
    const val ADDON = "ADDON"
    const val DISABLED = "DISABLED"
}

internal expect object PlayerTrackPreferenceStorage {
    fun load(contentId: String): PersistedPlayerTrackPreference?
    fun save(contentId: String, preference: PersistedPlayerTrackPreference)
    fun loadSubtitleDelayMs(videoId: String): Int?
    fun saveSubtitleDelayMs(videoId: String, delayMs: Int)
    fun loadAudioDelayMs(audioOutputKey: String): Int?
    fun saveAudioDelayMs(audioOutputKey: String, delayMs: Int)
}
