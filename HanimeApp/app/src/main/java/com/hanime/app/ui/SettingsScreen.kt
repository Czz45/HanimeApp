package com.hanime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanime.app.core.Settings
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop

/**
 * Page 3: settings. Only the switches that change behaviour of the core are here;
 * a proxy field exists because the site is unreachable on some networks.
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    settings: Settings,
    topInset: Int,
    backdrop: Backdrop?,
    onHideWatched: (Boolean) -> Unit,
    onQuality: (Int) -> Unit,
    onAutoPlay: (Boolean) -> Unit,
    onSeekSeconds: (Int) -> Unit,
    onProxy: (String) -> Unit,
    onClearWatched: () -> Unit,
    downloadDir: String,
    watchedCount: Int,
    appVersion: String,
) {
    var proxy by remember(settings.proxy) { mutableStateOf(settings.proxy) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = (topInset + 16).dp, bottom = 130.dp),
    ) {
        Text(
            "设置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Ink.TextPrimary,
            modifier = Modifier.padding(start = 2.dp, bottom = 16.dp, top = 6.dp),
        )

        SettingsGroup("播放体验", backdrop) {
            SettingSwitch(
                title = "自动播放",
                subtitle = "进入详情页后自动开始播放",
                checked = settings.autoPlay,
                onCheckedChange = onAutoPlay,
            )
            Divider()
            Text(
                "默认清晰度",
                fontSize = 13.sp,
                color = Ink.TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1080, 720, 480).forEach { q ->
                    GlassChip(
                        text = "${q}p",
                        selected = settings.preferredQuality == q,
                        backdrop = backdrop,
                        onClick = { onQuality(q) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Divider()
            Text(
                "拖动调速",
                fontSize = 13.sp,
                color = Ink.TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Text(
                "播放时横向滑动一整屏所跨过的时间。范围越小越容易精确定位。",
                fontSize = 10.5.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(30, 60, 90, 180, 300).forEach { sec ->
                    GlassChip(
                        text = if (sec < 60) "${sec}秒" else "${sec / 60}分钟",
                        selected = settings.seekSeconds == sec,
                        backdrop = backdrop,
                        onClick = { onSeekSeconds(sec) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(14.dp))

        SettingsGroup("内容过滤", backdrop) {
            SettingSwitch(
                title = "屏蔽已观看",
                subtitle = "首页与列表不再显示看过的条目（已记录 $watchedCount 条）",
                checked = settings.hideWatched,
                onCheckedChange = onHideWatched,
            )
            Divider()
            ActionRow(
                title = "清空观看记录",
                subtitle = "移除后可重新在首页看到这些条目",
                actionLabel = "清空",
                onClick = onClearWatched,
            )
        }

        Spacer(Modifier.height(14.dp))

        SettingsGroup("网络", backdrop) {
            Text(
                "HTTP 代理",
                fontSize = 13.sp,
                color = Ink.TextPrimary,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp),
            )
            Text(
                "形如 http://127.0.0.1:7890，留空则直连",
                fontSize = 11.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(start = 14.dp, top = 3.dp),
            )
            androidx.compose.foundation.text.BasicTextField(
                value = proxy,
                onValueChange = { proxy = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Ink.TextPrimary,
                    fontSize = 12.5.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                GlassChip(
                    text = "保存代理",
                    selected = true,
                    backdrop = backdrop,
                    onClick = { onProxy(proxy.trim()) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsGroup("存储", backdrop) {
            Text(
                "下载目录",
                fontSize = 13.sp,
                color = Ink.TextPrimary,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp),
            )
            Text(
                downloadDir,
                fontSize = 10.5.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 6.dp),
            )
            Text(
                "合集下载会按合集名自动建子文件夹",
                fontSize = 10.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        ReelStatsGroup(vm, backdrop, settings.hideWatched)

        Spacer(Modifier.height(14.dp))

        CacheGroup(backdrop)

        Spacer(Modifier.height(14.dp))

        UpdateGroup(vm, appVersion, backdrop)

        Spacer(Modifier.height(20.dp))

        Text(
            "Hanime · Rust 核心 + Jetpack Compose\n第三方客户端，内容版权归原作者所有",
            fontSize = 10.5.sp,
            color = Ink.TextTertiary,
            lineHeight = 15.sp,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp),
        )
    }
}

@Composable
private fun UpdateGroup(vm: AppViewModel, appVersion: String, backdrop: Backdrop?) {
    val info = vm.updateInfo
    val progress = vm.updateProgress
    val hasNewer = info != null && info.version.isNotBlank() && info.version != appVersion &&
        runCatching { compareVersions(info.version, appVersion) > 0 }.getOrDefault(false)
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloading = progress.state == "running"

    // Install once the transfer lands. Doing it here rather than inside the click
    // keeps the button single-purpose and survives a recomposition mid-download.
    LaunchedEffect(progress.state, progress.detail) {
        if (progress.state == "done" && progress.detail.isNotBlank()) {
            installApk(context, progress.detail)
        }
    }

    SettingsGroup("关于", backdrop) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前版本", fontSize = 13.sp, color = Ink.TextPrimary)
                Text(
                    when {
                        downloading -> "正在下载 ${(progress.fraction * 100).toInt()}%"
                        progress.state == "error" -> "下载失败：${progress.detail}"
                        vm.updateChecking -> "正在检查…"
                        vm.updateError != null -> "检查失败：${vm.updateError}"
                        hasNewer -> "有新版本 ${info!!.version}"
                        info != null -> "已是最新版本"
                        else -> "v$appVersion"
                    },
                    fontSize = 10.5.sp,
                    color = when {
                        progress.state == "error" -> Color(0xFFFF8A8A)
                        vm.updateError != null -> Color(0xFFFF8A8A)
                        hasNewer -> Ink.Mint
                        else -> Ink.TextTertiary
                    },
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                "v$appVersion",
                fontSize = 11.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(end = 10.dp),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Ink.Accent.copy(alpha = if (downloading) 0.10f else 0.18f))
                    .clickable(enabled = !vm.updateChecking && !downloading) {
                        if (hasNewer) {
                            // Android's installer only accepts a local file this app
                            // owns, so pull the APK into the cache first.
                            vm.downloadUpdate(context.cacheDir.absolutePath)
                        } else {
                            vm.checkForUpdate()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        downloading -> "${(progress.fraction * 100).toInt()}%"
                        vm.updateChecking -> "检查中"
                        hasNewer -> "下载更新"
                        else -> "检查更新"
                    },
                    fontSize = 11.sp,
                    color = Ink.AccentSoft,
                )
            }
        }
        if (downloading) {
            Divider()
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.10f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.fraction)
                        .fillMaxHeight()
                        .background(Ink.Accent)
                )
            }
        }
        if (hasNewer && info!!.notes.isNotBlank()) {
            Divider()
            Text(
                info.notes.trim().take(300),
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = Ink.TextTertiary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Sampler telemetry for the short-video feed.
 *
 * The point is to make "random" auditable. The site's id space is two dense
 * clusters with a ~234k empty band between them, so a bar chart with purpose-made
 * buckets shows at a glance whether the feed is mixing across the catalogue or
 * stuck in one slice — and if a sample ever lands in the empty band, the caption
 * turns pink instead of quietly looking fine.
 *
 * Hidden while 屏蔽已观看 is off would be wrong: the histogram is about the
 * sampler, not about filtering. It is always available and refreshes on entry.
 */
@Composable
private fun ReelStatsGroup(vm: AppViewModel, backdrop: Backdrop?, hideWatched: Boolean) {
    LaunchedEffect(Unit) { vm.refreshReelStats() }
    val stats = vm.reelStats

    SettingsGroup("短视频抽样分布", backdrop) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            ReelStatsChart(stats)
            if (stats.total > 0) {
                Text(
                    if (hideWatched)
                        "已开启屏蔽已观看，已经看过的序号不会再出现"
                    else
                        "屏蔽已观看当前关闭，同一个序号可能重复出现",
                    fontSize = 9.5.sp,
                    lineHeight = 14.sp,
                    color = Ink.TextTertiary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Divider()
        ActionRow(
            title = "抽样记录",
            subtitle = "只记录短视频里真正滑到的序号，用于上面的分布图",
            actionLabel = if (vm.reelStatsLoading) "刷新中" else "刷新",
            onClick = { vm.refreshReelStats() },
        )
        Divider()
        ActionRow(
            title = "清空记录",
            subtitle = "不会删除观看历史，只清空这张图的数据",
            actionLabel = "清空",
            onClick = { vm.clearReelStats() },
        )
    }
}

/**
 * Shows and clears the two caches that keep scrolling and playback from reloading.
 *
 * They are separate on purpose: cover art is cheap to re-fetch and the video cache
 * holds real megabytes, so users may want to drop one without the other.
 */
@Composable
private fun CacheGroup(backdrop: Backdrop?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var imageBytes by remember { mutableStateOf(0L) }
    var videoBytes by remember { mutableStateOf(0L) }

    fun refresh() {
        imageBytes = dirSize(java.io.File(context.cacheDir, "image_cache"))
        videoBytes = dirSize(java.io.File(context.cacheDir, "video_cache"))
    }
    LaunchedEffect(Unit) { refresh() }

    SettingsGroup("缓存", backdrop) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("封面图片", fontSize = 13.sp, color = Ink.TextPrimary)
                Text(
                    if (imageBytes > 0) formatBytes(imageBytes) else "暂无缓存",
                    fontSize = 10.5.sp,
                    color = Ink.TextTertiary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            ClearButton {
                java.io.File(context.cacheDir, "image_cache").deleteRecursively()
                coil3.SingletonImageLoader.get(context).memoryCache?.clear()
                coil3.SingletonImageLoader.get(context).diskCache?.clear()
                refresh()
            }
        }
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("视频播放缓存", fontSize = 13.sp, color = Ink.TextPrimary)
                Text(
                    if (videoBytes > 0) formatBytes(videoBytes) else "暂无缓存",
                    fontSize = 10.5.sp,
                    color = Ink.TextTertiary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            ClearButton {
                // SimpleCache keeps an in-memory index over the folder, so it has to
                // be released before the files can be removed.
                com.hanime.app.MediaCache.release()
                java.io.File(context.cacheDir, "video_cache").deleteRecursively()
                refresh()
            }
        }
        Text(
            "视频缓存可减少重复播放和拖动时的加载，上限 2 GB",
            fontSize = 10.sp,
            color = Ink.TextTertiary,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("清除", fontSize = 11.sp, color = Ink.TextSecondary)
    }
}

private fun dirSize(dir: java.io.File): Long =
    if (!dir.exists()) 0L else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/** Numeric, segment-wise compare so 1.0.10 sorts above 1.0.9. */
private fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x - y
    }
    return 0
}

/**
 * Hands the downloaded APK to the system installer.
 *
 * The cache file is exposed through the manifest's FileProvider: a `file://` URI
 * throws FileUriExposedException from API 24 on, and the installer also needs an
 * explicit read grant on the URI it receives.
 */
private fun installApk(context: android.content.Context, path: String) {
    runCatching {
        val file = java.io.File(path)
        if (!file.exists()) throw IllegalStateException("安装包不存在")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    backdrop: Backdrop?,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            title,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.TextTertiary,
            modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(backdrop, RoundedCornerShape(18.dp), Color.White.copy(alpha = 0.035f), 20f, 6f)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            content()
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .height(0.6.dp)
            .background(Color.White.copy(alpha = 0.07f))
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Ink.TextPrimary)
            Text(
                subtitle,
                fontSize = 10.5.sp,
                color = Ink.TextTertiary,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ink.Accent,
                uncheckedThumbColor = Color(0xFF9A9AA8),
                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                uncheckedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Ink.TextPrimary)
            Text(
                subtitle,
                fontSize = 10.5.sp,
                color = Ink.TextTertiary,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            actionLabel,
            fontSize = 12.sp,
            color = Ink.AccentSoft,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
