// Decode-health telemetry feed (reporting only). IDENTICAL in iosApp/iosApp/Player and
// tvosApp/TuvoraTV/Sources/Player: keep the two in step, and keep both in step with the Kotlin side
// (core/analytics/DecodeHealthPolicy.kt, DecodeHealthBridgeWire.kt).
//
// What it does: copies a few mpv properties and the ad/ao/vd warning COUNTS into a lock-guarded struct
// on mpv's event queue, and hands them to Kotlin as one short text line (NuvioPlayerDecodeHealthBridge).
// Kotlin's DecodeHealthPolicy decides what is worth an event. Nothing here changes playback.
//
// Threading: values are written on the mpv event queue and read from Main by the Kotlin poll. The lock is
// held only to copy plain values, never across an mpv call, and there is no synchronous mpv read here
// (the mpv threading rule). Log TEXT never enters this file's state: only a count per decoder side, so a
// line that quotes a provider URL cannot leak (B116).

import Foundation
import Libmpv

final class DecodeHealthShadow {
    /// The properties this shadow observes, with the mpv format each is observed in. Handled on the event
    /// queue and NOT forwarded to Main, so a per-frame property cannot flood the main thread.
    static let observed: [(name: String, format: mpv_format)] = [
        ("avsync", MPV_FORMAT_DOUBLE),
        ("decoder-frame-drop-count", MPV_FORMAT_INT64),
        ("audio-pts", MPV_FORMAT_DOUBLE),
        ("hwdec-current", MPV_FORMAT_STRING),
        ("video-params/gamma", MPV_FORMAT_STRING),
        // Present only while an audio track is selected; unavailable (format none) otherwise.
        ("current-tracks/audio/id", MPV_FORMAT_INT64),
        // Stream shape (codec, size, rate, channels): observed here so nothing ever reads it synchronously from mpv.
        // These are the current-tracks aliases, which no other observer in either app uses, so this shadow can
        // consume them without starving another one.
        ("current-tracks/video/codec", MPV_FORMAT_STRING),
        ("current-tracks/video/demux-w", MPV_FORMAT_INT64),
        ("current-tracks/video/demux-h", MPV_FORMAT_INT64),
        ("current-tracks/video/demux-fps", MPV_FORMAT_DOUBLE),
        ("current-tracks/audio/codec", MPV_FORMAT_STRING),
        ("current-tracks/audio/demux-channel-count", MPV_FORMAT_INT64),
        ("current-tracks/audio/demux-samplerate", MPV_FORMAT_INT64),
    ]

    static let observedNames: Set<String> = Set(observed.map { $0.name })

    /// Audio is "flat" once `audio-pts` has not moved for this long (matches Kotlin's DECODE_HEALTH_AUDIO_FLAT_MS).
    static let audioFlatSeconds: TimeInterval = 2.0

    enum Side { case audio, video }

    private let lock = NSLock()
    private var avSyncSeconds: Double?
    private var decoderDropped: Int64 = 0
    private var audioPts: Double?
    private var audioPtsChangedAt: TimeInterval = 0
    private var hwdec: String?
    private var gamma: String?
    private var audioSelected = false
    private var audioWarns = 0
    private var videoWarns = 0
    private var muted = false
    private var videoCodec: String?
    private var videoWidth: Int64 = 0
    private var videoHeight: Int64 = 0
    private var videoFps: Double = 0
    private var audioCodec: String?
    private var audioChannels: Int64 = 0
    private var audioSampleRate: Int64 = 0

    // MARK: - Writes (mpv event queue, or Main for the mute flag)

    /// Applies one property-change event if it is one of [observed]; returns true when it was consumed.
    @discardableResult
    func apply(name: String, property: mpv_event_property) -> Bool {
        guard Self.observedNames.contains(name) else { return false }
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        defer { lock.unlock() }
        switch name {
        case "avsync":
            avSyncSeconds = Self.double(property)
        case "decoder-frame-drop-count":
            decoderDropped = Self.int64(property) ?? 0
        case "audio-pts":
            let value = Self.double(property)
            if let value {
                if audioPts == nil || abs(value - (audioPts ?? 0)) > 0.0005 { audioPtsChangedAt = now }
            }
            audioPts = value
        case "hwdec-current":
            hwdec = Self.string(property)
        case "video-params/gamma":
            gamma = Self.string(property)
        case "current-tracks/audio/id":
            audioSelected = Self.int64(property) != nil
        case "current-tracks/video/codec": videoCodec = Self.string(property)
        case "current-tracks/video/demux-w": videoWidth = Self.int64(property) ?? 0
        case "current-tracks/video/demux-h": videoHeight = Self.int64(property) ?? 0
        case "current-tracks/video/demux-fps": videoFps = Self.double(property) ?? 0
        case "current-tracks/audio/codec": audioCodec = Self.string(property)
        case "current-tracks/audio/demux-channel-count": audioChannels = Self.int64(property) ?? 0
        case "current-tracks/audio/demux-samplerate": audioSampleRate = Self.int64(property) ?? 0
        default:
            break
        }
        return true
    }

    /// One mpv log line. Only the count per decoder side survives; warn, error and fatal count.
    func noteLog(prefix: String, level: String) {
        guard level == "warn" || level == "error" || level == "fatal" else { return }
        guard let side = Self.side(ofLogPrefix: prefix) else { return }
        lock.lock()
        switch side {
        case .audio: audioWarns += 1
        case .video: videoWarns += 1
        }
        lock.unlock()
    }

    func setMuted(_ value: Bool) {
        lock.lock()
        muted = value
        lock.unlock()
    }

    // MARK: - Read (Main, from the Kotlin poll)

    /// The wire line for NuvioPlayerDecodeHealthBridge.getDecodeHealthLine. `voDropped` is the VO's
    /// `frame-drop-count` and `fps` the stream's frame rate, both of which the bridge already keeps.
    func line(voDropped: Int64, fps: Double) -> String {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        defer { lock.unlock() }
        let advancing: Bool? = audioSelected
            ? (audioPts != nil && now - audioPtsChangedAt < Self.audioFlatSeconds)
            : nil
        return Self.wireLine(
            avSyncSeconds: avSyncSeconds,
            dropped: max(voDropped, 0) + max(decoderDropped, 0),
            audioWarns: audioWarns,
            videoWarns: videoWarns,
            audioSelected: audioSelected,
            audioAdvancing: advancing,
            muted: muted,
            fps: fps
        )
    }

    /// The stream-shape wire line for NuvioPlayerDecodeHealthBridge.getDecodeHealthShapeLine, from the observed
    /// copies (lock only, never mpv). Empty until mpv has reported a track.
    func shapeLine() -> String {
        lock.lock()
        defer { lock.unlock() }
        let mode: String? = {
            guard let hwdec, !hwdec.isEmpty else { return nil }
            return hwdec == "no" ? "sw" : "hw"
        }()
        let hdr: Bool? = gamma.map { $0 == "pq" || $0 == "hlg" }
        return Self.wireShapeLine(
            videoCodec: videoCodec, width: videoWidth, height: videoHeight, fps: videoFps,
            audioCodec: audioCodec, channels: audioChannels, sampleRate: audioSampleRate,
            hdr: hdr, decodeMode: mode
        )
    }

    /// `decodeMode` / `hdr` for the stream info (read on Main, from the copied values).
    var streamFacts: (decodeMode: String?, hdr: Bool?) {
        lock.lock()
        defer { lock.unlock() }
        let mode: String? = {
            guard let hwdec, !hwdec.isEmpty else { return nil }
            return hwdec == "no" ? "sw" : "hw"
        }()
        let hdr: Bool? = gamma.map { $0 == "pq" || $0 == "hlg" }
        return (mode, hdr)
    }

    // MARK: - Pure helpers (no mpv, no state)

    /// `v1,<avsync ms|->,<dropped>,<audioWarns>,<videoWarns>,<audioSelected 1|0>,<audioAdvancing 1|0|->,<muted 1|0>,<fps x100|->`
    /// Parsed by Kotlin's DecodeHealthBridgeWire; a field is `-` when unknown.
    static func wireLine(
        avSyncSeconds: Double?,
        dropped: Int64,
        audioWarns: Int,
        videoWarns: Int,
        audioSelected: Bool,
        audioAdvancing: Bool?,
        muted: Bool,
        fps: Double
    ) -> String {
        let avSync: String = {
            guard let s = avSyncSeconds, s.isFinite else { return "-" }
            return String(Int64((s * 1000).rounded()))
        }()
        let advancing: String = audioAdvancing.map { $0 ? "1" : "0" } ?? "-"
        let fpsText: String = (fps.isFinite && fps > 0) ? String(Int64((fps * 100).rounded())) : "-"
        return ["v1", avSync, String(max(dropped, 0)), String(audioWarns), String(videoWarns),
                audioSelected ? "1" : "0", advancing, muted ? "1" : "0", fpsText].joined(separator: ",")
    }

    /// `s1,<video codec|->,<w|->,<h|->,<fps x100|->,<audio codec|->,<channels|->,<sample rate|->,<hdr 1|0|->,<hw|sw|->`
    /// Parsed by Kotlin's DecodeHealthBridgeWire.parseShape. Codec names are reduced to a short clean token
    /// (letters, digits, `.`, `_`, `-`, at most 24) or `-`: nothing free-form can cross the bridge.
    static func wireShapeLine(
        videoCodec: String?, width: Int64, height: Int64, fps: Double,
        audioCodec: String?, channels: Int64, sampleRate: Int64,
        hdr: Bool?, decodeMode: String?
    ) -> String {
        func token(_ value: String?) -> String {
            guard let raw = value?.lowercased(), !raw.isEmpty, raw.count <= 24,
                  raw.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-") })
            else { return "-" }
            return raw
        }
        func number(_ value: Int64) -> String { value > 0 ? String(value) : "-" }
        let fpsText = (fps.isFinite && fps > 0) ? String(Int64((fps * 100).rounded())) : "-"
        let hdrText = hdr.map { $0 ? "1" : "0" } ?? "-"
        let mode = (decodeMode == "hw" || decodeMode == "sw") ? decodeMode! : "-"
        return ["s1", token(videoCodec), number(width), number(height), fpsText,
                token(audioCodec), number(channels), number(sampleRate), hdrText, mode].joined(separator: ",")
    }

    /// Mirrors Kotlin's DecodeHealthPolicy.mpvLogSide: `ad`/`ao` are audio, `vd` is video, with or without a
    /// `/sub` part. Every other module (cplayer, stream, ffmpeg, vo, ...) can print URLs and is not counted.
    static func side(ofLogPrefix prefix: String) -> Side? {
        let head = prefix.trimmingCharacters(in: .whitespaces).lowercased().split(separator: "/", maxSplits: 1).first.map(String.init) ?? ""
        switch head {
        case "ad", "ao": return .audio
        case "vd": return .video
        default: return nil
        }
    }

    private static func double(_ property: mpv_event_property) -> Double? {
        guard property.format == MPV_FORMAT_DOUBLE, let data = property.data else { return nil }
        let value = data.assumingMemoryBound(to: Double.self).pointee
        return value.isFinite ? value : nil
    }

    private static func int64(_ property: mpv_event_property) -> Int64? {
        guard property.format == MPV_FORMAT_INT64, let data = property.data else { return nil }
        return data.assumingMemoryBound(to: Int64.self).pointee
    }

    private static func string(_ property: mpv_event_property) -> String? {
        guard property.format == MPV_FORMAT_STRING, let data = property.data,
              let cString = data.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee else { return nil }
        return String(cString: cString)
    }
}
