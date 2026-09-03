package com.nuvio.app

import com.nuvio.app.core.rec.RecEventStorage
import com.nuvio.app.core.startup.AndroidStartup
import com.nuvio.app.features.epg.EpgMirrorDbDriver
import com.nuvio.app.features.iptv.IptvRefreshWorker
import com.nuvio.app.features.iptv.M3UFilePicker
import com.nuvio.app.features.iptv.XtreamAccountStorage
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.match.MatchDbDriver

/**
 * Android startup wiring — the fork-touching half of MainActivity boot, kept here so MainActivity
 * never names a fork feature. Exempt from the firewall exactly like FeatureWiring
 * (ArchitectureTest.isWiringFile). Idempotent: guarded so an activity recreate does not re-register.
 */
private var registered = false

fun registerAndroidStartup() {
    if (registered) return
    registered = true
    // Order preserves the old MainActivity sequence; all are independent DB/driver inits.
    AndroidStartup.registerTask { RecEventStorage.initialize(it) }
    AndroidStartup.registerTask { XtreamAccountStorage.initialize(it) }
    AndroidStartup.registerTask { M3UFilePicker.initialize(it) }
    AndroidStartup.registerTask { MatchDbDriver.initialize(it) }
    AndroidStartup.registerTask { IptvContentDbDriver.initialize(it) }
    AndroidStartup.registerTask { com.nuvio.app.features.iptv.overlay.OverlayDbDriver.initialize(it) }
    AndroidStartup.registerTask { EpgMirrorDbDriver.initialize(it) }
    // P3: periodic background refresh of overdue IPTV playlists (idempotent — KEEP).
    AndroidStartup.registerTask { IptvRefreshWorker.schedule(it) }
    // ACTION_OPEN_DOCUMENT launcher for the IPTV M3U picker (bound in onCreate, pre-STARTED).
    AndroidStartup.registerBinder { M3UFilePicker.bindActivity(it) }
}
