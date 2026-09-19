package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        context: Context,
        streamUrl: String?,
        defaultRequestHeaders: Map<String, String>,
        defaultResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
        useLongReadTimeout: Boolean = false,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    ): DataSource.Factory {
        val httpFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(
            defaultRequestHeaders,
            useLongReadTimeout,
        )
        // Clean client with no stream default headers — subtitle fetches get only policy-scoped ones.
        val subtitleNetworkFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(
            emptyMap(),
            useLongReadTimeout,
        )
        val subtitleHeaderFactory = SubtitleRequestHeaderDataSourceFactory(
            streamUpstreamFactory = httpFactory,
            subtitleUpstreamFactory = subtitleNetworkFactory,
            streamUrl = streamUrl,
            streamHeaders = defaultRequestHeaders,
            externalSubtitles = externalSubtitles,
        )
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, subtitleHeaderFactory)
        return if (defaultResponseHeaders.isEmpty()) {
            baseFactory
        } else {
            ResponseHeaderOverridingDataSourceFactory(
                upstreamFactory = baseFactory,
                defaultResponseHeaders = defaultResponseHeaders,
            )
        }
    }
}
