package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Cross-device delta sync for the IPTV overlay, mirroring [com.nuvio.app.features.library.sync.SupabaseLibrarySyncAdapter]:
 * a server cursor + append-only event stream. Pulls the website's edits into [IptvOverlayStore] and
 * pushes this device's edits. Everything is keyed on canon-v1 identity, so the ids match the web.
 */
internal object IptvOverlaySyncAdapter {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class DeltaRow(
        @SerialName("event_id") val eventId: Long,
        val operation: String,
        val kind: String,
        val okey: String,
        @SerialName("playlist_id") val playlistId: String? = null,
        val value: JsonObject = JsonObject(emptyMap()),
        @SerialName("updated_at") val updatedAt: Long = 0,
    )

    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.jsonPrimitive?.content?.let { it == "true" } ?: false
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    /** Pull all overlay changes since the local cursor and apply them; returns true if anything changed. */
    suspend fun pullInto(profileId: Int): Boolean {
        var since = IptvOverlayStore.getCursor(profileId)
        var changed = false
        while (true) {
            val rows = SupabaseProvider.client.postgrest.rpc(
                "sync_pull_iptv_overlay_delta",
                buildJsonObject { put("p_profile_id", profileId); put("p_since_event_id", since); put("p_limit", 500) },
            ).decodeList<DeltaRow>()
            if (rows.isEmpty()) break
            for (r in rows) {
                since = maxOf(since, r.eventId)
                val deleted = r.operation == "delete"
                val v = r.value
                when (r.kind) {
                    "channel" -> IptvOverlayStore.applyRemoteChannel(
                        profileId, r.okey, r.playlistId,
                        ChannelOverlay(hidden = v.bool("hidden"), pinned = v.bool("pinned"), position = v.int("position"), rename = v.str("rename")),
                        r.updatedAt, deleted,
                    )
                    "category" -> IptvOverlayStore.applyRemoteCategory(
                        profileId, r.playlistId ?: continue, v.str("content_type") ?: "live", r.okey,
                        CategoryOverlay(hidden = v.bool("hidden"), pinned = v.bool("pinned"), position = v.int("position"), rename = v.str("rename")),
                        r.updatedAt, deleted,
                    )
                    "group" -> IptvOverlayStore.applyRemoteGroup(
                        profileId, r.okey, r.playlistId, v.str("content_type") ?: "live", v.str("name") ?: "", v.int("position") ?: 0, r.updatedAt, deleted,
                    )
                    "member" -> {
                        val parts = r.okey.split("|", limit = 2)
                        if (parts.size == 2) IptvOverlayStore.applyRemoteMember(profileId, parts[0], parts[1], v.int("position") ?: 0, r.updatedAt, deleted)
                    }
                }
                changed = true
            }
            IptvOverlayStore.setCursor(profileId, since)
            if (rows.size < 500) break
        }
        return changed
    }

    /** Push this device's overlay rows (currently channel edits) to the server. */
    suspend fun push(profileId: Int) {
        val rows = IptvOverlayStore.rowsForPush(profileId)
        if (rows.isEmpty()) return
        val upserts = rows.filter { !it.deleted }
        val deletes = rows.filter { it.deleted }
        if (upserts.isNotEmpty()) {
            SupabaseProvider.client.postgrest.rpc(
                "sync_push_iptv_overlay",
                buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_items", buildJsonArray {
                        upserts.forEach { row ->
                            add(buildJsonObject {
                                put("kind", row.kind); put("okey", row.okey)
                                if (row.playlistId != null) put("playlist_id", row.playlistId)
                                put("value", json.parseToJsonElement(row.valueJson))
                                put("updated_at", row.updatedAt)
                            })
                        }
                    })
                    putSyncOriginClientId()
                },
            )
        }
        if (deletes.isNotEmpty()) {
            SupabaseProvider.client.postgrest.rpc(
                "sync_delete_iptv_overlay",
                buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_keys", buildJsonArray { deletes.forEach { add(buildJsonObject { put("kind", it.kind); put("okey", it.okey) }) } })
                    putSyncOriginClientId()
                },
            )
        }
    }
}
