package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.overlay.*
import com.nuvio.app.features.livetv.LiveGuideChannel
import kotlin.test.*

class TvGuideGroupsTest {
    @Test fun syncedGroupsKeepMemberOrderAndExcludeHiddenMissingAndOtherPlaylists() {
        val lineup = listOf("a", "b", "c").map { LiveGuideChannel(it, it, null, 0, null, entityId = it) }
        val overlay = OverlaySnapshot(
            channels = mapOf("b" to ChannelOverlay(hidden = true)),
            groups = listOf(CustomGroup("later", "live", "p", "Later", 2),
                CustomGroup("first", "live", null, "Across playlists", 1, listOf("c", "b", "missing", "a", "c")),
                CustomGroup("other", "live", "other-playlist", "Other", 0),
                CustomGroup("movies", "movies", "p", "Movies", 0)))
        val rows = TvGuideGroups.rows("p", lineup, overlay)
        assertEquals(listOf("grp:first", "grp:later"), rows.map { it.id })
        assertEquals(listOf("c", "a"), rows.first().channels.map { it.contentId })
        assertTrue(rows.last().channels.isEmpty())
    }
}
