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

    external fun startDownload(id: Long, quality: Int): String
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

    external fun getSettingsJson(): String
    external fun setSettingsJson(json: String): String
}
