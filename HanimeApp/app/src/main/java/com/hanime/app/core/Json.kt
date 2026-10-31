package com.hanime.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-rolled JSON mapping for the Rust core's payloads.
 *
 * The project has no kotlinx-serialization-json in the local dependency cache, and
 * `org.json` ships with the platform, so the bridge stays dependency free.
 */
object Json {

    private fun optString(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")

    private fun optLong(o: JSONObject, key: String): Long = o.optLong(key, 0L)

    /**
     * Best-effort human message from a payload that is not the expected shape.
     * The core reports failures as `{"error": ...}` even for array endpoints, so
     * unwrap that before falling back to raw text the user should never see.
     */
    private fun failureText(raw: String): String {
        if (raw.isBlank()) return "网络请求失败"
        runCatching { JSONObject(raw) }
            .getOrNull()
            ?.let { if (it.has("error")) return optString(it, "error").ifBlank { "操作失败" } }
        return "响应异常"
    }

    fun card(o: JSONObject): Card = Card(
        id = optLong(o, "id"),
        title = optString(o, "title"),
        cover = optString(o, "cover"),
        dur = optString(o, "dur"),
        score = optString(o, "score"),
        views = optString(o, "views"),
        author = optString(o, "author"),
        time = optString(o, "time"),
        wide = if (o.has("wide")) o.optBoolean("wide", true) else !optString(o, "cover").contains("/image/cover/"),
    )

    /**
     * Runs [block] and turns an `{"error": ...}` payload into a [CoreException].
     * Every core call funnels through here so callers only deal with two paths.
     */
    fun <T> parse(raw: String, block: (JSONObject) -> T): T {
        val obj = runCatching { JSONObject(raw) }
            .getOrElse { throw CoreException(failureText(raw)) }
        // Only a non-blank `error` is a failure: DownloadTask carries an always-present
        // (usually empty) error field, and treating its presence as failure made every
        // download report "操作失败" while it actually started fine.
        if (obj.has("error") && optString(obj, "error").isNotBlank()) {
            throw CoreException(optString(obj, "error"))
        }
        return block(obj)
    }

    fun sections(raw: String): List<Section> {
        val arr = runCatching { JSONArray(raw) }
            .getOrElse { throw CoreException(failureText(raw)) }
        val out = ArrayList<Section>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val items = s.optJSONArray("items") ?: JSONArray()
            val cards = ArrayList<Card>(items.length())
            for (j in 0 until items.length()) {
                items.optJSONObject(j)?.let { cards.add(card(it)) }
            }
            out.add(Section(optString(s, "title"), cards))
        }
        return out
    }

    fun listPage(raw: String): ListPage = parse(raw) { o ->
        val arr = o.optJSONArray("items") ?: JSONArray()
        val cards = ArrayList<Card>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { cards.add(card(it)) }
        }
        ListPage(
            items = cards,
            page = o.optInt("page", 1),
            maxPage = o.optInt("max_page", 1),
            hasNext = o.optBoolean("has_next", false),
        )
    }

    fun detail(raw: String): Detail = parse(raw) { o ->
        val tagsArr = o.optJSONArray("tags") ?: JSONArray()
        val tags = ArrayList<String>(tagsArr.length())
        for (i in 0 until tagsArr.length()) tags.add(tagsArr.optString(i, ""))

        val srcArr = o.optJSONArray("sources") ?: JSONArray()
        val sources = ArrayList<Source>(srcArr.length())
        for (i in 0 until srcArr.length()) {
            val so = srcArr.optJSONObject(i) ?: continue
            sources.add(Source(so.optInt("quality", 0), optString(so, "url")))
        }

        val relArr = o.optJSONArray("related") ?: JSONArray()
        val related = ArrayList<Card>(relArr.length())
        for (i in 0 until relArr.length()) {
            relArr.optJSONObject(i)?.let { related.add(card(it)) }
        }

        Detail(
            id = optLong(o, "id"),
            title = optString(o, "title"),
            cnTitle = optString(o, "cn_title"),
            cover = optString(o, "cover"),
            poster = optString(o, "poster"),
            duration = o.optInt("duration", 0),
            views = optString(o, "views"),
            date = optString(o, "date"),
            author = optString(o, "author"),
            genre = optString(o, "genre"),
            likePct = optString(o, "like_pct"),
            likeCount = optString(o, "like_count"),
            tags = tags,
            desc = optString(o, "desc"),
            sources = sources,
            related = related,
            listId = optLong(o, "list_id"),
            listTitle = optString(o, "list_title"),
        )
    }

    fun tagGroups(raw: String): List<TagGroup> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<TagGroup>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val tArr = g.optJSONArray("tags") ?: JSONArray()
            val tags = ArrayList<Tag>(tArr.length())
            for (j in 0 until tArr.length()) {
                val t = tArr.optJSONObject(j) ?: continue
                tags.add(Tag(optString(t, "value"), optString(t, "label")))
            }
            out.add(TagGroup(optString(g, "name"), tags))
        }
        return out
    }

    fun playlist(raw: String): Playlist = parse(raw) { o ->
        val arr = o.optJSONArray("items") ?: JSONArray()
        val cards = ArrayList<Card>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { cards.add(card(it)) }
        }
        Playlist(
            id = optLong(o, "id"),
            title = optString(o, "title"),
            author = optString(o, "author"),
            count = o.optInt("count", cards.size),
            items = cards,
        )
    }

    fun tasks(raw: String): List<DownloadTask> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<DownloadTask>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                DownloadTask(
                    id = optLong(o, "id"),
                    title = optString(o, "title"),
                    cover = optString(o, "cover"),
                    quality = o.optInt("quality", 0),
                    path = optString(o, "path"),
                    total = optLong(o, "total"),
                    done = optLong(o, "done"),
                    state = optString(o, "state"),
                    error = optString(o, "error"),
                )
            )
        }
        return out
    }

    fun settings(raw: String): Settings = runCatching {
        val o = JSONObject(raw)
        Settings(
            proxy = optString(o, "proxy"),
            hideWatched = o.optBoolean("hide_watched", true),
            preferredQuality = o.optInt("preferred_quality", 1080),
            storageDir = optString(o, "storage_dir"),
            autoPlay = o.optBoolean("auto_play", false),
        )
    }.getOrElse { Settings() }

    fun settingsJson(s: Settings): String = JSONObject()
        .put("proxy", s.proxy)
        .put("hide_watched", s.hideWatched)
        .put("preferred_quality", s.preferredQuality)
        .put("storage_dir", s.storageDir)
        .put("auto_play", s.autoPlay)
        .toString()
}
