package com.nuvio.app.features.iptv.overlay

/**
 * Collapses an overlay push batch so no two entries share the same `(kind, okey)` identity.
 *
 * `sync_push_iptv_overlay` upserts the batch with `on conflict (user_id, profile_id, kind, okey)
 * do update`. If the same identity appears twice in one call, Postgres refuses the whole statement
 * with `SQLSTATE 21000: ON CONFLICT DO UPDATE command cannot affect row a second time` — measured
 * on prod as a small but steady stream of failed overlay pushes. Deduping here (and matching the
 * server's own `distinct on` backstop) keeps the freshest edit per identity and drops the rest, so
 * the batch is internally unique before it goes out — which also trims the payload.
 *
 * Pure and generic on purpose: it takes accessors rather than a concrete row type, so it unit-tests
 * without the store, the network, or serialization.
 */
object IptvOverlayPushDedupPolicy {

    /**
     * Returns [rows] with at most one entry per `(kind, okey)` — the one with the greatest
     * [updatedAt] (ties keep the later element, matching the server's last-write-wins). The first
     * appearance of each identity fixes its position in the result; order is otherwise preserved.
     */
    fun <T> dedupe(
        rows: List<T>,
        kind: (T) -> String,
        okey: (T) -> String,
        updatedAt: (T) -> Long,
    ): List<T> {
        if (rows.size <= 1) return rows
        val byIdentity = LinkedHashMap<Pair<String, String>, T>(rows.size)
        for (row in rows) {
            val key = kind(row) to okey(row)
            val existing = byIdentity[key]
            if (existing == null || updatedAt(row) >= updatedAt(existing)) {
                byIdentity[key] = row
            }
        }
        return byIdentity.values.toList()
    }
}
