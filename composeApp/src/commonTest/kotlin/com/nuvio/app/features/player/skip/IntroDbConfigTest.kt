package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Release builds set no INTRODB_API_URL, so the public IntroDB read API must be the default —
 * otherwise SkipIntroRepository never queries IntroDB and regular shows get no skip button.
 */
class IntroDbConfigTest {

    @Test
    fun defaultsToThePublicIntroDbReadApi() {
        assertEquals("https://api.introdb.app", IntroDbConfig.URL, "IntroDB base URL default")
    }
}
