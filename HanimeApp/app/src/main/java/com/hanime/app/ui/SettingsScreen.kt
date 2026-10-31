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
    settings: Settings,
    topInset: Int,
    backdrop: Backdrop?,
    onHideWatched: (Boolean) -> Unit,
    onQuality: (Int) -> Unit,
    onAutoPlay: (Boolean) -> Unit,
    onProxy: (String) -> Unit,
    onClearWatched: () -> Unit,
    downloadDir: String,
    watchedCount: Int,
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
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 14.dp),
            )
        }

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
