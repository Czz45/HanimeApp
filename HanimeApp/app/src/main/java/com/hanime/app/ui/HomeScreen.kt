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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanime.app.core.Card
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop

/**
 * Home: category chips on top, a sort row underneath, then either the rich
 * editorial rows ("全部") or a paged grid for a specific genre/sort/filter.
 *
 * The chips and the sort row are drawn as an overlay above the list rather than
 * as list items, so they stay put while content scrolls under the glass.
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    backdrop: Backdrop?,
    watched: Set<Long>,
    topInset: Int,
    hideWatched: Boolean,
    onToggleHideWatched: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpen: (Card) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()
    var sheetOpen by remember { mutableStateOf(false) }

    // Paging trigger: start the next request while eight cells are still ahead, so
    // the page is usually already in hand by the time the user reaches the bottom.
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, vm.genre, vm.sort) {
        if (nearEnd) vm.loadMore()
    }

    Box(Modifier.fillMaxSize()) {
        if (vm.isGridMode) {
            // Staggered, not fixed rows: the site mixes 16:9 stills with portrait DVD
            // covers in a single genre, and a plain grid would pad the short cards.
            LazyVerticalStaggeredGrid(
                state = gridState,
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = (topInset + 116).dp,
                    bottom = 120.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalItemSpacing = 14.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(vm.feed, key = { it.id }) { card ->
                    GridCard(
                        card = card,
                        watched = card.id in watched,
                        onClick = { onOpen(card) },
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = (topInset + 116).dp, bottom = 120.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(vm.sections, key = { it.title }) { section ->
                    Column {
                        SectionHeader(section.title)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            items(section.items, key = { it.id }) { card ->
                                RowCard(
                                    card = card,
                                    watched = card.id in watched,
                                    onClick = { onOpen(card) },
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }

        // ---- sticky chrome -------------------------------------------------
        Column(
            Modifier
                .fillMaxWidth()
                // Opaque until just under the chip rows: cards scrolling underneath used
                // to show through the old 92% stop and collide with the title.
                .background(
                    Brush.verticalGradient(
                        0f to Ink.Bg,
                        0.72f to Ink.Bg,
                        0.93f to Ink.Bg.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    )
                )
                .padding(top = topInset.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hanime", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.TextPrimary)
                Spacer(Modifier.weight(1f))

                // Quick toggles. Hiding watched videos used to be three taps deep in
                // Settings, which is far from where the user notices a watched card.
                HeaderButton(
                    icon = if (hideWatched) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    description = if (hideWatched) "显示已观看" else "屏蔽已观看",
                    active = hideWatched,
                    onClick = onToggleHideWatched,
                )
                HeaderButton(
                    icon = Icons.Rounded.Search,
                    description = "搜索",
                    active = false,
                    onClick = onOpenSearch,
                )
            }

            // Category switch (user requirement: 首页上方切换 里番/泡面番…)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(GENRES) { g ->
                    GlassChip(
                        text = g,
                        selected = vm.genre == g,
                        backdrop = backdrop,
                        onClick = { vm.chooseGenre(g) },
                    )
                }
            }

            // Sort switch, prefixed by the filter entry that carries date/duration/tags.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    val active = vm.date.isNotBlank() || vm.duration.isNotBlank() || vm.tags.isNotEmpty()
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (active) Ink.Accent.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.07f)
                            )
                            .clickable {
                                vm.ensureTagGroups()
                                sheetOpen = true
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = "筛选",
                            tint = if (active) Ink.AccentSoft else Ink.TextSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            if (active) {
                                buildList {
                                    if (vm.date.isNotBlank()) add(vm.date)
                                    if (vm.duration.isNotBlank()) add(vm.duration)
                                    if (vm.tags.isNotEmpty()) add("${vm.tags.size} 标签")
                                }.joinToString(" · ")
                            } else "筛选",
                            fontSize = 11.5.sp,
                            color = if (active) Ink.AccentSoft else Ink.TextSecondary,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
                items(SORTS) { s ->
                    GlassChip(
                        text = s,
                        selected = vm.sort == s,
                        backdrop = backdrop,
                        onClick = { vm.chooseSort(s) },
                        modifier = Modifier,
                    )
                }
            }
        }

        if (vm.error != null) {
            ErrorPill(vm.error!!, onRetry = { vm.refreshFeed() }, Modifier.align(Alignment.TopCenter).padding(top = (topInset + 112).dp))
        }
        if (vm.loading && vm.sections.isEmpty() && vm.feed.isEmpty()) {
            LoadingDots(Modifier.align(Alignment.Center))
        }
        if (!vm.loading && !vm.isGridMode && vm.sections.isEmpty() && vm.error == null) {
            EmptyHint("这里还没有内容", Modifier.align(Alignment.Center))
        }
        if (vm.loadingMore) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 108.dp)) {
                LoadingDots()
            }
        }

        if (sheetOpen) {
            FilterSheet(
                vm = vm,
                onDismiss = { sheetOpen = false },
                onApply = { sheetOpen = false },
            )
        }
    }
}

/** Round 32dp header action; filled when the toggle it controls is on. */
@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Ink.Accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.07f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) Ink.AccentSoft else Ink.TextSecondary,
            modifier = Modifier.size(17.dp),
        )
    }
}
