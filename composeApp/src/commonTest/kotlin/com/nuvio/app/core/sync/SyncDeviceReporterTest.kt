package com.nuvio.app.core.sync

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDeviceReporterTest {
    @Test
    fun `report_device arguments include the app version`() {
        val params = reportDeviceParams("client-1", "Pixel 8", "1.22.0")
        assertEquals("1.22.0", params["p_app_version"]?.jsonPrimitive?.content, "p_app_version")
        assertEquals("client-1", params["p_client_id"]?.jsonPrimitive?.content)
        assertEquals("Pixel 8", params["p_device_name"]?.jsonPrimitive?.content)
        assertEquals("mobile", params["p_platform"]?.jsonPrimitive?.content)
    }
}
