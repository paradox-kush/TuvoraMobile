package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The outcome of decoding the persisted Xtream playlist store, with EXPLICIT validity so the sync
 * push and the local re-persist never mistake unreadable, partially-recovered, or not-yet-written
 * data for a genuine empty collection (B24). The states are the ones the resilience assessment §7
 * requires to be kept distinct:
 *
 *  - [Valid]      = a clean decode of a present array. `[]` (a real empty array) is a DELIBERATE
 *                   deletion — the user cleared every playlist — and MUST be allowed to push up.
 *  - [Recovered]  = the array parsed but some element(s) failed (e.g. a forward-compat field this
 *                   build can't read). Usable rows are recovered, but the subset must NOT silently
 *                   replace the complete local or server collection, and the original bytes are
 *                   preserved.
 *  - [Corrupt]    = present but not a decodable array (unreadable / wrong shape).
 *  - [Absent]     = blank/null storage: a fresh install, a cleared store, or a corruption-reset
 *                   (Android DataStore's ReplaceFileCorruptionHandler writes an EMPTY store, not
 *                   `[]`). Empty, but NOT user-authored — pushing it up could wipe a server that
 *                   another device populated. A deliberate delete-all always writes `[]`, never
 *                   an absent blob, so [Absent] is never a real deletion.
 *
 * [Recovered] and [Corrupt] are [isDamaged]: the local bytes hold information this build could not
 * fully read, so we must not overwrite them. [Valid] is [isAuthoritative]: a complete, trusted
 * snapshot safe to full-replace the server or the store.
 */
sealed interface PlaylistLoadOutcome {
    val accounts: List<XtreamAccount>

    data class Valid(override val accounts: List<XtreamAccount>) : PlaylistLoadOutcome
    data class Recovered(override val accounts: List<XtreamAccount>, val droppedCount: Int) : PlaylistLoadOutcome
    data object Corrupt : PlaylistLoadOutcome {
        override val accounts: List<XtreamAccount> get() = emptyList()
    }
    data object Absent : PlaylistLoadOutcome {
        override val accounts: List<XtreamAccount> get() = emptyList()
    }
}

/** A clean, complete decode — safe to full-replace the server or overwrite the store. */
val PlaylistLoadOutcome.isAuthoritative: Boolean
    get() = this is PlaylistLoadOutcome.Valid

/** The stored bytes hold rows/fields this build couldn't read — never overwrite or push them. */
val PlaylistLoadOutcome.isDamaged: Boolean
    get() = this is PlaylistLoadOutcome.Recovered || this is PlaylistLoadOutcome.Corrupt

/**
 * Element-wise decode of the persisted playlist array. A single incompatible row recovers the rest
 * instead of zeroing the whole list; a genuinely unreadable blob is [Corrupt]; blank/null is
 * [Absent] (not a real empty list). Pure and side-effect-free so it can be unit-tested without
 * storage. [json] should be the store's own `Json { ignoreUnknownKeys = true }`, so unknown/newer
 * top-level keys on an otherwise-valid row do not drop the row (§5).
 */
fun decodePlaylistStore(json: Json, stored: String?): PlaylistLoadOutcome {
    if (stored.isNullOrBlank()) return PlaylistLoadOutcome.Absent
    val array: JsonArray = runCatching { json.parseToJsonElement(stored).jsonArray }.getOrNull()
        ?: return PlaylistLoadOutcome.Corrupt
    var dropped = 0
    val accounts = array.mapNotNull { element ->
        runCatching { json.decodeFromJsonElement(XtreamAccount.serializer(), element) }
            .getOrElse { dropped++; null }
    }
    return if (dropped == 0) {
        PlaylistLoadOutcome.Valid(accounts)
    } else {
        PlaylistLoadOutcome.Recovered(accounts, dropped)
    }
}

/**
 * Re-encode [accounts] for local storage while PRESERVING any per-row keys the current serializer
 * does not know (a forward-compat field written by a newer build). `encodeToString` alone drops
 * them, so an older build that edits one field would silently strip a newer build's data on the
 * next write (B24 §3). For each row we overlay the freshly-encoded KNOWN fields onto the row's
 * ORIGINAL stored object (matched by `id`), keeping unknown keys — including nested ones. A new row
 * (no original) is encoded fresh; a removed row simply isn't emitted.
 *
 * Scope is LOCAL storage only: the sync push and the backend both carry fixed, known columns, so an
 * unknown field cannot survive a server round-trip and a genuinely new field must be added as a
 * real synced column. This merely stops a same-device read-modify-write from destroying it.
 */
fun mergePlaylistJson(json: Json, originalStored: String?, accounts: List<XtreamAccount>): String {
    val originalById: Map<String, JsonObject> =
        runCatching { json.parseToJsonElement(originalStored ?: "").jsonArray }
            .getOrNull()
            ?.mapNotNull { el -> runCatching { el.jsonObject }.getOrNull() }
            ?.mapNotNull { obj -> (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { it to obj } }
            ?.toMap()
            .orEmpty()
    val merged: List<JsonElement> = accounts.map { acc ->
        val fresh = json.encodeToJsonElement(XtreamAccount.serializer(), acc).jsonObject
        val original = originalById[acc.id]
        if (original == null) fresh else JsonObject(original + fresh)
    }
    return json.encodeToString(JsonArray.serializer(), JsonArray(merged))
}
