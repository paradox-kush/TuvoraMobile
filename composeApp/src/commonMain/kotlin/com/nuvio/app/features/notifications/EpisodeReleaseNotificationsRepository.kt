@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.notifications

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.ShowAlertBellMirrorAccess
import com.nuvio.app.core.deeplink.buildHomeDeepLinkUrl
import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.serialization.json.Json

object EpisodeReleaseNotificationsRepository {
    private const val sessionsAuxName = "viewing_sessions"
    private const val runtimeAuxName = "runtime"

    private val log = Logger.withTag("EpisodeReleaseNotifications")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    /**
     * Episode candidates per alerted show, and the rule for when a request may be made (see [AlertRefreshPolicy]).
     * The fetch is a port so a test can count requests without the add-on stack.
     */
    internal var metadataFetcher: suspend (TrackedFollowedShow) -> List<AlertCandidate>? = { show -> fetchCandidatesForShow(show) }
    private val candidateCache = AlertCandidateCache(fetch = { show -> metadataFetcher(show) })

    /** What was last handed to the platform, so a progress tick that plans the same thing does not reschedule it. */
    private var lastScheduled: List<EpisodeReleaseNotificationRequest>? = null
    private val refreshMutex = Mutex()
    private val permissionMutex = Mutex()

    private val _uiState = MutableStateFlow(EpisodeReleaseNotificationsUiState())
    val uiState: StateFlow<EpisodeReleaseNotificationsUiState> = _uiState.asStateFlow()

    /** Bumped on any change the Calendar's switch port should recompose for. */
    private val _revision = MutableStateFlow(0L)
    internal val revision: StateFlow<Long> = _revision.asStateFlow()

    @Volatile
    private var hasLoaded = false
    @Volatile
    private var trackedShowsByKey: Map<String, TrackedFollowedShow> = emptyMap()
    @Volatile
    private var preferences: AlertPreferences = AlertPreferences()
    @Volatile
    private var promptedShowKeys: Set<String> = emptySet()
    @Volatile
    private var runtime: StoredAlertRuntime = StoredAlertRuntime()
    @Volatile
    private var viewingStarts: List<String> = emptyList()

    init {
        scope.launch {
            combine(LibraryRepository.uiState, WatchProgressRepository.uiState) { library, progress -> library to progress }
                .collectLatest { (library, progress) ->
                    if (!hasLoaded) return@collectLatest

                    val changed = reconcileTrackedShows(library, progress)
                    if (changed) {
                        persist()
                    }

                    if (_uiState.value.isEnabled) {
                        // Watch progress is saved every minute during playback: this must stay free (no request).
                        refreshScheduledNotifications(AlertRefreshReason.ProgressTick)
                    }
                }
        }
    }

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
        scope.launch {
            syncAuthorizationState(refreshIfEnabled = true)
        }
    }

    fun onProfileChanged() {
        // Candidates carry the profile id in their episode keys: the previous profile's must not be planned.
        candidateCache.clear()
        lastScheduled = null
        loadFromDisk()
        scope.launch {
            syncAuthorizationState(refreshIfEnabled = true)
        }
    }

    /**
     * The app came to the foreground (or started). Records that today was opened, works out which earlier alerts
     * were acted on and which ignored, then reschedules: an alert for today is dropped by the gate because the app
     * was opened today. This is also how iOS, which cannot run code when an alert is due, honours "nothing on a day
     * you already opened Tuvora".
     */
    fun onAppOpened() {
        ensureLoaded()
        scope.launch {
            val now = Clock.System.now()
            val authorized = runCatching { EpisodeReleaseNotificationPlatform.notificationsAuthorized() }.getOrDefault(false)
            val awayOn = AwayReminderPolicy.effective(preferences.awayReminder, authorized, _uiState.value.isEnabled) != AwayReminder.Off
            val couldHaveFired = (_uiState.value.isEnabled || awayOn) && authorized && !preferences.allPaused
            val before = runtime
            runtime = AlertAftermath.onAppOpened(
                runtime = runtime,
                now = now,
                zone = TimeZone.currentSystemDefault(),
                alertsCouldHaveFired = couldHaveFired,
            )
            AlertAftermath.summarize(before, runtime, now, couldHaveFired)
                ?.let { AlertTelemetry.send(AlertTelemetry.aftermath(it)) }
            persistRuntime()
            runCatching { EpisodeReleaseNotificationPlatform.recordAppOpened(now.toEpochMilliseconds()) }
            publish()
            refreshScheduledNotifications(AlertRefreshReason.AppOpened)
        }
    }

    /** One playback start on this device. Only the time of day is kept, locally; see [ViewingHistory]. */
    fun recordPlaybackStart() {
        ensureLoaded()
        val local = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val updated = ViewingHistory.append(viewingStarts, local)
        if (updated == viewingStarts) return
        viewingStarts = updated
        persistViewingHistory()
        publish()
    }

    fun clearLocalState() {
        // Signing out or wiping local data also forgets when this phone is used and what was sent: that history is
        // device-local by design, so nothing else holds a copy.
        runCatching {
            EpisodeReleaseNotificationsStorage.saveAux(sessionsAuxName, "")
            EpisodeReleaseNotificationsStorage.saveAux(runtimeAuxName, "")
        }
        hasLoaded = false
        candidateCache.clear()
        lastScheduled = null
        trackedShowsByKey = emptyMap()
        preferences = AlertPreferences()
        promptedShowKeys = emptySet()
        runtime = StoredAlertRuntime()
        viewingStarts = emptyList()
        _uiState.value = EpisodeReleaseNotificationsUiState()
        scope.launch {
            runCatching { EpisodeReleaseNotificationPlatform.clearScheduledEpisodeReleaseNotifications() }
                .onFailure { error ->
                    log.w { "Failed to clear scheduled episode release notifications: ${error.message}" }
                }
        }
    }

    internal fun applyFromSync(enabled: Boolean, remotePreferences: JsonObject) {
        ensureLoaded()
        val mergedPreferences = AlertPreferencesSyncCodec.applyOver(preferences, remotePreferences)
        val preferencesChanged = mergedPreferences != preferences
        if (_uiState.value.isEnabled == enabled && !preferencesChanged) return

        preferences = mergedPreferences
        _uiState.value = _uiState.value.copy(
            isEnabled = enabled,
            isLoading = false,
            isSendingTest = false,
            statusMessage = null,
            errorMessage = null,
        )
        persist()
        publish()

        scope.launch {
            refreshScheduledNotifications(AlertRefreshReason.SettingsChange)
        }
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (enabled != _uiState.value.isEnabled) {
            AlertTelemetry.send(AlertTelemetry.settingChanged(AlertTelemetry.Setting.EpisodeAlerts, AlertTelemetry.onOff(enabled)))
        }
        scope.launch {
            if (!enabled) {
                runCatching { EpisodeReleaseNotificationPlatform.clearScheduledEpisodeReleaseNotifications() }
                    .onFailure { error ->
                        log.w { "Failed to clear episode release notifications: ${error.message}" }
                    }
                _uiState.value = _uiState.value.copy(
                    isEnabled = false,
                    isLoading = false,
                    scheduledCount = 0,
                    statusMessage = null,
                    errorMessage = null,
                    permissionDenied = false,
                )
                runtime = runtime.copy(scheduled = emptyList())
                persistRuntime()
                persist()
                publish()
                // Only the episode alerts are off: a "Remind me what's new" the viewer chose is scheduled again.
                refreshScheduledNotifications(AlertRefreshReason.SettingsChange)
                return@launch
            }

            enableWithPermission(AlertTelemetry.PermissionEntry.Settings)
        }
    }

    /** Asks for the OS permission and turns the master switch on. Returns false when the viewer refused. */
    private suspend fun enableWithPermission(entry: AlertTelemetry.PermissionEntry): Boolean {
        // Two shows asked for at once (the Home chip) must not open two OS permission dialogs.
        if (!permissionMutex.tryLock()) return false
        try {
            return enableWithPermissionLocked(entry)
        } finally {
            permissionMutex.unlock()
        }
    }

    private suspend fun enableWithPermissionLocked(entry: AlertTelemetry.PermissionEntry): Boolean {
        val alreadyGranted = _uiState.value.permissionGranted
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        val granted = runCatching { EpisodeReleaseNotificationPlatform.requestAuthorization() }
            .onFailure { error ->
                log.e(error) { "Failed to request episode release notification permission" }
            }
            .getOrDefault(false)
        markPermissionAsked()
        if (!alreadyGranted) AlertTelemetry.send(AlertTelemetry.permissionResult(granted, entry))

        if (!granted) {
            _uiState.value = _uiState.value.copy(
                isEnabled = false,
                isLoading = false,
                permissionGranted = false,
                permissionDenied = true,
                scheduledCount = 0,
                statusMessage = null,
                errorMessage = getString(Res.string.settings_notifications_permission_disabled),
            )
            persist()
            publish()
            return false
        }

        _uiState.value = _uiState.value.copy(
            isEnabled = true,
            isLoading = false,
            permissionGranted = true,
            permissionDenied = false,
            statusMessage = null,
            errorMessage = null,
        )
        persist()
        publish()
        refreshScheduledNotifications(AlertRefreshReason.SettingsChange)
        return true
    }

    fun openSystemNotificationSettings() {
        runCatching { EpisodeReleaseNotificationPlatform.openNotificationSettings() }
            .onFailure { error -> log.w { "Failed to open notification settings: ${error.message}" } }
    }

    // ---- the viewer's choices ----

    fun setTimingMode(mode: AlertTimingMode) =
        change(AlertTelemetry.Setting.TimingMode, mode.wire) { it.copy(timingMode = mode) }

    fun setSetTimeMinute(minute: Int) {
        updatePreferences { it.copy(setTimeMinute = minute.mod(MinutesPerDay)) }
    }

    fun setQuietHoursEnabled(enabled: Boolean) =
        change(AlertTelemetry.Setting.QuietHours, AlertTelemetry.onOff(enabled)) {
            it.copy(quietHours = it.quietHours.copy(enabled = enabled))
        }

    fun setQuietHoursRange(startMinute: Int, endMinute: Int) {
        updatePreferences {
            it.copy(quietHours = it.quietHours.copy(startMinute = startMinute.mod(MinutesPerDay), endMinute = endMinute.mod(MinutesPerDay)))
        }
    }

    fun setCap(cap: AlertCap) = change(AlertTelemetry.Setting.Cap, AlertTelemetry.capValue(cap)) { it.copy(cap = cap) }

    fun setDontAskAgain(value: Boolean) =
        change(AlertTelemetry.Setting.EndOfEpisodePrompt, AlertTelemetry.onOff(!value)) { it.copy(dontAskAgain = value) }

    /**
     * "Pause everything": no alerts and no reminders, whatever else is on. Every other choice is kept. Turning it on
     * clears what is already scheduled (the refresh sees the pause and plans nothing).
     */
    fun setAllPaused(paused: Boolean) =
        change(AlertTelemetry.Setting.AllPaused, AlertTelemetry.onOff(paused)) { it.copy(allPaused = paused) }

    /** "Keep show names private": the text of the notification says "a show you follow" instead of the name. */
    fun setPrivateNames(hidden: Boolean) =
        change(AlertTelemetry.Setting.PrivateNames, AlertTelemetry.onOff(hidden)) { it.copy(privateNames = hidden) }

    /** "Remind me what's new". Any pick, Off included, is a choice that beats the default from then on. */
    fun setAwayReminder(choice: AwayReminder) {
        change(AlertTelemetry.Setting.AwayReminder, AlertTelemetry.awayValue(choice)) { it.copy(awayReminder = choice) }
        // The choice is stored either way; a permission not asked yet is asked for now, like the episode switch does.
        if (AwayGroupPolicy.shouldAskOnPick(choice, _uiState.value.osPermission)) {
            scope.launch { askPermissionForAwayReminder() }
        }
    }

    private suspend fun askPermissionForAwayReminder() {
        if (!permissionMutex.tryLock()) return
        try {
            val alreadyGranted = _uiState.value.permissionGranted
            val granted = runCatching { EpisodeReleaseNotificationPlatform.requestAuthorization() }
                .onFailure { error -> log.e(error) { "Failed to request notification permission" } }
                .getOrDefault(false)
            markPermissionAsked()
            if (!alreadyGranted) AlertTelemetry.send(AlertTelemetry.permissionResult(granted, AlertTelemetry.PermissionEntry.Settings))
            _uiState.value = _uiState.value.copy(
                permissionGranted = granted,
                permissionDenied = _uiState.value.isEnabled && !granted,
            )
            publish()
            if (granted) refreshScheduledNotifications(AlertRefreshReason.SettingsChange)
        } finally {
            permissionMutex.unlock()
        }
    }

    private fun markPermissionAsked() {
        if (runtime.permissionAsked) return
        runtime = runtime.copy(permissionAsked = true)
        persistRuntime()
    }

    /** Forgets what was learned about when this phone is used: "Learning again" until there is enough history. */
    fun resetLearnedTime() {
        ensureLoaded()
        viewingStarts = emptyList()
        runtime = runtime.copy(learningAgain = true)
        persistViewingHistory()
        persistRuntime()
        publish()
        refreshAsync()
    }

    /** Undo for "Sending less often": the ignored count starts again from zero. */
    fun undoSendingLessOften() {
        ensureLoaded()
        runtime = runtime.copy(ignoredStreak = 0, backOffNoticeShown = false)
        persistRuntime()
        publish()
        refreshAsync()
    }

    /** Applies a choice and reports it ([setting] to [value], closed vocabulary) when it really changed. */
    private fun change(setting: AlertTelemetry.Setting, value: String, transform: (AlertPreferences) -> AlertPreferences) {
        if (updatePreferences(transform)) AlertTelemetry.send(AlertTelemetry.settingChanged(setting, value))
    }

    private fun updatePreferences(transform: (AlertPreferences) -> AlertPreferences): Boolean {
        ensureLoaded()
        val updated = transform(preferences)
        if (updated == preferences) return false
        preferences = updated
        persist()
        publish()
        refreshAsync()
        return true
    }

    // ---- per-show switches ----

    fun isShowOn(contentType: String, contentId: String): Boolean {
        ensureLoaded()
        return trackedShowsByKey[buildTrackedShowKey(contentType, contentId)]?.let { showIsOn(it) } ?: false
    }

    fun isDelivering(contentType: String, contentId: String): Boolean {
        val state = _uiState.value
        return state.isEnabled && state.permissionGranted && isShowOn(contentType, contentId)
    }

    /** Flips one show's switch. Quiet: never asks for the OS permission. Mirrors the Calendar's bell. */
    fun setShowAlert(contentType: String, contentId: String, title: String?, on: Boolean) {
        // A live channel or movie has no episodes: it is never enrolled, and its bell is never set.
        if (!TrackedShows.isTrackable(contentType, contentId)) return
        ensureLoaded()
        val key = buildTrackedShowKey(contentType, contentId)
        val existing = trackedShowsByKey[key]
        val updated = (existing ?: TrackedFollowedShow(
            contentId = contentId.trim(),
            contentType = contentType,
            followedOnIsoDate = CurrentDateProvider.todayIsoDate(),
            source = FollowSource.ContinueWatching.wire,
        )).let { show -> show.copy(alertsOn = on, title = show.title ?: title) }
        trackedShowsByKey = trackedShowsByKey + (key to updated)
        persist()
        publish()
        runCatching { ShowAlertBellMirrorAccess.current()?.mirrorBell(contentType, contentId, on) }
            .onFailure { error -> log.w { "Failed to mirror the bell: ${error.message}" } }
        if (_uiState.value.isEnabled) refreshAsync(AlertRefreshReason.ShowsChanged)
    }

    /** The viewer asked for alerts on this show: turn it on, and the master switch too (asking permission now). */
    internal fun requestShowAlert(
        contentType: String,
        contentId: String,
        title: String?,
        entry: AlertTelemetry.PermissionEntry = AlertTelemetry.PermissionEntry.Chip,
    ) {
        if (!TrackedShows.isTrackable(contentType, contentId)) return
        ensureLoaded()
        setShowAlert(contentType, contentId, title, on = true)
        if (_uiState.value.isEnabled) return
        scope.launch { enableWithPermission(entry) }
    }

    fun hasPromptedFor(contentType: String, contentId: String): Boolean =
        buildTrackedShowKey(contentType, contentId) in promptedShowKeys

    fun markPrompted(contentType: String, contentId: String) {
        ensureLoaded()
        promptedShowKeys = promptedShowKeys + buildTrackedShowKey(contentType, contentId)
        persist()
    }

    /** Whether the end-of-episode prompt may appear for this show now. [nextEpisodeAired] is null with no later episode. */
    fun shouldOfferEndOfEpisodePrompt(contentType: String, contentId: String, nextEpisodeAired: Boolean?): Boolean {
        ensureLoaded()
        if (!TrackedShows.isTrackable(contentType, contentId)) return false
        val state = _uiState.value
        return EndOfEpisodePromptPolicy.shouldOffer(
            dontAskAgain = preferences.dontAskAgain,
            alreadyPromptedForShow = hasPromptedFor(contentType, contentId),
            alertsAlreadyOnForShow = state.isEnabled && isShowOn(contentType, contentId),
            nearlyCaughtUp = EndOfEpisodePromptPolicy.nearlyCaughtUp(nextEpisodeAired),
        )
    }

    fun sendTestNotification() {
        ensureLoaded()
        scope.launch {
            val target = currentTestTarget()
            if (target == null) {
                _uiState.value = _uiState.value.copy(
                    isSendingTest = false,
                    statusMessage = null,
                    errorMessage = getString(Res.string.settings_notifications_test_requires_saved_show),
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isSendingTest = true,
                statusMessage = null,
                errorMessage = null,
            )

            val alreadyGranted = _uiState.value.permissionGranted
            val granted = runCatching { EpisodeReleaseNotificationPlatform.requestAuthorization() }
                .onFailure { error ->
                    log.e(error) { "Failed to request permission for test notification" }
                }
                .getOrDefault(false)
            markPermissionAsked()
            if (!alreadyGranted) AlertTelemetry.send(AlertTelemetry.permissionResult(granted, AlertTelemetry.PermissionEntry.Settings))

            if (!granted) {
                _uiState.value = _uiState.value.copy(
                    isSendingTest = false,
                    permissionGranted = false,
                    permissionDenied = true,
                    statusMessage = null,
                    errorMessage = getString(Res.string.settings_notifications_permission_disabled),
                )
                publish()
                return@launch
            }

            val request = EpisodeReleaseNotificationRequest(
                requestId = "episode-release-test-${ProfileRepository.activeProfileId}-${EpisodeReleaseDatePlatform.nowEpochMs()}",
                notificationTitle = if (preferences.privateNames) getString(Res.string.notifications_private_show_name) else target.name,
                notificationBody = getString(Res.string.notifications_test_preview_body),
                releaseDateIso = CurrentDateProvider.todayIsoDate(),
                deepLinkUrl = buildMetaDeepLinkUrl(type = target.type, id = target.id),
                backdropUrl = target.banner ?: target.poster,
            )

            runCatching {
                EpisodeReleaseNotificationPlatform.showTestNotification(request)
            }.onFailure { error ->
                log.e(error) { "Failed to send test notification" }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isSendingTest = false,
                    permissionGranted = true,
                    permissionDenied = false,
                    statusMessage = getString(Res.string.notifications_test_sent_for, target.name),
                    errorMessage = null,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isSendingTest = false,
                    permissionGranted = true,
                    statusMessage = null,
                    errorMessage = getString(Res.string.notifications_test_send_failed),
                )
            }
        }
    }

    fun refreshAsync() = refreshAsync(AlertRefreshReason.SettingsChange)

    internal fun refreshAsync(reason: AlertRefreshReason) {
        ensureLoaded()
        scope.launch {
            refreshScheduledNotifications(reason)
        }
    }

    private fun loadFromDisk() {
        hasLoaded = true

        val payload = EpisodeReleaseNotificationsStorage.loadPayload().orEmpty().trim()
        val stored = payload.takeIf { it.isNotEmpty() }
            ?.let { rawPayload ->
                runCatching {
                    json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(rawPayload)
                }.onFailure { error ->
                    log.w { "Failed to decode episode release notifications payload: ${error.message}" }
                }.getOrNull()
            }

        // Older installs stored live channels here (a favourite put them in the Library as type "tv"): prune them.
        val storedShows = stored?.followedShows.orEmpty()
        val keptShows = TrackedShows.prune(storedShows)
        trackedShowsByKey = buildMap {
            keptShows.forEach { trackedShow ->
                put(buildTrackedShowKey(trackedShow.contentType, trackedShow.contentId), trackedShow)
            }
        }
        val prunedStored = keptShows.size != storedShows.size
        preferences = stored?.preferences?.toModel() ?: AlertPreferences()
        promptedShowKeys = stored?.promptedShowKeys.orEmpty().toSet()
        runtime = loadAux<StoredAlertRuntime>(runtimeAuxName) ?: StoredAlertRuntime()
        viewingStarts = loadAux<StoredViewingHistory>(sessionsAuxName)?.starts.orEmpty()

        _uiState.value = EpisodeReleaseNotificationsUiState(
            isEnabled = stored?.enabled ?: false,
            permissionGranted = false,
            scheduledCount = 0,
            testTargetTitle = null,
            errorMessage = null,
        )
        if (prunedStored) persist()
        publish()
    }

    private inline fun <reified T> loadAux(name: String): T? =
        EpisodeReleaseNotificationsStorage.loadAux(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { json.decodeFromString<T>(raw) }
                .onFailure { error -> log.w { "Failed to decode notifications $name: ${error.message}" } }
                .getOrNull()
        }

    private fun persist() {
        EpisodeReleaseNotificationsStorage.savePayload(
            json.encodeToString(
                StoredEpisodeReleaseNotificationsPayload(
                    enabled = _uiState.value.isEnabled,
                    followedShows = trackedShowsByKey.values
                        .sortedWith(compareBy(TrackedFollowedShow::contentType, TrackedFollowedShow::contentId)),
                    preferences = StoredAlertPreferences.of(preferences),
                    promptedShowKeys = promptedShowKeys.sorted(),
                ),
            ),
        )
    }

    private fun persistRuntime() {
        EpisodeReleaseNotificationsStorage.saveAux(runtimeAuxName, json.encodeToString(runtime))
    }

    private fun persistViewingHistory() {
        EpisodeReleaseNotificationsStorage.saveAux(sessionsAuxName, json.encodeToString(StoredViewingHistory(viewingStarts)))
    }

    /** Recomputes everything the screens show from the current choices, history and shows. */
    private fun publish() {
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val habit = WatchHabitPolicy.learn(ViewingHistory.parse(viewingStarts), today)
        val times = preferences.timesFor(habit)
        val ready = habit as? WatchHabitPolicy.Habit.Ready
        val weekend = WatchHabitPolicy.isWeekend(today)
        val streak = runtime.ignoredStreak
        if (ready != null && runtime.learningAgain) {
            runtime = runtime.copy(learningAgain = false)
            persistRuntime()
        }
        _uiState.value = _uiState.value.copy(
            learningAgain = runtime.learningAgain && ready == null,
            permissionAsked = runtime.permissionAsked,
            preferences = preferences,
            habitSessions = (habit as? WatchHabitPolicy.Habit.StillLearning)?.sessions
                ?: WatchHabitPolicy.sessionsOf(ViewingHistory.parse(viewingStarts)).size,
            habitDays = (habit as? WatchHabitPolicy.Habit.StillLearning)?.days
                ?: ViewingHistory.parse(viewingStarts).map { it.date }.toSet().size,
            usualMinute = ready?.let { if (weekend) it.usualWeekendMinute else it.usualWeekdayMinute },
            alertMinute = preferences.quietHours.endOfQuietFor(
                today.atTimeOfDay(times.minuteFor(today)),
            ).let { it.hour * 60 + it.minute },
            quietConflict = AlertTimingExplainer.conflict(habit, preferences),
            sendingLessOften = BackOff.effective(preferences.cap, streak).tightened,
            shows = TrackedShows.rows(trackedShowsByKey.values, ::showIsOn),
        )
        _revision.value = _revision.value + 1
    }

    /** The page resumed (or the app came back from system settings): re-read the OS permission. No network. */
    fun refreshPermissionState() {
        ensureLoaded()
        scope.launch { syncAuthorizationState(refreshIfEnabled = false, rescheduleWhenRegranted = true) }
    }

    private suspend fun syncAuthorizationState(refreshIfEnabled: Boolean, rescheduleWhenRegranted: Boolean = false) {
        val granted = runCatching { EpisodeReleaseNotificationPlatform.notificationsAuthorized() }
            .onFailure { error ->
                log.w { "Failed to read episode release notification permission: ${error.message}" }
            }
            .getOrDefault(false)

        val before = _uiState.value
        val view = NotificationPermissionPolicy.reconcile(
            isEnabled = before.isEnabled,
            granted = granted,
            currentError = before.errorMessage,
            disabledMessage = getString(Res.string.settings_notifications_permission_disabled),
        )
        _uiState.value = before.copy(
            permissionGranted = view.granted,
            permissionDenied = view.denied,
            testTargetTitle = currentTestTarget()?.name,
            errorMessage = view.errorMessage,
        )
        _revision.value = _revision.value + 1

        val reschedule = refreshIfEnabled && _uiState.value.isEnabled ||
            rescheduleWhenRegranted && NotificationPermissionPolicy.shouldReschedule(before.permissionGranted, granted, before.isEnabled)
        if (reschedule) {
            refreshScheduledNotifications(if (refreshIfEnabled) AlertRefreshReason.AppOpened else AlertRefreshReason.SettingsChange)
        }
    }

    private fun showIsOn(show: TrackedFollowedShow): Boolean =
        ShowAlertDefaults.isOn(show.alertsOn, FollowSource.fromWire(show.source))

    private fun reconcileTrackedShows(state: LibraryUiState, progress: WatchProgressUiState): Boolean {
        val nextTrackedShows = TrackedShows.reconcile(
            existing = trackedShowsByKey,
            library = state,
            progress = progress,
            today = CurrentDateProvider.todayIsoDate(),
            followedOn = ::inferFollowedOnIsoDate,
        ) ?: return false

        val changed = nextTrackedShows != trackedShowsByKey
        if (changed) {
            trackedShowsByKey = nextTrackedShows.toMap()
        }
        updateTestTargetState()
        if (changed) publish()
        return changed
    }

    private fun inferFollowedOnIsoDate(item: LibraryItem): String {
        if (item.savedAtEpochMs >= MinReasonableSavedAtEpochMs) {
            return EpisodeReleaseNotificationsClock.isoDateFromEpochMs(item.savedAtEpochMs)
        }
        return CurrentDateProvider.todayIsoDate()
    }

    /**
     * Plans and schedules the alerts. [reason] says why, and with the cache it decides whether any request is made:
     * a progress tick or a settings change plans from what is already known (no request); only a show that has just
     * been switched on, or a full refresh older than six hours, asks the add-ons (see [AlertRefreshPolicy]).
     */
    private suspend fun refreshScheduledNotifications(reason: AlertRefreshReason) {
        refreshMutex.withLock {
            LibraryRepository.ensureLoaded()
            WatchProgressRepository.ensureLoaded()

            val trackedShowsChanged = reconcileTrackedShows(LibraryRepository.uiState.value, WatchProgressRepository.uiState.value)
            if (trackedShowsChanged) {
                persist()
            }

            val permissionGranted = runCatching { EpisodeReleaseNotificationPlatform.notificationsAuthorized() }
                .onFailure { error ->
                    log.w { "Failed to refresh episode release notification permission: ${error.message}" }
                }
                .getOrDefault(false)

            // The away reminder is its own choice: it needs the OS permission but not the episode-alert switch.
            val episodeAlertsOn = _uiState.value.isEnabled
            val awayDays = AwayReminderPolicy.effective(preferences.awayReminder, permissionGranted, episodeAlertsOn).days
            // "Pause everything" beats every other choice: nothing is planned and what was scheduled is cleared.
            if (preferences.allPaused || (!episodeAlertsOn && awayDays == 0) || !permissionGranted) {
                runCatching { EpisodeReleaseNotificationPlatform.clearScheduledEpisodeReleaseNotifications() }
                    .onFailure { error ->
                        log.w { "Failed to clear scheduled episode release notifications: ${error.message}" }
                    }
                lastScheduled = null
                runtime = runtime.copy(scheduled = emptyList())
                persistRuntime()
                val denied = _uiState.value.isEnabled && !permissionGranted
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    permissionGranted = permissionGranted,
                    permissionDenied = denied || (_uiState.value.permissionDenied && !permissionGranted),
                    scheduledCount = 0,
                    testTargetTitle = currentTestTarget()?.name,
                    errorMessage = if (denied) {
                        runBlocking { getString(Res.string.settings_notifications_permission_disabled) }
                    } else {
                        null
                    },
                )
                _revision.value = _revision.value + 1
                return
            }

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                permissionGranted = true,
                permissionDenied = false,
                errorMessage = null,
            )

            val wantedShows = trackedShowsByKey.values.filter { showIsOn(it) }
            if (wantedShows.isEmpty()) {
                runCatching { EpisodeReleaseNotificationPlatform.clearScheduledEpisodeReleaseNotifications() }
                lastScheduled = null
                runtime = runtime.copy(scheduled = emptyList())
                persistRuntime()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    scheduledCount = 0,
                    testTargetTitle = currentTestTarget()?.name,
                    errorMessage = null,
                )
                _revision.value = _revision.value + 1
                return
            }

            val zone = TimeZone.currentSystemDefault()
            val now = Clock.System.now()
            if (candidateCache.wouldFetch(wantedShows, now.toEpochMilliseconds(), reason)) {
                AddonRepository.initialize()
                withTimeoutOrNull(10_000L) {
                    AddonRepository.awaitManifestsLoaded()
                }
            }
            val resolved = candidateCache.resolve(wantedShows, now.toEpochMilliseconds(), reason)
            val candidates = resolved.candidates

            val habit = WatchHabitPolicy.learn(ViewingHistory.parse(viewingStarts), now.toLocalDateTime(zone).date)
            val times = preferences.timesFor(habit)
            val openedDates = runtime.openedDates.mapNotNull(::parseIsoDate).toSet()
            val sentDates = runtime.sentDates.mapNotNull(::parseIsoDate).toSet()
            val enabledTypes = AlertType.enabled(
                episodeAlertsOn = episodeAlertsOn,
                awayReminderOn = awayDays > 0,
                allPaused = preferences.allPaused,
            )
            val gate = if (AlertType.Episode in enabledTypes) {
                NotificationGatePolicy.planWithStats(
                    GateInput(
                        now = now,
                        zone = zone,
                        candidates = candidates,
                        times = times,
                        quietHours = preferences.quietHours,
                        cap = preferences.cap,
                        openedDates = openedDates,
                        sentDates = sentDates,
                        ignoredStreak = runtime.ignoredStreak,
                        backOffNoticeShown = runtime.backOffNoticeShown,
                        enabledTypes = enabledTypes,
                        allPaused = preferences.allPaused,
                    ),
                )
            } else {
                GatePlan(emptyList(), GateStats())
            }
            val planned = gate.alerts
            val away = if (AlertType.Away !in enabledTypes) null else AwayReminderPolicy.plan(
                AwayReminderInput(
                    now = now,
                    zone = zone,
                    awayDays = awayDays,
                    lastOpenedAt = runtime.lastOpenedAtMs.takeIf { it > 0L }?.let { Instant.fromEpochMilliseconds(it) },
                    candidates = candidates,
                    times = times,
                    quietHours = preferences.quietHours,
                    cap = preferences.cap,
                    openedDates = openedDates,
                    sentDates = sentDates,
                    ignoredStreak = runtime.ignoredStreak,
                    backOffNoticeShown = runtime.backOffNoticeShown,
                    episodeAlerts = planned,
                ),
            )

            val templates = ResourceAlertTemplates
            val profileId = ProfileRepository.activeProfileId
            val requests = planned.map { alert ->
                val content = AlertContentBuilder.build(alert, templates, preferences.privateNames)
                val first = alert.candidates.first()
                EpisodeReleaseNotificationRequest(
                    requestId = "episode-alert-$profileId-${alert.fireDate}",
                    notificationTitle = content.title,
                    notificationBody = content.body,
                    releaseDateIso = alert.fireDate.toString(),
                    deepLinkUrl = first.deepLinkUrl,
                    backdropUrl = first.backdropUrl,
                    fireAtEpochMs = alert.fireAt.toEpochMilliseconds(),
                    quietHours = preferences.quietHours,
                )
            }

            val awayRequest = away?.let { reminder ->
                val content = AwayReminderContentBuilder.build(reminder, ResourceAwayTemplates, preferences.privateNames)
                EpisodeReleaseNotificationRequest(
                    requestId = "away-reminder-$profileId",
                    notificationTitle = content.title,
                    notificationBody = content.body,
                    releaseDateIso = reminder.fireDate.toString(),
                    deepLinkUrl = buildHomeDeepLinkUrl(),
                    fireAtEpochMs = reminder.fireAt.toEpochMilliseconds(),
                    quietHours = preferences.quietHours,
                    isAwayReminder = true,
                )
            }

            val allRequests = requests + listOfNotNull(awayRequest)
            // A progress tick that plans exactly what is already scheduled has nothing to say to the platform.
            val unchanged = reason == AlertRefreshReason.ProgressTick && allRequests == lastScheduled
            if (!unchanged) {
                runCatching {
                    EpisodeReleaseNotificationPlatform.scheduleEpisodeReleaseNotifications(allRequests)
                }.onFailure { error ->
                    log.e(error) { "Failed to schedule episode release notifications" }
                }
                lastScheduled = allRequests
            }
            AlertTelemetry.send(
                AlertTelemetry.counters(
                    planned = if (unchanged) 0 else planned.size + (if (away != null) 1 else 0),
                    stats = if (unchanged) GateStats() else gate.stats,
                    metaFetches = resolved.fetches,
                ),
            )

            runtime = runtime.copy(
                // The reminder is recorded like an alert: a sent one counts toward the cap, an unopened one toward back-off.
                scheduled = planned.map { ScheduledAlertRecord(it.fireAt.toEpochMilliseconds(), it.sendingLessNotice) } +
                    listOfNotNull(away?.let { ScheduledAlertRecord(it.fireAt.toEpochMilliseconds(), it.sendingLessNotice) }),
            )
            persistRuntime()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                permissionGranted = true,
                scheduledCount = planned.size,
                testTargetTitle = currentTestTarget()?.name,
                errorMessage = null,
            )
            publish()
        }
    }

    private fun parseIsoDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    private fun updateTestTargetState() {
        _uiState.value = _uiState.value.copy(
            testTargetTitle = currentTestTarget()?.name,
        )
    }

    private fun currentTestTarget(): LibraryItem? {
        LibraryRepository.ensureLoaded()
        val libraryItems = LibraryRepository.uiState.value.items
        return TrackedShows.testTarget(libraryItems)
    }

    /** The show's episodes as candidates, or null when the request failed (the caller keeps what it had). */
    private suspend fun fetchCandidatesForShow(trackedShow: TrackedFollowedShow): List<AlertCandidate>? {
        val meta = runCatching {
            MetaDetailsRepository.fetch(
                type = trackedShow.contentType,
                id = trackedShow.contentId,
                cacheResult = false,
            )
        }.onFailure { error ->
            log.w { "Failed to resolve metadata for ${trackedShow.contentType}:${trackedShow.contentId}: ${error.message}" }
        }.getOrNull() ?: return null

        val showTitle = meta.name.ifBlank { trackedShow.contentId }
        val deepLink = buildMetaDeepLinkUrl(type = trackedShow.contentType, id = trackedShow.contentId)
        return meta.videos.mapNotNull { episode ->
            val releaseDate = releaseDateIso(episode.released) ?: return@mapNotNull null
            if (releaseDate < trackedShow.followedOnIsoDate) return@mapNotNull null
            if (episode.season == null && episode.episode == null) return@mapNotNull null
            val parsedDate = parseIsoDate(releaseDate) ?: return@mapNotNull null

            val code = buildEpisodeCodeText(episode.season, episode.episode)
            AlertCandidate(
                showKey = buildTrackedShowKey(trackedShow.contentType, trackedShow.contentId),
                showTitle = showTitle,
                episodeKey = buildEpisodeReleaseNotificationId(
                    profileId = ProfileRepository.activeProfileId,
                    contentType = trackedShow.contentType,
                    contentId = trackedShow.contentId,
                    episodeId = episode.id,
                    releaseDateIso = releaseDate,
                ),
                releaseDate = parsedDate,
                label = listOf(showTitle, code).filter { it.isNotBlank() }.joinToString(" "),
                bodyText = buildEpisodeReleaseNotificationBody(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                    episodeTitle = episode.title,
                ),
                privateBodyText = buildEpisodeReleaseNotificationBody(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                    episodeTitle = null,
                ),
                deepLinkUrl = deepLink,
                backdropUrl = meta.background ?: episode.thumbnail ?: episode.seasonPoster ?: meta.poster,
            )
        }
    }
}

private fun LocalDate.atTimeOfDay(minute: Int) =
    kotlinx.datetime.LocalDateTime(year, monthNumber, dayOfMonth, minute / 60, minute % 60)

/** Real words for [AlertTemplates], from the app's string resources. */
private object ResourceAlertTemplates : AlertTemplates {
    override suspend fun batchTitle(episodeCount: Int) = getString(Res.string.notifications_alert_title_batch, episodeCount)
    override suspend fun sameShowBody(episodeCount: Int) = getString(Res.string.notifications_alert_body_same_show, episodeCount)
    override suspend fun listBody(labels: List<String>) =
        getString(Res.string.notifications_alert_body_list, labels.joinToString(", "))
    override suspend fun listBodyWithMore(labels: List<String>, more: Int) =
        getString(Res.string.notifications_alert_body_list_more, labels.joinToString(", "), more)
    override suspend fun lessOftenLine() = getString(Res.string.notifications_alert_less_often)
    override suspend fun privateShowName() = getString(Res.string.notifications_private_show_name)
    override suspend fun genericBody() = getString(Res.string.notifications_episode_release_body_generic)
}

/** Real words for [AwayTemplates], from the app's string resources. */
private object ResourceAwayTemplates : AwayTemplates {
    override suspend fun titleOne() = getString(Res.string.notifications_away_title_one)
    override suspend fun titleMany(showCount: Int) = getString(Res.string.notifications_away_title_many, showCount)
    override suspend fun bodyNames(first: String, second: String?) = getString(
        Res.string.notifications_away_body_names,
        if (second == null) first else getString(Res.string.notifications_away_names_two, first, second),
    )
    override suspend fun bodyCount() = getString(Res.string.notifications_away_body_count)
    override suspend fun lessOftenLine() = getString(Res.string.notifications_alert_less_often)
}
