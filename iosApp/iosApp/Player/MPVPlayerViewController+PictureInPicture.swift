// Ported from the GPL-3.0 fork luqmanfadlli/NuvioMobile-iOS (branch `enhanced`, 2026-08-18), which
// forks the same NuvioMedia/NuvioMobile upstream this repo does — so the licence is compatible.
//
// Why this approach: AVPictureInPictureController only accepts an AVPlayerLayer or an
// AVSampleBufferDisplayLayer, and we render libmpv -> libplacebo -> MoltenVK into a CAMetalLayer.
// Rather than change the renderer (which would cost gpu-next's HDR tone mapping, EDR, deband and
// interpolation), this taps the RENDERER'S OUTPUT: MetalLayer overrides nextDrawable(), the
// presented drawable's MTLTexture is GPU-blitted into a CVPixelBuffer, wrapped in a CMSampleBuffer
// and enqueued into an AVSampleBufferDisplayLayer that PiP is bound to. mpv is untouched.
//
// KEEP THESE FILES CLOSE TO THE UPSTREAM FORK so they can be re-synced with a clean diff. Local
// adaptations are confined to InAppLogBridge.swift (our logging shim) and the bridge wiring.

import UIKit
import AVFoundation
import CoreMedia
import Libmpv
import ComposeApp

extension MPVPlayerViewController {
    func installExperimentalPictureInPictureCaptureIfNeeded() {
        guard experimentalSinglePrimaryPictureInPictureEnabled else { return }

        InAppLogBridge.shared.info(
            tag: "PiP/iOS",
            message: "PiP frame capture enabled; inline video keeps gpu-next/MoltenVK and EDR"
        )
        let renderSurface = MPVPictureInPictureFrameCapture(
            displayLayer: sampleBufferDisplayView.displayLayer,
            metalLayer: metalLayer,
            videoSizeProvider: { [weak self] in self?.currentRenderVideoSize() ?? .zero },
            playbackPositionProvider: { [weak self] in self?.interpolatedPositionSeconds ?? 0 },
            videoFrameRateProvider: { [weak self] in self?.currentRenderFrameRate ?? 30.0 },
            playbackRateProvider: { [weak self] in Double(self?.currentSpeed ?? 1.0) }
        )
        primaryRenderSurface = renderSurface
        sampleBufferDisplayView.alpha = 1.0
        view.insertSubview(sampleBufferDisplayView, at: 0)
        // ---- LOCAL (differs from the upstream fork) ----
        // The fork keeps the system's automatic start OFF and starts PiP from its own bottom-edge
        // pan recognizer. On device that recognizer never sees the Home swipe (the system gesture
        // and the Compose overlay above this view both take it first), so PiP was hit-or-miss:
        // most minimises fell through to "background-without-pip". Let AVKit start PiP when the
        // app backgrounds while playing — Apple's supported path — and keep the recognizer only as
        // a secondary trigger. The source is prewarmed on playback start so it is possible by then.
        // Enabled from the 250ms state poll, only while actually playing (updateState).
        automaticPictureInPictureStartOffered = false
        sampleBufferDisplayView.pictureInPictureController?.setAutomaticStartEnabled(false)
    }

    func layoutExperimentalPictureInPictureSurfaces(in bounds: CGRect) {
        guard experimentalSinglePrimaryPictureInPictureEnabled else { return }
        sampleBufferDisplayView.frame = CGRect(origin: .zero, size: bounds.size)
        primaryRenderSurface?.requestRenderBurst(reason: "layout", count: 2)
    }

    func setupNotifications() {
        NotificationCenter.default.addObserver(self, selector: #selector(enterBackground),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(enterForeground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(cancelAutomaticPictureInPictureIfForegrounded),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    @objc func handleAutomaticPiPHomeSwipe(_ recognizer: UIPanGestureRecognizer) {
        guard experimentalSinglePrimaryPictureInPictureEnabled else { return }
        guard UIApplication.shared.applicationState == .active else { return }

        switch recognizer.state {
        case .began:
            let location = recognizer.location(in: view)
            let activationHeight = max(28.0, view.safeAreaInsets.bottom + 10.0)
            automaticPiPHomeSwipeCandidate = location.y >= view.bounds.maxY - activationHeight

        case .changed:
            guard automaticPiPHomeSwipeCandidate else { return }
            let translation = recognizer.translation(in: view)
            guard translation.y <= -18.0, abs(translation.y) > abs(translation.x) * 1.15 else { return }
            automaticPiPHomeSwipeCandidate = false
            startAutomaticPictureInPictureFromHomeGesture()

        case .ended, .cancelled, .failed:
            automaticPiPHomeSwipeCandidate = false
            scheduleAutomaticPiPCancellationIfHomeGestureWasAborted()

        default:
            break
        }
    }

    func scheduleAutomaticPiPCancellationIfHomeGestureWasAborted() {
        guard automaticPictureInPictureStartArmed else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { [weak self] in
            guard let self else { return }
            guard UIApplication.shared.applicationState == .active else { return }
            guard self.automaticPictureInPictureStartArmed else { return }
            InAppLogBridge.shared.debug(
                tag: "PiP/iOS",
                message: "Cancelling gesture-started automatic PiP because the Home transition was aborted"
            )
            self.sampleBufferDisplayView.pictureInPictureController?.stop(source: "automatic-home-gesture-aborted")
            self.cancelAutomaticPictureInPictureStart(stopPriming: false)
            self.primaryRenderSurface?.requestRenderBurst(reason: "auto-pip-gesture-aborted", count: 3)
        }
    }

    func startAutomaticPictureInPictureFromHomeGesture() {
        guard mpv != nil, isViewLoaded, view.window != nil else { return }
        guard !isPictureInPictureActive(), !isPictureInPictureStarting else { return }
        guard !cachedPaused, !cachedEofReached else { return }
        guard let controller = sampleBufferDisplayView.pictureInPictureController else { return }

        automaticPictureInPictureStartArmed = true
        isPictureInPictureStarting = true
        preservePlaybackDuringPictureInPictureStart = !cachedPaused && !cachedEofReached
        ignorePictureInPicturePauseCallbacksUntil = 0
        beginAutomaticPictureInPictureBackgroundTask()
        scheduleAutomaticPictureInPictureTimeout()
        controller.invalidatePlaybackState()

        if automaticPictureInPicturePrepared, controller.isPossible {
            let frameAge = automaticPictureInPicturePreparedAt > 0
                ? CACurrentMediaTime() - automaticPictureInPicturePreparedAt
                : .infinity
            InAppLogBridge.shared.info(
                tag: "PiP/iOS",
                message: "Requesting automatic PiP during bottom Home gesture prepared=true frameAgeMs=\(frameAge.isFinite ? Int(frameAge * 1000) : -1)"
            )
            controller.start(source: "automatic-home")
            return
        }

        InAppLogBridge.shared.info(
            tag: "PiP/iOS",
            message: "Bottom Home gesture detected before PiP source was prepared; refreshing frame while app is active"
        )
        prepareFreshAutomaticPictureInPictureFrame(reason: "home-gesture")
    }

    func prewarmAutomaticPictureInPictureSource(reason: String) {
        guard experimentalSinglePrimaryPictureInPictureEnabled else { return }
        guard UIApplication.shared.applicationState == .active else { return }
        guard !isPictureInPictureActive(), !isPictureInPictureStarting else { return }
        guard !cachedPaused, !cachedEofReached else { return }
        guard let primaryRenderSurface else { return }
        guard !automaticPictureInPictureStartPreparationInFlight else { return }

        if automaticPictureInPicturePrepared, automaticPictureInPicturePreparedAt > 0 {
            let frameAge = CACurrentMediaTime() - automaticPictureInPicturePreparedAt
            if frameAge < 1.0 { return }
        }

        automaticPictureInPicturePrepared = false
        automaticPictureInPicturePreparedAt = 0
        automaticPictureInPictureStartPreparationInFlight = true
        primaryRenderSurface.prepareAutomaticPictureInPicturePreview { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.automaticPictureInPictureStartPreparationInFlight = false
                guard !self.isPictureInPictureActive(), !self.isPictureInPictureStarting else { return }
                self.automaticPictureInPicturePrepared = true
                self.automaticPictureInPicturePreparedAt = CACurrentMediaTime()
                self.sampleBufferDisplayView.pictureInPictureController?.invalidatePlaybackState()
                InAppLogBridge.shared.info(
                    tag: "PiP/iOS",
                    message: "Automatic PiP source prewarmed reason=\(reason) possible=\(self.sampleBufferDisplayView.pictureInPictureController?.isPossible ?? false)"
                )
            }
        }
    }

    @objc func cancelAutomaticPictureInPictureIfForegrounded() {
        retryDeviceLossRecoveryNow()
        if !isAwaitingDeviceLossRecovery {
            metalLayer.setRenderingSuspended(false, reason: "did-become-active")
        }
        defer {
            if !isPictureInPictureActive(), !isPictureInPictureStarting {
                clearPictureInPictureStartPlaybackPreservation()
                primaryRenderSurface?.requestRenderBurst(reason: "did-become-active", count: 4)
            }
        }
        guard automaticPictureInPictureStartArmed else { return }
        guard !isPictureInPictureActive() else { return }

        let shouldResumeInlinePlayback = preservePlaybackDuringPictureInPictureStart
        InAppLogBridge.shared.debug(
            tag: "PiP/iOS",
            message: "Automatic PiP cancelled because app remained or returned to foreground"
        )
        sampleBufferDisplayView.pictureInPictureController?.stop(source: "automatic-foreground-return")
        cancelAutomaticPictureInPictureStart(stopPriming: false)
        restoreVideoTrackAfterBackgroundIfNeeded(reloadDecoder: false)
        if shouldResumeInlinePlayback {
            playPlayback()
        }
        primaryRenderSurface?.requestRenderBurst(reason: "auto-pip-cancel", count: 4)
    }

    @objc func enterBackground() {
        metalLayer.releasePendingDrawable()
        primaryRenderSurface?.setBackgrounded(true)
        guard mpv != nil else { return }
        if experimentalSinglePrimaryPictureInPictureEnabled {
            if isPictureInPictureActive() {
                InAppLogBridge.shared.info(
                    tag: "PiP/iOS",
                    message: "Entering background while PiP is active; keeping primary libmpv pipeline alive"
                )
                return
            }
            if automaticPictureInPictureStartArmed {
                let transitionAccepted = sampleBufferDisplayView.pictureInPictureController?.hasStartedTransition ?? false
                if transitionAccepted {
                    InAppLogBridge.shared.info(
                        tag: "PiP/iOS",
                        message: "Entering background while explicit automatic PiP transition is pending"
                    )
                    return
                }
                InAppLogBridge.shared.warn(
                    tag: "PiP/iOS",
                    message: "Automatic PiP start was not accepted before background; suspending video track to protect VideoToolbox"
                )
                sampleBufferDisplayView.pictureInPictureController?.stop(source: "automatic-background-rejected")
                cancelAutomaticPictureInPictureStart(stopPriming: true)
                suspendVideoTrackForBackground(reason: "automatic-start-rejected")
                return
            }
            if isPictureInPictureStarting {
                InAppLogBridge.shared.info(
                    tag: "PiP/iOS",
                    message: "Entering background while manually-started PiP is pending; keeping primary libmpv pipeline alive"
                )
                return
            }
            // No grace period here: on device AVKit's automatic start reports willStart/didStart
            // BEFORE didEnterBackground, so a start that is coming is already visible above. Waiting
            // instead kept decoding and rendering in the background with no PiP, which iOS refuses
            // (VideoToolbox session -12903, GPU work denied) and left the app stuck on return.
            suspendVideoTrackForBackground(reason: "background-without-pip")
            return
        }
        pausePlayback()
        detachVideoLayerForVoTeardown(reason: "background")
        setStringProperty("vid", "no")
    }

    @objc func enterForeground() {
        retryDeviceLossRecoveryNow()
        if !isAwaitingDeviceLossRecovery {
            metalLayer.setRenderingSuspended(false, reason: "enter-foreground")
        }
        primaryRenderSurface?.setBackgrounded(false)
        guard mpv != nil else { return }
        if experimentalSinglePrimaryPictureInPictureEnabled {
            if isPictureInPictureActive() || isPictureInPictureStarting { return }
            restoreVideoTrackAfterBackgroundIfNeeded()
            primaryRenderSurface?.requestRenderBurst(reason: "foreground", count: 4)
            playPlayback()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                self?.prewarmAutomaticPictureInPictureSource(reason: "foreground")
            }
            return
        }
        reattachVideoLayerAfterVoTeardown(reason: "foreground")
        setStringProperty("vid", "auto")
        // ---- LOCAL (not in the upstream fork) ----
        // A live stream paused in the background goes stale (dead socket, old buffer): unpausing
        // plays the leftover buffer then stalls. Reload to rejoin the live edge. This is our
        // nuvio-live-resume fix; the fork has no live-TV path and drops straight into playPlayback.
        // Main thread: the observed path, not a synchronous mpv read (see MPVPropertyShadow).
        if isLiveStream, let path = propertyShadow.snapshot.path {
            clearPlaybackError()
            applyRequestHeaders(activeRequestHeaders)
            command("loadfile", args: [path, "replace"])
        }
        // ---- end LOCAL ----
        playPlayback()
    }

    func isPictureInPictureSupported() -> Bool {
        experimentalSinglePrimaryPictureInPictureEnabled && (sampleBufferDisplayView.pictureInPictureController?.isSupported ?? false)
    }

    func isPictureInPictureActive() -> Bool {
        experimentalSinglePrimaryPictureInPictureEnabled && (sampleBufferDisplayView.pictureInPictureController?.isActive ?? false)
    }

    func startPictureInPicture() {
        cancelAutomaticPictureInPictureStart(stopPriming: false)
        guard experimentalSinglePrimaryPictureInPictureEnabled else {
            InAppLogBridge.shared.warn(tag: "PiP/iOS", message: "PiP ignored because experimental single-primary renderer is disabled")
            return
        }
        guard !isPictureInPictureActive(), !isPictureInPictureStarting else { return }
        guard let primaryRenderSurface else {
            InAppLogBridge.shared.warn(tag: "PiP/iOS", message: "PiP ignored because primary render surface is unavailable")
            return
        }

        isPictureInPictureStarting = true
        preservePlaybackDuringPictureInPictureStart = !cachedPaused && !cachedEofReached
        ignorePictureInPicturePauseCallbacksUntil = 0
        schedulePictureInPictureStartTimeout()
        primaryRenderSurface.startPictureInPicturePriming { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                guard self.isPictureInPictureStarting else { return }
                self.sampleBufferDisplayView.pictureInPictureController?.start(source: "manual-button")
            }
        }
    }

    func stopPictureInPicture(source: String) {
        guard experimentalSinglePrimaryPictureInPictureEnabled else { return }
        cancelAutomaticPictureInPictureStart(stopPriming: false)
        cancelPictureInPictureStartTimeout()
        cancelPictureInPictureRestoreResume()
        clearPictureInPictureStartPlaybackPreservation()
        isPictureInPictureStarting = false
        primaryRenderSurface?.stopPictureInPictureRendering(removingDisplayedImage: true)
        sampleBufferDisplayView.pictureInPictureController?.stop(source: source)
        sampleBufferDisplayView.flush()
    }

    func schedulePictureInPictureStartTimeout() {
        pipStartTimeoutWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.isPictureInPictureStarting else { return }
            InAppLogBridge.shared.warn(tag: "PiP/iOS", message: "PiP start timed out before activation")
            self.stopPictureInPicture(source: "start-timeout")
        }
        pipStartTimeoutWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 10.0, execute: workItem)
    }

    func cancelPictureInPictureStartTimeout() {
        pipStartTimeoutWorkItem?.cancel()
        pipStartTimeoutWorkItem = nil
    }

    func prepareFreshAutomaticPictureInPictureFrame(reason: String) {
        guard automaticPictureInPictureStartArmed else { return }
        guard !automaticPictureInPictureStartPreparationInFlight else { return }
        guard let primaryRenderSurface else { return }

        automaticPictureInPictureStartRetryWorkItem?.cancel()
        automaticPictureInPictureStartRetryWorkItem = nil
        automaticPictureInPicturePrepared = false
        automaticPictureInPicturePreparedAt = 0
        automaticPictureInPictureStartPreparationInFlight = true

        InAppLogBridge.shared.info(
            tag: "PiP/iOS",
            message: "Refreshing automatic PiP frame immediately before Home transition reason=\(reason)"
        )

        primaryRenderSurface.startPictureInPicturePriming { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                guard self.automaticPictureInPictureStartArmed else {
                    self.automaticPictureInPictureStartPreparationInFlight = false
                    return
                }

                self.automaticPictureInPictureStartPreparationInFlight = false
                self.automaticPictureInPicturePrepared = true
                self.automaticPictureInPicturePreparedAt = CACurrentMediaTime()
                self.sampleBufferDisplayView.pictureInPictureController?.invalidatePlaybackState()

                self.requestAutomaticPictureInPictureStartWhenPossible(reason: reason, attempt: 0)
            }
        }
    }

    func requestAutomaticPictureInPictureStartWhenPossible(reason: String, attempt: Int) {
        guard automaticPictureInPictureStartArmed else { return }
        guard !isPictureInPictureActive() else { return }
        guard let controller = sampleBufferDisplayView.pictureInPictureController else { return }

        let frameAge = automaticPictureInPicturePreparedAt > 0
            ? CACurrentMediaTime() - automaticPictureInPicturePreparedAt
            : .infinity

        if automaticPictureInPicturePrepared, controller.isPossible, frameAge <= 1.0 {
            automaticPictureInPictureStartRetryWorkItem?.cancel()
            automaticPictureInPictureStartRetryWorkItem = nil
            InAppLogBridge.shared.info(
                tag: "PiP/iOS",
                message: "Requesting explicit automatic PiP start reason=\(reason) freshFrameAgeMs=\(Int(frameAge * 1000))"
            )
            controller.start(source: "automatic-home")
            return
        }

        guard attempt < 20 else {
            InAppLogBridge.shared.warn(
                tag: "PiP/iOS",
                message: "Automatic PiP source did not become ready after fresh priming reason=\(reason) prepared=\(automaticPictureInPicturePrepared) possible=\(controller.isPossible) frameAgeMs=\(frameAge.isFinite ? Int(frameAge * 1000) : -1)"
            )
            return
        }

        let workItem = DispatchWorkItem { [weak self] in
            self?.requestAutomaticPictureInPictureStartWhenPossible(reason: reason, attempt: attempt + 1)
        }
        automaticPictureInPictureStartRetryWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.025, execute: workItem)
    }

    func beginAutomaticPictureInPictureBackgroundTask() {
        guard automaticPictureInPictureBackgroundTask == .invalid else { return }
        automaticPictureInPictureBackgroundTask = UIApplication.shared.beginBackgroundTask(
            withName: "NuvioAutoPictureInPicture"
        ) { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                if !self.isPictureInPictureActive() {
                    self.sampleBufferDisplayView.pictureInPictureController?.stop(source: "automatic-background-task-expired")
                    self.cancelAutomaticPictureInPictureStart(stopPriming: true)
                    self.suspendVideoTrackForBackground(reason: "automatic-background-task-expired")
                }
            }
        }
    }

    func endAutomaticPictureInPictureBackgroundTask() {
        guard automaticPictureInPictureBackgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(automaticPictureInPictureBackgroundTask)
        automaticPictureInPictureBackgroundTask = .invalid
    }

    func suspendVideoTrackForBackground(reason: String) {
        guard experimentalSinglePrimaryPictureInPictureEnabled else { return }
        guard UIApplication.shared.applicationState == .background else {
            InAppLogBridge.shared.debug(
                tag: "PiP/iOS",
                message: "Ignoring video-track suspension while app is not background reason=\(reason) state=\(String(describing: UIApplication.shared.applicationState))"
            )
            return
        }
        pausePlayback()
        guard !videoTrackSuspendedForBackground else { return }
        detachVideoLayerForVoTeardown(reason: reason)
        setStringProperty("vid", "no")
        videoTrackSuspendedForBackground = true
        InAppLogBridge.shared.info(
            tag: "PiP/iOS",
            message: "Primary video track suspended for background reason=\(reason)"
        )
    }

    func restoreVideoTrackAfterBackgroundIfNeeded(reloadDecoder: Bool = true) {
        guard videoTrackSuspendedForBackground else { return }
        videoTrackSuspendedForBackground = false
        reattachVideoLayerAfterVoTeardown(reason: "restore-after-background")
        setStringProperty("vid", "auto")
        if reloadDecoder {
            command("video-reload", checkForErrors: false)
        }
        InAppLogBridge.shared.info(
            tag: "PiP/iOS",
            message: "Primary video track restored after failed automatic PiP transition reloadDecoder=\(reloadDecoder)"
        )
    }

    func scheduleAutomaticPictureInPictureTimeout() {
        automaticPictureInPictureTimeoutWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.automaticPictureInPictureStartArmed else { return }
            guard !self.isPictureInPictureActive() else {
                self.cancelAutomaticPictureInPictureStart(stopPriming: false)
                return
            }

            InAppLogBridge.shared.warn(
                tag: "PiP/iOS",
                message: "Automatic PiP did not activate before timeout prepared=\(self.automaticPictureInPicturePrepared) possible=\(self.sampleBufferDisplayView.pictureInPictureController?.isPossible ?? false)"
            )
            self.sampleBufferDisplayView.pictureInPictureController?.stop(source: "automatic-start-timeout")
            self.cancelAutomaticPictureInPictureStart(stopPriming: true)
            if UIApplication.shared.applicationState == .background {
                self.suspendVideoTrackForBackground(reason: "automatic-start-timeout")
            }
        }
        automaticPictureInPictureTimeoutWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0, execute: workItem)
    }

    func cancelAutomaticPictureInPictureStart(stopPriming: Bool) {
        automaticPictureInPictureTimeoutWorkItem?.cancel()
        automaticPictureInPictureTimeoutWorkItem = nil
        automaticPictureInPictureStartRetryWorkItem?.cancel()
        automaticPictureInPictureStartRetryWorkItem = nil
        automaticPictureInPictureStartArmed = false
        automaticPictureInPictureStartPreparationInFlight = false
        endAutomaticPictureInPictureBackgroundTask()
        if !isPictureInPictureActive() {
            isPictureInPictureStarting = false
            clearPictureInPictureStartPlaybackPreservation()
        }
        if stopPriming {
            automaticPictureInPicturePrepared = false
            automaticPictureInPicturePreparedAt = 0
            primaryRenderSurface?.stopPictureInPictureRendering(removingDisplayedImage: true)
            sampleBufferDisplayView.flush()
        }
    }

    func preservePlaybackAfterPictureInPictureDidStart() {
        guard preservePlaybackDuringPictureInPictureStart else { return }

        ignorePictureInPicturePauseCallbacksUntil = CACurrentMediaTime() + 0.45
        playPlayback()
        sampleBufferDisplayView.pictureInPictureController?.invalidatePlaybackState()

        InAppLogBridge.shared.debug(
            tag: "PiP/iOS",
            message: "Preserving playback through PiP start transition graceMs=450"
        )

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self else { return }
            guard CACurrentMediaTime() >= self.ignorePictureInPicturePauseCallbacksUntil else { return }
            self.preservePlaybackDuringPictureInPictureStart = false
            self.ignorePictureInPicturePauseCallbacksUntil = 0
        }
    }

    func clearPictureInPictureStartPlaybackPreservation() {
        preservePlaybackDuringPictureInPictureStart = false
        ignorePictureInPicturePauseCallbacksUntil = 0
    }

    func cancelPictureInPictureRestoreResume() {
        pipRestoreResumeWorkItem?.cancel()
        pipRestoreResumeWorkItem = nil
        resumePlaybackAfterPictureInPictureRestore = false
    }

    func schedulePictureInPictureRestoreResume() {
        pipRestoreResumeWorkItem?.cancel()

        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.resumePlaybackAfterPictureInPictureRestore else { return }

            self.playPlayback()
            self.sampleBufferDisplayView.pictureInPictureController?.invalidatePlaybackState()

            let position = self.interpolatedPositionSeconds
            InAppLogBridge.shared.info(
                tag: "PiP/iOS",
                message: "Resumed primary playback after PiP restore position=\(String(format: "%.2f", position))"
            )

            self.resumePlaybackAfterPictureInPictureRestore = false
            self.pipRestoreResumeWorkItem = nil
        }

        pipRestoreResumeWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: workItem)
    }

    func currentRenderVideoSize() -> CGSize {
        let width = currentVideoWidth
        let height = currentVideoHeight
        if width > 0, height > 0 {
            return CGSize(width: width, height: height)
        }
        return .zero
    }

    func configureAudioSessionForPlayback() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .moviePlayback, options: [])
            try session.setActive(true)
        } catch {
            InAppLogBridge.shared.warn(tag: "MPV/iOS", message: "Failed to configure AVAudioSession for playback: \(error.localizedDescription)")
        }
    }

    func setExperimentalSinglePrimaryPictureInPictureEnabled(_ enabled: Bool) {
        guard enabled != experimentalSinglePrimaryPictureInPictureEnabled else { return }
        // ---- LOCAL (differs from the upstream fork) ----
        // The fork passes this through MPVPlayerViewController.init(...) and so only warns here.
        // Our view controller is constructed with a plain init() and the flag assigned immediately
        // after, before viewDidLoad, so this setter has to actually store it — otherwise the
        // capture is never installed and PiP silently does nothing.
        experimentalSinglePrimaryPictureInPictureEnabled = enabled
        guard isViewLoaded else { return }
        // The Compose factory builds this view controller during composition, but the bridge is only
        // registered (and the setting replayed) from a DisposableEffect that runs after it — so on a
        // cold open viewDidLoad has ALREADY skipped the install by the time we get here. Install now
        // instead of waiting for the next playback session.
        if enabled, primaryRenderSurface == nil {
            installExperimentalPictureInPictureCaptureIfNeeded()
            layoutExperimentalPictureInPictureSurfaces(in: view.bounds)
            view.addGestureRecognizer(automaticPiPHomeSwipeRecognizer)
            return
        }
        // ---- end LOCAL ----
        InAppLogBridge.shared.warn(
            tag: "PiP/iOS",
            message: "Picture-in-Picture setting changed while the player is alive. Restart playback to apply this renderer change."
        )
    }
}

extension MPVPlayerViewController: UIGestureRecognizerDelegate {
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        gestureRecognizer === automaticPiPHomeSwipeRecognizer
            || otherGestureRecognizer === automaticPiPHomeSwipeRecognizer
    }
}

// MARK: - Picture in Picture delegate

extension MPVPlayerViewController: PictureInPictureControllerDelegate {
    func pictureInPictureControllerWillStart(_ controller: PictureInPictureController) {
        cancelPictureInPictureRestoreResume()
        isPictureInPictureStarting = true
    }

    func pictureInPictureControllerDidStart(_ controller: PictureInPictureController) {
        SystemUI.shared.setPictureInPicturePlaceholder(self, visible: true)
        cancelPictureInPictureStartTimeout()
        cancelAutomaticPictureInPictureStart(stopPriming: false)
        isPictureInPictureStarting = false
        primaryRenderSurface?.markPictureInPictureActive(true)
        preservePlaybackAfterPictureInPictureDidStart()
        sampleBufferDisplayView.pictureInPictureController?.invalidatePlaybackState()
    }

    func pictureInPictureController(_ controller: PictureInPictureController, failedToStart error: Error) {
        SystemUI.shared.setPictureInPicturePlaceholder(self, visible: false)
        let shouldResumeInlinePlayback = preservePlaybackDuringPictureInPictureStart
        cancelPictureInPictureStartTimeout()
        cancelAutomaticPictureInPictureStart(stopPriming: false)
        cancelPictureInPictureRestoreResume()
        clearPictureInPictureStartPlaybackPreservation()
        isPictureInPictureStarting = false
        automaticPictureInPicturePrepared = false
        automaticPictureInPicturePreparedAt = 0
        primaryRenderSurface?.markPictureInPictureActive(false)
        sampleBufferDisplayView.flush()

        let applicationState = UIApplication.shared.applicationState
        if applicationState == .background {
            suspendVideoTrackForBackground(reason: "pip-start-failed")
            return
        }

        if shouldResumeInlinePlayback {
            preservePlaybackDuringPictureInPictureStart = true
            ignorePictureInPicturePauseCallbacksUntil = CACurrentMediaTime() + 1.0
            playPlayback()
            controller.invalidatePlaybackState()
        }
        primaryRenderSurface?.requestRenderBurst(reason: "pip-start-failed-inline", count: 4)
        InAppLogBridge.shared.debug(
            tag: "PiP/iOS",
            message: "PiP start failed while app remained foreground/inactive; preserving inline playback state=\(String(describing: applicationState))"
        )
    }

    func pictureInPictureControllerWillStop(_ controller: PictureInPictureController) {
        primaryRenderSurface?.markPictureInPictureActive(false)
    }

    func pictureInPictureControllerDidStop(_ controller: PictureInPictureController) {
        SystemUI.shared.setPictureInPicturePlaceholder(self, visible: false)
        cancelPictureInPictureStartTimeout()
        cancelAutomaticPictureInPictureStart(stopPriming: false)
        clearPictureInPictureStartPlaybackPreservation()
        isPictureInPictureStarting = false
        primaryRenderSurface?.markPictureInPictureActive(false)
        sampleBufferDisplayView.flush()

        if resumePlaybackAfterPictureInPictureRestore {
            schedulePictureInPictureRestoreResume()
        }
        automaticPictureInPicturePrepared = false
        automaticPictureInPicturePreparedAt = 0
        if UIApplication.shared.applicationState == .active, !cachedPaused, !cachedEofReached {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                self?.prewarmAutomaticPictureInPictureSource(reason: "pip-stopped")
            }
        }
    }

    func pictureInPictureControllerRestoreUI(_ controller: PictureInPictureController, completion: @escaping (Bool) -> Void) {
        resumePlaybackAfterPictureInPictureRestore = true

        let position = interpolatedPositionSeconds
        InAppLogBridge.shared.info(
            tag: "PiP/iOS",
            message: "Restoring player UI from PiP position=\(String(format: "%.2f", position)); playback will resume"
        )

        playPlayback()
        completion(true)
    }

    func pictureInPictureControllerPlay(_ controller: PictureInPictureController) {
        playPlayback()
    }

    func pictureInPictureControllerPause(_ controller: PictureInPictureController) {
        if resumePlaybackAfterPictureInPictureRestore {
            InAppLogBridge.shared.debug(
                tag: "PiP/iOS",
                message: "Ignoring transient pause callback during PiP restore"
            )
            return
        }

        let withinStartGrace = CACurrentMediaTime() < ignorePictureInPicturePauseCallbacksUntil
        if preservePlaybackDuringPictureInPictureStart,
           isPictureInPictureStarting || controller.isStartPending || withinStartGrace {
            InAppLogBridge.shared.debug(
                tag: "PiP/iOS",
                message: "Ignoring transient pause callback during PiP start pending=\(controller.isStartPending) active=\(controller.isActive) grace=\(withinStartGrace)"
            )
            playPlayback()
            controller.invalidatePlaybackState()
            return
        }

        clearPictureInPictureStartPlaybackPreservation()
        pausePlayback()
    }

    func pictureInPictureController(_ controller: PictureInPictureController, skipBy interval: CMTime) {
        let seconds = CMTimeGetSeconds(interval)
        guard seconds.isFinite else { return }
        seekByMs(Int64(seconds * 1000.0))
    }

    func pictureInPictureControllerIsPlaying(_ controller: PictureInPictureController) -> Bool {
        guard mpv != nil else { return false }
        return !cachedPaused && !cachedEofReached
    }

    func pictureInPictureControllerDuration(_ controller: PictureInPictureController) -> Double {
        Double(durationMs) / 1000.0
    }

    func pictureInPictureControllerCurrentTime(_ controller: PictureInPictureController) -> Double {
        Double(positionMs) / 1000.0
    }
}
