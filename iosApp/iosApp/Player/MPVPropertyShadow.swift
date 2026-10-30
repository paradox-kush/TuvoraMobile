// LOCAL (not in the upstream fork). Pure Swift on purpose — no Libmpv import — so the decisions
// here can be exercised without a player.
//
// Why this exists: every synchronous mpv read (mpv_get_property*) takes mpv's core dispatch lock.
// Two callers must never take it:
//
//  * mpv's own vo/render thread. The PiP frame capture runs inside MetalLayer.nextDrawable(), on
//    the vo thread, while the core thread sits in vo_wait_frame waiting for that very frame. A
//    property read there waits for the core, the core waits for the vo: a permanent deadlock that
//    froze the whole app ~2 minutes into an IPTV VOD (sim `sample`, 2026-10-01; same code shipped
//    in 1.8.3 / TestFlight 145-146).
//  * the main thread. A stalled live demuxer can hold the core lock for seconds (the Android ANR
//    family — see nuvio-mpv-anr-fix: "ALL mpv calls off main, reads → shadow").
//
// So every value those callers need is observed (mpv_observe_property), copied out of the event on
// mpv's event-reading queue, and stored here behind a short lock. Readers never touch mpv.

import CoreGraphics
import Foundation

/// A property value copied out of an `mpv_event_property`, so it outlives the mpv event buffer.
enum MPVPropertyValue: Equatable {
    /// `MPV_FORMAT_NONE`: the property is unavailable right now (no file, no video, ...).
    case none
    case flag(Bool)
    case int(Int64)
    case double(Double)
    case string(String)

    var doubleValue: Double? {
        switch self {
        case .double(let v): return v
        case .int(let v): return Double(v)
        case .flag(let v): return v ? 1 : 0
        case .string(let v): return Double(v)
        case .none: return nil
        }
    }

    var int64Value: Int64? {
        switch self {
        case .int(let v): return v
        case .double(let v): return v.isFinite ? Int64(v) : nil
        case .flag(let v): return v ? 1 : 0
        case .string(let v): return Int64(v)
        case .none: return nil
        }
    }

    var boolValue: Bool? {
        switch self {
        case .flag(let v): return v
        case .int(let v): return v != 0
        case .double(let v): return v != 0
        case .string(let v): return v == "yes"
        case .none: return nil
        }
    }

    var stringValue: String? {
        switch self {
        case .string(let v): return v
        case .int(let v): return String(v)
        case .double(let v): return String(v)
        case .flag(let v): return v ? "yes" : "no"
        case .none: return nil
        }
    }
}

/// One entry of mpv's `track-list`, taken from the observed node rather than ~10 synchronous
/// `track-list/N/...` reads per track. Field absent in the node == the old failed read's default.
struct MPVTrackShadow: Equatable {
    var id: Int = 0
    var type: String = ""
    var title: String = ""
    var lang: String = ""
    var codec: String = ""
    var decoderDescription: String = ""
    var demuxChannels: String = ""
    var demuxChannelCount: Int = 0
    var demuxSampleRate: Int = 0
    var demuxWidth: Int = 0
    var demuxHeight: Int = 0
    var demuxFps: Double = 0
    var demuxBitrate: Double = 0
    var hlsBitrate: Double = 0
    var selected: Bool = false
    var external: Bool = false

    init() {}

    /// Builds a track from one `track-list` map entry. Strings are trimmed the way the old
    /// per-field `getTrackString` reads trimmed them.
    init(fields: [String: MPVPropertyValue]) {
        func str(_ key: String) -> String {
            (fields[key]?.stringValue ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        func int(_ key: String) -> Int { Int(fields[key]?.int64Value ?? 0) }
        func dbl(_ key: String) -> Double { fields[key]?.doubleValue ?? 0 }
        func flag(_ key: String) -> Bool { fields[key]?.boolValue ?? false }

        id = int("id")
        type = str("type")
        title = str("title")
        lang = str("lang")
        codec = str("codec")
        decoderDescription = str("decoder-desc")
        demuxChannels = str("demux-channels")
        demuxChannelCount = int("demux-channel-count")
        demuxSampleRate = int("demux-samplerate")
        demuxWidth = int("demux-w")
        demuxHeight = int("demux-h")
        demuxFps = dbl("demux-fps")
        demuxBitrate = dbl("demux-bitrate")
        hlsBitrate = dbl("hls-bitrate")
        selected = flag("selected")
        external = flag("external")
    }
}

/// The observed mpv state, as plain values. Defaults equal what the old synchronous reads returned
/// when a property was unavailable (0, false, nil), so a `.none` event resets a field to its default.
struct MPVPlaybackProperties: Equatable {
    var duration: Double = 0
    var timePos: Double = 0
    var demuxerCacheTime: Double = 0
    var speed: Double = 0
    var paused = false
    var eofReached = false
    /// An mpv core with nothing loaded is idle; starting `true` keeps the first poll after
    /// creation (before mpv delivers the initial observed values) reading "loading", not "playing".
    var coreIdle = true
    var seeking = false
    var pausedForCache = false
    var estimatedVfFps: Double = 0
    var containerFps: Double = 0
    var videoFormat: String?
    var frameDropCount: Int64 = 0
    var voDelayedFrameCount: Int64 = 0
    var videoWidth: Int64 = 0
    var videoHeight: Int64 = 0
    var videoBitrate: Double = 0
    var audioBitrate: Double = 0
    var path: String?
    var aid: String?
    var subtitleText: String?
    var subtitleCodec: String?
    var tracks: [MPVTrackShadow] = []

    /// Sticky, like the old 250ms-poll cache: the measured rate wins, then the container's
    /// declared rate, else the last good value (30 before any). Feeds PiP sample durations.
    private(set) var renderFrameRate: Double = 30.0

    /// Aspect source for the PiP capture's crop. `.zero` until mpv knows the decoded size.
    var videoSize: CGSize {
        videoWidth > 0 && videoHeight > 0
            ? CGSize(width: Double(videoWidth), height: Double(videoHeight))
            : .zero
    }

    /// Same formulas the bridge has always used for the Kotlin snapshot.
    var isLoading: Bool { (coreIdle && !paused && !eofReached) || seeking || pausedForCache }
    var isPlaying: Bool { !paused && !coreIdle && !eofReached }

    /// Applies one property-change event. Returns false for a property this shadow does not track.
    @discardableResult
    mutating func apply(_ name: String, _ value: MPVPropertyValue) -> Bool {
        switch name {
        case "duration": duration = value.doubleValue ?? 0
        case "time-pos": timePos = value.doubleValue ?? 0
        case "demuxer-cache-time": demuxerCacheTime = value.doubleValue ?? 0
        case "speed": speed = value.doubleValue ?? 0
        case "pause": paused = value.boolValue ?? false
        case "eof-reached": eofReached = value.boolValue ?? false
        case "core-idle": coreIdle = value.boolValue ?? true
        case "seeking": seeking = value.boolValue ?? false
        case "paused-for-cache": pausedForCache = value.boolValue ?? false
        case "estimated-vf-fps":
            estimatedVfFps = value.doubleValue ?? 0
            updateRenderFrameRate()
        case "container-fps":
            containerFps = value.doubleValue ?? 0
            updateRenderFrameRate()
        case "video-format": videoFormat = value.stringValue
        case "frame-drop-count": frameDropCount = value.int64Value ?? 0
        case "vo-delayed-frame-count": voDelayedFrameCount = value.int64Value ?? 0
        case "video-params/w": videoWidth = max(value.int64Value ?? 0, 0)
        case "video-params/h": videoHeight = max(value.int64Value ?? 0, 0)
        case "video-bitrate": videoBitrate = value.doubleValue ?? 0
        case "audio-bitrate": audioBitrate = value.doubleValue ?? 0
        case "path": path = value.stringValue
        case "aid": aid = value.stringValue
        case "sid": break  // observed only to refresh Main on a subtitle-track change
        case "sub-text": subtitleText = value.stringValue
        case "current-tracks/sub/codec": subtitleCodec = value.stringValue
        default: return false
        }
        return true
    }

    private mutating func updateRenderFrameRate() {
        if estimatedVfFps.isFinite && estimatedVfFps > 1 {
            renderFrameRate = estimatedVfFps
        } else if containerFps.isFinite && containerFps > 1 {
            renderFrameRate = containerFps
        }
    }

    /// The properties the bridge observes, with the mpv format each is observed in. Every one is
    /// in mpv 0.41's change-notification table (player/command.c `mp_event_property_change`), so
    /// the shadow tracks the live value; `path` rides the "*" events of file start/end/load.
    static let observed: [(name: String, format: Format)] = [
        ("pause", .flag), ("paused-for-cache", .flag), ("core-idle", .flag),
        ("eof-reached", .flag), ("seeking", .flag),
        ("track-list", .node), ("aid", .string), ("sid", .string),
        ("sub-text", .string), ("current-tracks/sub/codec", .string),
        ("duration", .double), ("time-pos", .double), ("demuxer-cache-time", .double),
        ("speed", .double), ("estimated-vf-fps", .double), ("container-fps", .double),
        ("video-format", .string), ("frame-drop-count", .int64), ("vo-delayed-frame-count", .int64),
        ("video-params/w", .int64), ("video-params/h", .int64),
        ("video-bitrate", .double), ("audio-bitrate", .double), ("path", .string),
    ]

    enum Format { case flag, int64, double, string, node }

    /// Changes that used to schedule a full state refresh on Main. Only these still do: the
    /// per-tick properties (time-pos, fps, counters) update the shadow silently, and Main picks
    /// them up on the Kotlin 250ms poll — exactly the cadence they were read at before.
    static let mainRefreshProperties: Set<String> = [
        "pause", "paused-for-cache", "core-idle", "eof-reached", "seeking", "track-list", "aid", "sid",
    ]
}

/// Thread-safe holder: written on mpv's event queue, read from Main, the vo/render thread and the
/// Metal completion thread. The lock is held only to copy plain values, never across an mpv call.
final class MPVPropertyShadow {
    private let lock = NSLock()
    private var properties = MPVPlaybackProperties()

    func apply(_ name: String, _ value: MPVPropertyValue) {
        lock.lock()
        properties.apply(name, value)
        lock.unlock()
    }

    func setTracks(_ tracks: [MPVTrackShadow]) {
        lock.lock()
        properties.tracks = tracks
        lock.unlock()
    }

    var snapshot: MPVPlaybackProperties {
        lock.lock()
        defer { lock.unlock() }
        return properties
    }

    var videoSize: CGSize {
        lock.lock()
        defer { lock.unlock() }
        return properties.videoSize
    }

    var renderFrameRate: Double {
        lock.lock()
        defer { lock.unlock() }
        return properties.renderFrameRate
    }
}

/// DEBUG tripwire for the invariant above. The PiP capture runs its render-path callbacks (the
/// vo thread on the deferred path, Metal's presented/completed handler threads otherwise) inside
/// `whileOnRenderCallback`; the bridge's mpv helpers check the mark before calling into mpv. A
/// regression then fails loudly, naming the property, instead of deadlocking at some random minute
/// of playback. Scoped (set, run, restore) because those threads can be pooled GCD workers.
enum MPVRenderThreadGuard {
    private static let key = "tuvora.mpv.renderCallbackThread"

    static func whileOnRenderCallback(_ body: () -> Void) {
        #if DEBUG
        let dictionary = Thread.current.threadDictionary
        let previous = dictionary[key]
        dictionary[key] = true
        defer { dictionary[key] = previous }
        #endif
        body()
    }

    static func assertNotOnRenderCallback(_ call: @autoclosure () -> String) {
        #if DEBUG
        if Thread.current.threadDictionary[key] != nil {
            let message = "Synchronous mpv call on the render callback thread: \(call()) - " +
                "this deadlocks against vo_wait_frame; read MPVPropertyShadow instead"
            InAppLogBridge.shared.error(tag: "MPV/iOS", message: message)
            assertionFailure(message)
        }
        #endif
    }
}
