package com.hanime.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanime.app.Graph
import com.hanime.app.core.Card
import com.hanime.app.core.Detail
import com.hanime.app.core.DownloadTask
import com.hanime.app.core.Section
import com.hanime.app.core.Settings
import com.hanime.app.core.Source
import com.hanime.app.data.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Genres the site exposes; "全部" keeps the rich home rows instead of a flat grid. */
val GENRES = listOf(
    "全部", "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D",
    "2D動畫", "AI生成", "MMD", "Cosplay", "新番預告",
)

val SORTS = listOf(
    "最新上市", "最新上傳", "本日排行", "本週排行", "本月排行",
    "觀看次數", "讚好比例", "時長最長", "他們在看",
    "過去 24 小時", "過去 2 天", "過去 1 週", "過去 1 個月", "過去 3 個月",
)

/** Where the floating player / detail sheet sits above the tab content. */
sealed interface Route {
    data object Tabs : Route
    data class Info(val id: Long, val seed: Card?) : Route
    data class Player(val detail: Detail, val source: Source) : Route
}

class AppViewModel : ViewModel() {

    private val repo = Graph.repo

    val downloads: StateFlow<List<DownloadTask>> = repo.downloads
    val settings: StateFlow<Settings> = repo.settings

    var tab by mutableStateOf(0)
        private set
    var route by mutableStateOf<Route>(Route.Tabs)
        private set

    // ---- home feed -------------------------------------------------------
    var genre by mutableStateOf("全部")
        private set
    var sort by mutableStateOf("最新上市")
        private set

    val sections = mutableStateListOf<Section>()
    val feed = mutableStateListOf<Card>()
    var feedPage by mutableStateOf(1)
        private set
    var hasNext by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Sorting the flat list is the only mode that paginates. */
    val isGridMode: Boolean get() = genre != "全部" || sort != "最新上市"

    // ---- detail ----------------------------------------------------------
    var detail by mutableStateOf<Detail?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<String?>(null)

    /** Transient failure text for actions (download, delete). Never gates a retry. */
    var notice by mutableStateOf<String?>(null)
        private set

    private var feedJob: Job? = null
    private var detailJob: Job? = null
    private var pollJob: Job? = null

    init {
        refreshFeed()
        startPolling()
    }

    fun selectTab(index: Int) {
        tab = index
    }

    fun chooseGenre(value: String) {
        if (genre == value) return
        genre = value
        refreshFeed()
    }

    fun chooseSort(value: String) {
        if (sort == value) return
        sort = value
        refreshFeed()
    }

    /** Reloads whatever the current genre/sort pair maps to. */
    fun refreshFeed() {
        feedJob?.cancel()
        error = null
        loading = true
        feedJob = viewModelScope.launch {
            runCatching {
                if (isGridMode) {
                    val page = repo.list(genre, sort, page = 1)
                    sections.clear()
                    feed.clear()
                    feed.addAll(page.items)
                    feedPage = 1
                    hasNext = page.hasNext
                } else {
                    val rows = repo.home()
                    sections.clear()
                    sections.addAll(rows)
                    feed.clear()
                    hasNext = false
                }
            }.onFailure {
                error = it.userMessage()
            }
            loading = false
        }
    }

    /** Appends the next page; called when the grid nears its end. */
    fun loadMore() {
        if (!isGridMode || loading || loadingMore || !hasNext) return
        loadingMore = true
        viewModelScope.launch {
            runCatching {
                val page = repo.list(genre, sort, page = feedPage + 1)
                val known = feed.mapTo(HashSet()) { it.id }
                feed.addAll(page.items.filterNot { it.id in known })
                feedPage = page.page
                hasNext = page.hasNext
            }.onFailure { error = it.userMessage() }
            loadingMore = false
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(900)
                if (repo.downloads.value.any { it.isRunning }) {
                    repo.pollDownloads()
                }
            }
        }
    }

    // ---- navigation ------------------------------------------------------

    fun openDetail(id: Long, seed: Card? = null) {
        route = Route.Info(id, seed)
        detail = null
        detailError = null
        detailLoading = true
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            runCatching { repo.detail(id) }
                .onSuccess { detail = it }
                .onFailure { detailError = it.userMessage() }
            detailLoading = false
        }
    }

    fun play(source: Source) {
        val d = detail ?: return
        route = Route.Player(d, source)
        viewModelScope.launch { repo.markWatched(d.id) }
    }

    fun back() {
        route = when (val r = route) {
            is Route.Player -> Route.Info(r.detail.id, null)
            else -> Route.Tabs
        }
    }

    fun closeOverlay() {
        route = Route.Tabs
    }

    // ---- downloads -------------------------------------------------------

    fun download(quality: Int) {
        val id = detail?.id ?: return
        viewModelScope.launch {
            runCatching { repo.startDownload(id, quality) }
                .onFailure { notice = it.userMessage() }
        }
    }

    fun cancelDownload(id: Long) = viewModelScope.launch { repo.cancelDownload(id) }

    fun removeDownload(id: Long, deleteFile: Boolean) = viewModelScope.launch {
        repo.removeDownload(id, deleteFile)
    }

    /**
     * 本地资源页点条目直接起播，不走网络请求：离线时也能看已下载的内容。
     * 详情信息用下载任务的元数据拼装，避免为了播放再抓一次页面。
     */
    fun playLocal(task: DownloadTask) {
        if (!task.isDone) {
            openDetail(task.id)
            return
        }
        val local = Detail(
            id = task.id,
            title = task.title,
            cover = task.cover,
            poster = task.cover,
            sources = listOf(Source(task.quality, "file://${task.path}")),
        )
        route = Route.Player(local, local.sources.first())
        viewModelScope.launch { repo.markWatched(task.id) }
    }

    // ---- settings --------------------------------------------------------

    fun saveSettings(block: (Settings) -> Settings) {
        viewModelScope.launch { repo.saveSettings(block(settings.value)) }
    }

    fun clearWatched() = viewModelScope.launch { repo.clearWatched() }

    fun downloadDir(): String = repo.downloadDir()

    fun clearNotice() {
        notice = null
    }
}
