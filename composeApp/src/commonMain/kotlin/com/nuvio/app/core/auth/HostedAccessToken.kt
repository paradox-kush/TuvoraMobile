package com.nuvio.app.core.auth

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.SyncBackendDefaults
import com.nuvio.app.core.network.SyncBackendRepository
import com.nuvio.app.core.network.hasSameConnectionIdentity
import io.github.jan.supabase.auth.auth

/**
 * The signed-in (non-anonymous) access token, only while the sync backend is the hosted Tuvora one.
 *
 * Lives in core/auth, not in a feature: both the IPTV provider-setup API (fork feature) and the managed
 * add-on bridge (features/servers, non-fork) need it, and a non-fork file may not import a fork feature
 * (ArchitectureTest R2b).
 */
internal suspend fun hostedAccessTokenOrNull(): String? {
    val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return null
    if (auth.isAnonymous) return null
    if (!SyncBackendDefaults.hosted().hasSameConnectionIdentity(SyncBackendRepository.selectedBackend)) return null
    return runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }.getOrNull()
}
