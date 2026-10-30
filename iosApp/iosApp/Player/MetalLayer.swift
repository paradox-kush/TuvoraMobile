// iOS video layer. mpv renders libmpv -> libplacebo -> MoltenVK into this CAMetalLayer.
//
// Picture-in-Picture is upstream's: the patched libmpv (MPVKit nuvio_pip.m, VideoToolbox frame
// handoff) hands decoded VideoToolbox frames to `nuvioEnqueuePiPFrame` on the VO thread, and
// PlayerPictureInPictureController feeds them to an AVSampleBufferDisplayLayer. While PiP consumes
// video (`nuvioPiPConsumesVideo`) mpv does not acquire a drawable at all. The fork's older
// drawable-capture PiP is retired.
//
// LOCAL (fork) additions kept here: render-thread exit guard, drawable-acquisition suspension, and
// the Main-to-render-thread drawableSize hand-over (iOS 26 drawable-pool heap race).

import Foundation
import UIKit
import QuartzCore
import CoreVideo
import Metal

class MetalLayer: CAMetalLayer {
    var onRenderingSuspensionChanged: ((Bool, String) -> Void)?

    // LOCAL: detaches this layer before a render thread the bridge did not stop exits on screen.
    let renderThreadExitGuard = RenderThreadExitGuard()

    private var storedHasAcquiredDrawable = false
    // A drawable size handed over from the MAIN thread (layout during a fullscreen/orientation
    // resize) to be applied on the render thread inside nextDrawable(). See setPendingDrawableSize.
    private var pendingDrawableSize: CGSize?
    private var didLogRenderThread = false
    private let captureLock = NSLock()

    private static let failureThresholdBeforeSuspension = 2
    private static let suspendedRetryInterval: CFTimeInterval = 1.0
    private static let suspendedIdleSleep: TimeInterval = 0.03

    private var isRenderingSuspended = false
    private var isSuspensionLatched = false
    private var consecutiveAcquisitionFailures = 0
    private var lastSuspendedProbeTime: CFTimeInterval = 0

    var isSuspended: Bool {
        captureLock.lock()
        defer { captureLock.unlock() }
        return isRenderingSuspended
    }

    /// True once mpv's render thread has successfully acquired at least one drawable — i.e. the
    /// swapchain exists and rendering is live. Before this flips, the drawable pool is not being
    /// churned by a render thread, so the layer's drawableSize can be established directly from
    /// Main; after it, Main must hand resizes to nextDrawable() (setPendingDrawableSize) to avoid
    /// the iOS-26 drawable-pool heap race (EXC_BREAKPOINT _xzm_corruption_detected).
    var hasAcquiredDrawable: Bool {
        captureLock.lock()
        defer { captureLock.unlock() }
        return storedHasAcquiredDrawable
    }

    override var drawableSize: CGSize {
        get { return super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }

    override var wantsExtendedDynamicRangeContent: Bool {
        get { return super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                DispatchQueue.main.async {
                    super.wantsExtendedDynamicRangeContent = newValue
                }
            }
        }
    }

    func setRenderingSuspended(_ suspended: Bool, reason: String) {
        captureLock.lock()
        let changed = isRenderingSuspended != suspended
        isRenderingSuspended = suspended
        isSuspensionLatched = suspended
        consecutiveAcquisitionFailures = 0
        lastSuspendedProbeTime = suspended ? CACurrentMediaTime() : 0
        captureLock.unlock()

        if changed { onRenderingSuspensionChanged?(suspended, reason) }
    }

    /// Hand over a new drawable size from the MAIN thread (layout during a fullscreen/orientation
    /// resize). We deliberately do NOT write `super.drawableSize` here: mpv's MoltenVK context
    /// (single Vulkan queue — vulkan-queue-count 1, async off) reads drawableSize and recreates its
    /// swapchain on its OWN render thread, and mutating the CAMetalLayer's drawable pool from Main
    /// concurrently corrupts the Metal heap (iOS 26 traps it as EXC_BREAKPOINT
    /// _xzm_corruption_detected). The pending size is applied inside nextDrawable(), on that same
    /// render thread, so the write and mpv's read are serialized onto one thread.
    func setPendingDrawableSize(_ size: CGSize) {
        captureLock.lock()
        pendingDrawableSize = size
        captureLock.unlock()
    }

    /// Applies any pending drawable size. MUST be called with `captureLock` held, from nextDrawable()
    /// (mpv's render thread). Writes `super.drawableSize` directly to bypass the clamping override.
    private func applyPendingDrawableSizeLocked() {
        guard let size = pendingDrawableSize else { return }
        pendingDrawableSize = nil
        if Int(size.width) > 1 && Int(size.height) > 1 {
            super.drawableSize = size
        }
    }

    override func nextDrawable() -> CAMetalDrawable? {
        renderThreadExitGuard.noteRenderThread(layer: self) // LOCAL
        captureLock.lock()
        // Apply a resize handed over from Main on THIS (render) thread, before acquiring a drawable,
        // so the swapchain is only ever resized here. Both suspended and normal paths fall through
        // this point while the lock is held.
        applyPendingDrawableSizeLocked()
        if !didLogRenderThread {
            didLogRenderThread = true
            InAppLogBridge.shared.info(
                tag: "Player/iOS",
                message: "MetalLayer.nextDrawable render thread=\(Thread.current)"
            )
        }
        if isRenderingSuspended {
            let now = CACurrentMediaTime()
            let shouldProbe = now - lastSuspendedProbeTime >= Self.suspendedRetryInterval
            if shouldProbe {
                lastSuspendedProbeTime = now
            }
            captureLock.unlock()

            guard shouldProbe else {
                Thread.sleep(forTimeInterval: Self.suspendedIdleSleep)
                return nil
            }
        } else {
            captureLock.unlock()
        }

        pictureInPictureLock.lock()
        let generation = presentationObserver?.generation
        pictureInPictureLock.unlock()
        // Return the same drawable that MoltenVK would normally acquire.
        let drawable = super.nextDrawable()
        let acquisitionFailed = drawable == nil

        captureLock.lock()
        var didSuspend = false
        var didResume = false
        if acquisitionFailed {
            consecutiveAcquisitionFailures += 1
            if !isRenderingSuspended &&
                consecutiveAcquisitionFailures >= Self.failureThresholdBeforeSuspension {
                isRenderingSuspended = true
                lastSuspendedProbeTime = CACurrentMediaTime()
                didSuspend = true
            }
        } else {
            consecutiveAcquisitionFailures = 0
            storedHasAcquiredDrawable = true
            if isRenderingSuspended && !isSuspensionLatched {
                isRenderingSuspended = false
                lastSuspendedProbeTime = 0
                didResume = true
            }
        }
        captureLock.unlock()

        if didSuspend { onRenderingSuspensionChanged?(true, "drawable-acquisition-stalled") }
        if didResume { onRenderingSuspensionChanged?(false, "drawable-acquired") }

        // Upstream PiP: tell the controller when the first inline drawable is on screen again.
        guard let drawable, let generation else { return drawable }
        pictureInPictureLock.lock()
        let observer = presentationObserver?.generation == generation ? presentationObserver : nil
        if observer != nil { presentationObserver = nil }
        pictureInPictureLock.unlock()
        if let observer {
            let notify: (TimeInterval) -> Void = { [weak self] time in
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.pictureInPictureLock.lock()
                    let current = self.presentationGeneration == generation
                    self.pictureInPictureLock.unlock()
                    if current { observer.callback(time) }
                }
            }
#if targetEnvironment(simulator)
            notify(CACurrentMediaTime())
#else
            drawable.addPresentedHandler { notify($0.presentedTime) }
#endif
        }
        return drawable
    }

    // ---- Upstream PiP surface (see PlayerPictureInPictureController) ----
    weak var pictureInPictureSink: PlayerPictureInPictureController?
    private let pictureInPictureLock = NSLock()
    private var consumesVideo = false
    private var presentationGeneration: UInt = 0
    private var presentationObserver: (generation: UInt, callback: (TimeInterval) -> Void)?

    func observeNextPresentedDrawable(_ callback: @escaping (TimeInterval) -> Void) {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = (presentationGeneration, callback)
        pictureInPictureLock.unlock()
    }

    func cancelPresentationObserver() {
        pictureInPictureLock.lock()
        presentationGeneration &+= 1
        presentationObserver = nil
        pictureInPictureLock.unlock()
    }

    /// Read by libmpv's VO thread (nuvio_pip.m) before every frame.
    @objc var nuvioPiPConsumesVideo: Bool {
        get {
            pictureInPictureLock.lock()
            defer { pictureInPictureLock.unlock() }
            return consumesVideo
        }
        set {
            pictureInPictureLock.lock()
            consumesVideo = newValue
            pictureInPictureLock.unlock()
        }
    }

    /// Called by libmpv's VO thread with a decoded VideoToolbox frame.
    @objc func nuvioEnqueuePiPFrame(_ pixelBuffer: CVPixelBuffer, presentationTime: Double, isPictureInPictureFrame: Bool) {
        pictureInPictureSink?.submit(pixelBuffer, pts: presentationTime, isPictureInPictureFrame: isPictureInPictureFrame)
    }
}
