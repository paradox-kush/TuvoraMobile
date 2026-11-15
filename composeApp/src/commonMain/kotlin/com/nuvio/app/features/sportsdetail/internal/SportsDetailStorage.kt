package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.MappingStorage
import com.nuvio.app.features.sportsdetail.api.StoredMapping

/** Per-DEVICE key-value storage for the last good remote mapping. Android needs a Context first (AndroidFeatureWiring). */
internal expect object SportsDetailStorage {
    fun loadString(key: String): String?
    fun saveString(key: String, value: String)
}

/** The persistent [MappingStorage]: survives restarts; a load before Android init is just "nothing stored". */
internal class SettingsMappingStorage : MappingStorage {
    override fun load(): StoredMapping? {
        val json = SportsDetailStorage.loadString(JSON_KEY) ?: return null
        return StoredMapping(json, SportsDetailStorage.loadString(ETAG_KEY))
    }

    override fun save(value: StoredMapping) {
        SportsDetailStorage.saveString(JSON_KEY, value.json)
        SportsDetailStorage.saveString(ETAG_KEY, value.etag.orEmpty())
    }

    override fun loadRejectedEtag(): String? = SportsDetailStorage.loadString(REJECTED_KEY)?.takeIf { it.isNotBlank() }

    override fun saveRejectedEtag(etag: String?) = SportsDetailStorage.saveString(REJECTED_KEY, etag.orEmpty())

    private companion object {
        const val REJECTED_KEY = "mapping.rejectedEtag"
        const val JSON_KEY = "mapping.json"
        const val ETAG_KEY = "mapping.etag"
    }
}
