package com.nuvio.app.features.calendar.internal

import kotlinx.serialization.Serializable

/**
 * Per-DEVICE, per-PROFILE storage (never synced): the snapshot of followed titles and their releases, and the
 * viewer's Calendar settings. Android needs a Context before first use (AndroidFeatureWiring); until then loads
 * return null and saves are dropped, which only costs a refresh.
 */
internal expect object CalendarStorage {
    fun loadString(key: String): String?
    fun saveString(key: String, value: String)
}

/** Key-value seam so the repository tests without platform storage. */
interface CalendarStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
}

internal object PlatformCalendarStore : CalendarStore {
    override fun load(key: String): String? = CalendarStorage.loadString(key)
    override fun save(key: String, value: String) = CalendarStorage.saveString(key, value)
}

enum class CalendarViewMode { LIST, MONTH }

/** What the Calendar page shows: shows, films, sports games. Multi-select; all on by default. */
@Serializable
enum class CalendarFilter { SHOWS, MOVIES, SPORTS }

@Serializable
data class CalendarSettings(
    val bellOn: Set<String> = emptySet(),
    val hiddenTitleKeys: Set<String> = emptySet(),
    /** Names of hidden titles, so they can be listed after the Calendar has dropped them. */
    val hiddenTitleNames: Map<String, String> = emptyMap(),
    val windowDays: Int = CALENDAR_DEFAULT_WINDOW_DAYS,
    val zoneOverride: String? = null,
    val cornerMessage: Boolean = true,
    val comingUpRow: Boolean = false,
    val comingUpHiddenKeys: Set<String> = emptySet(),
    val viewMode: CalendarViewMode = CalendarViewMode.LIST,
    val filters: Set<CalendarFilter> = CalendarFilter.entries.toSet(),
) {
    fun normalized(): CalendarSettings = copy(
        windowDays = normalizeCalendarWindowDays(windowDays),
        zoneOverride = normalizeCalendarZoneOverride(zoneOverride),
    )
}

internal fun snapshotKey(profileId: Int) = "snapshot.$profileId"
internal fun settingsKey(profileId: Int) = "settings.$profileId"

fun CalendarStore.loadSnapshot(profileId: Int): CalendarSnapshot =
    load(snapshotKey(profileId))?.let { raw ->
        runCatching { calendarJson.decodeFromString(CalendarSnapshot.serializer(), raw) }.getOrNull()
            ?.takeIf { it.version == CalendarSnapshot.CURRENT_VERSION }
    } ?: CalendarSnapshot()

fun CalendarStore.saveSnapshot(profileId: Int, snapshot: CalendarSnapshot) =
    save(snapshotKey(profileId), calendarJson.encodeToString(CalendarSnapshot.serializer(), snapshot))

fun CalendarStore.loadSettings(profileId: Int): CalendarSettings =
    load(settingsKey(profileId))?.let { raw ->
        runCatching { calendarJson.decodeFromString(CalendarSettings.serializer(), raw) }.getOrNull()
    }?.normalized() ?: CalendarSettings()

fun CalendarStore.saveSettings(profileId: Int, settings: CalendarSettings) =
    save(settingsKey(profileId), calendarJson.encodeToString(CalendarSettings.serializer(), settings.normalized()))
