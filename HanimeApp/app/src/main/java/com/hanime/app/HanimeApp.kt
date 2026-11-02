package com.hanime.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.hanime.app.data.Repository
import com.hanime.app.data.adoptSystemProxy
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

/**
 * Holds the process-wide singletons.
 *
 * There is no DI framework in the dependency cache, and the graph is three objects
 * deep, so a hand-written holder is smaller and faster than wiring a container.
 */
object Graph {
    lateinit var repo: Repository
        private set

    fun install(app: Application) {
        repo = Repository()
        repo.init(app.filesDir.absolutePath + "/core")
        adoptSystemProxy(app)
    }
}

class HanimeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.install(this)
        SingletonImageLoader.setSafe(::imageLoader)
    }

    /**
     * Cover URLs are signed (`?secure=...`) and hotlink-protected, so the loader
     * has to look like the site's own pages or every thumbnail 403s.
     *
     * Cache sizes are set explicitly because the CDN sends no `Cache-Control` at
     * all (verified: only ETag/Last-Modified), so Coil's defaults govern reuse and
     * their memory cache — a fraction of the heap — evicts covers as soon as a few
     * screens' worth of thumbnails are decoded. Scrolling back re-fetched them.
     */
    private fun imageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", Ua.BROWSER)
                        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
                        .header("Referer", "https://hanime1.me/")
                        .build()
                )
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(client)) }
            // 30% of the heap: covers are small (25-60 KB compressed) but decode to
            // a few hundred KB each, and a full home feed is ~150 of them.
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.30)
                    .build()
            }
            // The image set is effectively fixed, so a large disk cache is cheaper
            // than re-downloading over mobile data.
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
