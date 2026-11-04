package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.AccountKind
import com.nuvio.app.features.iptv.CompletionKind
import com.nuvio.app.features.iptv.ContactKind
import com.nuvio.app.features.iptv.ContactLink
import com.nuvio.app.features.iptv.ProviderSupport
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.SetupCodeOutcome
import com.nuvio.app.features.iptv.SetupCodeUiState
import com.nuvio.app.features.iptv.SetupCompletion

/** Which pane of the Apple TV code screen shows. */
enum class TvSetupPhase { ENTRY, CHECKING, PREVIEW, ADDING, DONE }

/** A provider contact as text + the URL its QR code carries. [kind] is stable (`telegram`, `whatsapp`, `email`, `website`). */
data class TvSetupContact(val kind: String, val label: String, val text: String, val url: String)

data class TvSetupPlaylistLine(val name: String, val typeLabel: String)

data class TvSetupProfile(val index: Int, val name: String)

/** Everything the SwiftUI code screen draws. Plain values only. */
data class TvSetupState(
    val phase: TvSetupPhase = TvSetupPhase.ENTRY,
    /** The entry text as typed (`TUV-XXXX-...`). */
    val typed: String = "",
    /** Twelve slots for the code boxes. */
    val boxes: List<String> = List(12) { "" },
    val canContinue: Boolean = false,
    /** The sentence to show under the boxes / on the preview, or null. */
    val problem: String? = null,
    /** The provider's contacts when the code had expired (so the viewer can ask for a new one). */
    val problemContacts: List<TvSetupContact> = emptyList(),
    /**
     * No real account (signed out, or a local guest): the playlists are added to this Apple TV only, and a later
     * sign-in claims them into the account (B205). No profile to pick, no phone to wait for.
     */
    val onDevice: Boolean = false,
    /** The line under the preview when [onDevice] ("Sign in any time to keep these in your Tuvora account."). */
    val deviceNote: String? = null,
    /** Which account the playlist will be added to (email), when known. */
    val accountLabel: String? = null,
    val providerName: String? = null,
    val packageName: String? = null,
    val playlists: List<TvSetupPlaylistLine> = emptyList(),
    /** Empty in store builds (they hide add-ons) and when the package has none. */
    val addons: List<String> = emptyList(),
    val profiles: List<TvSetupProfile> = emptyList(),
    val selectedProfile: Int = 1,
    /** The profile a phone redeem is watched for (the active one): the wait only sees that profile. */
    val watchedProfile: String? = null,
    /** The sentence a finished redeem earns ("Starshare added your playlist"). */
    val doneText: String? = null,
    /** The playlist to open after a finished redeem, or null. */
    val openPlaylistKey: String? = null,
)

/** The pure mapping from the shared controller's state to what the Apple TV code screen shows (contract sections 3 and 8). */
object TvSetupStateBuilder {

    /** Only for an account redeem the server refused as signed-out (the session lapsed): with no account a code works on its own. */
    private const val SIGN_IN_NEEDED = "Your Tuvora session ended. Sign in again on this Apple TV to add this setup."
    private const val DEVICE_NOTE = "Sign in any time to keep these in your Tuvora account."

    internal fun build(
        ui: SetupCodeUiState,
        kind: AccountKind,
        accountLabel: String?,
        profiles: List<TvSetupProfile>,
        activeProfile: Int,
        showAddons: Boolean,
    ): TvSetupState {
        val preview = ui.preview
        val phase = when {
            ui.completed != null -> TvSetupPhase.DONE
            ui.redeeming -> TvSetupPhase.ADDING
            ui.previewLoading -> TvSetupPhase.CHECKING
            preview != null -> TvSetupPhase.PREVIEW
            else -> TvSetupPhase.ENTRY
        }
        val onDevice = kind != AccountKind.REAL
        // The sentence under the boxes or on the preview: why a code was refused, else what is wrong with the typing.
        val refusal: SetupCodeOutcome? = ui.redeemRefusal
            ?: ui.previewOutcome?.takeIf { it !is SetupCodeOutcome.Ready }
            ?: ui.typedProblem?.let { SetupCodeOutcome.Problem(it) }
        val problem = when {
            refusal == null -> null
            refusal is SetupCodeOutcome.NeedsSignIn -> SIGN_IN_NEEDED
            else -> refusal.message?.english
        }
        val contacts = (refusal as? SetupCodeOutcome.Expired)?.support?.let(::contactsOf).orEmpty()
        val selected = ui.selectedProfileIndex ?: activeProfile
        return TvSetupState(
            phase = phase,
            typed = ui.typed,
            boxes = TvSetupCodeEntry.boxes(ui.typed),
            canContinue = TvSetupCodeEntry.canContinue(ui.typed),
            problem = problem,
            problemContacts = contacts,
            onDevice = onDevice,
            deviceNote = if (onDevice) DEVICE_NOTE else null,
            accountLabel = if (onDevice) null else accountLabel,
            providerName = preview?.providerName,
            packageName = preview?.packageName,
            playlists = preview?.playlists?.map { TvSetupPlaylistLine(it.name, sourceLabel(it.sourceType)) }.orEmpty(),
            addons = if (showAddons) preview?.addons.orEmpty() else emptyList(),
            profiles = if (onDevice) emptyList() else profiles,
            selectedProfile = selected,
            watchedProfile = if (onDevice) null else profiles.firstOrNull { it.index == activeProfile }?.name,
            doneText = ui.completed?.let { doneText(it, profiles, activeProfile) },
            openPlaylistKey = ui.completed?.openPlaylistKey,
        )
    }

    private fun contactsOf(support: ProviderSupport): List<TvSetupContact> = support.links().map(::contact)

    /** One provider contact as text + QR target (the same rules the phone's buttons use). */
    fun contact(link: ContactLink): TvSetupContact = TvSetupContact(
        kind = link.kind.name.lowercase(), label = labelOf(link.kind), text = link.text, url = link.url,
    )

    fun labelOf(kind: ContactKind): String = when (kind) {
        ContactKind.TELEGRAM -> "Telegram"
        ContactKind.WHATSAPP -> "WhatsApp"
        ContactKind.EMAIL -> "Email"
        ContactKind.WEBSITE -> "Website"
    }

    private fun sourceLabel(sourceType: String): String = when (sourceType) {
        SOURCE_TYPE_XTREAM -> "Xtream account"
        SOURCE_TYPE_M3U_URL -> "M3U playlist"
        SOURCE_TYPE_STALKER -> "Stalker portal"
        else -> "Playlist"
    }

    private fun doneText(done: SetupCompletion, profiles: List<TvSetupProfile>, activeProfile: Int): String {
        val provider = done.providerName
        return when (done.outcome) {
            CompletionKind.ADDED_ON_DEVICE -> "$provider added your playlist to this Apple TV"
            CompletionKind.ALREADY_IN_ACCOUNT -> "Your playlist from $provider is already in your account"
            CompletionKind.NOTHING_MISSING_LOGIN -> "$provider has not filled in your login yet. Ask them to: your playlist is added as soon as they do."
            CompletionKind.NOTHING_INVALID_URL -> "$provider's address for this playlist isn't valid. Ask them to check it."
            CompletionKind.NOTHING_ADDED -> "Nothing was added. Ask your provider to check your setup."
            CompletionKind.ADDED -> {
                val other = done.profileIndex.takeIf { it != activeProfile }?.let { index -> profiles.firstOrNull { it.index == index }?.name }
                if (other != null) "$provider added your playlist to $other" else "$provider added your playlist"
            }
        }
    }
}

/**
 * What counts as "already here" when the wait for a phone starts: the SERVER's managed list when it answered (so a
 * playlist redeemed on another device and never pulled here is not announced as new), else the cached map; plus every
 * playlist already on this device, so a stale cache can never make an old playlist look new.
 */
object TvWaitSnapshot {
    fun of(serverKeys: Set<String>?, cachedKeys: Set<String>, localIds: Set<String>): Set<String> =
        (serverKeys ?: cachedKeys) + localIds
}
