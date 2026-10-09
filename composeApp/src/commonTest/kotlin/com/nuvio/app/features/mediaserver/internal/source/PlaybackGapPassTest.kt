package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.*
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.player.PlayerStreamsRepository
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Repository regressions: no provider/network access. */
class PlaybackGapPassTest {
    @Test
    fun failedFreshStalkerResolutionMustNotRepublishTheConsumedLink() = runBlocking {
        val id = "audit:stalker:movie:1"
        var attempts = 0
        StreamSourceAccess.resetForTest()
        MetaSourceAccess.resetForTest()
        PlayerStreamsRepository.clearAll()
        StreamSourceRegistry.register("audit-stalker", object : StreamSourceProvider {
            override fun isHandledId(videoId: String?) = videoId == id
            override fun isStalkerSource(videoId: String) = true
            override fun directStreamItem(videoId: String) = StreamItem(name="old", url="https://provider.invalid/consumed", addonId="audit", addonName="audit")
            override fun matchSourceGroups(type: String) = emptyList<StreamSourceGroup>()
            override suspend fun resolveMatchStreams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?) = emptyList<StreamItem>()
            override fun isMatchSourceId(providerAddonId: String) = false
            override fun isDeferredUrl(url: String?) = false
            override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? = null
        })
        MetaSourceRegistry.register("audit-stalker", object : MetaSourceProvider {
            override fun handlesId(id: String) = true
            override suspend fun buildNativeMeta(id: String): MetaDetails? = null
            override suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean, forceMint: Boolean): Boolean {
                assertTrue(forceFresh)
                attempts++
                return false // portal did not issue a replacement
            }
        })
        try {
            PlayerStreamsRepository.loadSources(type="movie", videoId=id, forceRefresh=true, forceMintIptv=true)
            val state = withTimeout(5_000) {
                PlayerStreamsRepository.sourceState.first { !it.isAnyLoading && (it.groups.isNotEmpty() || it.emptyStateReason != null) }
            }
            assertEquals(1, attempts)
            assertTrue(state.groups.flatMap { it.streams }.isEmpty(), "failed fresh resolution must not return the consumed cached URL")
        } finally {
            PlayerStreamsRepository.clearAll()
            StreamSourceAccess.resetForTest()
            MetaSourceAccess.resetForTest()
        }
    }

    private suspend fun jellyfinEpisodeSourceList(explicitEnsure: Boolean) {
        val client = com.nuvio.app.features.mediaserver.internal.FakeClient()
        val rig = com.nuvio.app.features.mediaserver.internal.TestRig(clientFactory = { client })
        val e = com.nuvio.app.features.mediaserver.internal.entry()
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, com.nuvio.app.features.mediaserver.internal.store.StoredCredential("AUDIT-TEST-TOKEN"))
        val sidecar = com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaStreamDto(index=4, type="Subtitle", codec="srt", language="eng", isExternal=true)
        val a = com.nuvio.app.features.mediaserver.internal.source("version-a").let { it.copy(mediaStreams=it.mediaStreams + sidecar) }
        val full = com.nuvio.app.features.mediaserver.internal.item("ep1", type="Episode", sources=listOf(a, com.nuvio.app.features.mediaserver.internal.source("version-b")))
        client.items["ep1"] = full
        val light = full.copy(mediaSources=emptyList()) // the lightweight series episode response
        val registered = com.nuvio.app.features.mediaserver.internal.source.MediaServerItemMapper.registered(e, light)!!
        val meta = com.nuvio.app.features.mediaserver.internal.source.MediaServerMetaSource(rig.store, rig.services)
        val provider = com.nuvio.app.features.mediaserver.internal.source.MediaServerStreamSourceProvider(rig.store, rig.services)
        StreamSourceAccess.resetForTest()
        MetaSourceAccess.resetForTest()
        PlayerStreamsRepository.clearAll()
        com.nuvio.app.features.mediaserver.internal.source.MediaServerItemRegistry.reset()
        com.nuvio.app.features.mediaserver.internal.source.MediaServerItemRegistry.register(registered)
        StreamSourceRegistry.register("audit-jellyfin", provider)
        MetaSourceRegistry.register("audit-jellyfin", meta)
        try {
            if (explicitEnsure) assertTrue(meta.ensureStreamRegistered(registered.contentId, false, false))
            PlayerStreamsRepository.loadSources(type="series", videoId=registered.contentId, forceRefresh=true)
            val state = withTimeout(5_000) {
                PlayerStreamsRepository.sourceState.first { !it.isAnyLoading && (it.groups.isNotEmpty() || it.emptyStateReason != null) }
            }
            val streams = state.groups.flatMap { it.streams }
            assertEquals(2, streams.size, "lightweight episode must hydrate both versions; actual item requests=" + client.itemRequests.size)
            assertEquals(1, streams.first().externalSubtitles.size, "hydrated source must retain its external subtitle")
        } finally {
            PlayerStreamsRepository.clearAll()
            StreamSourceAccess.resetForTest()
            MetaSourceAccess.resetForTest()
            com.nuvio.app.features.mediaserver.internal.source.MediaServerItemRegistry.reset()
        }
    }

    @Test
    fun lightweightJellyfinEpisodeMustHydrateVersionsAndSidecarsBeforeListing() = runBlocking {
        jellyfinEpisodeSourceList(explicitEnsure=false)
    }

    @Test
    fun explicitJellyfinHydrationIsAWorkingControl() = runBlocking {
        jellyfinEpisodeSourceList(explicitEnsure=true)
    }
}
