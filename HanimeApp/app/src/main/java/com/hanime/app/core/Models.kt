package com.hanime.app.core

data class Card(
    val id: Long,
    val title: String,
    val cover: String,
    val dur: String = "",
    val score: String = "",
    val views: String = "",
    val author: String = "",
    val time: String = "",
    /** 16:9 still frame (home rows, 3DCG/MMD lists) vs portrait DVD cover (裏番/泡麵番). */
    val wide: Boolean = true,
) {
    /** "• 1小時前" -> "1小時前"; the bullet is decoration, not data. */
    val timeText: String get() = time.trimStart('•', ' ', '\u00a0').trim()
}

data class Section(val title: String, val items: List<Card>)

data class Source(val quality: Int, val url: String)

data class Detail(
    val id: Long,
    val title: String,
    /** Localized title; equals [title] when the upload was never localized. */
    val cnTitle: String = "",
    val cover: String,
    val poster: String = "",
    val duration: Int = 0,
    val views: String = "",
    val date: String = "",
    val author: String = "",
    val genre: String = "",
    val likePct: String = "",
    val likeCount: String = "",
    val tags: List<String> = emptyList(),
    val desc: String = "",
    val sources: List<Source> = emptyList(),
    val related: List<Card> = emptyList(),
    /** Playlist (collection) this upload belongs to; 0 when standalone. */
    val listId: Long = 0,
    val listTitle: String = "",
) {
    fun durationText(): String {
        if (duration <= 0) return ""
        val h = duration / 3600
        val m = (duration % 3600) / 60
        val s = duration % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

data class ListPage(
    val items: List<Card>,
    val page: Int,
    val maxPage: Int,
    val hasNext: Boolean,
)

/** One checkbox in the tag filter. [value] is what the site expects on the wire. */
data class Tag(val value: String, val label: String)

data class TagGroup(val name: String, val tags: List<Tag>)

/** A collection page (`/playlist?list=`). */
data class Playlist(
    val id: Long,
    val title: String = "",
    val author: String = "",
    val count: Int = 0,
    val items: List<Card> = emptyList(),
)

data class DownloadTask(
    val id: Long,
    val title: String,
    val cover: String,
    val quality: Int,
    val path: String,
    val total: Long,
    val done: Long,
    val state: String,
    val error: String = "",
) {
    val progress: Float
        get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    val isRunning: Boolean get() = state == "running" || state == "queued"
    val isDone: Boolean get() = state == "done"
    val isPaused: Boolean get() = state == "paused"

    fun sizeText(): String {
        fun human(b: Long): String = when {
            b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
            else -> "$b B"
        }
        return if (total > 0) human(done) + " / " + human(total) else human(done)
    }
}

data class Settings(
    val proxy: String = "",
    val hideWatched: Boolean = true,
    val preferredQuality: Int = 1080,
    val storageDir: String = "",
    val autoPlay: Boolean = false,
)

/** Thrown when the Rust core reports a failure instead of a payload. */
class CoreException(message: String) : Exception(message)
