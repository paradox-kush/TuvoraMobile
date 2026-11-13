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
        /** False for a single-use link (LiveLinkReconnectPolicy): OkHttp must not re-send the stream request. */
        retryConnectionFailures: Boolean = true,
        /**
         * B259: origin-bound credentials for the STREAM data source only (never the subtitle path, never YouTube).
         * Its interceptor drops them on any redirect hop that leaves the host or downgrades.
         */
        originBoundRequestHeaders: Map<String, String> = emptyMap(),
    ): DataSource.Factory {
        val httpFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(
            defaultRequestHeaders,
            useLongReadTimeout,
            retryConnectionFailures = retryConnectionFailures,
            originBoundHeaders = originBoundRequestHeaders,
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
