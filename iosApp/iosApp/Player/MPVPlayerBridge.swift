import Foundation
import UIKit
import AVFoundation
import Libmpv
import ComposeApp

// MARK: - Player Bridge Implementation (Kotlin protocol conformance)

final class MPVPlayerBridgeImpl: NSObject, NuvioPlayerBridge, NuvioPlayerPropertyBridge {

    private var playerVC: MPVPlayerViewController?
    /// Mirrors the user's Picture-in-Picture setting so a view controller created later still gets
    /// it — Kotlin sets this before the surface exists on a cold player open.
    private var pictureInPictureEnabled = false

    func createPlayerViewController() -> UIViewController {
        return ensurePlayerViewController()
    }

    private func ensurePlayerViewController() -> MPVPlayerViewController {
        if let playerVC { return playerVC }
        let vc = MPVPlayerViewController()
        vc.setExperimentalSinglePrimaryPictureInPictureEnabled(pictureInPictureEnabled)
        self.playerVC = vc
        return vc
    }

    func loadFile(url: String) { ensurePlayerViewController().loadFile(url) }
    func loadFileWithAudio(videoUrl: String, audioUrl: String?, headersJson: String?, subtitlesJson: String?, startOption: String?) {
        ensurePlayerViewController().loadFile(
            videoUrl,
            audioUrl: audioUrl,
            requestHeaders: parseRequestHeaders(headersJson),
            subtitles: parseSubtitles(subtitlesJson),
            startOption: startOption
        )
    }

    private func parseSubtitles(_ json: String?) -> [PluginSubtitle] {
        guard
            let json,
            let data = json.data(using: .utf8),
            let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else {
            return []
        }
        return raw.compactMap { dict in
            guard let url = dict["url"] as? String else { return nil }
            return PluginSubtitle(
                url: url,
                language: dict["language"] as? String ?? "Unknown",
                name: dict["name"] as? String,
                headers: dict["headers"] as? [String: String]
            )
        }
    }

    func play() { playerVC?.playPlayback() }
    func pause() { playerVC?.pausePlayback() }
    func seekTo(positionMs: Int64) { playerVC?.seekToMs(positionMs) }
    func seekBy(offsetMs: Int64) { playerVC?.seekByMs(offsetMs) }
    func setSystemControlHandler(handler: PlayerSystemControlHandler?) {
        if let handler {
            ensurePlayerViewController().systemControlHandler = handler
        } else {
            playerVC?.systemControlHandler = nil
        }
    }
    func getKeepsPlaybackInBackground() -> Bool {
        playerVC?.keepsPlaybackInBackground ?? false
    }
    func retry() { playerVC?.retryPlayback() }
    func restartFromBeginning() { playerVC?.restartFromBeginning() }
    func setIsLiveStream(isLive: Bool) { ensurePlayerViewController().isLiveStream = isLive }
    // Picture-in-Picture bridge surface. Mirrored by hand in NuvioPlayerBridge (Kotlin) — Gradle
    // cannot check these signatures, so keep the two in step (the getVideoFrameTicks precedent).
    func isPictureInPictureSupported() -> Bool { playerVC?.isPictureInPictureSupported() ?? false }
    func startPictureInPicture() { playerVC?.startPictureInPicture() }
    func stopPictureInPicture() { playerVC?.stopPictureInPicture(source: "kotlin") }
    func isPictureInPictureActive() -> Bool { playerVC?.isPictureInPictureActive() ?? false }
    func setPictureInPictureEnabled(enabled: Bool) {
        pictureInPictureEnabled = enabled
        playerVC?.setExperimentalSinglePrimaryPictureInPictureEnabled(enabled)
    }
    func updateNowPlayingMetadata(
        title: String,
        subtitle: String?,
        artworkUrl: String?
    ) {
        ensurePlayerViewController().updateNowPlayingMetadata(
            title: title,
            subtitle: subtitle,
            artworkUrl: artworkUrl
        )
    }
    func clearNowPlayingInfo() { playerVC?.clearNowPlayingInfo() }
    func configureVideoOutput(
        hardwareDecoder: String,
        targetColorspaceHint: Bool,
        toneMapping: String,
        hdrComputePeak: Bool,
        targetPrimaries: String,
        targetTransfer: String,
        extendedDynamicRange: Bool,
        deband: Bool,
        interpolation: Bool,
        brightness: Int32,
        contrast: Int32,
        saturation: Int32,
        gamma: Int32
    ) {
        playerVC?.configureVideoOutput(
            hardwareDecoder: hardwareDecoder,
            targetColorspaceHint: targetColorspaceHint,
            toneMapping: toneMapping,
            hdrComputePeak: hdrComputePeak,
            targetPrimaries: targetPrimaries,
            targetTransfer: targetTransfer,
            extendedDynamicRange: extendedDynamicRange,
            deband: deband,
            interpolation: interpolation,
            brightness: Int(brightness),
            contrast: Int(contrast),
            saturation: Int(saturation),
            gamma: Int(gamma)
        )
    }
    func configureAudioOutput(audioOutput: String) {
        playerVC?.configureAudioOutput(audioOutput: audioOutput)
    }
    func setPlaybackSpeed(speed: Float) { playerVC?.setSpeed(speed) }
    func setMuted(muted: Bool) { playerVC?.setMuted(muted) }
    func setResizeMode(mode: Int32) { playerVC?.setResize(Int(mode)) }
    /// NuvioPlayerPropertyBridge: generic property setter for the shared Kotlin policies
    /// (VideoZoomPolicy, SubtitleStyleMpvMapping). Hand-mirrored against the Kotlin interface.
    func setMpvStringProperties(names: [String], values: [String]) {
        playerVC?.setStringProperties(names, values)
    }
    func syncVideoSurfaceLayout(width: Double, height: Double) {
        playerVC?.syncVideoSurfaceLayout(size: CGSize(width: width, height: height))
    }

    // Audio tracks
    func getAudioTrackCount() -> Int32 { Int32(playerVC?.audioTracks.count ?? 0) }
    func getAudioTrackIndex(at: Int32) -> Int32 {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return 0 }
        return Int32(t[Int(at)].index)
    }
    func getAudioTrackId(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "0" }
        return "\(t[Int(at)].id)"
    }
    func getAudioTrackLabel(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].title
    }
    func getAudioTrackLang(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].lang
    }
    func isAudioTrackSelected(at: Int32) -> Bool {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return false }
        return t[Int(at)].selected
    }

    // Subtitle tracks
    func getSubtitleTrackCount() -> Int32 { Int32(playerVC?.subtitleTracks.count ?? 0) }
    func getSubtitleTrackIndex(at: Int32) -> Int32 {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return 0 }
        return Int32(t[Int(at)].index)
    }
    func getSubtitleTrackId(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "0" }
        return "\(t[Int(at)].id)"
    }
    func getSubtitleTrackLabel(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].title
    }
    func getSubtitleTrackLang(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].lang
    }
    func isSubtitleTrackSelected(at: Int32) -> Bool {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return false }
        return t[Int(at)].selected
    }

    func selectAudioTrack(trackId: Int32) { playerVC?.selectAudio(Int(trackId)) }
    func applyAudioLanguagePreferences(languages: [String]) {
        ensurePlayerViewController().applyAudioLanguagePreferences(languages)
    }
    func selectSubtitleTrack(trackId: Int32) { playerVC?.selectSubtitle(Int(trackId)) }
    func setSubtitleUrl(url: String) { playerVC?.addSubtitleUrl(url) }
    func clearExternalSubtitle() { playerVC?.removeExternalSubtitles() }
    func clearExternalSubtitleAndSelect(trackId: Int32) { playerVC?.removeExternalSubtitlesAndSelect(Int(trackId)) }
    func setSubtitleDelayMs(delayMs: Int32) { playerVC?.setSubtitleDelayMs(Int(delayMs)) }
    func setSubtitleTextHandler(handler: SubtitleTextHandler?) {
        ensurePlayerViewController().subtitleTextHandler = handler
    }

    func applySubtitleStyle(
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Bool,
        fontSize: Float,
        subPos: Int32,
        stripSdh: Bool,
        styledOverlay: Bool
    ) {
        playerVC?.applySubtitleStyle(
            textColor: textColor,
            backgroundColor: backgroundColor,
            outlineColor: outlineColor,
            outlineSize: outlineSize,
            bold: bold,
            fontSize: fontSize,
            subPos: Int(subPos),
            stripSdh: stripSdh,
            styledOverlay: styledOverlay
        )
    }

    // State - refreshes position from mpv on each call (polled from Kotlin every 250ms)
    func getIsLoading() -> Bool { playerVC?.refreshPlaybackState(); return playerVC?.isPlayerLoading ?? true }
    func getIsPlaying() -> Bool { return playerVC?.isPlayerPlaying ?? false }
    func getIsEnded() -> Bool { return playerVC?.isPlayerEnded ?? false }
    func getDurationMs() -> Int64 { return playerVC?.durationMs ?? 0 }
    func getPositionMs() -> Int64 { return playerVC?.positionMs ?? 0 }
    func getBufferedMs() -> Int64 { return playerVC?.bufferedMs ?? 0 }
    func getPlaybackSpeed() -> Float { playerVC?.currentSpeed ?? 1.0 }

    /// -1 when the track has no picture, so the freeze policy can tell an IPTV radio station
    /// from a video channel whose picture died. See NuvioPlayerBridge.getVideoFrameTicks.
    func getVideoFrameTicks() -> Int64 {
        guard let vc = playerVC, vc.hasVideoTrack else { return -1 }
        return vc.videoFrameTicks
    }

    /// mpv's VO-level counters packed into one call, because every bridge method is a
    /// hand-mirrored Swift signature Gradle cannot check (the getVideoFrameTicks precedent).
    /// High 32 bits: `frame-drop-count`; low 32 bits: `vo-delayed-frame-count`; both clamped
    /// so the packed value is never negative. See NuvioPlayerBridge.getVoFrameStats.
    func getVoFrameStats() -> Int64 {
        guard let vc = playerVC else { return 0 }
        let dropped = min(max(vc.voDroppedFrames, 0), Int64(Int32.max))
        let delayed = min(max(vc.voDelayedFrames, 0), Int64(UInt32.max))
        return (dropped << 32) | delayed
    }
    func getErrorMessage() -> String { playerVC?.currentErrorMessage ?? "" }
    func getStreamInfoJson() -> String { playerVC?.streamInfoJson() ?? "" }

    func destroy() {
        playerVC?.systemControlHandler = nil
        playerVC?.destroyPlayer()
        playerVC = nil
    }

    private func parseRequestHeaders(_ headersJson: String?) -> [String: String] {
        guard
            let headersJson,
            !headersJson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            let data = headersJson.data(using: .utf8),
            let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return [:]
        }

        var headers: [String: String] = [:]
        headers.reserveCapacity(raw.count)
        raw.forEach { key, value in
            guard let headerValue = value as? String else { return }
            headers[key] = headerValue
        }
        return headers
    }
}

struct PluginSubtitle {
    let url: String
    let language: String
    let name: String?
    let headers: [String: String]?
}

// MARK: - Track Info

struct TrackInfo {
    let index: Int
    let id: Int
    let type: String
    let title: String
    let lang: String
    let selected: Bool
}

private struct PendingLoadRequest {
    let urlString: String
    let audioUrl: String?
    let requestHeaders: [String: String]
    let subtitles: [PluginSubtitle]
    /// mpv per-file `start=` option for a resume (see Kotlin MpvStartPosition), or nil.
    let startOption: String?
    let queuedAtUptime: TimeInterval
}

// MARK: - MPV Player View Controller

final class MPVPlayerViewController: UIViewController {

    private static let defaultAudioOutput = "audiounit"

    private struct CachedNowPlayingMetadata {
        let title: String
        let subtitle: String?
        let artworkUrl: String?
    }

    private let errorStateLock = NSLock()
    var metalLayer = MetalLayer()
    private var videoLayerDetachedByRenderThreadExit = false
    var subtitleTextHandler: SubtitleTextHandler?
    private var usesStyledSubtitles = false
    private var styledSubtitleVisible = false
    private var lastSubtitleText = ""
    var systemControlHandler: PlayerSystemControlHandler?
    // The fork's PiP is the sample-buffer capture path (MPVPlayerViewController+PictureInPicture.swift):
    // playback must survive the background while PiP is starting or on screen.
    var keepsPlaybackInBackground: Bool { isPictureInPictureStarting || isPictureInPictureActive() }
    private var lastAppliedDrawableSize: CGSize = .zero
    private var externallyManagedViewSize: CGSize?
    private var pendingSurfaceLayoutWorkItems: [DispatchWorkItem] = []
    private var pendingLoadRequest: PendingLoadRequest?
    private var startupSubtitles: [PluginSubtitle] = []
    private var pendingLoadRetryWorkItem: DispatchWorkItem?
    var mpv: OpaquePointer?
    private var cachedNowPlayingMetadata: CachedNowPlayingMetadata?
    private lazy var audioSessionController: PlayerAudioSessionController = {
        let controller = PlayerAudioSessionController()
        controller.onConfigurationChanged = { [weak self] in
            guard let self, self.mpv != nil else { return }
            self.publishCachedNowPlayingInfoIfNeeded()
            self.syncNowPlayingPlaybackState(isPlaying: self.isPlayerPlaying)
        }
        return controller
    }()
    private lazy var nowPlayingController = PlayerNowPlayingController(owner: self)
    private lazy var eventQueue = DispatchQueue(label: "mpv-events", qos: .userInitiated)

    /// B187: every libmpv client call waits for the core lock, and the core holds it while the
    /// demuxer waits on the network — a seek in a remote file re-reads it. Made on Main, a burst of
    /// seeks blocked past the system watchdog deadline (0x8badf00d, iPad). Writes therefore run in
    /// order on the serial event queue with the handle captured when queued; destroyPlayer
    /// terminates on the same queue, after them. Same rule as Android/TV: never wait on mpv on Main.
    private func onMpvQueue(_ work: @escaping (OpaquePointer) -> Void) {
        guard let ctx = mpv else { return }
        eventQueue.async { work(ctx) }
    }
    private var recentPlaybackLogs: [String] = []
    var activeRequestHeaders: [String: String] = [:]
    private var preferredAudioLanguages: [String] = []
    var isLiveStream = false

    /// mpv `pause` / `eof-reached`, sampled on the 250ms poll. See refreshPlaybackState.
    var cachedPaused: Bool = false
    var cachedEofReached: Bool = false

    private var cachedPositionSeconds: Double = 0
    private var cachedPositionSampledAt: CFTimeInterval = 0

    /// Observed mpv state, written on the event queue (see readEvents). Main-thread state and the
    /// PiP capture's per-frame providers read THIS, never mpv: see MPVPropertyShadow.swift for the
    /// vo-thread deadlock a synchronous read on the render path caused.
    let propertyShadow = MPVPropertyShadow()

    /// Playback position for PiP sample timestamps, interpolated from the last 250ms poll so the
    /// PiP window's timeline advances smoothly without a per-frame mpv property read.
    var interpolatedPositionSeconds: Double {
        guard cachedPositionSampledAt > 0 else { return cachedPositionSeconds }
        guard isPlayerPlaying else { return cachedPositionSeconds }
        let elapsed = CACurrentMediaTime() - cachedPositionSampledAt
        guard elapsed > 0, elapsed < 2 else { return cachedPositionSeconds }
        return cachedPositionSeconds + elapsed * Double(currentSpeed)
    }

    /// Frame rate for the CMSampleBuffer duration. Called per captured frame on the vo/render
    /// thread, so it reads the observed shadow (estimated-vf-fps, else container-fps, sticky).
    var currentRenderFrameRate: Double { propertyShadow.renderFrameRate }

    // Video dimensions for the PiP capture's aspect handling. Called from INSIDE mpv's render path
    // (MetalLayer.nextDrawable → capture → videoRegion), so this must never be a synchronous
    // mpv_get_property: that waits on the core, which is itself in vo_wait_frame waiting for this
    // frame — the 2026-10-01 whole-app freeze. Observed `video-params/w|h` instead.
    var currentVideoWidth: Int { Int(propertyShadow.videoSize.width) }
    var currentVideoHeight: Int { Int(propertyShadow.videoSize.height) }

    // The fork carries a Metal device-loss recovery subsystem (part of a larger bridge rework we
    // did not take) and the PiP code consults it before re-arming capture. We have no such
    // tracking, so we are never "awaiting recovery" and there is nothing to retry. If Metal device
    // loss during backgrounding turns out to break PiP on real hardware, THIS is the gap to close.
    var isAwaitingDeviceLossRecovery: Bool { false }
    func retryDeviceLossRecoveryNow() {}

    // ---- Picture-in-Picture (ported; see MPVPlayerViewController+PictureInPicture.swift) ----
    var experimentalSinglePrimaryPictureInPictureEnabled = false
    var primaryRenderSurface: MPVPictureInPictureFrameCapture?
    lazy var sampleBufferDisplayView: SampleBufferDisplayView = {
        // Deliberately 2x2 and offscreen: this layer exists only so AVPictureInPictureController has
        // something it will accept. The picture the user sees comes from frames blitted into it.
        let view = SampleBufferDisplayView(frame: CGRect(x: -4, y: -4, width: 2, height: 2))
        view.alpha = 0.01
        view.isHidden = false
        view.pictureInPictureDelegate = self
        return view
    }()
    var isPictureInPictureStarting = false
    var pipStartTimeoutWorkItem: DispatchWorkItem?
    var automaticPictureInPictureStartArmed = false
    var automaticPictureInPicturePrepared = false
    var automaticPictureInPicturePreparedAt: CFTimeInterval = 0
    var automaticPictureInPictureStartPreparationInFlight = false
    var automaticPictureInPictureStartRetryWorkItem: DispatchWorkItem?
    var automaticPictureInPictureTimeoutWorkItem: DispatchWorkItem?
    var automaticPictureInPictureBackgroundTask: UIBackgroundTaskIdentifier = .invalid
    var videoTrackSuspendedForBackground = false
    var automaticPictureInPictureStartOffered = false
    private var detachedVideoLayerIndex: UInt32?
    var resumePlaybackAfterPictureInPictureRestore = false
    var pipRestoreResumeWorkItem: DispatchWorkItem?
    var preservePlaybackDuringPictureInPictureStart = false
    var ignorePictureInPicturePauseCallbacksUntil: CFTimeInterval = 0
    var automaticPiPHomeSwipeCandidate = false
    lazy var automaticPiPHomeSwipeRecognizer: UIPanGestureRecognizer = {
        let recognizer = UIPanGestureRecognizer(target: self, action: #selector(handleAutomaticPiPHomeSwipe(_:)))
        recognizer.maximumNumberOfTouches = 1
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesBegan = false
        recognizer.delaysTouchesEnded = false
        recognizer.delegate = self
        return recognizer
    }()

    // Cached track lists
    var audioTracks: [TrackInfo] = []
    var subtitleTracks: [TrackInfo] = []

    // State (polled from Kotlin every 250ms)
    var isPlayerLoading: Bool = true
    var isPlayerPlaying: Bool = false
    var isPlayerEnded: Bool = false
    var durationMs: Int64 = 0
    var positionMs: Int64 = 0
    var bufferedMs: Int64 = 0
    var currentSpeed: Float = 1.0

    /// Counts polls where mpv reported the video filter chain producing frames. Only ever
    /// increments, so any change means the picture moved since the last sample.
    var videoFrameTicks: Int64 = 0
    var hasVideoTrack: Bool = false

    /// mpv's VO-level counters. `estimated-vf-fps` above proves decoding, not presentation;
    /// these are the closest mpv has to "the picture reached the screen" (there is no true
    /// presented-frames property). Snapshot diagnostics only for now.
    var voDroppedFrames: Int64 = 0
    var voDelayedFrames: Int64 = 0
    var currentErrorMessage: String {
        errorStateLock.lock()
        defer { errorStateLock.unlock() }
        return _currentErrorMessage ?? ""
    }
    private var _currentErrorMessage: String?

    override var canBecomeFirstResponder: Bool {
        true
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        true
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        [.bottom, .left, .right]
    }

    override var prefersStatusBarHidden: Bool {
        true
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.layer.masksToBounds = true

        metalLayer.contentsGravity = .resize
        metalLayer.contentsScale = view.window?.screen.nativeScale ?? UIScreen.main.nativeScale
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        metalLayer.wantsExtendedDynamicRangeContent = true
        metalLayer.anchorPoint = CGPoint(x: 0, y: 0)
        metalLayer.position = .zero
        view.layer.addSublayer(metalLayer)
        layoutMetalLayer()
        installRenderThreadExitGuard()

        setupMpv()
        // Must run after setupMpv (it needs the live metalLayer) and before the view is on screen.
        installExperimentalPictureInPictureCaptureIfNeeded()
        activateAudioSessionForPlayback()
        setupNotifications()
        if experimentalSinglePrimaryPictureInPictureEnabled {
            view.addGestureRecognizer(automaticPiPHomeSwipeRecognizer)
        }
        refreshImmersiveSystemUI()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshImmersiveSystemUI()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutMetalLayer()
        layoutExperimentalPictureInPictureSurfaces(in: view.bounds)
        attemptStartPendingLoad()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        SystemUI.shared.playerDidBecomeVisible(self)
        refreshImmersiveSystemUI()
        becomeFirstResponder()
        UIApplication.shared.beginReceivingRemoteControlEvents()
        publishCachedNowPlayingInfoIfNeeded()
        syncVideoSurfaceLayout()
        attemptStartPendingLoad()
    }

    override func viewWillDisappear(_ animated: Bool) {
        SystemUI.shared.playerDidBecomeHidden(self)
        super.viewWillDisappear(animated)
    }

    override func didMove(toParent parent: UIViewController?) {
        super.didMove(toParent: parent)
        if parent == nil {
            SystemUI.shared.playerDidBecomeHidden(self)
        }
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        syncVideoSurfaceLayout()
        refreshImmersiveSystemUI()
        attemptStartPendingLoad()
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)

        syncVideoSurfaceLayoutNow(scheduleDeferredPasses: false)
        coordinator.animate(alongsideTransition: { [weak self] _ in
            self?.syncVideoSurfaceLayoutNow(scheduleDeferredPasses: false)
        }, completion: { [weak self] _ in
            self?.syncVideoSurfaceLayout()
            self?.attemptStartPendingLoad()
        })
    }

    func syncVideoSurfaceLayout(size: CGSize) {
        if Thread.isMainThread {
            syncVideoSurfaceLayoutNow(size: size, scheduleDeferredPasses: true)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.syncVideoSurfaceLayoutNow(size: size, scheduleDeferredPasses: true)
            }
        }
    }

    private func syncVideoSurfaceLayout() {
        if Thread.isMainThread {
            syncVideoSurfaceLayoutNow(size: nil, scheduleDeferredPasses: true)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.syncVideoSurfaceLayoutNow(size: nil, scheduleDeferredPasses: true)
            }
        }
    }

    private func syncVideoSurfaceLayoutNow(size: CGSize? = nil, scheduleDeferredPasses: Bool) {
        guard isViewLoaded else { return }
        if let size, size.width > 1, size.height > 1 {
            externallyManagedViewSize = size
            applyExternallyManagedViewSize(size)
        }
        view.setNeedsLayout()
        view.layoutIfNeeded()
        layoutMetalLayer()

        if scheduleDeferredPasses {
            scheduleDeferredSurfaceLayoutPasses()
        }
    }

    private func scheduleDeferredSurfaceLayoutPasses() {
        pendingSurfaceLayoutWorkItems.forEach { $0.cancel() }
        pendingSurfaceLayoutWorkItems.removeAll(keepingCapacity: true)

        [0.0, 0.05, 0.15, 0.35].forEach { delay in
            let workItem = DispatchWorkItem { [weak self] in
                self?.syncVideoSurfaceLayoutNow(scheduleDeferredPasses: false)
            }
            pendingSurfaceLayoutWorkItems.append(workItem)
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
        }
    }

    private func applyExternallyManagedViewSize(_ size: CGSize) {
        let targetBounds = CGRect(origin: .zero, size: size)
        if view.bounds != targetBounds {
            view.bounds = targetBounds
        }

        var targetFrame = view.frame
        if targetFrame.size != size {
            targetFrame.size = size
            view.frame = targetFrame
        }
    }

    // ---- LOCAL (not in the upstream fork) ----
    // The video layer leaves the window tree while mpv tears its vo down. MoltenVK writes layer
    // properties from the vo thread (swapchain destroy sets drawableSize and friends), and that
    // thread has no run loop, so its implicit CATransaction commits when the thread exits. With the
    // layer still on screen, that commit lays out and displays the WINDOW's tree off the main
    // thread, and Compose's SurfaceMetalRedrawer.draw aborts on its main-thread check (TestFlight
    // crash: enterBackground → vid=no → vo_destroy → pthread_join). Detached, the commit touches
    // no on-screen context. Main thread only; every vo teardown is preceded by this and every vo
    // re-creation by [reattachVideoLayerAfterVoTeardown].
    func detachVideoLayerForVoTeardown(reason: String) {
        guard Thread.isMainThread else {
            InAppLogBridge.shared.warn(tag: "MPV/iOS", message: "Video layer detach skipped off main reason=\(reason)")
            return
        }
        guard let superlayer = metalLayer.superlayer else { return }
        // Remember the z-position: the PiP sample-buffer view sits BELOW the video layer and keeps
        // showing its last captured frame, so reattaching at the bottom freezes the picture.
        detachedVideoLayerIndex = superlayer.sublayers?.firstIndex(of: metalLayer).map { UInt32($0) }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.removeFromSuperlayer()
        CATransaction.commit()
        InAppLogBridge.shared.debug(tag: "MPV/iOS", message: "Video layer detached for vo teardown reason=\(reason)")
    }

    // A vo teardown mpv started on its own (see RenderThreadExitGuard): the layer leaves the window
    // before the render thread's transaction commits, and comes back when mpv starts a new one.
    private func installRenderThreadExitGuard() {
        let exitGuard = metalLayer.renderThreadExitGuard
        exitGuard.onDetachNeeded = { [weak self] in
            guard let self, self.metalLayer.superlayer != nil else { return }
            self.detachVideoLayerForVoTeardown(reason: "render-thread-exit")
            self.videoLayerDetachedByRenderThreadExit = self.metalLayer.superlayer == nil
        }
        exitGuard.onRenderThreadStarted = { [weak self] in
            DispatchQueue.main.async {
                guard let self, self.videoLayerDetachedByRenderThreadExit else { return }
                self.videoLayerDetachedByRenderThreadExit = false
                self.reattachVideoLayerAfterVoTeardown(reason: "render-thread-restart")
            }
        }
    }

    func reattachVideoLayerAfterVoTeardown(reason: String) {
        guard Thread.isMainThread, isViewLoaded, metalLayer.superlayer == nil else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let count = UInt32(view.layer.sublayers?.count ?? 0)
        view.layer.insertSublayer(metalLayer, at: min(detachedVideoLayerIndex ?? count, count))
        detachedVideoLayerIndex = nil
        CATransaction.commit()
        layoutMetalLayer()
        InAppLogBridge.shared.debug(tag: "MPV/iOS", message: "Video layer reattached reason=\(reason)")
    }

    private func layoutMetalLayer() {
        let bounds = CGRect(origin: .zero, size: externallyManagedViewSize ?? view.bounds.size)
        guard bounds.width > 1, bounds.height > 1 else { return }

        let scale = view.window?.screen.nativeScale ?? UIScreen.main.nativeScale
        let drawableSize = CGSize(
            width: (bounds.width * scale).rounded(.toNearestOrAwayFromZero),
            height: (bounds.height * scale).rounded(.toNearestOrAwayFromZero)
        )

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.contentsScale = scale
        metalLayer.position = .zero
        metalLayer.bounds = CGRect(origin: .zero, size: bounds.size)
        let drawableSizeChanged = drawableSize != lastAppliedDrawableSize
        if drawableSizeChanged {
            if metalLayer.hasAcquiredDrawable {
                // mpv is presenting: its moltenvk context polls drawableSize and recreates its
                // swapchain on ITS OWN render thread. Writing drawableSize here (Main, during a
                // fullscreen/orientation resize) races that read and corrupts the Metal heap (iOS 26
                // EXC_BREAKPOINT _xzm_corruption_detected). Hand the size to MetalLayer, which applies
                // it inside nextDrawable() on the render thread. contentsScale/position/bounds stay on
                // Main below — they are display geometry and do not size the drawable pool.
                metalLayer.setPendingDrawableSize(drawableSize)
            } else {
                // Bootstrap (no drawable acquired yet): mpv is NOT presenting, so no render thread is
                // churning the drawable pool and a Main-thread write cannot race it. We MUST set the
                // size here — mpv reads drawableSize when it first creates its swapchain (setupMpv()
                // runs right after this in viewDidLoad), and if it stays 0-sized the swapchain never
                // comes up, nextDrawable() is never called, and the deferred (setPendingDrawableSize)
                // path can never fire → permanent black video with audio only. Matches the pipfork
                // reference, which always writes drawableSize on Main.
                metalLayer.drawableSize = drawableSize
            }
            lastAppliedDrawableSize = drawableSize
        }
        CATransaction.commit()
        if drawableSizeChanged {
            command("osd-overlay", args: ["1", "ass-events", ""], checkForErrors: false)
        }
    }

    // MARK: - MPV Setup

    private func setupMpv() {
        mpv = mpv_create()
        guard mpv != nil else {
            print("[MPV] Failed to create mpv instance")
            return
        }

        checkError(mpv_request_log_messages(mpv, "warn"))

        var layerPointer = Int64(Int(bitPattern: Unmanaged.passUnretained(metalLayer).toOpaque()))
        checkError(mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &layerPointer))
        checkError(mpv_set_option_string(mpv, "vo", "gpu-next"))
        checkError(mpv_set_option_string(mpv, "gpu-api", "vulkan"))
        checkError(mpv_set_option_string(mpv, "gpu-context", "moltenvk"))
        checkError(mpv_set_option_string(mpv, "hwdec", "videotoolbox"))
        // iOS can invalidate the VideoToolbox session (e.g. Notification Center during PiP).
        // FFmpeg recreates it at the next frames; keep hardware decoding meanwhile.
        checkError(mpv_set_option_string(mpv, "hwdec-software-fallback", "300"))
        checkError(mpv_set_option_string(mpv, "ao", Self.defaultAudioOutput))
        checkError(mpv_set_option_string(mpv, "audio-channels", "auto"))
        checkError(mpv_set_option_string(mpv, "audio-fallback-to-null", "yes"))
        // No youtube-dl/yt-dlp can exist on iOS (no subprocess spawning), and mpv's ytdl_hook is
        // FATAL without one: for an EXTENSIONLESS live URL (common in M3U playlists —
        // /live/play/<token>/<id>) the hook takes over the load, fails, and ends the file instead
        // of falling through to ffmpeg (device-reproduced on Android TV, 1.5.8; same hook ships in
        // MPVKit). .ts URLs bypass the hook, which masked this on Xtream. Go straight to ffmpeg.
        checkError(mpv_set_option_string(mpv, "ytdl", "no"))
        // NEVER let the core self-quit: without idle=yes a FAILED load (dead IPTV channel) empties
        // the playlist and the core exits (event: shutdown) while the bridge still holds it — every
        // later load into that core is silently ignored (device-traced on Android TV, 2026-08-26;
        // same libmpv behaviour here). Canonical embedded-mpv setting.
        checkError(mpv_set_option_string(mpv, "idle", "yes"))
        checkError(mpv_set_option_string(mpv, "vulkan-swap-mode", "fifo"))
        checkError(mpv_set_option_string(mpv, "vulkan-queue-count", "1"))
        checkError(mpv_set_option_string(mpv, "vulkan-async-compute", "no"))
        checkError(mpv_set_option_string(mpv, "vulkan-async-transfer", "no"))
        checkError(mpv_set_option_string(mpv, "vulkan-disable-interop", "yes"))
        checkError(mpv_set_option_string(mpv, "video-rotate", "no"))
        checkError(mpv_set_option_string(mpv, "subs-match-os-language", "yes"))
        checkError(mpv_set_option_string(mpv, "subs-fallback", "yes"))
        configureBundledSubtitleFont()
        checkError(mpv_set_option_string(mpv, "keep-open", "yes"))
        // keep-open parks the core on the last frame at EOF, which is right for a file and wrong
        // for a live channel: an IPTV panel closing the socket mid-stream reads as a clean EOF,
        // so the picture freezes with nothing to time it out. Bound the blocking read, and let
        // ffmpeg re-open the URL itself — that heals a transient drop before the app-level
        // reconnect (LivePlaybackFreezeTracking) has to tear playback down and rebuild it.
        checkError(mpv_set_option_string(mpv, "network-timeout", "15"))
        // Verify peer certificates (Android sets the same). MPVKit's FFmpeg/GnuTLS does NOT verify by default - mpv's
        // tls-verify default is "no" when built without libcurl and libavformat < 63, so a self-signed or intercepted
        // https stream used to load - and on iOS/tvOS the system trust store is not visible to GnuTLS, so
        // "tls-verify=yes" alone refuses EVERY https URL. The bundled CA list (the same cacert.pem Android ships in
        // mpv-android-lib) makes public certificates verify. Verified on the iOS simulator slice with a self-signed
        // server (refused) and a public CA server (plays). Fail closed: no CA file means https is refused.
        checkError(mpv_set_option_string(mpv, "tls-verify", "yes"))
        if let caPath = Bundle.main.path(forResource: "cacert", ofType: "pem") {
            checkError(mpv_set_option_string(mpv, "tls-ca-file", caPath))
        } else {
            print("[MPV] Bundled CA list (cacert.pem) is missing: https streams will be refused")
        }
        checkError(mpv_set_option_string(
            mpv,
            "stream-lavf-o",
            "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5"
        ))
        // mpv's defaults keep ~150MiB of demuxer cache ahead plus 50MiB behind, in memory. iOS
        // kills on total footprint (jetsam), so a long live session on those defaults is exactly
        // the "crashes minutes into a stream" report. Same budgets as the Android build.
        checkError(mpv_set_option_string(mpv, "demuxer-max-bytes", "64MiB"))
        checkError(mpv_set_option_string(mpv, "demuxer-max-back-bytes", "32MiB"))
        checkError(mpv_set_option_string(mpv, "target-colorspace-hint", "yes"))
        checkError(mpv_set_option_string(mpv, "tone-mapping", "auto"))
        checkError(mpv_set_option_string(mpv, "hdr-compute-peak", "yes"))

        checkError(mpv_initialize(mpv))
        applyAudioLanguagePreferences(preferredAudioLanguages)

        // Observe every property this bridge reads. The values land in propertyShadow on the event
        // queue, so neither Main nor the render path ever has to take mpv's core lock to read them.
        for (name, format) in MPVPlaybackProperties.observed {
            let mpvFormat: mpv_format
            switch format {
            case .flag: mpvFormat = MPV_FORMAT_FLAG
            case .int64: mpvFormat = MPV_FORMAT_INT64
            case .double: mpvFormat = MPV_FORMAT_DOUBLE
            case .string: mpvFormat = MPV_FORMAT_STRING
            case .node: mpvFormat = MPV_FORMAT_NODE
            }
            checkError(mpv_observe_property(mpv, 0, name, mpvFormat))
        }

        mpv_set_wakeup_callback(mpv, { ctx in
            let vc = unsafeBitCast(ctx, to: MPVPlayerViewController.self)
            vc.readEvents()
        }, UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()))
    }

    // setupNotifications / enterBackground / enterForeground now live in
    // MPVPlayerViewController+PictureInPicture.swift, because backgrounding is exactly the moment
    // PiP has to decide whether to keep the video track alive or suspend it. Our live-edge rejoin
    // is preserved there — see the LOCAL block in enterForeground().

    private func configureBundledSubtitleFont() {
        guard let fontURL = Bundle.main.url(
            forResource: "NotoSans-Regular",
            withExtension: "ttf"
        ) else {
            print("[MPV] Bundled subtitle font is missing")
            return
        }

        let fontDirectory = fontURL.deletingLastPathComponent().path
        checkError(mpv_set_option_string(mpv, "config", "yes"))
        fontDirectory.withCString { path in
            checkError(mpv_set_option_string(mpv, "config-dir", path))
            checkError(mpv_set_option_string(mpv, "sub-fonts-dir", path))
        }
        checkError(mpv_set_option_string(mpv, "sub-font", "Noto Sans"))
        print("[MPV] Using bundled subtitle font: \(fontURL.lastPathComponent)")
    }


    // MARK: - Playback API

    func loadFile(_ urlString: String, audioUrl: String? = nil, requestHeaders: [String: String] = [:], subtitles: [PluginSubtitle] = [], startOption: String? = nil) {
        let request = PendingLoadRequest(
            urlString: urlString,
            audioUrl: audioUrl,
            requestHeaders: requestHeaders,
            subtitles: subtitles,
            startOption: startOption,
            queuedAtUptime: ProcessInfo.processInfo.systemUptime
        )

        if Thread.isMainThread {
            queueLoad(request)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.queueLoad(request)
            }
        }
    }

    private func queueLoad(_ request: PendingLoadRequest) {
        pendingLoadRequest = request
        attemptStartPendingLoad()
    }

    private func attemptStartPendingLoad() {
        guard let request = pendingLoadRequest else { return }
        guard mpv != nil else { return }
        layoutMetalLayer()
        guard isViewportReadyForPlayback(queuedAtUptime: request.queuedAtUptime) else {
            schedulePendingLoadRetry()
            return
        }

        pendingLoadRequest = nil
        pendingLoadRetryWorkItem?.cancel()
        pendingLoadRetryWorkItem = nil
        startLoad(request)
    }

    private func startLoad(_ request: PendingLoadRequest) {
        guard mpv != nil else { return }
        layoutMetalLayer()
        clearPlaybackError()
        let sanitizedHeaders = sanitizeRequestHeaders(request.requestHeaders)
        activeRequestHeaders = sanitizedHeaders
        applyRequestHeaders(sanitizedHeaders)
        isPlayerLoading = true
        isPlayerEnded = false
        // A resume opens the file AT the position (`start=`): a seek sent before playback is
        // initialised is rejected by mpv and left the player stuck at 0:00 (B59b).
        applyAudioLanguagePreferences(preferredAudioLanguages)
        startupSubtitles = request.subtitles
        if let startOption = request.startOption {
            command("loadfile", args: [request.urlString, "replace", "-1", startOption])
        } else {
            command("loadfile", args: [request.urlString, "replace"])
        }
        if let audioUrl = request.audioUrl, !audioUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.command("audio-add", args: [audioUrl, "select"], checkForErrors: false)
            }
        }
    }

    private func isViewportReadyForPlayback(queuedAtUptime: TimeInterval) -> Bool {
        guard isViewLoaded, view.window != nil else { return false }
        let bounds = view.bounds
        guard bounds.width > 1, bounds.height > 1 else { return false }
        if bounds.width >= bounds.height { return true }

        let age = ProcessInfo.processInfo.systemUptime - queuedAtUptime
        return age >= 0.9
    }

    private func schedulePendingLoadRetry() {
        guard pendingLoadRetryWorkItem == nil else { return }

        let workItem = DispatchWorkItem { [weak self] in
            self?.pendingLoadRetryWorkItem = nil
            self?.attemptStartPendingLoad()
        }
        pendingLoadRetryWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: workItem)
    }

    // Requests from PiP and Now Playing go to the session controller when one handles them.
    func requestSystemPlaying(_ playing: Bool) {
        if systemControlHandler?.handleSetPlaying(playing: playing) == true { return }
        if playing { playPlayback() } else { pausePlayback() }
    }

    func requestSystemSeekTo(_ positionMs: Int64) {
        if systemControlHandler?.handleSeekTo(positionMs: positionMs) == true { return }
        seekToMs(positionMs)
    }

    func requestSystemSeekBy(_ offsetMs: Int64, exact: Bool = false) {
        if systemControlHandler?.handleSeekBy(offsetMs: offsetMs) == true { return }
        seekByMs(offsetMs, exact: exact)
    }

    func playPlayback() {
        guard mpv != nil else { return }
        publishNowPlayingForPlaybackSession()
        setFlag("pause", false)
        isPlayerPlaying = true
        syncNowPlayingPlaybackState(isPlaying: true)
    }

    func pausePlayback() {
        guard mpv != nil else { return }
        setFlag("pause", true)
        isPlayerPlaying = false
        syncNowPlayingPlaybackState(isPlaying: false)
    }

    func seekToMs(_ ms: Int64) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        command("seek", args: [String(format: "%.3f", seconds), "absolute"])
    }

    func seekByMs(_ ms: Int64, exact: Bool = false) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        let seekMode = exact ? "relative+exact" : "relative"
        command("seek", args: [String(format: "%.3f", seconds), seekMode])
    }

    /// "Start from beginning": reopen at 0:00 on this player. mpv drops a seek issued before playback
    /// initialises, and a slow resume is stuck exactly there, so reload with a start position instead.
    func restartFromBeginning() {
        guard mpv != nil, let path = propertyShadow.snapshot.path else { return }
        clearPlaybackError()
        applyRequestHeaders(activeRequestHeaders)
        command("loadfile", args: [path, "replace", "-1", "start=0"])
    }

    func retryPlayback() {
        guard mpv != nil else { return }
        let observed = propertyShadow.snapshot
        if let path = observed.path {
            clearPlaybackError()
            applyRequestHeaders(activeRequestHeaders)
            let pos = observed.timePos
            // Reopen AT the position rather than seeking 0.5 s later — mpv drops a seek that lands
            // before playback is initialised, so the retry used to restart from 0:00 (B59b).
            if pos > 0 {
                command("loadfile", args: [path, "replace", "-1", String(format: "start=%.3f", pos)])
            } else {
                command("loadfile", args: [path, "replace"])
            }
        }
    }

    func configureVideoOutput(
        hardwareDecoder: String,
        targetColorspaceHint: Bool,
        toneMapping: String,
        hdrComputePeak: Bool,
        targetPrimaries: String,
        targetTransfer: String,
        extendedDynamicRange: Bool,
        deband: Bool,
        interpolation: Bool,
        brightness: Int,
        contrast: Int,
        saturation: Int,
        gamma: Int
    ) {
        metalLayer.wantsExtendedDynamicRangeContent = extendedDynamicRange
        guard mpv != nil else { return }

        setStringProperty("hwdec", hardwareDecoder)
        setStringProperty("target-colorspace-hint", targetColorspaceHint ? "yes" : "no")
        setStringProperty("tone-mapping", toneMapping)
        setStringProperty("hdr-compute-peak", hdrComputePeak ? "yes" : "no")
        setStringProperty("target-prim", targetPrimaries)
        setStringProperty("target-trc", targetTransfer)
        setStringProperty("deband", deband ? "yes" : "no")
        setStringProperty("interpolation", interpolation ? "yes" : "no")
        setVideoEqualizer("brightness", brightness)
        setVideoEqualizer("contrast", contrast)
        setVideoEqualizer("saturation", saturation)
        setVideoEqualizer("gamma", gamma)
    }

    func configureAudioOutput(audioOutput: String) {
        guard mpv != nil else { return }
        let resolvedAudioOutput: String
        if audioOutput.contains("avfoundation") {
            resolvedAudioOutput = Self.defaultAudioOutput
        } else {
            resolvedAudioOutput = audioOutput
        }
        setStringProperty("ao", resolvedAudioOutput)
    }

    func setSpeed(_ speed: Float) {
        guard mpv != nil else { return }
        onMpvQueue { ctx in
            var s = Double(speed)
            mpv_set_property(ctx, "speed", MPV_FORMAT_DOUBLE, &s)
        }
    }

    func setMuted(_ muted: Bool) {
        guard mpv != nil else { return }
        setFlag("mute", muted)
    }

    func setResize(_ mode: Int) {
        guard mpv != nil else { return }
        switch mode {
        case 1: // Fill
            setStringProperty("panscan", "1.0")
            setStringProperty("video-unscaled", "no")
        case 2: // Zoom
            setStringProperty("panscan", "1.0")
            setStringProperty("video-unscaled", "no")
        default: // Fit
            setStringProperty("panscan", "0.0")
            setStringProperty("video-unscaled", "no")
        }
    }

    // MARK: - Track selection

    func selectAudio(_ trackId: Int) {
        guard mpv != nil else { return }
        onMpvQueue { ctx in
            var id = Int64(trackId)
            mpv_set_property(ctx, "aid", MPV_FORMAT_INT64, &id)
        }
    }

    func applyAudioLanguagePreferences(_ languages: [String]) {
        preferredAudioLanguages = languages
        guard mpv != nil else { return }
        setStringProperty("alang", languages.joined(separator: ","))
        if let currentId = propertyShadow.snapshot.aid, Int(currentId) != nil {
            setStringProperty("aid", currentId)
        }
        setStringProperty("aid", "auto")
    }

    func selectSubtitle(_ trackId: Int) {
        guard mpv != nil else { return }
        if trackId < 0 {
            setStringProperty("sid", "no")
        } else {
            onMpvQueue { ctx in
                var id = Int64(trackId)
                mpv_set_property(ctx, "sid", MPV_FORMAT_INT64, &id)
            }
        }
    }

    func addSubtitleUrl(_ url: String) {
        guard mpv != nil else { return }
        command("sub-add", args: [url, "select"])
    }

    private func addSubtitle(_ subtitle: PluginSubtitle, mode: String) {
        guard mpv != nil else { return }
        let subtitleHeaders = sanitizeRequestHeaders(subtitle.headers ?? [:])
        let previousHeaders = activeRequestHeaders

        if !subtitleHeaders.isEmpty {
            applyRequestHeaders(previousHeaders.merging(subtitleHeaders) { _, subtitleValue in subtitleValue })
        }

        command(
            "sub-add",
            args: [subtitle.url, mode, subtitle.name ?? subtitle.language, subtitle.language],
            checkForErrors: false
        )

        if !subtitleHeaders.isEmpty {
            applyRequestHeaders(previousHeaders)
        }
    }

    func removeExternalSubtitles() {
        guard mpv != nil else { return }
        for track in propertyShadow.snapshot.tracks.reversed() where track.type == "sub" && track.external {
            command("sub-remove", args: ["\(track.id)"], checkForErrors: false)
        }
        setStringProperty("sid", "no")
    }

    func removeExternalSubtitlesAndSelect(_ trackId: Int) {
        guard mpv != nil else { return }
        for track in propertyShadow.snapshot.tracks.reversed() where track.type == "sub" && track.external {
            command("sub-remove", args: ["\(track.id)"], checkForErrors: false)
        }
        if trackId >= 0 {
            selectSubtitle(trackId)
        } else {
            setStringProperty("sid", "no")
        }
    }

    func setSubtitleDelayMs(_ delayMs: Int) {
        guard mpv != nil else { return }
        onMpvQueue { [self] ctx in
            var delaySeconds = Double(max(-60_000, min(60_000, delayMs))) / 1000.0
            checkError(mpv_set_property(ctx, "sub-delay", MPV_FORMAT_DOUBLE, &delaySeconds))
        }
    }

    func applySubtitleStyle(
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Bool,
        fontSize: Float,
        subPos: Int,
        stripSdh: Bool,
        styledOverlay: Bool
    ) {
        guard mpv != nil else { return }
        onMpvQueue { [self] ctx in
            checkError(mpv_set_property_string(ctx, "sub-ass-override", "no"))
            checkError(mpv_set_property_string(ctx, "sub-color", textColor))
            checkError(mpv_set_property_string(ctx, "sub-back-color", backgroundColor))
            checkError(mpv_set_property_string(ctx, "sub-outline-color", outlineColor))
            // UX61: background-box paints the box in sub-back-color with its alpha; opaque-box painted it in
            // the outline colour (near-solid "dim"). The shared SubtitleStyleMpvMapping re-applies this.
            checkError(mpv_set_property_string(ctx, "sub-border-style", backgroundColor.hasPrefix("#00") ? "outline-and-shadow" : "background-box"))
            checkError(mpv_set_property_string(ctx, "sub-bold", bold ? "yes" : "no"))

            var outline = Double(outlineSize)
            checkError(mpv_set_property(ctx, "sub-outline-size", MPV_FORMAT_DOUBLE, &outline))

            var size = Double(fontSize)
            checkError(mpv_set_property(ctx, "sub-font-size", MPV_FORMAT_DOUBLE, &size))

            var position = Int64(subPos)
            checkError(mpv_set_property(ctx, "sub-pos", MPV_FORMAT_INT64, &position))
            checkError(mpv_set_property_string(ctx, "sub-filter-sdh", stripSdh ? "yes" : "no"))
            checkError(mpv_set_property_string(ctx, "sub-filter-sdh-harder", stripSdh ? "yes" : "no"))
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.usesStyledSubtitles = styledOverlay
                self.updateStyledSubtitle()
            }
        }
    }

    /// Styled-overlay subtitles (upstream feature). Reads come from the property shadow, never from
    /// mpv: this runs on Main and the fork rule is no synchronous mpv reads there.
    private func updateStyledSubtitle() {
        guard mpv != nil else { return }
        let snapshot = propertyShadow.snapshot
        let codec = snapshot.subtitleCodec ?? ""
        let styled = usesStyledSubtitles && SubtitleRendering.shared.supportsTextOverlay(codec: codec)
        if styled != styledSubtitleVisible {
            setStringProperty("sub-visibility", styled ? "no" : "yes")
            styledSubtitleVisible = styled
        }
        let text = styled ? (snapshot.subtitleText ?? "") : ""
        if text != lastSubtitleText {
            lastSubtitleText = text
            subtitleTextHandler?.onTextChanged(text: text)
        }
    }

    func destroyPlayer() {
        NotificationCenter.default.removeObserver(self)
        UIApplication.shared.endReceivingRemoteControlEvents()
        resignFirstResponder()
        pendingLoadRetryWorkItem?.cancel()
        pendingLoadRetryWorkItem = nil
        pendingSurfaceLayoutWorkItems.forEach { $0.cancel() }
        pendingSurfaceLayoutWorkItems.removeAll(keepingCapacity: false)
        pendingLoadRequest = nil
        startupSubtitles = []
        nowPlayingController.invalidate()
        clearPlaybackError()
        deactivateAudioSession()
        guard let ctx = mpv else { return }
        mpv = nil  // nil first so event loop stops reading
        detachVideoLayerForVoTeardown(reason: "destroy")
        // Profile changes and Compose disposal run on Main. Demuxer shutdown can block on a
        // dead provider, so drain the event reader and terminate off Main. Retain the controller
        // until the C wakeup callback has been cleared: its context is passUnretained(self).
        eventQueue.async { [self] in
            withExtendedLifetime(self) {
                mpv_set_wakeup_callback(ctx, nil, nil)
                mpv_terminate_destroy(ctx)
            }
        }
    }

    private func activateAudioSessionForPlayback() {
        audioSessionController.activate()
    }

    private func deactivateAudioSession() {
        audioSessionController.deactivate()
    }

    // MARK: - State Update

    /// Lightweight state refresh — called by Kotlin polling (every 250ms).
    /// Only reads cheap scalar properties; does NOT re-enumerate tracks.
    func refreshPlaybackState() {
        guard mpv != nil else { return }
        // Main thread (Kotlin poll + event hops): read the observed shadow, never mpv itself — a
        // synchronous read here queues behind the core lock (stalled demuxer, or the core waiting
        // on a vo frame) and freezes the UI. Same values the old per-poll reads returned.
        let observed = propertyShadow.snapshot
        let duration = observed.duration
        let position = observed.timePos
        let cached = observed.demuxerCacheTime
        let speed = observed.speed
        let paused = observed.paused
        let eofReached = observed.eofReached

        isPlayerLoading = observed.isLoading
        isPlayerPlaying = observed.isPlaying
        isPlayerEnded = eofReached
        // Mirrored for the PiP paths, which run on Main — including inside the Home-swipe gesture
        // and AVKit's delegate callbacks. Reading these from mpv there means mpv_get_property, which
        // takes the core lock a stalled live demuxer can hold for seconds: the same shape that
        // caused the Android ANRs, but on the iOS main thread at the exact moment the user swipes.
        // 250ms of staleness is irrelevant to "may PiP start"; a watchdog kill is not.
        cachedPaused = paused
        cachedEofReached = eofReached
        // LOCAL: AVKit's automatic start ignores the playback delegate's paused answer on iPadOS 26
        // and would PiP a paused video (device-observed). Offer automatic start only while playing.
        let automaticStartWanted = experimentalSinglePrimaryPictureInPictureEnabled && !paused && !eofReached
        if automaticStartWanted != automaticPictureInPictureStartOffered {
            automaticPictureInPictureStartOffered = automaticStartWanted
            sampleBufferDisplayView.pictureInPictureController?.setAutomaticStartEnabled(automaticStartWanted)
            InAppLogBridge.shared.info(tag: "PiP/iOS", message: "Automatic PiP start offered=\(automaticStartWanted)")
        }
        durationMs = Int64(duration * 1000)
        positionMs = Int64(max(position, 0) * 1000)
        bufferedMs = Int64(max(position + cached, 0) * 1000)
        currentSpeed = Float(speed > 0 ? speed : 1.0)

        // PiP frame capture reads position and frame rate for every CMSampleBuffer it enqueues.
        // Those are per-presented-frame calls on the render path, so they must NOT touch mpv (see
        // currentVideoWidth). Position is cached here and interpolated; the frame rate comes from
        // the observed shadow (currentRenderFrameRate).
        cachedPositionSeconds = max(position, 0)
        cachedPositionSampledAt = CACurrentMediaTime()

        // Live-freeze detection: the picture can stop while audio plays on, which leaves every
        // other field here looking healthy. `estimated-vf-fps` is the one signal that stops too.
        // Advanced at read time here (this poll), which is the correct pattern — a callback-driven
        // count plateaus once the estimate settles. Floor of 1.0 fps matches Kotlin's
        // MpvVideoOutputSignal.MIN_LIVE_FPS so all platforms agree on "the picture is alive".
        hasVideoTrack = !(observed.videoFormat ?? "").isEmpty
        if observed.estimatedVfFps >= 1 { videoFrameTicks &+= 1 }
        // VO-level counters for the playback snapshot; see the property declarations.
        voDroppedFrames = observed.frameDropCount
        voDelayedFrames = observed.voDelayedFrameCount

        let shouldPublishNowPlayingState = !isPlayerLoading || isPlayerPlaying || durationMs > 0 || positionMs > 0
        if shouldPublishNowPlayingState {
            syncNowPlayingPlaybackState(isPlaying: isPlayerPlaying)
        }
    }

    private func syncNowPlayingPlaybackState(isPlaying: Bool) {
        nowPlayingController.syncPlayback(
            positionMs: positionMs,
            durationMs: durationMs,
            isPlaying: isPlaying,
            playbackSpeed: currentSpeed
        )
    }

    /// Full state + track refresh — called from MPV event loop on property changes.
    func updateState() {
        refreshPlaybackState()
        refreshTracks()
        updateStyledSubtitle()
    }

    private func refreshTracks() {
        guard mpv != nil else { return }
        var audio = [TrackInfo]()
        var subs = [TrackInfo]()
        var audioIdx = 0
        var subIdx = 0

        // From the observed track-list node — previously ~10 synchronous reads per track on Main.
        for track in propertyShadow.snapshot.tracks {
            let type = track.type
            let id = track.id
            let title = track.title
            let lang = track.lang
            let codec = track.codec
            let decoderDescription = track.decoderDescription
            let channels = track.demuxChannels
            let channelCount = track.demuxChannelCount
            let selected = track.selected
            let displayTitle = formatTrackTitle(
                type: type,
                index: type == "audio" ? audioIdx : subIdx,
                title: title,
                lang: lang,
                codec: codec,
                decoderDescription: decoderDescription,
                channels: channels,
                channelCount: channelCount
            )

            if type == "audio" {
                audio.append(TrackInfo(index: audioIdx, id: id, type: type, title: displayTitle, lang: lang, selected: selected))
                audioIdx += 1
            } else if type == "sub" {
                subs.append(TrackInfo(index: subIdx, id: id, type: type, title: displayTitle, lang: lang, selected: selected))
                subIdx += 1
            }
        }
        audioTracks = audio
        subtitleTracks = subs
    }

    func updateNowPlayingMetadata(
        title: String,
        subtitle: String?,
        artworkUrl: String?
    ) {
        cachedNowPlayingMetadata = CachedNowPlayingMetadata(
            title: title,
            subtitle: subtitle,
            artworkUrl: artworkUrl
        )
        nowPlayingController.updateMetadata(
            title: title,
            subtitle: subtitle,
            artworkUrl: artworkUrl
        )
        publishNowPlayingForPlaybackSession()
    }

    func clearNowPlayingInfo() {
        cachedNowPlayingMetadata = nil
        nowPlayingController.clear()
    }

    private func publishCachedNowPlayingInfoIfNeeded() {
        guard let metadata = cachedNowPlayingMetadata else { return }
        nowPlayingController.updateMetadata(
            title: metadata.title,
            subtitle: metadata.subtitle,
            artworkUrl: metadata.artworkUrl
        )
    }

    private func publishNowPlayingForPlaybackSession() {
        activateAudioSessionForPlayback()
        if isViewLoaded, view.window != nil {
            becomeFirstResponder()
        }
        UIApplication.shared.beginReceivingRemoteControlEvents()
        publishCachedNowPlayingInfoIfNeeded()
        syncNowPlayingPlaybackState(isPlaying: isPlayerPlaying)
    }

    /// Stream facts for the info overlay, as a `PlayerStreamInfoPayload` JSON object.
    /// Keys and units must stay in step with the Kotlin payload and with the Android and
    /// desktop bridges: bitrates are bits per second, matching `Format.bitrate`.
    func streamInfoJson() -> String {
        guard mpv != nil else { return "" }
        // Called on Main (Kotlin getStreamInfo): built from the observed shadow, not mpv reads.
        let observed = propertyShadow.snapshot

        // The selected track of a given type, or nil when there is none.
        func selectedTrack(_ type: String) -> MPVTrackShadow? {
            observed.tracks.first { $0.type == type && $0.selected }
        }

        var fields: [String] = []
        func put(_ key: String, _ value: Int) { if value > 0 { fields.append("\"\(key)\":\(value)") } }
        func put(_ key: String, _ value: Double) { if value > 0 { fields.append("\"\(key)\":\(value)") } }
        func put(_ key: String, _ value: String) {
            guard !value.isEmpty else { return }
            // Codec names are bare identifiers, but escape defensively rather than emit
            // malformed JSON that the Kotlin decoder would silently drop.
            let escaped = value.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
            fields.append("\"\(key)\":\"\(escaped)\"")
        }

        if let v = selectedTrack("video") {
            put("videoCodec", v.codec)
            put("videoWidth", observed.videoWidth > 0 ? Int(observed.videoWidth) : v.demuxWidth)
            put("videoHeight", observed.videoHeight > 0 ? Int(observed.videoHeight) : v.demuxHeight)
            put("videoFps", v.demuxFps)
            // Measured first, then the container's average, then the HLS variant's rate —
            // live MPEG-TS usually declares none of the latter two.
            var bitrate = observed.videoBitrate
            if bitrate <= 0 { bitrate = v.demuxBitrate }
            if bitrate <= 0 { bitrate = v.hlsBitrate }
            put("videoBitrate", bitrate)
        }

        if let a = selectedTrack("audio") {
            put("audioCodec", a.codec)
            put("audioChannels", a.demuxChannelCount)
            put("audioSampleRate", a.demuxSampleRate)
            put("audioBitrate", observed.audioBitrate)
        }

        return fields.isEmpty ? "" : "{" + fields.joined(separator: ",") + "}"
    }

    private func formatTrackTitle(
        type: String,
        index: Int,
        title: String,
        lang: String,
        codec: String,
        decoderDescription: String,
        channels: String,
        channelCount: Int
    ) -> String {
        let base = ifNotBlank(title)
            ?? localizedLanguageName(lang)
            ?? (type == "sub" ? "Subtitle \(index + 1)" : "Track \(index + 1)")
        let codecName = codecDisplayName(codec) ?? codecDisplayName(decoderDescription)
        let channelName = type == "audio" ? channelLayoutName(channels: channels, channelCount: channelCount) : nil
        let details = [channelName, codecName]
            .compactMap { $0 }
            .filter { detail in !base.localizedCaseInsensitiveContains(detail) }
        return details.isEmpty ? base : "\(base) (\(details.joined(separator: ", ")))"
    }

    private func ifNotBlank(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func localizedLanguageName(_ languageCode: String) -> String? {
        guard let code = ifNotBlank(languageCode) else { return nil }
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    private func channelLayoutName(channels: String, channelCount: Int) -> String? {
        if let normalized = ifNotBlank(channels), normalized != "unknown" {
            let lower = normalized.lowercased()
            if lower == "mono" { return "Mono" }
            if lower == "stereo" { return "Stereo" }
            return normalized
        }
        switch channelCount {
        case 1:
            return "Mono"
        case 2:
            return "Stereo"
        case 6:
            return "5.1"
        case 8:
            return "7.1"
        case let count where count > 0:
            return "\(count)ch"
        default:
            return nil
        }
    }

    private func codecDisplayName(_ value: String) -> String? {
        guard let raw = ifNotBlank(value) else { return nil }
        let codec = raw.lowercased()
        if codec.contains("eac3") || codec.contains("e-ac-3") || codec.contains("e ac-3") {
            return codec.contains("joc") || codec.contains("atmos") ? "E-AC-3-JOC" : "E-AC-3"
        }
        if codec.contains("truehd") || codec.contains("true hd") { return "TrueHD" }
        if codec.contains("ac3") || codec.contains("ac-3") { return "AC-3" }
        if codec.contains("dts-hd") || codec.contains("dtshd") || codec.contains("dts hd") { return "DTS-HD" }
        if codec.contains("dts") || codec == "dca" { return "DTS" }
        if codec.contains("aac") { return "AAC" }
        if codec.contains("mp3") || codec.contains("mpeg audio") { return "MP3" }
        if codec.contains("mp2") { return "MP2" }
        if codec.contains("opus") { return "Opus" }
        if codec.contains("vorbis") { return "Vorbis" }
        if codec.contains("flac") { return "FLAC" }
        if codec.contains("alac") { return "ALAC" }
        if codec.contains("pcm") || codec.contains("wav") { return "WAV" }
        if codec.contains("amr_wb") || codec.contains("amr-wb") { return "AMR-WB" }
        if codec.contains("amr_nb") || codec.contains("amr-nb") { return "AMR-NB" }
        if codec.contains("amr") { return "AMR" }
        if codec.contains("iamf") { return "IAMF" }
        if codec.contains("mpegh") || codec.contains("mpeg-h") { return "MPEG-H" }
        if codec.contains("pgs") || codec.contains("hdmv") { return "PGS" }
        if codec.contains("subrip") || codec == "srt" { return "SRT" }
        if codec.contains("ass") || codec.contains("ssa") { return "SSA" }
        if codec.contains("webvtt") || codec == "vtt" { return "VTT" }
        if codec.contains("ttml") { return "TTML" }
        if codec.contains("mov_text") || codec.contains("tx3g") { return "TX3G" }
        if codec.contains("dvb") { return "DVB" }
        return raw
    }

    func clearPlaybackError() {
        errorStateLock.lock()
        recentPlaybackLogs.removeAll(keepingCapacity: true)
        _currentErrorMessage = nil
        errorStateLock.unlock()
    }

    private func appendPlaybackLog(prefix: String, level: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard level == "warn" || level == "error" || level == "fatal" else { return }

        let formatted = "[\(prefix)] \(trimmed)"
        errorStateLock.lock()
        recentPlaybackLogs.append(formatted)
        if recentPlaybackLogs.count > 4 {
            recentPlaybackLogs.removeFirst(recentPlaybackLogs.count - 4)
        }
        errorStateLock.unlock()
    }

    private func setPlaybackError(_ fallback: String) {
        let trimmedFallback = fallback.trimmingCharacters(in: .whitespacesAndNewlines)
        errorStateLock.lock()
        var parts = recentPlaybackLogs.suffix(3)
        if !trimmedFallback.isEmpty && !parts.contains(trimmedFallback) {
            parts.append(trimmedFallback)
        }
        _currentErrorMessage = parts.isEmpty ? "Unable to play this stream." : parts.joined(separator: "\n")
        errorStateLock.unlock()
    }

    // MARK: - Event Loop

    private func readEvents() {
        eventQueue.async { [weak self] in
            guard let self, let mpv = self.mpv else { return }

            while true {
                let event = mpv_wait_event(mpv, 0)
                guard let eventPtr = event else { break }
                if eventPtr.pointee.event_id == MPV_EVENT_NONE { break }

                switch eventPtr.pointee.event_id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    // Copy the value into the shadow HERE, on the event queue: the event's data is
                    // only valid until the next mpv_wait_event. Only the changes that always
                    // refreshed Main still do; per-tick values wait for the 250ms poll.
                    guard let data = eventPtr.pointee.data else { break }
                    let property = UnsafePointer<mpv_event_property>(OpaquePointer(data)).pointee
                    let name = String(cString: property.name)
                    if name == "track-list" {
                        self.propertyShadow.setTracks(Self.decodeTrackList(property))
                    } else {
                        self.propertyShadow.apply(name, Self.decodePropertyValue(property))
                    }
                    if name == "sub-text" {
                        DispatchQueue.main.async { self.updateStyledSubtitle() }
                    } else if MPVPlaybackProperties.mainRefreshProperties.contains(name) {
                        DispatchQueue.main.async { self.updateState() }
                    }
                case MPV_EVENT_FILE_LOADED:
                    DispatchQueue.main.async {
                        self.clearPlaybackError()
                        self.isPlayerLoading = false
                        let subtitles = self.startupSubtitles
                        self.startupSubtitles = []
                        subtitles.forEach { self.addSubtitle($0, mode: "auto") }
                        self.updateState()
                        self.publishNowPlayingForPlaybackSession()
                        self.logCurrentAudioOutput()
                    }
                case MPV_EVENT_PLAYBACK_RESTART:
                    DispatchQueue.main.async {
                        self.updateState()
                        self.publishNowPlayingForPlaybackSession()
                        // LOCAL: prepare the PiP source as soon as video is playing, not only after a
                        // foreground return — otherwise the first minimise has nothing to show.
                        self.prewarmAutomaticPictureInPictureSource(reason: "playback-restart")
                    }
                case MPV_EVENT_END_FILE:
                    if let data = eventPtr.pointee.data {
                        let endFile = UnsafePointer<mpv_event_end_file>(OpaquePointer(data)).pointee
                        if endFile.reason == MPV_END_FILE_REASON_ERROR {
                            let errorText = String(cString: mpv_error_string(endFile.error))
                            self.setPlaybackError("[mpv] \(errorText)")
                            print("[MPV] End file error: \(errorText)")
                        }
                    }
                case MPV_EVENT_SHUTDOWN:
                    return
                case MPV_EVENT_LOG_MESSAGE:
                    if let msg = UnsafeMutablePointer<mpv_event_log_message>(OpaquePointer(eventPtr.pointee.data)) {
                        let prefix = String(cString: msg.pointee.prefix!)
                        let level = String(cString: msg.pointee.level!)
                        // B116: mpv/FFmpeg lines embed request URLs ("Failed to open <url>.") —
                        // provider credentials included. Redact before the line is printed or
                        // composed into the on-screen playback error.
                        let text = LogRedaction.shared.text(message: String(cString: msg.pointee.text!))
                        self.appendPlaybackLog(prefix: prefix, level: level, text: text)
                        print("[MPV][\(prefix)] \(level): \(text)", terminator: "")
                    }
                default:
                    break
                }
            }
        }
    }

    // MARK: - Property-change decoding (event queue only)

    /// Copies a scalar property-change payload out of mpv memory.
    private static func decodePropertyValue(_ property: mpv_event_property) -> MPVPropertyValue {
        guard let data = property.data else { return .none }
        switch property.format {
        case MPV_FORMAT_FLAG:
            return .flag(data.assumingMemoryBound(to: Int32.self).pointee != 0)
        case MPV_FORMAT_INT64:
            return .int(data.assumingMemoryBound(to: Int64.self).pointee)
        case MPV_FORMAT_DOUBLE:
            return .double(data.assumingMemoryBound(to: Double.self).pointee)
        case MPV_FORMAT_STRING:
            guard let cString = data.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee else {
                return .none
            }
            return .string(String(cString: cString))
        default:
            return .none
        }
    }

    private static func decodeNodeScalar(_ node: mpv_node) -> MPVPropertyValue {
        switch node.format {
        case MPV_FORMAT_FLAG: return .flag(node.u.flag != 0)
        case MPV_FORMAT_INT64: return .int(node.u.int64)
        case MPV_FORMAT_DOUBLE: return .double(node.u.double_)
        case MPV_FORMAT_STRING:
            guard let cString = node.u.string else { return .none }
            return .string(String(cString: cString))
        default: return .none
        }
    }

    /// The observed `track-list` node (an array of maps) as plain tracks. `.none`/unexpected
    /// shapes decode to no tracks, matching the old `track-list/count` read failing to 0.
    private static func decodeTrackList(_ property: mpv_event_property) -> [MPVTrackShadow] {
        guard property.format == MPV_FORMAT_NODE, let data = property.data else { return [] }
        let root = data.assumingMemoryBound(to: mpv_node.self).pointee
        guard root.format == MPV_FORMAT_NODE_ARRAY, let list = root.u.list?.pointee,
              list.num > 0, let values = list.values else { return [] }
        var tracks: [MPVTrackShadow] = []
        tracks.reserveCapacity(Int(list.num))
        for i in 0..<Int(list.num) {
            let entry = values[i]
            guard entry.format == MPV_FORMAT_NODE_MAP, let map = entry.u.list?.pointee,
                  let keys = map.keys, let fieldValues = map.values else { continue }
            var fields: [String: MPVPropertyValue] = [:]
            for j in 0..<Int(map.num) {
                guard let key = keys[j] else { continue }
                fields[String(cString: key)] = decodeNodeScalar(fieldValues[j])
            }
            tracks.append(MPVTrackShadow(fields: fields))
        }
        return tracks
    }

    // MARK: - MPV Helpers

    func command(_ command: String, args: [String?] = [], checkForErrors: Bool = true) {
        guard mpv != nil else { return }
        MPVRenderThreadGuard.assertNotOnRenderCallback("command \(command)")
        let strArgs = makeCArgs(command, args)
        onMpvQueue { [self] ctx in
            var cargs = strArgs.map { $0.flatMap { UnsafePointer<CChar>(strdup($0)) } }
            defer { for ptr in cargs where ptr != nil { free(UnsafeMutablePointer(mutating: ptr!)) } }
            let ret = mpv_command(ctx, &cargs)
            if checkForErrors { checkError(ret) }
        }
    }

    private func makeCArgs(_ command: String, _ args: [String?]) -> [String?] {
        var strArgs = args
        strArgs.insert(command, at: 0)
        strArgs.append(nil)
        return strArgs
    }

    func getDouble(_ name: String) -> Double {
        guard mpv != nil else { return 0.0 }
        MPVRenderThreadGuard.assertNotOnRenderCallback(name)
        var data = Double()
        mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &data)
        return data
    }

    func getString(_ name: String) -> String? {
        guard mpv != nil else { return nil }
        MPVRenderThreadGuard.assertNotOnRenderCallback(name)
        let cstr = mpv_get_property_string(mpv, name)
        let str: String? = cstr == nil ? nil : String(cString: cstr!)
        mpv_free(cstr)
        return str
    }

    func getFlag(_ name: String) -> Bool {
        guard mpv != nil else { return false }
        MPVRenderThreadGuard.assertNotOnRenderCallback(name)
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &data)
        return data > 0
    }

    private func setFlag(_ name: String, _ flag: Bool) {
        guard mpv != nil else { return }
        MPVRenderThreadGuard.assertNotOnRenderCallback(name)
        onMpvQueue { ctx in
            var data: Int = flag ? 1 : 0
            mpv_set_property(ctx, name, MPV_FORMAT_FLAG, &data)
        }
    }

    func setStringProperties(_ names: [String], _ values: [String]) {
        guard mpv != nil else { return }
        for (name, value) in zip(names, values) {
            setStringProperty(name, value)
        }
    }

    func setStringProperty(_ name: String, _ value: String) {
        guard mpv != nil else { return }
        MPVRenderThreadGuard.assertNotOnRenderCallback(name)
        onMpvQueue { [self] ctx in
            checkError(mpv_set_property_string(ctx, name, value))
        }
    }

    private func setVideoEqualizer(_ name: String, _ value: Int) {
        guard mpv != nil else { return }
        onMpvQueue { [self] ctx in
            var clamped = Int64(max(-100, min(100, value)))
            checkError(mpv_set_property(ctx, name, MPV_FORMAT_INT64, &clamped))
        }
    }

    private func getInt(_ name: String) -> Int {
        guard mpv != nil else { return 0 }
        MPVRenderThreadGuard.assertNotOnRenderCallback(name)
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_INT64, &data)
        return Int(data)
    }

    private func logCurrentAudioOutput() {
        eventQueue.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, self.mpv != nil else { return }
            let currentAo = self.getString("current-ao") ?? "unknown"
            let channels = self.getString("audio-out-params/hr-channels")
                ?? self.getString("audio-params/hr-channels")
                ?? "unknown"
            let channelCount = self.getInt("audio-out-params/channel-count")
            let codec = self.getString("audio-codec-name") ?? "unknown"
            print("[MPV] Audio output: ao=\(currentAo), channels=\(channels), channelCount=\(channelCount), codec=\(codec)")
        }
    }

    private func checkError(_ status: CInt) {
        if status < 0 {
            print("[MPV] API error: \(String(cString: mpv_error_string(status)))")
        }
    }

    private func sanitizeRequestHeaders(_ headers: [String: String]) -> [String: String] {
        guard !headers.isEmpty else { return [:] }

        var sanitized: [String: String] = [:]
        sanitized.reserveCapacity(headers.count)
        headers.forEach { rawKey, rawValue in
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, !value.isEmpty else { return }
            guard key.caseInsensitiveCompare("Range") != .orderedSame else { return }
            sanitized[key] = value
        }
        return sanitized
    }

    func applyRequestHeaders(_ headers: [String: String]) {
        guard mpv != nil else { return }
        onMpvQueue { [self] ctx in
            if headers.isEmpty {
                checkError(mpv_set_property_string(ctx, "http-header-fields", ""))
                return
            }

            let serialized = headers
                .sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
                .map { key, value in
                    let escapedValue = value
                        .replacingOccurrences(of: "\\", with: "\\\\")
                        .replacingOccurrences(of: ",", with: "\\,")
                    return "\(key): \(escapedValue)"
                }
                .joined(separator: ",")
            checkError(mpv_set_property_string(ctx, "http-header-fields", serialized))
        }
    }

    private func refreshImmersiveSystemUI() {
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        setNeedsStatusBarAppearanceUpdate()

        var currentParent = parent
        while let controller = currentParent {
            controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
            controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
            controller.setNeedsStatusBarAppearanceUpdate()
            if let rootController = controller as? RootComposeViewController {
                rootController.refreshImmersiveSystemUI()
            }
            currentParent = controller.parent
        }
    }
}

// MARK: - Bridge Creator (implements Kotlin protocol)

final class MPVPlayerBridgeCreator: NSObject, NuvioPlayerBridgeCreator {
    func createBridge() -> any NuvioPlayerBridge {
        return MPVPlayerBridgeImpl()
    }
}

// MARK: - Registration (called from Swift app startup)

enum NuvioPlayerRegistration {
    static func register() {
        NuvioPlayerBridgeFactory.shared.registerFactory(creator: MPVPlayerBridgeCreator())
    }
}
