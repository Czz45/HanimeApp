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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanime.app.core.Card
import com.hanime.app.core.Playlist
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop

/**
 * A site playlist ("合集"). Playlists are author-curated series, so the page is a
 * plain ordered list rather than a grid: the order is the point.
 */
@Composable
fun CollectionScreen(
    vm: AppViewModel,
    backdrop: Backdrop?,
    watched: Set<Long>,
    topInset: Int,
    onOpen: (Card) -> Unit,
    onDownloadAll: (Long, Int) -> Unit,
    onBack: () -> Unit,
) {
    val state = rememberLazyListState()
    val pl: Playlist? = vm.collection
    // Batch downloads use the same preferred quality as single ones.
    val quality = vm.settings.value.preferredQuality

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(top = (topInset + 58).dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (pl != null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            // Prefer the translated name once it arrives; the raw
                            // Japanese title stays as the fallback and as the folder
                            // name the downloader already used.
                            vm.collectionTitle.ifBlank { pl.title },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink.TextPrimary,
                        )
                        Text(
                            listOfNotNull(
                                pl.author.ifBlank { null },
                                if (pl.count > 0) "${pl.count} 部影片" else "${pl.items.size} 部影片",
                            ).joinToString(" · "),
                            fontSize = 11.sp,
                            color = Ink.TextTertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                items(pl.items, key = { it.id }) { card ->
                    val index = pl.items.indexOfFirst { it.id == card.id }.coerceAtLeast(0)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .clickable { onOpen(card) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(22.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                fontSize = 11.sp,
                                color = Ink.TextTertiary,
                            )
                        }
                        Box(
                            Modifier
                                .width(if (card.wide) 100.dp else 60.dp)
                                .height(if (card.wide) 56.dp else 88.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            Cover(
                url = card.cover,
                modifier = Modifier.fillMaxSize(),
                favorite = vm.isFavorite(card.id),
                onToggleFavorite = { vm.toggleFavorite(card) },
                duration = card.dur, watched = false, wide = card.wide)
                        }
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                card.title,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = if (card.id in watched) Ink.TextTertiary else Ink.TextPrimary,
                                maxLines = 2,
                            )
                            val meta = listOfNotNull(
                                card.dur.ifBlank { null },
                                card.views.ifBlank { null },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    fontSize = 10.sp,
                                    color = Ink.TextTertiary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- top bar --------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.Bg,
                        0.78f to Ink.Bg,
                        1f to Color.Transparent,
                    )
                )
                .padding(start = 10.dp, end = 14.dp, top = (topInset + 6).dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Ink.TextPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Text(
                "合集",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.TextPrimary,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            if (pl != null && pl.items.isNotEmpty()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Ink.Accent.copy(alpha = 0.18f))
                        .clickable { onOpen(pl.items.first()) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Ink.AccentSoft,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "全部播放",
                        fontSize = 11.sp,
                        color = Ink.AccentSoft,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
                Spacer(Modifier.width(7.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.09f))
                        .clickable(enabled = !vm.batchDownloading) {
                            onDownloadAll(pl.id, quality)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (vm.batchDownloading) {
                        LoadingDots(Modifier.size(14.dp))
                    } else {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            tint = Ink.Mint,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        "全部下载",
                        fontSize = 11.sp,
                        color = Ink.Mint,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
        }

        if (vm.collectionLoading) {
            LoadingDots(Modifier.align(Alignment.Center))
        }
        if (vm.collectionError != null) {
            ErrorPill(
                vm.collectionError!!,
                onRetry = { vm.openCollection(vm.collection?.id ?: 0L, "") },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
