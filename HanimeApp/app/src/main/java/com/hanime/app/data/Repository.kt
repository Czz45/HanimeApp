package com.hanime.app.data

import com.hanime.app.core.Card
import com.hanime.app.core.CoreException
import com.hanime.app.core.Detail
import com.hanime.app.core.DownloadTask
import com.hanime.app.core.Json
import com.hanime.app.core.ListPage
import com.hanime.app.core.Native
import com.hanime.app.core.Playlist
import com.hanime.app.core.Section
import com.hanime.app.core.ReelStats
import com.hanime.app.core.Settings
import com.hanime.app.core.TagGroup
import com.hanime.app.core.UpdateInfo
import com.hanime.app.core.UpdateProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Single source of truth for everything the UI reads.
 *
 * All heavy work (HTTP, HTML parsing, file transfer) happens in the Rust core, so
 * this class is only responsible for hopping off the main thread and caching the
 * last successful payload so tab switches never show an empty screen.
 */
class Repository {

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private val _watched = MutableStateFlow<Set<Long>>(emptySet())
    val watched: StateFlow<Set<Long>> = _watched.asStateFlow()

    /**
     * Bookmarks, kept in the Rust core next to the watch history.
     *
     * Deliberately separate from [downloads]: a favourite is a "come back to this"
     * marker and must cost no disk space, so it stores the card metadata only and
     * the detail page is still fetched on open.
     */
    private val _favorites = MutableStateFlow<List<Card>>(emptyList())
    val favorites: StateFlow<List<Card>> = _favorites.asStateFlow()

    fun init(stateDir: String) {
        Native.init(stateDir)
        _settings.value = Json.settings(Native.getSettingsJson())
        refreshDownloads()
        _watched.value = runCatching {
            val arr = org.json.JSONArray(Native.watchedJson())
            (0 until arr.length()).map { arr.optLong(it, 0L) }.toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun home(): List<Section> = withContext(Dispatchers.IO) {
        Json.sections(Native.fetchHome())
    }

    suspend fun list(genre: String, sort: String, date: String = "", duration: String = "", page: Int = 1): ListPage =
        withContext(Dispatchers.IO) {
            Json.listPage(Native.fetchList(genre, sort, date, duration, page))
        }

    suspend fun search(query: String, page: Int = 1): ListPage = withContext(Dispatchers.IO) {
        Json.listPage(Native.fetchSearch(query, page))
    }

    /** Search with the whole filter set; every argument composes with the others. */
    suspend fun searchFiltered(
        query: String,
        genre: String,
        sort: String,
        date: String,
        duration: String,
        tags: List<String>,
        broad: Boolean,
        page: Int = 1,
    ): ListPage = withContext(Dispatchers.IO) {
        Json.listPage(
            Native.fetchSearchFiltered(
                query, genre, sort, date, duration,
                tags.joinToString("\u0001"), broad, page,
            )
        )
    }

    suspend fun tagGroups(): List<TagGroup> = withContext(Dispatchers.IO) {
        Json.tagGroups(Native.tagGroups())
    }

    suspend fun playlist(id: Long): Playlist = withContext(Dispatchers.IO) {
        Json.playlist(Native.fetchPlaylist(id))
    }

    suspend fun detail(id: Long): Detail = withContext(Dispatchers.IO) {
        Json.detail(Native.fetchDetail(id))
    }

    /**
     * A random title, optionally restricted to one category.
     *
     * The catalogue is a contiguous integer id space, so the core samples ids and
     * retries on misses (deleted uploads 302 off-site, others have no source).
     */
    /**
     * A shuffled batch from one random listing page.
     *
     * Much cheaper than [randomDetail] in a loop: that sampled bare ids, which miss
     * ~80% of the time on this site, so a six-item batch meant ~30 requests.
     */
    suspend fun randomCards(genre: String, count: Int = 6): ListPage =
        withContext(Dispatchers.IO) { Json.listPage(Native.fetchRandomCards(genre, count)) }

    suspend fun randomDetail(genre: String, tries: Int = 24): Detail = withContext(Dispatchers.IO) {
        Json.detail(Native.fetchRandomDetail(genre, tries))
    }

    /** Marks a title as watched; the home/list feeds filter it out afterwards. */
    suspend fun markWatched(id: Long) = withContext(Dispatchers.IO) {
        Native.markWatched(id)
        _watched.value = _watched.value + id
    }

    suspend fun clearWatched() = withContext(Dispatchers.IO) {
        Native.clearWatched()
        _watched.value = emptySet()
    }

    private fun reloadFavorites() {
        _favorites.value = runCatching { Json.cards(Native.favoritesJson()) }.getOrDefault(emptyList())
    }

    /** Adds or removes one bookmark and hands back the updated list. */
    suspend fun toggleFavorite(card: Card) = withContext(Dispatchers.IO) {
        val raw = Native.toggleFavorite(Json.cardToJson(card))
        _favorites.value = runCatching { Json.cards(raw) }.getOrElse { _favorites.value }
        _favorites.value
    }

    suspend fun clearFavorites() = withContext(Dispatchers.IO) {
        Native.clearFavorites()
        _favorites.value = emptyList()
    }

    // ---- feed sampling telemetry ----

    /**
     * Records that the short-video feed showed `id`. Fire-and-forget on IO: the
     * caller is a pager landing callback and must never wait on disk.
     */
    suspend fun noteReel(id: Long, genre: String) = withContext(Dispatchers.IO) {
        Native.noteReel(id, genre)
    }

    suspend fun reelStats(): ReelStats = withContext(Dispatchers.IO) {
        Json.reelStats(Native.reelStats())
    }

    suspend fun clearReelStats() = withContext(Dispatchers.IO) {
        Native.clearReelStats()
    }

    fun refreshDownloads() {
        _downloads.value = Json.tasks(Native.libraryJson())
    }

    /**
     * Polls the library file while transfers are active. The Rust side writes
     * progress there, so there is no callback channel to keep in sync.
     */
    suspend fun pollDownloads() = withContext(Dispatchers.IO) {
        Native.rescanLibrary()
        refreshDownloads()
    }

    suspend fun startDownload(id: Long, quality: Int, listId: Long = 0, listTitle: String = ""): DownloadTask =
        withContext(Dispatchers.IO) {
            val t = Json.parse(Native.startDownload(id, quality, listId, listTitle)) { o ->
                DownloadTask(
                    id = o.optLong("id"),
                    title = o.optString("title"),
                    cover = o.optString("cover"),
                    quality = o.optInt("quality"),
                    path = o.optString("path"),
                    total = o.optLong("total"),
                    done = o.optLong("done"),
                    state = o.optString("state"),
                    listId = o.optLong("list_id"),
                    listTitle = o.optString("list_title"),
                )
            }
            refreshDownloads()
            t
        }

    /** Queues every title of a playlist; returns how many were scheduled. */
    suspend fun startPlaylistDownload(listId: Long, quality: Int): Int = withContext(Dispatchers.IO) {
        val n = Json.parse(Native.startPlaylistDownload(listId, quality)) { o ->
            o.optInt("queued", 0)
        }
        refreshDownloads()
        n
    }

    suspend fun checkUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        Json.update(Native.checkUpdate())
    }

    /** Localizes a Japanese collection name; the core returns the input on failure. */
    suspend fun translateTitle(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) text else Native.translateTitle(text)
    }

    /** Starts the APK transfer; progress arrives through [updateProgress]. */
    suspend fun downloadUpdate(url: String, dest: String) = withContext(Dispatchers.IO) {
        val raw = Native.downloadUpdate(url, dest)
        Json.parse<Unit>(raw) { }
    }

    suspend fun updateProgress(): UpdateProgress = withContext(Dispatchers.IO) {
        Json.updateProgress(Native.updateDownloadState())
    }

    suspend fun cancelDownload(id: Long) = withContext(Dispatchers.IO) {
        Native.cancelDownload(id)
        refreshDownloads()
    }

    suspend fun removeDownload(id: Long, deleteFile: Boolean) = withContext(Dispatchers.IO) {
        Native.removeDownload(id, deleteFile)
        refreshDownloads()
    }

    fun downloadDir(): String = Native.downloadDir()

    suspend fun saveSettings(s: Settings): Settings = withContext(Dispatchers.IO) {
        val out = Json.settings(Native.setSettingsJson(Json.settingsJson(s)))
        _settings.value = out
        out
    }
}

/** Error text the UI can show without leaking a Kotlin stack trace. */
fun Throwable.userMessage(): String = when (this) {
    is CoreException -> message ?: "网络请求失败"
    else -> message ?: "出错了"
}

data class CardHit(val card: Card)

/**
 * Hands the platform HTTP proxy to the Rust core.
 *
 * The core reads it only as a fallback, so a proxy the user typed in Settings wins.
 * Emulators and corporate networks need this to reach the site at all.
 */
fun adoptSystemProxy(context: android.content.Context) {
    // Android exposes host and port as two separate properties, so pair them up here.
    val host = System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }
    val port = System.getProperty("http.proxyPort")?.takeIf { it.isNotBlank() }
    val raw = host?.let { if (port != null) "$it:$port" else it }
        ?: runCatching {
            // WiFi/mobile proxies live here; reading it needs no permission.
            android.provider.Settings.Global.getString(
                context.contentResolver,
                android.provider.Settings.Global.HTTP_PROXY,
            )
        }.getOrNull()
    val value = runCatching { java.net.URI("http://$raw") }.getOrNull()
        ?.takeIf { it.host != null && it.port > 0 }
        ?.let { "${it.host}:${it.port}" }
    val finalValue = if (value.isNullOrBlank()) parseProxy(raw) else value
    android.util.Log.i("HanimeProxy", "raw=$raw final=$finalValue")
    com.hanime.app.core.Native.setProxy(finalValue)
}

private fun parseProxy(raw: String?): String {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return ""
    val parts = t.substringAfterLast("://").split(":")
    return if (parts.size == 2 && parts[1].toIntOrNull() != null) "${parts[0]}:${parts[1]}" else t
}
