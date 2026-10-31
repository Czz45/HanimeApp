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
) {
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val active = downloads.filter { it.isRunning || it.isPaused }
    val done = downloads.filter { it.isDone }
    val other = downloads.filter { !it.isRunning && !it.isPaused && !it.isDone }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(top = (topInset + 74).dp, bottom = 126.dp, start = 14.dp, end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (active.isNotEmpty()) {
                item { GroupLabel("正在下载 · ${active.size}") }
                items(active, key = { "a${it.id}" }) { task ->
                    ActiveRow(
                        task = task,
                        onClick = { onOpenDetail(task.id) },
                        onCancel = { vm.cancelDownload(task.id) },
                    )
                }
            }
            if (done.isNotEmpty()) {
                item { GroupLabel("已下载 · ${done.size}") }
                items(done, key = { "d${it.id}" }) { task ->
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
            Text("下载", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("${done.size} 个本地文件", fontSize = 11.sp, color = Ink.TextTertiary)
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
