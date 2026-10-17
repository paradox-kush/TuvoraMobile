package com.nuvio.app.features.radar

import com.nuvio.app.features.iptv.CategorySelections
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamChannel
import com.nuvio.app.features.iptv.identity.IptvIdentity
import com.nuvio.app.features.iptv.overlay.CategoryOverlay
import com.nuvio.app.features.iptv.overlay.ChannelOverlay
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.NamedCategory
import com.nuvio.app.features.iptv.overlay.OverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SportsChannelVisibilityPolicyTest {
    private val account = XtreamAccount("playlist", "Playlist", "https://example.invalid", "user", "pass")
    private val categories = listOf(NamedCategory("1", "Sports"), NamedCategory("2", "News"))
    private val channels = listOf(channel(1, "Sports HD", "1"), channel(2, "Sports SD", "1"), channel(3, "News", "2"))

    @Test
    fun hides_only_the_selected_variant_even_when_guide_id_is_shared() {
        val overlay = OverlaySnapshot(channels = mapOf(entity(channels[0]) to ChannelOverlay(hidden = true)))
        assertEquals(listOf(2, 3), visible(overlay))
    }

    @Test
    fun hidden_categories_remove_their_channels_before_matching() {
        val overlay = OverlaySnapshot(categories = mapOf(
            IptvIdentity.categoryKey(account.id, "live", "Sports") to CategoryOverlay(hidden = true),
        ))
        assertEquals(listOf(3), visible(overlay))
    }

    @Test
    fun hides_are_scoped_to_playlist_and_content_type() {
        val overlay = OverlaySnapshot(categories = mapOf(
            IptvIdentity.categoryKey("other", "live", "Sports") to CategoryOverlay(hidden = true),
            IptvIdentity.categoryKey(account.id, "movies", "Sports") to CategoryOverlay(hidden = true),
        ), channels = mapOf(IptvIdentity.entityId("other", channels[0].name, "shared") to ChannelOverlay(hidden = true)))
        assertEquals(listOf(1, 2, 3), visible(overlay))
    }

    @Test
    fun category_selection_accepts_current_ids_and_legacy_group_names() {
        for (selection in listOf(listOf("1"), listOf("Sports"))) {
            val selected = account.copy(categorySelections = CategorySelections(live = selection))
            assertEquals(listOf(1, 2), SportsChannelVisibilityPolicy.filter(selected, channels, categories, OverlaySnapshot.EMPTY).map { it.streamId })
        }
    }

    @Test
    fun empty_selection_or_disabled_live_type_contributes_no_channels() {
        for (disabled in listOf(account.copy(enabled = false), account.copy(contentTypes = setOf("movies")),
            account.copy(categorySelections = CategorySelections(live = emptyList())))) {
            assertTrue(SportsChannelVisibilityPolicy.filter(disabled, channels, categories, OverlaySnapshot.EMPTY).isEmpty())
        }
    }

    @Test
    fun unhide_or_profile_change_uses_the_same_unmodified_raw_catalog() {
        val overlay = OverlaySnapshot(channels = mapOf(entity(channels[0]) to ChannelOverlay(hidden = true)))
        assertEquals(listOf(2, 3), visible(overlay))
        assertEquals(listOf(1, 2, 3), visible(OverlaySnapshot.EMPTY))
        assertEquals(3, channels.size)
    }

    @Test
    fun pins_and_renames_do_not_hide_or_change_provider_identity() {
        val overlay = OverlaySnapshot(channels = mapOf(entity(channels[0]) to ChannelOverlay(pinned = true, rename = "My channel")))
        assertEquals(channels, SportsChannelVisibilityPolicy.filter(account, channels, categories, overlay))
    }

    @Test
    fun unknown_category_is_allowed_only_when_selection_is_all() {
        val unknown = channel(4, "Unknown", null)
        assertEquals(listOf(unknown), SportsChannelVisibilityPolicy.filter(account, listOf(unknown), categories, OverlaySnapshot.EMPTY))
        assertTrue(SportsChannelVisibilityPolicy.filter(account.copy(categorySelections = CategorySelections(live = listOf("1"))), listOf(unknown), categories, OverlaySnapshot.EMPTY).isEmpty())
    }

    @Test
    fun channel_only_hides_need_no_category_request() {
        val overlay = OverlaySnapshot(channels = mapOf(entity(channels[0]) to ChannelOverlay(hidden = true)))
        assertFalse(SportsChannelVisibilityPolicy.needsCategoryNames(account, overlay))
        assertTrue(SportsChannelVisibilityPolicy.needsCategoryNames(account.copy(categorySelections = CategorySelections(live = listOf("1"))), overlay))
    }

    private fun visible(overlay: OverlaySnapshot) =
        SportsChannelVisibilityPolicy.filter(account, channels, categories, overlay).map { it.streamId }

    private fun entity(channel: XtreamChannel) = IptvIdentity.entityId(account.id, channel.name, channel.epgChannelId)
    private fun channel(id: Int, name: String, category: String?) = XtreamChannel(
        streamId = id, name = name, logo = null, epgChannelId = "shared", categoryId = category,
        hasArchive = false, streamUrl = "https://example.invalid/$id",
    )
}
