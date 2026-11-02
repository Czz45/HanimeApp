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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hanime.app.core.Card
import com.hanime.app.core.Detail
import com.hanime.app.core.DownloadTask
import com.hanime.app.core.Source
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop

/**
 * Detail view: a poster that bleeds into the page, then metadata, quality picks and
 * related titles. Opens as a sheet over the tabs so the back stack stays one level.
 */
@Composable
fun DetailSheet(
    vm: AppViewModel,
    detail: Detail?,
    loading: Boolean,
    error: String?,
    seed: Card?,
    topInset: Int,
    backdrop: Backdrop?,
    downloads: List<DownloadTask>,
    preferredQuality: Int,
    autoPlay: Boolean,
    onBack: () -> Unit,
    onOpenRelated: (Card) -> Unit,
    onOpenCollection: (Long, String) -> Unit,
    onFullscreen: (Source?, Long) -> Unit,
) {
    val scroll = rememberScrollState()
    val poster = detail?.poster?.ifBlank { detail.cover } ?: seed?.cover.orEmpty()
    val title = detail?.title ?: seed?.title.orEmpty()

    // Auto play honours the user's preferred quality. The inline player is now
    // always mounted once a detail resolves, so this only has to make sure the
    // right quality is the selected one — no navigation involved.
    LaunchedEffect(detail?.id, autoPlay) {
        val d = detail ?: return@LaunchedEffect
        if (!autoPlay) return@LaunchedEffect
        val pick = d.sources.minByOrNull { kotlin.math.abs(it.quality - preferredQuality) }
        if (pick != null && vm.inlineSource?.url != pick.url) vm.playInline(pick)
    }

    // Opaque root: the sheet floats above the tab content, which must not bleed
    // through while the hero image and page scroll.
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Bg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bottom = 120.dp),
        ) {
            // ---- hero / inline player --------------------------------------
            // The poster yields to the player as soon as a source is selected: the
            // user asked to watch here, not to be sent to another screen.
            val inline = vm.inlineSource
            if (detail != null && inline != null) {
                Column(Modifier.padding(top = topInset.dp + 54.dp, start = 14.dp, end = 14.dp)) {
                    InlinePlayer(
                        detail = detail,
                        source = inline,
                        onFullscreen = { pos -> onFullscreen(inline, pos) },
                        // Same title, same playhead: entering full screen and coming
                        // back both resume where the other left off.
                        startAt = vm.positionFor(detail.id),
                        onDisposePosition = { ms -> vm.rememberPosition(detail.id, ms) },
                    )
                }
                Spacer(Modifier.height(14.dp))
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.35f to Color.Transparent,
                                    0.75f to Ink.Bg.copy(alpha = 0.85f),
                                    1f to Ink.Bg,
                                )
                            )
                    )
                }
            }

            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink.TextPrimary,
                )
                // The site keeps a localized (Chinese) name in the description panel for
                // translated releases while the heading stays the Japanese original, so show
                // it as a secondary line whenever the two actually differ.
                val cn = detail?.cnTitle?.takeIf { it.isNotBlank() && it != title }
                if (cn != null) {
                    Text(
                        text = cn,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Ink.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val meta = listOfNotNull(
                    detail?.durationText()?.ifBlank { null },
                    detail?.views?.ifBlank { null },
                    detail?.date?.ifBlank { null },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        fontSize = 11.5.sp,
                        color = Ink.TextTertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (!detail?.likePct.isNullOrBlank()) {
                    Row(
                        Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.ThumbUp,
                            contentDescription = null,
                            tint = Ink.Mint,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = " ${detail!!.likePct} ${detail.likeCount}".trim(),
                            fontSize = 11.5.sp,
                            color = Ink.Mint,
                        )
                    }
                }

                val author = detail?.author.orEmpty()
                if (author.isNotBlank()) {
                    Row(
                        Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Ink.SurfaceHigh)
                        )
                        Text(
                            text = author,
                            fontSize = 12.sp,
                            color = Ink.TextSecondary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        val genre = detail?.genre.orEmpty()
                        if (genre.isNotBlank()) {
                            Text(
                                text = "· $genre",
                                fontSize = 11.5.sp,
                                color = Ink.TextTertiary,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }

                // ---- tags --------------------------------------------------
                val tags = detail?.tags.orEmpty()
                if (tags.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(tags) { t ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(t, fontSize = 10.5.sp, color = Ink.TextSecondary)
                            }
                        }
                    }
                }

                // ---- play / download --------------------------------------
                val sources = detail?.sources.orEmpty()
                if (sources.isNotEmpty()) {
                    Text(
                        "清晰度",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextPrimary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sources.sortedByDescending { it.quality }.forEach { s ->
                            // Switching quality restarts the inline player on the new
                            // stream; the old full-screen route is now only entered
                            // from the expand button.
                            SourceButton(
                                label = "${s.quality}p",
                                icon = null,
                                accent = s.url == vm.inlineSource?.url,
                                onClick = { vm.playInline(s) },
                            )
                        }
                    }

                    Text(
                        "下载",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextPrimary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        sources.sortedByDescending { it.quality }.forEach { s ->
                            val task = downloads.firstOrNull { it.id == detail!!.id && it.quality == s.quality }
                            SourceButton(
                                label = when {
                                    task == null -> "${s.quality}p"
                                    task.isDone -> "${s.quality}p 已完成"
                                    else -> "${(task.progress * 100).toInt()}%"
                                },
                                icon = Icons.Rounded.Download,
                                accent = false,
                                onClick = { vm.download(s.quality) },
                            )
                        }
                    }
                }

                // ---- collection -------------------------------------------
                val listId = detail?.listId ?: 0L
                if (listId > 0L) {
                    val listTitle = detail?.listTitle.orEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable { onOpenCollection(listId, listTitle) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.PlaylistPlay,
                            contentDescription = null,
                            tint = Ink.AccentSoft,
                            modifier = Modifier.size(17.dp),
                        )
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                "所属合集",
                                fontSize = 10.5.sp,
                                color = Ink.TextTertiary,
                            )
                            Text(
                                listTitle.ifBlank { "查看合集" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ink.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = Ink.TextTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                val desc = detail?.desc.orEmpty().trim()
                if (desc.isNotBlank()) {
                    Text(
                        "简介",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextPrimary,
                        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                    )
                    Text(
                        text = desc.take(600),
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = Ink.TextTertiary,
                    )
                }

                if (loading) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) { LoadingDots() }
                }
                if (error != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 30.dp),
                        contentAlignment = Alignment.Center,
                    ) { ErrorPill(error, onRetry = { vm.openDetail(detail?.id ?: seed!!.id, seed) }) }
                }
            }

            // ---- related ---------------------------------------------------
            val related = detail?.related.orEmpty()
            if (related.isNotEmpty()) {
                SectionHeader("相关推荐")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    items(related, key = { it.id }) { card ->
                        RowCard(card = card, watched = false, onClick = { onOpenRelated(card) })
                    }
                }
            }
        }

        // ---- back button ---------------------------------------------------
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .padding(start = 16.dp, top = (topInset + 10).dp)
                .size(36.dp)
                .glass(backdrop, RoundedCornerShape(50), Color.White.copy(alpha = 0.1f), 16f, 8f)
                .clip(RoundedCornerShape(50))
                .clickable(interactionSource = interaction, indication = null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Ink.TextPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SourceButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .clip(shape)
            .background(
                if (accent) Ink.Accent else Color.White.copy(alpha = 0.08f),
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon ?: Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = if (accent) Color.White else Ink.TextSecondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
            color = if (accent) Color.White else Ink.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Closest available quality to [want]; the list is assumed non-empty. */
private fun bestQuality(sources: List<Source>, want: Int): Int =
    sources.minByOrNull { kotlin.math.abs(it.quality - want) }?.quality ?: want
