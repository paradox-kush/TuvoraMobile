package com.nuvio.app.features.sportsdetail.api

import com.nuvio.app.features.sportsdetail.internal.DefaultSportsDetailService
import com.nuvio.app.features.sportsdetail.internal.SettingsMappingStorage

/** Entry point for the UI lanes: `SportsDetail.create().open(ref)`. Keep one instance per app (it holds the mapping). */
object SportsDetail {
    /** [storage] persists the last good remote mapping; omit for process-memory only. */
    fun create(storage: MappingStorage? = null): SportsDetailService =
        if (storage != null) DefaultSportsDetailService(storage) else DefaultSportsDetailService()

    /** The production service: the last good remote mapping is persisted in device settings storage. */
    fun createPersistent(): SportsDetailService = DefaultSportsDetailService(SettingsMappingStorage())
}
