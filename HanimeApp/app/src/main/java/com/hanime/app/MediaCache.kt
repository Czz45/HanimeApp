package com.hanime.app

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide disk cache for streamed video.
 *
 * The CDN serves progressive MP4 with full `Content-Length` and honours `Range`,
 * so ExoPlayer can cache while it plays and reuse the bytes next time. Without a
 * cache every seek beyond the buffered window and every replay starts a fresh
 * request, which is the "loading again" the app used to show.
 *
 * [SimpleCache] must be a singleton per directory: constructing two instances over
 * the same folder corrupts the index, so this lives at process scope.
 */
object MediaCache {

    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB

    @Volatile
    private var cache: SimpleCache? = null

    /** Serialised because two screens can ask for the data source concurrently. */
    fun instance(context: Context): SimpleCache = synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "video_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            androidx.media3.database.StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }

    /**
     * Data source feeding ExoPlayer from [instance].
     *
     * `cacheWriteDataSinkFactory` is what makes playback populate the cache;
     * without it the cache is read-only and never fills.
     */
    fun dataSource(context: Context, userAgent: String): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            // The CDN signs URLs with a `?secure=` token, so a stale response from
            // an intermediate cache is worse than no cache at all.
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://hanime1.me/",
                    "Accept-Language" to "zh-TW,zh;q=0.9,en;q=0.8",
                )
            )
        val base = DefaultDataSource.Factory(context, upstream)
        return CacheDataSource.Factory()
            .setCache(instance(context))
            .setUpstreamDataSourceFactory(base)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun release() = synchronized(this) {
        cache?.release()
        cache = null
    }
}
