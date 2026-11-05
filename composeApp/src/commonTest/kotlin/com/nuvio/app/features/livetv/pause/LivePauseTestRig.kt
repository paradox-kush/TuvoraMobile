package com.nuvio.app.features.livetv.pause

import kotlinx.coroutines.CoroutineScope

/** A controller with all its ports faked, for the focused pause-controller test files. */
class LivePauseTestRig(
    scope: CoroutineScope,
    val capture: FakeCapturePort = FakeCapturePort(),
    config: LivePauseConfig = LivePauseConfig(),
    minter: LiveLinkMinter = LiveLinkMinter { LiveLink("http://p/fresh") },
) {
    val clock = FakeLiveClock()
    val engine = FakeLiveEngine()
    val host = FakeLiveHost()
    val controller = LivePauseController(
        instanceId = "p1",
        scope = scope,
        clock = clock,
        capture = capture,
        minter = minter,
        host = host,
        config = config,
    )

    fun open(
        id: String,
        caps: LivePauseCapabilities,
        link: LiveLink = LiveLink("http://p/live/$id.ts"),
        singleUse: Boolean = false,
        channel: LivePauseChannel = LivePauseChannel(isLive = true, isCatchUpPlayback = false, hasProviderArchive = false),
    ) {
        controller.onLiveSourceOpened(id, channel, link, singleUse)
        controller.attachEngine(engine, caps)
    }
}
