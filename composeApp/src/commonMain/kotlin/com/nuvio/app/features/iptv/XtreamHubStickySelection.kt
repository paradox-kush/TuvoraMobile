package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fix 1 (sticky provider): the hub's last on-screen selection — provider id + section tab name —
 * persisted per profile via [XtreamAccountStorage.saveHubSelectionJson] and restored on the next
 * fresh entry (cold start, or any resetForProfile).
 *
 * Deliberately DEVICE-LOCAL UI state: it lives in the local prefs bag, NOT on the [XtreamAccount],
 * precisely so it never rides the account sync — which provider you were browsing on this device
 * is not a fact about the playlist. Category and scroll state stay session-only on purpose.
 */
@Serializable
internal data class XtreamHubSelection(
    val accountId: String? = null,
    val section: String? = null,
)

private val hubSelectionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Corrupted or absent storage reads as "nothing remembered", never a throw. */
internal fun parseHubSelection(json: String?): XtreamHubSelection? {
    if (json.isNullOrBlank()) return null
    return try {
        hubSelectionJson.decodeFromString<XtreamHubSelection>(json)
    } catch (e: Exception) {
        null
    }
}

internal fun encodeHubSelection(selection: XtreamHubSelection): String =
    hubSelectionJson.encodeToString(XtreamHubSelection.serializer(), selection)

/**
 * Which account the hub lands on. The in-session choice wins; a fresh entry (current == null)
 * restores the remembered provider — but only while that account still exists AND is enabled.
 * Otherwise the first ENABLED account (the pre-fix behavior): a remembered id whose playlist was
 * deleted or toggled off must fall back, never resurrect. Content-type clamping (a section the
 * account has disabled) stays the caller's job — this only decides WHICH account.
 */
internal fun resolveStickyAccount(
    current: String?,
    remembered: String?,
    accounts: List<XtreamAccount>,
): String? {
    val enabled = accounts.filter { it.availableOnDevice }
    return (current ?: remembered)?.takeIf { id -> enabled.any { it.id == id } }
        ?: enabled.firstOrNull()?.id
}

/**
 * The remembered section tab (stored by name), tolerating junk or names written by other builds —
 * anything unrecognized reads as "nothing remembered" and yields [fallback], never a throw.
 */
internal fun resolveStickySection(remembered: String?, fallback: XtreamHubSection): XtreamHubSection =
    XtreamHubSection.entries.firstOrNull { it.name == remembered } ?: fallback

/**
 * Whether the hub's account snapshot has fallen behind the playlist store and must re-sync.
 *
 * The hub copies the ENABLED playlists into its own state; it never read the store again, and since
 * the root tab host keeps a visited tab composed (upstream "reduce root tab switch stalls"), the
 * screen's one-shot load no longer re-ran on return — so a playlist added from Settings (or
 * removed, or synced in) stayed invisible until an app restart. True when the hub was never loaded
 * or was reset (a removal wipes it), or the enabled list differs; false when nothing changed, so a
 * repeat emission never re-drives the hub.
 */
internal fun hubNeedsAccountSync(hub: XtreamHubUiState, playlists: List<XtreamAccount>): Boolean =
    !hub.accountsLoaded || hub.hasConfiguredAccounts != playlists.isNotEmpty() || hub.accounts != playlists.filter { it.availableOnDevice }
