package com.nuvio.app.core.telemetry

import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.telemetry.internal.InstallSourcePolicy
import com.nuvio.app.core.telemetry.internal.TelemetryBuildProperties
import com.nuvio.app.core.telemetry.internal.TelemetryService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryBuildPropertiesTest {
    @Test
    fun `play installer maps to play`() = assertEquals("play", InstallSourcePolicy.bucket("com.android.vending"))

    @Test
    fun `amazon installer maps to amazon`() = assertEquals("amazon", InstallSourcePolicy.bucket("com.amazon.venezia"))

    @Test
    fun `system package installers map to package_installer`() {
        assertEquals("package_installer", InstallSourcePolicy.bucket("com.google.android.packageinstaller"))
        assertEquals("package_installer", InstallSourcePolicy.bucket("com.android.packageinstaller"))
    }

    @Test
    fun `null and blank installer map to unknown`() {
        assertEquals("unknown", InstallSourcePolicy.bucket(null))
        assertEquals("unknown", InstallSourcePolicy.bucket(""))
        assertEquals("unknown", InstallSourcePolicy.bucket("   "))
    }

    @Test
    fun `unrecognised installer maps to other and never leaks the package name`() {
        val bucket = InstallSourcePolicy.bucket("com.example.sideloader")
        assertEquals("other", bucket)
        assertTrue("example" !in bucket)
    }

    @Test
    fun `build flavor is store or full`() {
        assertTrue(AppFeaturePolicy.buildFlavor in setOf("store", "full"), "flavor was ${AppFeaturePolicy.buildFlavor}")
    }

    @Test
    fun `properties carry both names when install source is known`() {
        assertEquals(
            mapOf("build_flavor" to "store", "install_source" to "play"),
            TelemetryBuildProperties.of("store", "play"),
        )
    }

    @Test
    fun `install source is omitted where the platform has none`() {
        assertEquals(mapOf<String, Any>("build_flavor" to "full"), TelemetryBuildProperties.of("full", null))
    }

    @Test
    fun `both property names survive the privacy scrubber`() {
        val cleaned = PostHogPrivacy.sanitize(TelemetryBuildProperties.of("full", "other"))
        assertEquals("full", cleaned["build_flavor"], "build_flavor must not be redacted")
        assertEquals("other", cleaned["install_source"], "install_source must not be redacted")
    }

    @Test
    fun `service registers the build properties as super properties`() {
        val host = FakeTelemetryHost()
        TelemetryService(host).onSdkReady()
        val first = host.superProps.first()
        assertEquals(AppFeaturePolicy.buildFlavor, first["build_flavor"])
        assertEquals(true, first.containsKey("support_code"), "support_code still registered")
    }
}
