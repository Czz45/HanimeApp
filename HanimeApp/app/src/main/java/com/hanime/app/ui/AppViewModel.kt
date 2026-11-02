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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How many random picks one swipe-feed batch holds before it refetches. */
private const val RANDOM_BATCH = 6

/** Genres the site exposes; "全部" keeps the rich home rows instead of a flat grid. */
val GENRES = listOf(
    "全部", "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D",
    "2D動畫", "AI生成", "MMD", "Cosplay", "新番預告",
)

/**
 * Sort dropdown values. Date ranges used to be mixed in here, which silently
 * turned a sort switch into a `sort=` the site does not know; they live in
 * [DATES] now and travel as the separate `date=` parameter.
 */
val SORTS = listOf(
    "最新上市", "最新上傳", "本日排行", "本週排行", "本月排行",
    "觀看次數", "讚好比例", "時長最長", "他們在看",
)

/** "发布日期" dropdown. Empty means no date filter. */
val DATES = listOf(
    "", "過去 24 小時", "過去 2 天", "過去 1 週", "過去 1 個月", "過去 3 個月", "過去 1 年",
)

/** "时长" dropdown. Empty means no duration filter. */
val DURATIONS = listOf(
    "", "0 - 10 分鐘", "0 - 20 分鐘", "10 分鐘 +", "20 分鐘 +", "30 分鐘 +", "60 分鐘 +",
)

/** Label shown on a chip for an empty filter value. */
fun filterLabel(value: String, fallback: String): String = value.ifBlank { fallback }

/**
 * Two shapes for the first tab.
 *
 * [Home] is the editorial board: the site's own horizontal rows (最新上市, 他們在看…)
 * which have no sort parameters at all. [Browse] is the `/search` grid, where
 * every genre — including 全部 — can be sorted and filtered. They used to be one
 * screen that silently swapped shape based on the sort value, which made sorting
 * under 全部 impossible: any sort change looked like it did nothing because the
 * home rows ignore `sort=`.
 */
enum class FeedMode { Home, Browse }

/** Where the floating player / detail sheet sits above the tab content. */
sealed interface Route {
    data object Tabs : Route
    data class Info(val id: Long, val seed: Card?) : Route
    /** Full-screen playback; the inline player lives inside [Info] instead. */
    data class Player(val detail: Detail, val source: Source) : Route
    data object Search : Route
    data class Collection(val id: Long, val title: String) : Route
    /**
     * Vertical short-video feed over [cards], starting at [start].
     *
     * The queue is a snapshot taken when the feed opened: the grid underneath can
     * refetch, but the thing the user is swiping must not change under their finger.
     */
    /**
     * Swipe feed. It carries only the genre: the list itself lives in the view
     * model so switching genre, paging in more, or hiding watched items reloads
     * the feed in place instead of rebuilding the route (which would drop the
     * pager back to the first page).
     */
    data class Reels(val genre: String) : Route
}

class AppViewModel : ViewModel() {

    private val repo = Graph.repo

    val downloads: StateFlow<List<DownloadTask>> = repo.downloads
    val favorites: StateFlow<List<Card>> = repo.favorites
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
    /** Empty string means "no filter" for both of these. */
    var date by mutableStateOf("")
        private set
    var duration by mutableStateOf("")
        private set
    /** Tag values (`tags[]`) currently selected. Empty means "no tag filter". */
    val tags = mutableStateListOf<String>()
    /** `broad=on`: match any selected tag instead of all of them. */
    var broad by mutableStateOf(false)
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
    val isGridMode: Boolean
        get() = mode == FeedMode.Browse

    /** Editorial rows vs. the sortable grid. */
    var mode by mutableStateOf(FeedMode.Home)
        private set

    // ---- tag picker ------------------------------------------------------
    var tagGroups by mutableStateOf<List<com.hanime.app.core.TagGroup>>(emptyList())
        private set

    // ---- search ----------------------------------------------------------
    var query by mutableStateOf("")
        private set
    val searchResults = mutableStateListOf<Card>()
    var searchPage by mutableStateOf(1)
        private set
    var searchHasNext by mutableStateOf(false)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    // ---- collection ------------------------------------------------------
    var collection by mutableStateOf<com.hanime.app.core.Playlist?>(null)
        private set
    var collectionLoading by mutableStateOf(false)
        private set
    var collectionError by mutableStateOf<String?>(null)
        private set

    /** Raw name first, then the translated one once it arrives. */
    var collectionTitle by mutableStateOf("")
        private set

    /** True while a batch (whole-collection) download is being queued. */
    var batchDownloading by mutableStateOf(false)
        private set

    // ---- update checker --------------------------------------------------
    var updateChecking by mutableStateOf(false)
        private set
    var updateInfo by mutableStateOf<com.hanime.app.core.UpdateInfo?>(null)
        private set
    var updateError by mutableStateOf<String?>(null)
        private set

    /** APK transfer state; the settings row renders the bar and the install button. */
    var updateProgress by mutableStateOf(com.hanime.app.core.UpdateProgress())
        private set

    // ---- reels (short-video feed) ---------------------------------------
    /**
     * Details fetched for the swipe feed, memoised by id.
     *
     * The wall page is a ~380 KB HTML fetch, so the only way a swipe can start
     * playing immediately is to have the *next* item's sources already parsed
     * before the user gets there. In-flight requests are shared so a prefetch and
     * the page that just became visible don't fetch the same thing twice.
     */
    private val reelStore = mutableMapOf<Long, Detail>()
    private val reelJobs = mutableMapOf<Long, kotlinx.coroutines.Deferred<Detail>>()
    private val reelMutex = kotlinx.coroutines.sync.Mutex()

    // ---- detail ----------------------------------------------------------
    var detail by mutableStateOf<Detail?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<String?>(null)

    /** Source picked for the inline player; null until the user taps a quality. */
    var inlineSource by mutableStateOf<Source?>(null)
        private set

    /**
     * Where the current title was last playing, in ms.
     *
     * Inline and full-screen are two different ExoPlayer instances over the same
     * URL, so without this the full-screen route started from zero and coming back
     * from it restarted the inline one from zero too. Handing the position across
     * is what makes the two feel like one player.
     */
    var resumePosition by mutableStateOf(0L)
        private set
    /** Which title [resumePosition] belongs to, so a new title starts at 0. */
    private var resumeFor: Long = 0

    /** Remembered playback position for [id], or 0 when it is a different title. */
    fun positionFor(id: Long): Long = if (resumeFor == id) resumePosition else 0L

    /** Records the playhead; called on every surface change and on dispose. */
    fun rememberPosition(id: Long, ms: Long) {
        resumeFor = id
        resumePosition = ms.coerceAtLeast(0L)
    }

    /**
     * Video-fit preference for full screen.
     *
     * 0 = fit (whole frame visible, letterboxed), 1 = fill (crop the overflowing
     * edge), 2 = stretch (squash to the screen). Fill is what most people mean by
     * "make it bigger", but the default stays fit because cropping a subtitled
     * frame is how the bottom line of text gets cut off.
     */
    var videoScale by mutableStateOf(0)
        private set

    fun chooseVideoScale(mode: Int) {
        videoScale = mode.coerceIn(0, 2)
    }

    /**
     * Route to return to when the detail sheet is closed. Titles are opened from
     * the feed, from search results and from inside a collection, and each of
     * those has to be restored rather than dropping back to the tabs.
     */
    private var detailOrigin: Route = Route.Tabs

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

    // ---- reels -----------------------------------------------------------

    /**
     * Resolves a title for the swipe feed, sharing one request per id.
     *
     * Callers await this; [prefetchReels] warms the same cache ahead of time so the
     * await usually returns instantly.
     */
    suspend fun reelDetail(id: Long): Detail {
        reelStore[id]?.let { return it }
        val job = reelMutex.withLock {
            reelJobs.getOrPut(id) { viewModelScope.async { repo.detail(id) } }
        }
        val d = job.await()
        reelStore[id] = d
        return d
    }

    /**
     * Warms the cache for the pages likely to be swiped to next.
     *
     * Only a couple ahead: each miss costs a full page download plus parse, and
     * fetching the whole queue would compete with the video that is playing now.
     */
    fun prefetchReels(ids: List<Long>) {
        ids.forEach { id ->
            if (reelStore.containsKey(id)) return@forEach
            viewModelScope.launch {
                runCatching { reelDetail(id) }
            }
        }
    }

    // ---- swipe feed ------------------------------------------------------
    val reelCards = mutableStateListOf<Card>()
    var reelGenre by mutableStateOf("全部")
        private set
    /**
     * Random mode.
     *
     * The site has no random sort, but it does not need one: `watch?v=<id>` is a
     * contiguous integer space, so sampling ids *is* sampling the catalogue.
     * Misses are expected (deleted uploads redirect off-site), and the core rolls
     * again until it lands on a playable page.
     */
    var reelRandom by mutableStateOf(false)
        private set
    var reelLoading by mutableStateOf(false)
        private set
    var reelError by mutableStateOf<String?>(null)
        private set
    private var reelPage = 1
    private var reelMaxPage = 1
    private var reelHasNext = false
    private var reelJob: Job? = null

    /**
     * Swipe position, kept outside the composable.
     *
     * Reels is disposed while a title sheet is open, so the pager has to rebuild
     * from here — otherwise returning from a detail page restarts the stack at
     * the first clip.
     */
    var reelIndex by mutableStateOf(0)
        private set

    fun rememberReelIndex(index: Int) {
        reelIndex = index.coerceAtLeast(0)
    }

    fun openReels(genre: String = "全部") {
        reelGenre = genre
        route = Route.Reels(genre)
        loadReels(reset = true)
    }

    fun chooseReelRandom(on: Boolean) {
        if (reelRandom == on) return
        reelRandom = on
        loadReels(reset = true)
    }

    fun chooseReelGenre(genre: String) {
        if (reelGenre == genre) return
        reelGenre = genre
        loadReels(reset = true)
    }

    fun toggleReelRandom() {
        reelRandom = !reelRandom
        loadReels(reset = true)
    }

    /**
     * Pulls a batch for the swipe feed.
     *
     * Sequential mode walks pages 1..n of the current genre. Random mode instead
     * jumps to a page picked uniformly from the genre's real page range, read from
     * the first response; results are then shuffled inside the batch so two open
     * sessions do not start from the same title.
     */
    fun loadReels(reset: Boolean = false) {
        if (reelJob?.isActive == true) return
        if (!reset && !reelMayLoadMore()) return
        reelLoading = true
        reelError = null
        if (reset) {
            reelCards.clear()
            reelPage = 0
            reelMaxPage = 1
        }
        if (reelRandom) {
            loadRandomReels()
            return
        }
        val nextPage = reelPage + 1
        reelJob = viewModelScope.launch {
            runCatching {
                repo.searchFiltered(
                    query = "",
                    genre = reelGenre,
                    sort = "最新上市",
                    date = "",
                    duration = "",
                    tags = emptyList(),
                    broad = false,
                    page = nextPage,
                )
            }.onSuccess { lp ->
                reelMaxPage = lp.maxPage.coerceAtLeast(1)
                reelPage = nextPage
                val hide = settings.value.hideWatched
                val w = repo.watched.value
                val batch = if (hide) lp.items.filter { it.id !in w } else lp.items
                val seen = reelCards.mapTo(mutableSetOf()) { it.id }
                val fresh = batch.filter { seen.add(it.id) }
                // Shuffle only in random mode: sequential browsing should keep the
                // upload order the list is sorted by.
                reelCards.addAll(fresh)
                reelHasNext = lp.hasNext
                prefetchReels(reelCards.take(2).map { it.id })
            }.onFailure { reelError = it.userMessage() }
            reelLoading = false
        }
    }

    /**
     * Random batch built straight from random ids.
     *
     * Each fetch already returns the full detail, so the page can play without a
     * second round trip: the parsed [Detail]s go into [reelStore] and the swiper
     * only needs the card for the caption when it is off-screen.
     */
    private fun loadRandomReels() {
        reelJob = viewModelScope.launch {
            val batch = mutableListOf<Card>()
            val hide = settings.value.hideWatched
            val w = repo.watched.value
            var lastError: String? = null
            // A handful per batch keeps the first swipe fast; more load as the
            // user nears the end.
            while (batch.size < RANDOM_BATCH) {
                val d = runCatching { repo.randomDetail(reelGenre) }
                    .onFailure { lastError = it.userMessage() }
                    .getOrNull() ?: break
                if (hide && d.id in w) continue
                if (reelCards.any { it.id == d.id } || batch.any { it.id == d.id }) continue
                batch.add(d.toCard())
            }
            if (batch.isEmpty()) {
                reelError = lastError ?: "没抽到可播放的作品"
            } else {
                reelCards.addAll(batch)
                prefetchReels(reelCards.take(2).map { it.id })
            }
            reelHasNext = batch.isNotEmpty()
            reelLoading = false
        }
    }

    private fun reelMayLoadMore(): Boolean = reelRandom || reelHasNext

    fun closeReels() {
        if (route is Route.Reels) route = Route.Tabs
    }

    /** Bookmark toggle; the card on screen is passed in so nothing is refetched. */
    fun toggleFavorite(card: Card) {
        viewModelScope.launch {
            runCatching { repo.toggleFavorite(card) }
                .onFailure { notice = it.userMessage() }
        }
    }

    /**
     * Bookmark state read straight off the flow so every place that shows a heart
     * (grid, reels, detail) reflects one source of truth without extra plumbing.
     */
    fun isFavorite(id: Long): Boolean = repo.favorites.value.any { it.id == id }

    /** Marks a swipe-feed page watched as it becomes current, like opening it would. */
    fun markReelWatched(id: Long) {
        viewModelScope.launch { repo.markWatched(id) }
    }

    /**
     * Switches between the editorial board and the sortable grid.
     *
     * Switching to [FeedMode.Browse] keeps whatever genre/sort is already chosen, so
     * tapping 分类 while 裏番 is selected lands on that genre's grid instead of
     * resetting the user's context.
     */
    fun chooseMode(value: FeedMode) {
        if (mode == value) return
        mode = value
        refreshFeed()
    }

    fun chooseGenre(value: String) {
        // Picking a concrete genre is an explicit request to browse it, so leave the
        // editorial board even if the user never touched the mode switch.
        if (mode == FeedMode.Home && value != "全部") mode = FeedMode.Browse
        if (genre == value && mode == FeedMode.Browse) return
        genre = value
        refreshFeed()
    }

    fun chooseSort(value: String) {
        // Sorting only exists on the grid; silently staying on the editorial board
        // is what made the sort chips look broken under 全部.
        if (mode == FeedMode.Home) mode = FeedMode.Browse
        if (sort == value && mode == FeedMode.Browse) return
        sort = value
        refreshFeed()
    }

    fun chooseDate(value: String) {
        if (mode == FeedMode.Home) mode = FeedMode.Browse
        if (date == value) return
        date = value
        refreshFeed()
    }

    fun chooseDuration(value: String) {
        if (mode == FeedMode.Home) mode = FeedMode.Browse
        if (duration == value) return
        duration = value
        refreshFeed()
    }

    fun toggleTag(value: String) {
        if (mode == FeedMode.Home) mode = FeedMode.Browse
        if (!tags.remove(value)) tags.add(value)
        refreshFeed()
    }

    fun changeBroad(on: Boolean) {
        if (broad == on) return
        broad = on
        refreshFeed()
    }

    fun clearFilters() {
        if (tags.isEmpty() && date.isBlank() && duration.isBlank() && !broad) return
        tags.clear()
        date = ""
        duration = ""
        broad = false
        refreshFeed()
    }

    /** Fetched once, on first open of the tag sheet. */
    fun ensureTagGroups() {
        if (tagGroups.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { repo.tagGroups() }.onSuccess { tagGroups = it }
        }
    }

    /** Reloads whatever the current genre/sort/filter combination maps to. */
    fun refreshFeed() {
        feedJob?.cancel()
        error = null
        loading = true
        feedJob = viewModelScope.launch {
            runCatching {
                // The editorial rows have no sort parameter at all, so the grid is the
                // only shape that can honour a sort or filter.
                if (isGridMode) {
                    val page = repo.searchFiltered(
                        query = "", genre = genre, sort = sort,
                        date = date, duration = duration,
                        tags = tags.toList(), broad = broad, page = 1,
                    )
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
                val page = repo.searchFiltered(
                    query = "", genre = genre, sort = sort,
                    date = date, duration = duration,
                    tags = tags.toList(), broad = broad, page = feedPage + 1,
                )
                val known = feed.mapTo(HashSet()) { it.id }
                feed.addAll(page.items.filterNot { it.id in known })
                feedPage = page.page
                hasNext = page.hasNext
            }.onFailure { error = it.userMessage() }
            loadingMore = false
        }
    }

    // ---- search ----------------------------------------------------------

    fun openSearch() {
        route = Route.Search
    }

    fun submitSearch(text: String) {
        query = text
        searching = true
        searchError = null
        searchResults.clear()
        searchPage = 1
        viewModelScope.launch {
            runCatching {
                repo.searchFiltered(
                    query = text, genre = genre, sort = sort,
                    date = date, duration = duration,
                    tags = tags.toList(), broad = broad, page = 1,
                )
            }
                .onSuccess {
                    searchResults.addAll(it.items)
                    searchHasNext = it.hasNext
                    searchPage = it.page
                }
                .onFailure { searchError = it.userMessage() }
            searching = false
        }
    }

    fun searchMore() {
        if (searching || !searchHasNext) return
        searching = true
        viewModelScope.launch {
            runCatching {
                repo.searchFiltered(
                    query = query, genre = genre, sort = sort,
                    date = date, duration = duration,
                    tags = tags.toList(), broad = broad, page = searchPage + 1,
                )
            }
                .onSuccess {
                    val known = searchResults.mapTo(HashSet()) { it.id }
                    searchResults.addAll(it.items.filterNot { c -> c.id in known })
                    searchPage = it.page
                    searchHasNext = it.hasNext
                }
                .onFailure { searchError = it.userMessage() }
            searching = false
        }
    }

    // ---- collection ------------------------------------------------------

    /**
     * Localizes the collection name.
     *
     * The list itself renders immediately from the raw title; the translated one
     * replaces it when the request comes back, so a slow or offline translation
     * never delays the page.
     */
    fun openCollection(id: Long, title: String) {
        if (id <= 0L) return
        route = Route.Collection(id, title)
        collection = null
        collectionError = null
        collectionLoading = true
        collectionTitle = title
        viewModelScope.launch {
            runCatching { repo.playlist(id) }
                .onSuccess { collection = it }
                .onFailure { collectionError = it.userMessage() }
            collectionLoading = false
        }
        viewModelScope.launch {
            val localized = runCatching { repo.translateTitle(title) }.getOrDefault(title)
            if (localized.isNotBlank() && localized != title) {
                collectionTitle = localized
                collection = collection?.copy(title = localized)
            }
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
        // Remember where the user came from so [back] can unwind one level at a
        // time: a title opened from search results returns to those results.
        detailOrigin = when (val r = route) {
            is Route.Search -> Route.Search
            is Route.Collection -> Route.Collection(r.id, r.title)
            // Reels is a full-screen destination of its own: without this the back
            // button dropped the user on the tab feed and lost their place in the
            // swipe stack entirely.
            is Route.Reels -> Route.Reels(r.genre)
            else -> Route.Tabs
        }
        route = Route.Info(id, seed)
        detail = null
        detailError = null
        inlineSource = null
        detailLoading = true
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            runCatching { repo.detail(id) }
                .onSuccess { d ->
                    detail = d
                    // The sheet plays inline now: pick the user's preferred quality
                    // right away so the player shows up filled instead of as a poster.
                    inlineSource = d.sources.minByOrNull {
                        kotlin.math.abs(it.quality - settings.value.preferredQuality)
                    }
                }
                .onFailure { detailError = it.userMessage() }
            detailLoading = false
        }
    }

    /** Starts (or restarts) the inline player inside the detail sheet. */
    fun playInline(source: Source) {
        val d = detail ?: return
        inlineSource = source
        viewModelScope.launch { repo.markWatched(d.id) }
    }

    /** Opens the same title full screen, keeping the already chosen quality. */
    fun playFullscreen(source: Source? = null, positionMs: Long = 0L) {
        val d = detail ?: return
        val src = source ?: inlineSource ?: d.sources.maxByOrNull { it.quality } ?: return
        // Carry the playhead across: the full-screen route builds its own player,
        // and without this it would restart the video from the beginning.
        rememberPosition(d.id, positionMs)
        route = Route.Player(d, src)
        viewModelScope.launch { repo.markWatched(d.id) }
    }

    /** Leaves full screen, keeping the playhead so the inline player resumes there. */
    fun leaveFullscreen(detailId: Long, positionMs: Long) {
        rememberPosition(detailId, positionMs)
        route = Route.Info(detailId, null)
    }

    /**
     * Pops one level. Screens pushed on top of a detail page have to return to
     * it, not to the tabs: opening a collection from a title and then backing out
     * used to drop the user on the home feed.
     */
    fun back() {
        route = when (val r = route) {
            is Route.Player -> Route.Info(r.detail.id, null)
            // A collection lives on top of the title it was opened from.
            is Route.Collection -> Route.Info(r.id, null)
            is Route.Info -> detailOrigin
            is Route.Search -> Route.Tabs
            is Route.Reels -> Route.Tabs
            Route.Tabs -> Route.Tabs
        }
    }

    fun closeOverlay() {
        route = Route.Tabs
    }

    // ---- downloads -------------------------------------------------------

    fun download(quality: Int) {
        val d = detail ?: return
        viewModelScope.launch {
            // Passing the playlist through is what makes the file land in the
            // series folder instead of a flat downloads directory.
            runCatching { repo.startDownload(d.id, quality, d.listId, d.listTitle) }
                .onFailure { notice = it.userMessage() }
        }
    }

    /** Downloads a whole collection, one title after another. */
    fun downloadCollection(quality: Int) {
        val pl = collection ?: return
        if (pl.items.isEmpty()) return
        batchDownloading = true
        viewModelScope.launch {
            runCatching { repo.startPlaylistDownload(pl.id, quality) }
                .onSuccess { notice = "已加入 $it 个下载任务" }
                .onFailure { notice = it.userMessage() }
            batchDownloading = false
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
        detail = local
        inlineSource = local.sources.first()
        route = Route.Info(task.id, null)
        viewModelScope.launch { repo.markWatched(task.id) }
    }

    // ---- settings --------------------------------------------------------

    fun saveSettings(block: (Settings) -> Settings) {
        viewModelScope.launch { repo.saveSettings(block(settings.value)) }
    }

    fun clearWatched() = viewModelScope.launch { repo.clearWatched() }

    fun downloadDir(): String = repo.downloadDir()

    /** Asks GitHub for the newest release; the settings row compares it to BuildConfig. */
    fun checkForUpdate() {
        if (updateChecking) return
        updateChecking = true
        updateError = null
        viewModelScope.launch {
            runCatching { repo.checkUpdate() }
                .onSuccess { updateInfo = it }
                .onFailure { updateError = it.userMessage() }
            updateChecking = false
        }
    }

    /**
     * Pulls the update APK into the app cache and polls until it lands.
     *
     * The package installer only accepts a file this app owns, so the settings row
     * downloads first and installs second instead of handing a URL to the browser.
     */
    fun downloadUpdate(cacheDir: String) {
        val info = updateInfo ?: return
        if (info.url.isBlank()) return
        if (updateProgress.state == "running") return
        val dest = "$cacheDir/hanime-update-${info.version}.apk"
        updateProgress = com.hanime.app.core.UpdateProgress(state = "running")
        viewModelScope.launch {
            runCatching { repo.downloadUpdate(info.url, dest) }
                .onFailure { updateProgress = com.hanime.app.core.UpdateProgress(state = "error", detail = it.userMessage()) }
            // The transfer runs on a detached thread in Rust, so the coroutine only
            // has to observe it; 400 ms keeps the bar smooth without busy-looping.
            while (isActive) {
                delay(400)
                val p = runCatching { repo.updateProgress() }.getOrDefault(updateProgress)
                updateProgress = p
                if (p.state != "running") break
            }
        }
    }

    fun clearUpdateError() {
        updateError = null
        updateProgress = com.hanime.app.core.UpdateProgress()
    }

    fun clearNotice() {
        notice = null
    }
}

/** Card view of a detail, for feeds that arrive as full details. */
private fun com.hanime.app.core.Detail.toCard(): com.hanime.app.core.Card =
    com.hanime.app.core.Card(
        id = id,
        title = cnTitle.ifBlank { title },
        cover = cover.ifBlank { poster },
        dur = if (duration > 0) "%d:%02d".format(duration / 60, duration % 60) else "",
        score = likePct,
        views = views,
        author = author,
        time = date,
        wide = cover.contains("/image/thumbnail/") || cover.isBlank(),
    )
