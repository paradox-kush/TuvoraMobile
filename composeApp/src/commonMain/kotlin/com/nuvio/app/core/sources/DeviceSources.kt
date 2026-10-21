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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.uuid.Uuid

object DeviceSources {
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
                now = { Clock.System.now().toEpochMilliseconds() }, platform = if (com.nuvio.app.isIos) "ios" else "android", localOnly = auth.isLocalOnly,
            )
            key = next
        }
        current!!
    }
    fun activate() { activated = true }
    val epoch: Long get() = if (activated) engine().state.value.availabilityEpoch else 0
    fun allows(kind: String, ref: String): Boolean = !activated || (AuthRepository.state.value != com.nuvio.app.core.auth.AuthState.Loading && engine().allows(ProfileRepository.activeProfileId,kind,ref))
    fun allowsAddon(url: String): Boolean = !activated || (AuthRepository.state.value != com.nuvio.app.core.auth.AuthState.Loading && engine().allowsAddonRequest(ProfileRepository.activeProfileId,url))
    fun guard(kind: String, ref: String) { if (!allows(kind,ref)) throw CancellationException("Source disabled on this device") }
    suspend fun <T> guarded(kind: String, ref: String, block: suspend () -> T): T {
        if (!activated) return block()
        return engine().guarded(ProfileRepository.activeProfileId,kind,ref,block)
    }
    fun guardAddon(url: String) { if (!allowsAddon(url)) throw CancellationException("Source disabled on this device") }
    suspend fun refresh() = engine().refresh(ProfileRepository.activeProfileId)
}
