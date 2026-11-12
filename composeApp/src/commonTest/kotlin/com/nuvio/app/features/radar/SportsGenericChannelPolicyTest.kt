package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals

/** B141. kotlin.test here, so the argument order is (expected, actual, message). */
class SportsGenericChannelPolicyTest {

    private val lineup = listOf(
        "UK: Sky Sports Main Event FHD",
        "DE: RTL HD",
        "|AR| beIN Sports 3",
        "FR: TF1",
        "US: ESPN 2",
        "NL: Ziggo Sport Select",
        "PL: Polsat",
        "BG: Arena Sport 4",
        "IT: Rai 1",
    )

    @Test
    fun `each channel name is normalised once per lookup and not once per marker`() {
        var calls = 0
        SportsGenericChannelPolicy.genericSportChannels(lineup, { it }) {
            calls++
            SportsEventMatchEngine.normalize(it)
        }
        assertEquals(
            lineup.size,
            calls,
            "one normalisation per channel (the old loop did up to 12 for every non-sports channel)",
        )
    }

    @Test
    fun `the generic sports channels are the same ones the marker scan found`() {
        assertEquals(
            lineup.filter { n ->
                SportsGenericChannelPolicy.MARKERS.any { SportsEventMatchEngine.normalize(n).contains(it) }
            },
            SportsGenericChannelPolicy.genericSportChannels(lineup, { it }),
            "same selection as normalising inside the marker loop",
        )
    }

    @Test
    fun `sports brands are found and general channels are not`() {
        assertEquals(
            listOf(
                "UK: Sky Sports Main Event FHD", "|AR| beIN Sports 3", "US: ESPN 2",
                "NL: Ziggo Sport Select", "BG: Arena Sport 4",
            ),
            SportsGenericChannelPolicy.genericSportChannels(lineup, { it }),
            "sports brands in, general channels out",
        )
    }
}
