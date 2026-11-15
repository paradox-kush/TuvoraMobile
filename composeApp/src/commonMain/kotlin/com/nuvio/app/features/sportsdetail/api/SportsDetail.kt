package com.nuvio.app.features.sportsdetail.api

import com.nuvio.app.features.sportsdetail.internal.DefaultSportsDetailService

/** Entry point for the UI lanes: `SportsDetail.create().open(ref)`. Keep one instance per app (it holds the mapping). */
object SportsDetail {
    /** [storage] persists the last good remote mapping; omit for process-memory only. */
    fun create(storage: MappingStorage? = null): SportsDetailService =
        if (storage != null) DefaultSportsDetailService(storage) else DefaultSportsDetailService()
}
