package com.nuvio.app.features.iptv.identity

import com.nuvio.app.features.iptv.stalker.StalkerCrypto

/**
 * Channel identity — the deterministic fingerprint a live channel keeps across provider refreshes,
 * and the key the durable personalization overlay (hide / pin / reorder / custom groups) is stored
 * under. A provider's `stream_id` renumbers (commit 6c622d49 traced a panel reissuing its whole
 * catalog), so nothing durable may be keyed on it. Identity is derived from what stays put: the
 * tvg-id when the panel supplies one, else the channel's name; the quality tag (HD / FHD / 4K) is
 * folded in as [variant] so sibling feeds stay distinct, and the discriminating part of the name is
 * folded in so two feeds sharing a tvg-id (BBC ONE LONDON / NORTH) stay distinct.
 *
 * ## Canon v1 — frozen, cross-platform, sync-safe
 * The name is canonicalised through a FROZEN table ([CanonV1Table], generated from Unicode 17) rather
 * than any platform Unicode API, so the website's TypeScript, this Kotlin, and every client compute a
 * byte-identical id for the same channel — which is what lets an edit made on tuvora.co apply to the
 * channel on the phone. The parity is pinned by a shared golden-vector test (`IptvIdentityTest`) whose
 * expected ids come from the reference JS implementation (research/canon-v1/canon_v1.mjs). Because the
 * fold is frozen, a v1 id is safe to write to a synced table (unlike the earlier local-only v0 draft).
 *
 * The id carries its canon version (`fp:v1:…`); a future table change ships as `v2` and re-keys at
 * most the rebuildable caches, never losing user overlay data (the overlay re-joins by re-deriving
 * the id from the freshly-rebuilt catalog, exactly as `XtreamMatchIndex.resolveLiveSid` re-binds a
 * saved favourite).
 */
internal object IptvIdentity {

    const val VERSION: String = "v1"

    /** Quality tokens folded OUT of the discriminating name but kept as [variant]. Must match canon_v1.mjs. */
    private val QUALITY = setOf("sd", "hd", "fhd", "uhd", "4k", "8k", "hevc", "h265", "h264", "raw", "backup", "alt")

    private const val BREAK = '\u0001'

    /** code point -> replacement string (frozen). Absent = identity. Built once from [CanonV1Table]. */
    private val table: Map<Int, String> by lazy(LazyThreadSafetyMode.PUBLICATION) { parseTable(CanonV1Table.PACKED) }

    private fun parseTable(packed: String): Map<Int, String> {
        val m = HashMap<Int, String>(1 shl 15)
        var start = 0
        while (start <= packed.length) {
            var end = packed.indexOf('\n', start)
            if (end < 0) end = packed.length
            if (end > start) {
                val line = packed.substring(start, end)
                val gt = line.indexOf('>')
                if (gt > 0) {
                    val cp = line.substring(0, gt).toInt(16)
                    m[cp] = unescape(line.substring(gt + 1))
                }
            }
            if (end == packed.length) break
            start = end + 1
        }
        return m
    }

    /** Convert `\uXXXX` escapes in a table replacement to real chars (the only escape the table uses). */
    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == 'u') {
                sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * v1 canonical form: every code point mapped through the frozen table (case-fold, accent strip,
     * width fold), the separators `| - . : _ /` and whitespace collapsed to single spaces, leading and
     * trailing space dropped. Mirrors canon_v1.mjs exactly; touches no platform Unicode API.
     */
    fun canon(s: String): String {
        val out = StringBuilder(s.length)
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val srcLen: Int
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                srcLen = 2
            } else {
                cp = c.code
                srcLen = 1
            }
            val rep = table[cp]
            if (rep == null) {
                // identity: keep the source code unit(s) verbatim (never a break)
                if (pendingSpace) { out.append(' '); pendingSpace = false }
                out.append(s, i, i + srcLen)
            } else {
                var j = 0
                while (j < rep.length) {
                    val rc = rep[j]; j++
                    if (rc == BREAK) {
                        pendingSpace = out.isNotEmpty()
                    } else {
                        if (pendingSpace) { out.append(' '); pendingSpace = false }
                        out.append(rc)
                    }
                }
            }
            i += srcLen
        }
        return out.toString()
    }

    /** The tvg-id branch is EXACT: whitespace trimmed, case and punctuation preserved. */
    fun tvgKey(tvgId: String?): String? {
        val t = tvgId?.trim() ?: return null
        return if (t.isEmpty()) null else "e:$t"
    }

    fun tokens(name: String): List<String> = canon(name).split(' ').filter { it.isNotEmpty() }

    fun variant(name: String): String = tokens(name).firstOrNull { it in QUALITY } ?: ""

    fun nameDisc(name: String): String = tokens(name).filter { it !in QUALITY }.joinToString(" ")

    fun identityKey(name: String, tvgId: String?): String = tvgKey(tvgId) ?: ("n:" + nameDisc(name))

    /** Content-addressed (SHA-256, 128 bits kept), playlist-scoped, never random, sync-safe. */
    fun entityId(playlistId: String, name: String, tvgId: String?): String {
        val material = playlistId + "|" + identityKey(name, tvgId) + "|" + variant(name) + "|" + nameDisc(name)
        return "fp:$VERSION:" + StalkerCrypto.sha256Hex(material).substring(0, 32)
    }

    /**
     * The durable key for a provider CATEGORY. Categories have no tvg-id and their provider ids
     * renumber like sids, so the canon of the category name (scoped to playlist + content type) is
     * the stable key the category overlay (hide / reorder / rename) is stored under.
     */
    fun categoryKey(playlistId: String, contentType: String, name: String): String {
        val material = "$playlistId|$contentType|${canon(name)}"
        return "c:$VERSION:" + StalkerCrypto.sha256Hex(material).substring(0, 32)
    }
}
