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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanime.app.core.Card
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop

/**
 * Search with the site's full filter set.
 *
 * The filter sheet mirrors the four dropdowns the site actually has (genre, sort,
 * date, duration) plus the tag checkboxes. They all submit to the same `/search`
 * endpoint and compose, so one query string carries whatever the user picked.
 */
@Composable
fun SearchScreen(
    vm: AppViewModel,
    backdrop: Backdrop?,
    watched: Set<Long>,
    topInset: Int,
    onOpen: (Card) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf(vm.query) }
    var sheetOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.searchMore() }

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(topInset.dp))

            // ---- search bar -------------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = Ink.TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Row(
                    Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Ink.TextTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        if (text.isEmpty()) {
                            Text("搜索作品、作者", fontSize = 13.sp, color = Ink.TextTertiary)
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 13.sp,
                                color = Ink.TextPrimary,
                            ),
                            cursorBrush = SolidColor(Ink.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.submitSearch(text) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (text.isNotEmpty()) {
                        Text(
                            "搜索",
                            fontSize = 12.sp,
                            color = Ink.AccentSoft,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clickable { vm.submitSearch(text) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = Ink.TextTertiary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp)
                                .clickable { text = "" },
                        )
                    }
                }
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(38.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            vm.ensureTagGroups()
                            sheetOpen = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = "筛选",
                        tint = if (vm.tags.isNotEmpty() || vm.date.isNotBlank() || vm.duration.isNotBlank())
                            Ink.AccentSoft else Ink.TextSecondary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            // ---- active filter summary -------------------------------------
            if (vm.tags.isNotEmpty() || vm.date.isNotBlank() || vm.duration.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val summary = buildList {
                        if (vm.date.isNotBlank()) add(vm.date)
                        if (vm.duration.isNotBlank()) add(vm.duration)
                        if (vm.tags.isNotEmpty()) add("${vm.tags.size} 个标签")
                    }.joinToString(" · ")
                    Text(summary, fontSize = 10.5.sp, color = Ink.TextTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "清除",
                        fontSize = 10.5.sp,
                        color = Ink.AccentSoft,
                        modifier = Modifier.clickable {
                            vm.clearFilters()
                            text = ""
                        },
                    )
                }
            }

            // ---- results ----------------------------------------------------
            when {
                vm.searching && vm.searchResults.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }

                vm.searchError != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorPill(vm.searchError!!, onRetry = { vm.submitSearch(text) })
                    }

                vm.searchResults.isEmpty() && vm.query.isNotBlank() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyHint("没有找到结果")
                    }

                vm.query.isBlank() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyHint("输入关键词开始搜索")
                    }

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.searchResults, key = { it.id }) { card ->
                        SearchRow(
                            card = card,
                            watched = card.id in watched,
                            favorite = vm.isFavorite(card.id),
                            onClick = { onOpen(card) },
                            onToggleFavorite = { vm.toggleFavorite(card) },
                        )
                    }
                    if (vm.searching) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) { LoadingDots() }
                        }
                    }
                }
            }
        }

        if (sheetOpen) {
            FilterSheet(
                vm = vm,
                onDismiss = { sheetOpen = false },
                onApply = {
                    sheetOpen = false
                    vm.submitSearch(text)
                },
            )
        }
    }
}

/** Compact horizontal row: search results carry both wide and portrait covers. */
@Composable
private fun SearchRow(
    card: Card,
    watched: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (card.wide) 108.dp else 68.dp)
                .height(if (card.wide) 61.dp else 96.dp)
                .clip(RoundedCornerShape(9.dp))
        ) {
            Cover(
                url = card.cover,
                modifier = Modifier.fillMaxSize(),
                favorite = favorite,
                onToggleFavorite = onToggleFavorite,
                duration = card.dur, watched = false, wide = card.wide)
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(
                card.title,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = if (watched) Ink.TextTertiary else Ink.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            val meta = listOfNotNull(
                card.dur.ifBlank { null },
                card.score.ifBlank { null },
                card.views.ifBlank { null },
                card.author.ifBlank { null },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    fontSize = 10.sp,
                    color = Ink.TextTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            if (watched) {
                Text(
                    "已观看",
                    fontSize = 9.5.sp,
                    color = Ink.Mint.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
