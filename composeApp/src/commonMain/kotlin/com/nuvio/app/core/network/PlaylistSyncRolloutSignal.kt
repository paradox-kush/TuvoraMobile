package com.nuvio.app.core.network

/**
 * B24 — a neutral, core-owned holder for the server-driven IPTV-playlist v2 rollout string carried by
 * the sync-backend manifest. Kept in core so the dependency direction stays correct: the sync-backend
 * layer (core) WRITES it when it applies a manifest, and the IPTV feature READS it (feature → core) to
 * resolve its activation policy — the feature is never named from core.
 *
 * The value is the raw manifest string ("enabled" | "disabled" | "debug_only" | null); the IPTV
 * feature parses it. Null means the manifest carried no override, so the client build default holds.
 */
object PlaylistSyncRolloutSignal {
    @kotlin.concurrent.Volatile
    var raw: String? = null
}
