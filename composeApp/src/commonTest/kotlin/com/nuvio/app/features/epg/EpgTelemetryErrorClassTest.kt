package com.nuvio.app.features.epg

import com.nuvio.app.core.telemetry.RecordingTelemetryPort
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EpgTelemetryErrorClassTest {
    // Stands in for an R8-renamed class: phone 1.21.0 sent epg_ingest error_class=fb4.
    private class fb4(message: String) : IllegalStateException(message)

    @AfterTest
    fun tearDown() = TelemetryAccess.resetForTest()

    @Test
    fun `an ingest error reports a readable class and never the message`() {
        val events = ArrayList<Map<String, Any>>()
        TelemetryAccess.register(RecordingTelemetryPort { name, props -> if (name == "epg_ingest") events += props })

        EpgTelemetry.ingestFinished(
            source = EpgTelemetry.Source.PLAYLIST_XMLTV,
            outcome = EpgTelemetry.Outcome.ERROR,
            error = fb4("http://user:pass@panel.example.com/xmltv.php"),
        )
        EpgTelemetry.ingestFinished(source = EpgTelemetry.Source.MIRROR, outcome = EpgTelemetry.Outcome.OK)

        assertEquals("IllegalStateException", events[0]["error_class"])
        assertFalse("error_class" in events[1])
    }
}
