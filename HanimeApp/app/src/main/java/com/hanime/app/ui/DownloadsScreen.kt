package com.hanime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hanime.app.core.Card
import com.hanime.app.core.DownloadTask
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop

/** A finished or in-flight download rendered as a local-library entry. */
private fun DownloadTask.asCard() = Card(
    id = id,
    title = title,
    cover = cover,
    dur = if (isDone) "已下载" else "${(progress * 100).toInt()}%",
)

/**
 * Page 2: downloads *and* the local library — one list, because a downloaded file
 * is exactly what makes something "local". Running transfers sort first.
 */
@Composable
fun DownloadsScreen(
    vm: AppViewModel,
    downloads: List<DownloadTask>,
    topInset: Int,
    onPlayLocal: (DownloadTask) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenFavorite: (Card) -> Unit,
) {
    // Three libraries share this page because they answer the same question ("what
    // do I already have?"): downloaded files are local, favourites are on-site but
    // bookmarked, and a collection is a finished series already filed in its own
    // folder. A segmented control beats three more bottom tabs.
    var segment by remember { mutableStateOf(0) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val active = downloads.filter { it.isRunning || it.isPaused }
    val done = downloads.filter { it.isDone }
    val other = downloads.filter { !it.isRunning && !it.isPaused && !it.isDone }

    // Downloaded episodes of one collection are filed under a folder named after
    // it, so the library mirrors the disk layout: the same series groups together
    // instead of scattering through the newest-first list.
    val collections = done
        .filter { it.listId > 0L && it.listTitle.isNotBlank() }
        .groupBy { it.listTitle }
        .toSortedMap()

    val favorites by vm.favorites.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        if (segment == 2) {
            CollectionsList(
                collections = collections,
                topInset = topInset,
                onPlay = onPlayLocal,
                onOpenDetail = onOpenDetail,
            )
        } else if (segment == 1) {
            FavoritesList(
                favorites = favorites,
                topInset = topInset,
                onOpen = onOpenFavorite,
                onRemove = { vm.toggleFavorite(it) },
            )
        } else LazyColumn(
            contentPadding = PaddingValues(top = (topInset + 112).dp, bottom = 126.dp, start = 14.dp, end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (active.isNotEmpty()) {
                item { GroupLabel("正在下载 · ${active.size}") }
                items(active, key = { "a${it.id}" }) { task ->
                    ActiveRow(
                        task = task,
                        onClick = { onOpenDetail(task.id) },
                        onCancel = { vm.abortDownload(task.id) },
                    )
                }
            }
            if (done.isNotEmpty()) {
                // Grouped by the folder they were written to, so a downloaded
                // series reads as a series instead of N unrelated rows.
                val groups = done.groupBy { it.listTitle.ifBlank { "未分类" } }
                groups.forEach { (name, tasks) ->
                    item(key = "g$name") { GroupLabel("$name · ${tasks.size} 个文件") }
                    items(tasks, key = { "d${it.id}" }) { task ->
                        ListRow(
                            card = task.asCard(),
                            watched = false,
                            onClick = { onPlayLocal(task) },
                            trailing = {
                                IconButtonSmall(
                                    icon = Icons.Rounded.DeleteOutline,
                                    contentDescription = "删除",
                                    onClick = { expandedId = if (expandedId == task.id) null else task.id },
                                )
                            },
                        )
                        if (expandedId == task.id) {
                            ConfirmDelete(
                                onDeleteFile = {
                                    vm.removeDownload(task.id, true)
                                    expandedId = null
                                },
                                onKeepFile = {
                                    vm.removeDownload(task.id, false)
                                    expandedId = null
                                },
                            )
                        }
                    }
                }
            }
            if (other.isNotEmpty()) {
                item { GroupLabel("未完成") }
                items(other, key = { "o${it.id}" }) { task ->
                    ListRow(
                        card = task.asCard(),
                        watched = false,
                        onClick = { onOpenDetail(task.id) },
                        trailing = {
                            IconButtonSmall(
                                icon = Icons.Rounded.Close,
                                contentDescription = "移除",
                                onClick = { vm.removeDownload(task.id, false) },
                            )
                        },
                    )
                }
            }
            if (downloads.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("本地还没有内容", fontSize = 13.sp, color = Ink.TextSecondary)
                            Text(
                                "在详情页选择清晰度即可缓存到这里",
                                fontSize = 11.5.sp,
                                color = Ink.TextTertiary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = "存储位置 · " + vm.downloadDir(),
                    fontSize = 10.sp,
                    color = Ink.TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 18.dp, start = 6.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Ink.Bg, Ink.Bg.copy(alpha = 0.9f), Color.Transparent)
                    )
                )
                .padding(start = 18.dp, end = 18.dp, top = (topInset + 10).dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (segment) {
                    0 -> "下载"
                    1 -> "收藏"
                    else -> "合集"
                },
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (segment == 0) "${done.size} 个本地文件"
                else if (segment == 1) "${favorites.size} 部作品"
                else "${collections.size} 个合集",
                fontSize = 11.sp,
                color = Ink.TextTertiary,
            )
        }

        // Segmented control sits under the title, over the list.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = (topInset + 46).dp, start = 18.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("下载", "收藏", "合集").forEachIndexed { index, label ->
                SegmentTab(
                    label = label,
                    selected = segment == index,
                    onClick = { segment = index },
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.TextTertiary,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Row with a live progress bar for a transfer in flight. */
@Composable
private fun ActiveRow(task: DownloadTask, onClick: () -> Unit, onCancel: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = task.cover,
            contentDescription = null,
            modifier = Modifier
                .width(96.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Ink.SurfaceHigh),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = task.title,
                fontSize = 12.5.sp,
                color = Ink.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${task.quality}p · " + task.sizeText() +
                    if (task.isPaused) " · 已暂停" else "",
                fontSize = 10.5.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50)),
                color = Ink.Accent,
                trackColor = Color.White.copy(alpha = 0.1f),
            )
        }
        IconButtonSmall(
            icon = if (task.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            contentDescription = "取消",
            onClick = onCancel,
        )
    }
}

@Composable
private fun IconButtonSmall(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Ink.TextTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Two-step delete: file vs. entry only, so a stray tap never loses data. */
@Composable
private fun ConfirmDelete(onDeleteFile: () -> Unit, onKeepFile: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "删除文件",
            fontSize = 11.5.sp,
            color = Ink.AccentSoft,
            modifier = Modifier
                .clickable { onDeleteFile() }
                .padding(vertical = 4.dp),
        )
        Text(
            "仅移出列表",
            fontSize = 11.5.sp,
            color = Ink.TextSecondary,
            modifier = Modifier
                .clickable { onKeepFile() }
                .padding(vertical = 4.dp),
        )
    }
}

/** Small text tab with an underline, matching the home page's mode switch. */
@Composable
private fun SegmentTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Ink.TextPrimary else Ink.TextTertiary,
        )
    }
}

/** Bookmark list rendered inside the downloads page. */
@Composable
private fun FavoritesList(
    favorites: List<Card>,
    topInset: Int,
    onOpen: (Card) -> Unit,
    onRemove: (Card) -> Unit,
) {
    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有收藏", fontSize = 13.sp, color = Ink.TextSecondary)
                Text(
                    "点作品上的爱心即可保存，不占空间",
                    fontSize = 11.5.sp,
                    color = Ink.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = (topInset + 112).dp, bottom = 126.dp, start = 14.dp, end = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(favorites, key = { it.id }) { card ->
            ListRow(
                card = card,
                watched = false,
                onClick = { onOpen(card) },
                trailing = {
                    IconButtonSmall(
                        icon = Icons.Rounded.Favorite,
                        contentDescription = "取消收藏",
                        onClick = { onRemove(card) },
                    )
                },
            )
        }
    }
}

/**
 * Downloaded series, one card per folder.
 *
 * A collection download writes every episode into a folder named after the
 * collection, so the disk already has this shape — the page just shows it. Entry
 * taps open the folder's first episode; the row underneath lists the episodes in
 * their original order so "第 3 集" is where the user expects it, not sorted by
 * whichever download finished last.
 */
@Composable
private fun CollectionsList(
    collections: Map<String, List<DownloadTask>>,
    topInset: Int,
    onPlay: (DownloadTask) -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    if (collections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有合集", fontSize = 13.sp, color = Ink.TextSecondary)
                Text(
                    "在合集页点「全部下载」，整季会自动归到一个文件夹",
                    fontSize = 11.5.sp,
                    color = Ink.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }
    var expandedName by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(top = (topInset + 112).dp, bottom = 126.dp, start = 14.dp, end = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        collections.forEach { (name, tasks) ->
            item(key = "c$name") {
                CollectionHeader(
                    name = name,
                    count = tasks.size,
                    expanded = expandedName == name,
                    onToggle = { expandedName = if (expandedName == name) null else name },
                    onPlay = { onPlay(tasks.first()) },
                )
            }
            if (expandedName == name) {
                items(tasks, key = { "e${it.id}" }) { task ->
                    ListRow(
                        card = task.asCard(),
                        watched = false,
                        onClick = { onPlay(task) },
                        trailing = {
                            IconButtonSmall(
                                icon = Icons.Rounded.PlayArrow,
                                contentDescription = "播放",
                                onClick = { onPlay(task) },
                            )
                        },
                    )
                }
            }
        }
        item {
            Text(
                text = "每个合集是一个文件夹，展开即可按集播放",
                fontSize = 10.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(top = 18.dp, start = 6.dp),
            )
        }
    }
}

@Composable
private fun CollectionHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.SurfaceHigh)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = Ink.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$count 集",
                fontSize = 10.5.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButtonSmall(
            icon = Icons.Rounded.PlayArrow,
            contentDescription = "播放第一集",
            onClick = onPlay,
        )
    }
}
