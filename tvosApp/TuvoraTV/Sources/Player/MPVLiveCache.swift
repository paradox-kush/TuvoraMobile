import Foundation
import Libmpv

// Live pause / rewind, Apple TV side of the libmpv bridge (the iPhone bridge keeps the same ranges in
// MPVPropertyShadow). mpv's own demuxer cache is the buffer; Kotlin (MpvLiveCache) picks the property values
// and parses the text below, this file only observes `demuxer-cache-state` and hands the ranges over.

/// One seekable stretch of the demuxer cache, in mpv's time-pos seconds.
struct MPVSeekableRange: Equatable {
    var start: Double
    var end: Double
}

extension Array where Element == MPVSeekableRange {
    /// The hand-over format the Kotlin side parses (MpvLiveCache.parseRanges): `start,end;start,end`.
    /// Plain "." decimals whatever the device locale, unusable ranges dropped.
    var bridgeText: String {
        filter { $0.start.isFinite && $0.end.isFinite && $0.end > $0.start }
            .map { String(format: "%.3f,%.3f", locale: Locale(identifier: "en_US_POSIX"), $0.start, $0.end) }
            .joined(separator: ";")
    }
}

/// The last observed ranges, written on mpv's event queue and read from Main behind a short lock:
/// Main never asks mpv for them (a synchronous read waits on the core lock a stalled live demuxer holds).
final class MPVSeekableRangesStore {
    private let lock = NSLock()
    private var ranges: [MPVSeekableRange] = []

    func set(_ next: [MPVSeekableRange]) {
        lock.lock()
        ranges = next
        lock.unlock()
    }

    var text: String {
        lock.lock()
        defer { lock.unlock() }
        return ranges.bridgeText
    }
}

enum MPVLiveCacheDecoder {
    /// The observed `demuxer-cache-state` node's `seekable-ranges` (an array of {start, end} maps). Anything
    /// else decodes to no ranges, so a file without a seekable cache reads as "nothing to rewind into".
    static func seekableRanges(_ property: mpv_event_property) -> [MPVSeekableRange] {
        guard property.format == MPV_FORMAT_NODE, let data = property.data else { return [] }
        let root = data.assumingMemoryBound(to: mpv_node.self).pointee
        guard root.format == MPV_FORMAT_NODE_MAP, let map = root.u.list?.pointee,
              let keys = map.keys, let values = map.values else { return [] }
        for i in 0..<Int(map.num) {
            guard let key = keys[i], String(cString: key) == "seekable-ranges" else { continue }
            let ranges = values[i]
            guard ranges.format == MPV_FORMAT_NODE_ARRAY, let list = ranges.u.list?.pointee,
                  list.num > 0, let entries = list.values else { return [] }
            var result: [MPVSeekableRange] = []
            for j in 0..<Int(list.num) {
                let entry = entries[j]
                guard entry.format == MPV_FORMAT_NODE_MAP, let rangeMap = entry.u.list?.pointee,
                      let rangeKeys = rangeMap.keys, let rangeValues = rangeMap.values else { continue }
                var start: Double?
                var end: Double?
                for k in 0..<Int(rangeMap.num) {
                    guard let rangeKey = rangeKeys[k] else { continue }
                    switch String(cString: rangeKey) {
                    case "start": start = double(rangeValues[k])
                    case "end": end = double(rangeValues[k])
                    default: break
                    }
                }
                if let start, let end { result.append(MPVSeekableRange(start: start, end: end)) }
            }
            return result
        }
        return []
    }

    private static func double(_ node: mpv_node) -> Double? {
        switch node.format {
        case MPV_FORMAT_DOUBLE: return node.u.double_
        case MPV_FORMAT_INT64: return Double(node.u.int64)
        default: return nil
        }
    }
}
