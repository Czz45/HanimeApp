package com.hanime.app

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Builds the two players the app uses.
 *
 * Streaming gets a disk cache plus a deep read-ahead: the site serves plain
 * progressive MP4 over a CDN that frequently stalls mid-file, and with the stock
 * 4-second buffer every stall showed up as a spinner. Local files skip the cache —
 * wrapping a `file://` source in [CacheDataSource] would copy bytes the app
 * already owns into a second copy on disk.
 */
object Players {

    /** ~12 s of look-ahead before playback starts; enough to ride out a CDN hiccup. */
    private const val MIN_BUFFER_MS = 12_000
    private const val MAX_BUFFER_MS = 90_000
    private const val PLAYBACK_BUFFER_MS = 1_500
    private const val REBUFFER_MS = 3_000

    fun build(
        context: Context,
        url: String,
        userAgent: String,
        playWhenReady: Boolean = true,
    ): ExoPlayer {
        val local = url.startsWith("file:")

        // Retained back buffer: seeking a few seconds backwards replays from memory
        // instead of hitting the network again.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                REBUFFER_MS,
            )
            .setBackBuffer(30_000, true)
            .build()

        val builder = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)

        if (!local) {
            builder.setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    MediaCache.dataSource(context, userAgent)
                )
            )
        }

        return builder.build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            this.playWhenReady = playWhenReady
        }
    }
}
