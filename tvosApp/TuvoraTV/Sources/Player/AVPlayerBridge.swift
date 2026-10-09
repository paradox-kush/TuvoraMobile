import AVFoundation
import AVKit
import Foundation
import MediaPlayer
import TuvoraCore
import UIKit

/// Apple's player as a NuvioPlayerBridge: the AVPlayer lane of PlaybackLanePolicy (ExoPlayer's role on
/// Android TV). Chosen only for VOD AVPlayer is known to play, which is what gives Dolby Vision, Atmos and
/// Match Content. Any failure surfaces through getErrorMessage(), and TvPlayerSession escalates to libmpv.
/// mpv-only knobs (tone mapping, deband, subtitle styling, external subtitle files) are ignored here:
/// streams needing them are routed to libmpv by the policy.
final class AVPlayerBridgeImpl: NSObject, NuvioPlayerBridge {
    private let player = AVPlayer()
    private let makeAsset: (URL, [String: Any]) -> AVURLAsset

    init(makeAsset: @escaping (URL, [String: Any]) -> AVURLAsset = { AVURLAsset(url: $0, options: $1) }) {
        self.makeAsset = makeAsset
        super.init()
    }
    private var requestOptions: [String: Any] = [:]
    private lazy var controller = AVPlayerLaneViewController(player: player)
    private var statusObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var failedObserver: NSObjectProtocol?
    private var ended = false
    private var errorMessage = ""
    private var isLive = false

    func createPlayerViewController() -> UIViewController { controller }

    func loadFile(url: String) {
        loadFileWithAudio(videoUrl: url, audioUrl: nil, headersJson: nil, subtitlesJson: nil, startOption: nil)
    }

    func loadFileWithAudio(videoUrl: String, audioUrl: String?, headersJson: String?, subtitlesJson: String?, startOption: String?) {
        guard let url = URL(string: videoUrl) else { errorMessage = "Invalid address"; return }
        var options: [String: Any] = [:]
        if let data = headersJson?.data(using: .utf8),
           let headers = try? JSONSerialization.jsonObject(with: data) as? [String: String], !headers.isEmpty {
            // Not public API, but the long-standing way to pass request headers to AVURLAsset.
            options["AVURLAssetHTTPHeaderFieldsKey"] = headers
        }
        requestOptions = options
        let item = AVPlayerItem(asset: makeAsset(url, requestOptions))
        ended = false
        errorMessage = ""
        observe(item)
        player.replaceCurrentItem(with: item)
        if let seconds = Self.startSeconds(startOption), seconds > 0 {
            player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        player.play()
    }

    private func observe(_ item: AVPlayerItem) {
        itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            if item.status == .failed {
                self?.errorMessage = item.error?.localizedDescription ?? "Playback failed"
            }
        }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let failedObserver { NotificationCenter.default.removeObserver(failedObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.ended = true
        }
        failedObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.errorMessage = error?.localizedDescription ?? "Playback stopped"
        }
    }

    /// Parses the mpv `start=<seconds>` load option the session passes for resume.
    static func startSeconds(_ option: String?) -> Double? {
        guard let option, option.hasPrefix("start=") else { return nil }
        return Double(option.dropFirst("start=".count))
    }

    func play() { player.play() }
    func pause() { player.pause() }
    func seekTo(positionMs: Int64) {
        ended = false
        player.seek(to: CMTime(value: positionMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    func seekBy(offsetMs: Int64) { seekTo(positionMs: max(0, getPositionMs() + offsetMs)) }
    func retry() {
        guard let asset = player.currentItem?.asset as? AVURLAsset else { return }
        let position = getPositionMs()
        let item = AVPlayerItem(asset: makeAsset(asset.url, requestOptions))
        errorMessage = ""
        observe(item)
        player.replaceCurrentItem(with: item)
        if position > 0 { seekTo(positionMs: position) }
        player.play()
    }
    func restartFromBeginning() { seekTo(positionMs: 0); player.play() }
    func setIsLiveStream(isLive: Bool) { self.isLive = isLive }

    func isPictureInPictureSupported() -> Bool { false }
    func startPictureInPicture() {}
    func stopPictureInPicture() {}
    func isPictureInPictureActive() -> Bool { false }
    func setPictureInPictureEnabled(enabled: Bool) {}

    func updateNowPlayingMetadata(title: String, subtitle: String?, artworkUrl: String?) {
        var info: [String: Any] = [MPMediaItemPropertyTitle: title]
        if let subtitle { info[MPMediaItemPropertyArtist] = subtitle }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
    func clearNowPlayingInfo() { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil }

    func configureVideoOutput(hardwareDecoder: String, targetColorspaceHint: Bool, toneMapping: String, hdrComputePeak: Bool,
                              targetPrimaries: String, targetTransfer: String, extendedDynamicRange: Bool, deband: Bool,
                              interpolation: Bool, brightness: Int32, contrast: Int32, saturation: Int32, gamma: Int32) {}
    func configureAudioOutput(audioOutput: String) {}
    func setPlaybackSpeed(speed: Float) { player.rate = speed }
    func setMuted(muted: Bool) { player.isMuted = muted }
    func setResizeMode(mode: Int32) { controller.setResizeMode(Int(mode)) }
    func syncVideoSurfaceLayout(width: Double, height: Double) {}

    // Tracks: AVMediaSelectionGroup options, addressed by index.
    private func options(_ characteristic: AVMediaCharacteristic) -> (AVMediaSelectionGroup, [AVMediaSelectionOption])? {
        guard let item = player.currentItem,
              let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: characteristic) else { return nil }
        return (group, group.options)
    }
    private func label(_ option: AVMediaSelectionOption) -> String { option.displayName }
    private func lang(_ option: AVMediaSelectionOption) -> String { option.extendedLanguageTag ?? option.locale?.identifier ?? "" }
    private func selected(_ characteristic: AVMediaCharacteristic, _ at: Int32) -> Bool {
        guard let (group, opts) = options(characteristic), Int(at) < opts.count else { return false }
        return player.currentItem?.currentMediaSelection.selectedMediaOption(in: group) == opts[Int(at)]
    }

    func getAudioTrackCount() -> Int32 { Int32(options(.audible)?.1.count ?? 0) }
    func getAudioTrackIndex(at: Int32) -> Int32 { at }
    func getAudioTrackId(at: Int32) -> String { "\(at)" }
    func getAudioTrackLabel(at: Int32) -> String { options(.audible).flatMap { Int(at) < $0.1.count ? label($0.1[Int(at)]) : nil } ?? "" }
    func getAudioTrackLang(at: Int32) -> String { options(.audible).flatMap { Int(at) < $0.1.count ? lang($0.1[Int(at)]) : nil } ?? "" }
    func isAudioTrackSelected(at: Int32) -> Bool { selected(.audible, at) }
    func getSubtitleTrackCount() -> Int32 { Int32(options(.legible)?.1.count ?? 0) }
    func getSubtitleTrackIndex(at: Int32) -> Int32 { at }
    func getSubtitleTrackId(at: Int32) -> String { "\(at)" }
    func getSubtitleTrackLabel(at: Int32) -> String { options(.legible).flatMap { Int(at) < $0.1.count ? label($0.1[Int(at)]) : nil } ?? "" }
    func getSubtitleTrackLang(at: Int32) -> String { options(.legible).flatMap { Int(at) < $0.1.count ? lang($0.1[Int(at)]) : nil } ?? "" }
    func isSubtitleTrackSelected(at: Int32) -> Bool { selected(.legible, at) }

    func selectAudioTrack(trackId: Int32) {
        guard let (group, opts) = options(.audible), Int(trackId) < opts.count else { return }
        player.currentItem?.select(opts[Int(trackId)], in: group)
    }
    func applyAudioLanguagePreferences(languages: [String]) {
        guard let (group, opts) = options(.audible) else { return }
        for language in languages {
            if let match = opts.first(where: { lang($0).lowercased().hasPrefix(language.lowercased()) }) {
                player.currentItem?.select(match, in: group); return
            }
        }
    }
    func selectSubtitleTrack(trackId: Int32) {
        guard let (group, opts) = options(.legible) else { return }
        player.currentItem?.select(trackId >= 0 && Int(trackId) < opts.count ? opts[Int(trackId)] : nil, in: group)
    }
    func setSubtitleUrl(url: String) {}
    func clearExternalSubtitle() {}
    func clearExternalSubtitleAndSelect(trackId: Int32) { selectSubtitleTrack(trackId: trackId) }
    func setSubtitleDelayMs(delayMs: Int32) {}
    func applySubtitleStyle(textColor: String, backgroundColor: String, outlineColor: String, outlineSize: Float,
                            bold: Bool, fontSize: Float, subPos: Int32, stripSdh: Bool) {}

    func getIsLoading() -> Bool {
        guard let item = player.currentItem else { return true }
        if item.status != .readyToPlay { return errorMessage.isEmpty }
        return player.timeControlStatus == .waitingToPlayAtSpecifiedRate
    }
    func getIsPlaying() -> Bool { player.timeControlStatus == .playing }
    func getIsEnded() -> Bool { ended }
    func getDurationMs() -> Int64 {
        guard let duration = player.currentItem?.duration, duration.isNumeric else { return 0 }
        return Int64(duration.seconds * 1000)
    }
    func getPositionMs() -> Int64 {
        let t = player.currentTime()
        return t.isNumeric ? Int64(t.seconds * 1000) : 0
    }
    func getBufferedMs() -> Int64 {
        guard let range = player.currentItem?.loadedTimeRanges.last?.timeRangeValue else { return getPositionMs() }
        return Int64(CMTimeRangeGetEnd(range).seconds * 1000)
    }
    func getPlaybackSpeed() -> Float { player.rate == 0 ? 1 : player.rate }
    func getErrorMessage() -> String { errorMessage }
    /// Position is the only frame evidence AVPlayer offers without an output tap; enough for the session.
    func getVideoFrameTicks() -> Int64 { getPositionMs() / 40 }
    func getVoFrameStats() -> Int64 { 0 }
    /// What AVPlayerItem exposes about the stream, in the libmpv payload's shape (PlayerStreamInfoPayload):
    /// codecs from the tracks' format descriptions, the presentation size, the nominal frame rate, and
    /// the access log's indicated bitrate (HLS) or the track's estimated data rate (files).
    func getStreamInfoJson() -> String {
        guard let item = player.currentItem else { return "" }
        var info: [String: Any] = [:]
        let size = item.presentationSize
        if size.width > 0, size.height > 0 { info["videoWidth"] = Int(size.width); info["videoHeight"] = Int(size.height) }
        for track in item.tracks {
            guard let asset = track.assetTrack else { continue }
            let format = (asset.formatDescriptions as? [CMFormatDescription])?.first
            switch asset.mediaType {
            case .video:
                if let format { info["videoCodec"] = Self.codecName(CMFormatDescriptionGetMediaSubType(format)) }
                if asset.nominalFrameRate > 0 { info["videoFps"] = Double(asset.nominalFrameRate) }
                if asset.estimatedDataRate > 0 { info["videoBitrate"] = Double(asset.estimatedDataRate) }
            case .audio:
                if let format {
                    info["audioCodec"] = Self.codecName(CMFormatDescriptionGetMediaSubType(format))
                    if let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee {
                        if asbd.mChannelsPerFrame > 0 { info["audioChannels"] = Int(asbd.mChannelsPerFrame) }
                        if asbd.mSampleRate > 0 { info["audioSampleRate"] = Int(asbd.mSampleRate) }
                    }
                }
                if asset.estimatedDataRate > 0 { info["audioBitrate"] = Double(asset.estimatedDataRate) }
            default: break
            }
        }
        if let event = item.accessLog()?.events.last, event.indicatedBitrate > 0 { info["videoBitrate"] = event.indicatedBitrate }
        guard !info.isEmpty, let data = try? JSONSerialization.data(withJSONObject: info) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// FourCC → the names libmpv reports, for the common Apple codecs; anything else as its FourCC.
    static func codecName(_ code: FourCharCode) -> String {
        switch code {
        case kCMVideoCodecType_H264: return "H.264"
        case kCMVideoCodecType_HEVC, 0x68766331 /* hvc1 */: return "HEVC"
        case 0x64766831 /* dvh1 */, 0x64766865 /* dvhe */: return "Dolby Vision"
        case kCMVideoCodecType_AV1: return "AV1"
        case kAudioFormatMPEG4AAC: return "AAC"
        case kAudioFormatAC3: return "AC-3"
        case kAudioFormatEnhancedAC3: return "E-AC-3"
        case kAudioFormatMPEGLayer3: return "MP3"
        default:
            let chars = [24, 16, 8, 0].map { Character(UnicodeScalar(UInt8((code >> $0) & 0xFF))) }
            return String(chars).trimmingCharacters(in: .whitespaces)
        }
    }

    func destroy() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let failedObserver { NotificationCenter.default.removeObserver(failedObserver) }
        itemObservation = nil
        EngineLedger.released(self)
    }
}

/// A bare AVPlayerLayer host: controls are Tuvora's own overlay, the same over both engines.
final class AVPlayerLaneViewController: UIViewController {
    private let playerLayer: AVPlayerLayer

    init(player: AVPlayer) {
        playerLayer = AVPlayerLayer(player: player)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        playerLayer.videoGravity = .resizeAspect
        view.layer.addSublayer(playerLayer)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        playerLayer.frame = view.bounds
    }

    func setResizeMode(_ mode: Int) {
        playerLayer.videoGravity = mode == 1 ? .resizeAspectFill : mode == 2 ? .resize : .resizeAspect
    }
}
