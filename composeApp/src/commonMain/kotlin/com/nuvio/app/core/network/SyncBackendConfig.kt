package com.nuvio.app.core.network

import kotlinx.serialization.Serializable

/** Bucket holding user-uploaded profile pictures. Presets live in 'avatars' and stay read-only. */
const val USER_AVATAR_BUCKET = "user-avatars"

internal const val SYNC_BACKEND_HOSTED_ID = "hosted"
internal const val SYNC_BACKEND_NUVIO_ID = "nuvio"

@Serializable
data class SyncBackendConfig(
    val id: String,
    val displayName: String,
    val supabaseUrl: String,
    val anonKey: String,
    val avatarPublicBaseUrl: String,
    val schemaVersion: Int = 1,
) {
    val normalizedSupabaseUrl: String
        get() = supabaseUrl.trim().trimEnd('/')

    val normalizedAvatarPublicBaseUrl: String
        get() = avatarPublicBaseUrl.trim().trimEnd('/')

    fun avatarStorageUrl(storagePath: String): String =
        "${normalizedAvatarPublicBaseUrl}/${storagePath.trim().trimStart('/')}"

    /**
     * Public URL of an object in the user-upload bucket.
     *
     * Derived from this backend's own Supabase URL rather than from [avatarPublicBaseUrl]: that one
     * is a persisted per-backend value pointing at the preset 'avatars' bucket, so reusing it would
     * address user uploads inside the preset namespace. Deriving here also means a backend switch
     * cannot leave uploads resolving against the previous project.
     */
    fun userAvatarStorageUrl(objectPath: String): String =
        "$normalizedSupabaseUrl/storage/v1/object/public/$USER_AVATAR_BUCKET/${objectPath.trim().trimStart('/')}"

    fun normalized(): SyncBackendConfig =
        copy(
            id = id.trim().lowercase(),
            supabaseUrl = normalizedSupabaseUrl,
            anonKey = anonKey.trim(),
            avatarPublicBaseUrl = normalizedAvatarPublicBaseUrl,
        )
}

@Serializable
data class SyncBackendManifest(
    val version: Int = 1,
    val activeBackend: String,
    val revision: String = "",
    val forceLogoutOnChange: Boolean = true,
    /** B24 — server-driven rollout of the IPTV-playlist v2 revision-contract sync. One of
     *  "enabled" | "disabled" | "debug_only"; null/absent leaves the client's build default in force.
     *  This is how v2 is turned on in production (or killed) without shipping a client release. */
    val iptvPlaylistV2: String? = null,
    /** B24 canary — when [iptvPlaylistV2] is "enabled", enrol only this percent (0..100) of accounts,
     *  bucketed by a stable hash of the account id (raising it only adds accounts). null ⇒ 100. */
    val iptvPlaylistV2Percent: Int? = null,
    /** B24 canary — when "enabled", restrict to these platform tokens ("android"/"ios"/"androidtv").
     *  null/empty ⇒ all platforms. */
    val iptvPlaylistV2Platforms: List<String>? = null,
)

data class SyncBackendState(
    val selectedBackend: SyncBackendConfig = SyncBackendDefaults.hosted(),
    val appliedRevision: String = "",
    val isLoaded: Boolean = false,
    val lastManifestError: String? = null,
    val isManualDebugOverride: Boolean = false,
)

sealed interface SyncBackendRefreshResult {
    data object NotConfigured : SyncBackendRefreshResult
    data object Unchanged : SyncBackendRefreshResult
    data class Applied(
        val backend: SyncBackendConfig,
        val revision: String,
    ) : SyncBackendRefreshResult
    data class RequiresLogout(
        val currentBackend: SyncBackendConfig,
        val targetBackend: SyncBackendConfig,
        val revision: String,
        val forceLogout: Boolean,
    ) : SyncBackendRefreshResult
    data class Failed(val message: String) : SyncBackendRefreshResult
}

@Serializable
internal data class StoredSyncBackendSelection(
    val backend: SyncBackendConfig? = null,
    val backendId: String = "",
    val appliedRevision: String = "",
    val manualDebugOverride: Boolean = false,
)

object SyncBackendDefaults {
    fun hosted(): SyncBackendConfig =
        SyncBackendConfig(
            id = SYNC_BACKEND_HOSTED_ID,
            displayName = "Hosted",
            supabaseUrl = SupabaseConfig.URL,
            anonKey = SupabaseConfig.ANON_KEY,
            avatarPublicBaseUrl = "${SupabaseConfig.URL.trim().trimEnd('/')}/storage/v1/object/public/avatars",
            schemaVersion = 1,
        ).normalized()

    fun nuvio(): SyncBackendConfig =
        SyncBackendConfig(
            id = SYNC_BACKEND_NUVIO_ID,
            displayName = "Tuvora",
            supabaseUrl = SupabaseConfig.NUVIO_URL,
            anonKey = SupabaseConfig.NUVIO_ANON_KEY,
            avatarPublicBaseUrl = "${SupabaseConfig.NUVIO_URL.trim().trimEnd('/')}/storage/v1/object/public/avatars",
            schemaVersion = 1,
        ).normalized()

    fun byId(id: String): SyncBackendConfig? =
        when (id.trim().lowercase()) {
            SYNC_BACKEND_HOSTED_ID -> hosted()
            SYNC_BACKEND_NUVIO_ID -> nuvio()
            else -> null
        }
}

internal fun SyncBackendConfig.hasSameConnectionIdentity(other: SyncBackendConfig): Boolean =
    id == other.id &&
        normalizedSupabaseUrl == other.normalizedSupabaseUrl &&
        anonKey.trim() == other.anonKey.trim() &&
        schemaVersion == other.schemaVersion

internal fun SyncBackendManifest.backendConfigForActiveBackend(): SyncBackendConfig? {
    if (version != 1) return null

    val activeId = activeBackend.trim().lowercase()
    return SyncBackendDefaults.byId(activeId)
        ?.takeIf { it.isUsableClientConfig() }
}

internal fun SyncBackendConfig.isUsableClientConfig(): Boolean =
    id in setOf(SYNC_BACKEND_HOSTED_ID, SYNC_BACKEND_NUVIO_ID) &&
        normalizedSupabaseUrl.startsWith("https://") &&
        anonKey.isNotBlank() &&
        !anonKey.startsWith("<") &&
        normalizedAvatarPublicBaseUrl.startsWith("https://")
