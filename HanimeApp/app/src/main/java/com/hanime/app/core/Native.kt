package com.hanime.app.core

/**
 * Thin binding to the Rust core (`libhanime_core.so`).
 *
 * Every call is blocking and does network or disk I/O, so callers must dispatch
 * it off the main thread. Results come back as JSON that [Json] parses; an error
 * is surfaced as a JSON object with a single `error` key.
 */
object Native {
    init {
        System.loadLibrary("hanime_core")
    }

    external fun init(dir: String)

    /** Platform HTTP proxy; empty string clears it. Overridden by the Settings value. */
    external fun setProxy(value: String)

    external fun fetchHome(): String
    external fun fetchList(genre: String, sort: String, date: String, duration: String, page: Int): String
    external fun fetchSearch(query: String, page: Int): String

    /**
     * `/search` with the full filter set. [tags] are joined by U+0001 because the
     * values contain commas and pipes that would break a plainer separator.
     */
    external fun fetchSearchFiltered(
        query: String,
        genre: String,
        sort: String,
        date: String,
        duration: String,
        tags: String,
        broad: Boolean,
        page: Int,
    ): String

    external fun tagGroups(): String
    external fun fetchPlaylist(id: Long): String

    external fun fetchDetail(id: Long): String

    external fun startDownload(id: Long, quality: Int, listId: Long, listTitle: String): String

    /** Queues a whole playlist; returns `{"queued":N}`. */
    external fun startPlaylistDownload(listId: Long, quality: Int): String

    /** Latest GitHub release for the in-app updater. */
    external fun checkUpdate(): String

    /** Downloads the update APK to [dest] on a detached thread; poll [updateDownloadState]. */
    external fun downloadUpdate(url: String, dest: String): String

    /** `{"done":N,"total":M,"state":"running|done|error","detail":"…"}`. */
    external fun updateDownloadState(): String

    /** Localizes a Japanese collection title; returns the input when unavailable. */
    external fun translateTitle(text: String): String

    external fun cancelDownload(id: Long)
    external fun removeDownload(id: Long, deleteFile: Boolean)
    external fun rescanLibrary()
    external fun libraryJson(): String
    external fun downloadDir(): String
    external fun localFileExists(id: Long): Boolean

    external fun markWatched(id: Long)
    external fun isWatched(id: Long): Boolean
    external fun watchedJson(): String
    external fun clearWatched()

    /** Favourites are bookmarks only: they never touch the download library. */
    external fun favoritesJson(): String
    external fun isFavorite(id: Long): Boolean

    /** Adds when absent, removes when present; returns the whole list as JSON. */
    external fun toggleFavorite(cardJson: String): String
    external fun clearFavorites()

    external fun getSettingsJson(): String
    external fun setSettingsJson(json: String): String
}
