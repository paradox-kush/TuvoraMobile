package com.nuvio.app.core.sources

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.isLocalOnly
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerStorage
import com.nuvio.app.features.servers.secureRandomHex
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import co.touchlab.kermit.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.app.core.auth.AuthState
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.uuid.Uuid

object DeviceSources {
    private val log = Logger.withTag("DeviceSources")
    private var activated = false
    val isActive: Boolean get() = activated
    val showSettings = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val lock = SynchronizedObject()
    private var key: String? = null
    private var current: DeviceSourceEngine? = null
    fun engine(): DeviceSourceEngine = synchronized(lock) {
        val auth = AuthRepository.state.value
        val backend = SupabaseProvider.selectedBackend.normalizedSupabaseUrl
        val next = "device-sources:$backend:${auth.userId ?: "guest"}"
        if (key != next || current == null) {
            val client = if (auth.isLocalOnly) null else SupabaseProvider.client
            current = DeviceSourceEngine(
                object : DevicePreferenceStorage {
                    override fun read() = ServerStorage.read(next)
                    override fun write(value: String) {
                        ServerStorage.write(next,value)
                        check(ServerStorage.read(next) == value) { "Could not save device choices" }
                    }
                },
                rpc = { name,args -> checkNotNull(client).postgrest.rpc(name,args).decodeAs<JsonElement>() },
                uuid = { Uuid.random().toString() }, credential = { secureRandomHex(32) },
                now = { Clock.System.now().toEpochMilliseconds() }, platform = if (com.nuvio.app.core.sync.MOBILE_SYNC_PLATFORM == "tvos") "tv" else if (com.nuvio.app.isIos) "ios" else "android", localOnly = auth.isLocalOnly,
                automaticSetupName = com.nuvio.app.core.sync.syncDeviceName().take(120),
                existingInstallation = com.nuvio.app.core.sync.SyncClientIdentity.sourceSetupUpgradeEligible,
                onRefused = ::logRefusal,
            )
            key = next
        }
        current!!
    }
    fun activate() { activated = true }
    val epoch: Long get() = if (activated) engine().state.value.availabilityEpoch else 0
    fun allows(kind: String, ref: String): Boolean = !activated || (AuthRepository.state.value != com.nuvio.app.core.auth.AuthState.Loading && engine().allows(ProfileRepository.activeProfileId,kind,ref))
    fun allowsAddon(url: String): Boolean = !activated || (AuthRepository.state.value != com.nuvio.app.core.auth.AuthState.Loading && engine().allowsAddonRequest(ProfileRepository.activeProfileId,url))
    // Debug level: guards sit on hot paths (every addon request), so a refusal must not flood the log.
    private fun logRefusal(kind: String, mode: String) { log.d { "source refused on this device: kind=$kind mode=$mode" } }
    private fun refuse(kind: String, ref: String?): Nothing {
        val engine = engine()
        val scope = if (ref == null) RefusalScope.DEVICE else engine.refusalScope(ProfileRepository.activeProfileId, kind, ref)
        engine.refuse(kind, scope)
    }
    /** The wrong profile must never be read: wait (bounded) for sign-in state, else refuse. */
    private suspend fun authSettled(): Boolean = awaitAuthSettled(AuthRepository.state)
    private fun refuseSigningIn(kind: String): Nothing = engine().refuse(kind, RefusalScope.SIGNING_IN)
    fun guard(kind: String, ref: String) { if (!allows(kind,ref)) refuse(kind, ref) }
    /** [guard] for suspend callers: honours [verifying] / [inspecting] for the current request. */
    suspend fun checkAccess(kind: String, ref: String) {
        val access = currentCoroutineContext()[DeviceSourceAccessElement]?.access ?: DeviceSourceAccess.DISCOVERY
        if (!activated || access == DeviceSourceAccess.INSPECT) return
        if (access == DeviceSourceAccess.DISCOVERY) guard(kind, ref)
        else {
            if (!authSettled()) refuseSigningIn(kind)
            if (!engine().permits(access, ProfileRepository.activeProfileId, kind, ref)) refuse(kind, ref)
        }
    }
    suspend fun <T> guarded(kind: String, ref: String, block: suspend () -> T): T {
        if (!activated) return block()
        val access = currentCoroutineContext()[DeviceSourceAccessElement]?.access ?: DeviceSourceAccess.DISCOVERY
        if (access == DeviceSourceAccess.VERIFY && !authSettled()) refuseSigningIn(kind)
        return engine().guarded(ProfileRepository.activeProfileId,kind,ref,access,block)
    }
    /** The add/edit form's live check of a playlist: allowed before the device has a choice for it. */
    suspend fun <T> verifying(block: suspend () -> T): T = withContext(DeviceSourceAccessElement(DeviceSourceAccess.VERIFY)) { block() }
    /** A read-only account lookup the user opened (expiry, connections): allowed for a source turned off here. */
    suspend fun <T> inspecting(block: suspend () -> T): T = withContext(DeviceSourceAccessElement(DeviceSourceAccess.INSPECT)) { block() }
    fun guardAddon(url: String) { if (!allowsAddon(url)) refuse("addon", null) }
    suspend fun refresh() = engine().refresh(ProfileRepository.activeProfileId)
}

/** How long a verify waits for sign-in to settle before it says "still signing in". */
internal const val AUTH_SETTLE_TIMEOUT_MS = 5_000L

/** True once [state] is no longer [AuthState.Loading], waiting at most [timeoutMs]. */
internal suspend fun awaitAuthSettled(state: kotlinx.coroutines.flow.StateFlow<AuthState>, timeoutMs: Long = AUTH_SETTLE_TIMEOUT_MS): Boolean =
    state.value != AuthState.Loading || withTimeoutOrNull(timeoutMs) { state.first { it != AuthState.Loading } } != null
