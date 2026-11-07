package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameDetailSource
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.MappingStorage
import com.nuvio.app.features.sportsdetail.api.SportsDetailService
import com.nuvio.app.features.sportsdetail.api.StoredMapping

/** [SportsHttp] over the app's HTTP stack (`httpRequestRaw`: OkHttp / Darwin), with ETag passthrough. */
internal class PlatformSportsHttp : SportsHttp {
    override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long): HttpReply {
        val r = httpRequestRaw(method = "GET", url = url, headers = headers + ("Accept" to "application/json"), body = "")
        val etag = r.headers.entries.firstOrNull { it.key.equals("etag", ignoreCase = true) }?.value
        return HttpReply(r.status, r.body, etag)
    }
}

/** Process-memory storage; the app host swaps in a persistent [MappingStorage] at wiring time. */
internal class InMemoryMappingStorage : MappingStorage {
    private var stored: StoredMapping? = null
    override fun load(): StoredMapping? = stored
    override fun save(value: StoredMapping) { stored = value }
}

private const val SYNC_MIN_INTERVAL_MS = 10L * 60 * 1000

internal class DefaultSportsDetailService(
    storage: MappingStorage = InMemoryMappingStorage(),
    private val http: SportsHttp = PlatformSportsHttp(),
    endpoints: SportsDetailEndpoints = SportsDetailEndpoints.fromSupabaseUrl(com.nuvio.app.core.network.SupabaseConfig.URL),
) : SportsDetailService {
    private val bundled: SportsMapping =
        (SportsMappingParser.parse(BundledSportsMapping.JSON) as SportsMappingParser.ParseResult.Ok).mapping
    private val store = SportsMappingStore(storage, bundled)
    private val sync = RemoteMappingSync(store, http, endpoints.mappingUrl)
    private val espn = EspnHttpSource(http) { store.current }
    private val tsdb = GameDetailBackendSource(http, endpoints.gameDetailUrl)

    override fun open(ref: GameRef): GameDetailSource = GameDetailSession(ref, { store.current }, tsdb, espn)

    private var lastSyncMs = 0L

    /** Throttled: every screen open asks, the network is touched at most every 10 minutes. */
    override suspend fun syncMapping() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (lastSyncMs != 0L && now - lastSyncMs < SYNC_MIN_INTERVAL_MS) return
        lastSyncMs = now
        sync.sync()
    }

    override fun refreshConfig(): GameDetailRefreshPolicy.Config = store.current.espn.let {
        GameDetailRefreshPolicy.configFrom(it.liveMs, it.summaryMs, it.minMs)
    }
}
