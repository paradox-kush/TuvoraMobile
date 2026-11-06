package com.nuvio.app.features.calendar.api

/**
 * The Calendar's Compose UI slots, for the UI half of the composition root (FeatureWiring.kt). Kept apart from
 * [CalendarFeature] so the Apple TV build, which compiles no Compose UI, never sees them (its screens are SwiftUI).
 */
object CalendarUiSlots {
    fun homeSection(): com.nuvio.app.core.contracts.HomeCalendarSection =
        com.nuvio.app.features.calendar.internal.CalendarHomeSectionImpl { CalendarFeature.repository }

    fun detailsBell(): com.nuvio.app.core.contracts.DetailsCalendarBell =
        com.nuvio.app.features.calendar.internal.CalendarDetailsBellImpl { CalendarFeature.repository }

    fun screen(): com.nuvio.app.core.contracts.CalendarScreenContent =
        com.nuvio.app.features.calendar.internal.CalendarScreenContentImpl { CalendarFeature.repository }
}
