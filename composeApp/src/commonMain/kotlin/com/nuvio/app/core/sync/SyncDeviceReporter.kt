package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tells the account which device this is, so the Devices list in the web dashboard can say
 * "Kush's iPhone" instead of "Phone or tablet".
 *
 * The server already learns that a device exists from the origin client id stamped on every push;
 * this only adds the human name, which nothing else can supply. It is therefore best-effort in the
 * strictest sense: once per app launch, failures swallowed, and nothing waits on it.
 */
object SyncDeviceReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("SyncDeviceReporter")

    @Volatile
    private var reportedForClientId: String? = null

    /** Call whenever the app is authenticated. Repeat calls in the same launch are no-ops. */
    fun reportOnce() {
        if (!SyncSession.canSync()) return

        val clientId = SyncClientIdentity.currentClientId()
        if (reportedForClientId == clientId) return
        reportedForClientId = clientId

        scope.launch {
            runCatching {
                SupabaseProvider.client.postgrest.rpc(
                    "report_device",
                    reportDeviceParams(clientId, syncDeviceName(), AppVersionConfig.VERSION_NAME),
                )
                log.d { "reported device name" }
            }.onFailure { e ->
                // An older backend without the RPC, or simply offline. Neither is worth a retry:
                // the device is already listed by its sync traffic, just without a name.
                reportedForClientId = null
                log.d(e) { "device name report failed (harmless)" }
            }
        }
    }

    /** Forget the "already reported" flag so the next sign-in reports again. */
    fun clearAccountState() {
        reportedForClientId = null
    }
}

/** Arguments of the `report_device` RPC. `p_app_version` lets the backend split devices by app version. */
internal fun reportDeviceParams(clientId: String, deviceName: String, appVersion: String): JsonObject = buildJsonObject {
    put("p_client_id", clientId)
    put("p_device_name", deviceName)
    put("p_platform", MOBILE_SYNC_PLATFORM)
    put("p_app_version", appVersion)
}

/**
 * A name for this device that a person would recognise. Android has no user-set name to read on a
 * phone, so it's the hardware model; iOS has UIDevice.name.
 */
internal expect fun syncDeviceName(): String
