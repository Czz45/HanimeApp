package com.hanime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop

/**
 * The site's four filter dropdowns plus its 240 tag checkboxes, in one sheet.
 *
 * Rows are wrapped by hand instead of using FlowRow: the tag values are wide and
 * FlowRow re-measures the whole group on every toggle, which showed up as jank
 * while scrolling the character-design group.
 */
@Composable
fun FilterSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val backdrop: Backdrop? = null
    var openGroup by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(620.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1B1B26).copy(alpha = 0.99f), Color(0xFF0D0D14))
                    )
                )
                .border(
                    0.7.dp,
                    Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("筛选", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextPrimary)
                    Text(
                        "排序、发布日期、时长与标签可以任意组合",
                        fontSize = 10.5.sp,
                        color = Ink.TextTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = Ink.TextSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp,
                ),
            ) {
                item { FilterLabel("排序方式") }
                item {
                    ChipWrap(
                        options = SORTS,
                        selected = vm.sort,
                        onPick = { vm.chooseSort(it) },
                        backdrop = backdrop,
                    )
                }

                item { FilterLabel("发布日期") }
                item {
                    ChipWrap(
                        // Index 0 is the empty "no filter" value, shown as 全部.
                        options = DATES.map { filterLabel(it, "全部") },
                        selected = filterLabel(vm.date, "全部"),
                        onPick = { label ->
                            vm.chooseDate(DATES.firstOrNull { filterLabel(it, "全部") == label } ?: "")
                        },
                        backdrop = backdrop,
                    )
                }

                item { FilterLabel("片长") }
                item {
                    ChipWrap(
                        options = DURATIONS.map { filterLabel(it, "全部") },
                        selected = filterLabel(vm.duration, "全部"),
                        onPick = { label ->
                            vm.chooseDuration(DURATIONS.firstOrNull { filterLabel(it, "全部") == label } ?: "")
                        },
                        backdrop = backdrop,
                    )
                }

                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("广泛配对", fontSize = 12.5.sp, color = Ink.TextPrimary)
                            Text(
                                "匹配任一标签而非全部，结果更多但不那么精确",
                                fontSize = 10.sp,
                                color = Ink.TextTertiary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Switch(
                            checked = vm.broad,
                            onCheckedChange = { vm.changeBroad(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Ink.Accent,
                                uncheckedThumbColor = Color(0xFF8A8A94),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }

                item { FilterLabel("标签" + if (vm.tags.isEmpty()) "" else " · 已选 ${vm.tags.size}") }

                if (vm.tagGroups.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 22.dp),
                            contentAlignment = Alignment.Center,
                        ) { LoadingDots() }
                    }
                } else {
                    vm.tagGroups.forEach { group ->
                        item {
                            val expanded = openGroup == group.name
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp)
                                    .clickable { openGroup = if (expanded) null else group.name },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    group.name,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Ink.TextSecondary,
                                )
                                Text(
                                    "  ${group.tags.size}",
                                    fontSize = 10.sp,
                                    color = Ink.TextTertiary,
                                )
                                Spacer(Modifier.weight(1f))
                                val picked = group.tags.count { it.value in vm.tags }
                                Text(
                                    if (picked > 0) "已选 $picked · " + if (expanded) "收起" else "展开"
                                    else if (expanded) "收起" else "展开",
                                    fontSize = 10.sp,
                                    color = if (picked > 0) Ink.AccentSoft else Ink.TextTertiary,
                                )
                            }
                        }
                        if (openGroup == group.name) {
                            item {
                                ChipWrap(
                                    options = group.tags.map { it.label },
                                    selected = "",
                                    selectedSet = group.tags.filter { it.value in vm.tags }.map { it.label }.toSet(),
                                    multi = true,
                                    onPick = { label ->
                                        group.tags.firstOrNull { it.label == label }?.let { vm.toggleTag(it.value) }
                                    },
                                    backdrop = backdrop,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            vm.clearFilters()
                            onApply()
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("重置", fontSize = 12.5.sp, color = Ink.TextSecondary)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.Accent)
                        .clickable { onApply() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "应用筛选",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Ink.TextTertiary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** Manual wrap layout: rows of chips, measured once per row. */
@Composable
private fun ChipWrap(
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    backdrop: Backdrop?,
    multi: Boolean = false,
    selectedSet: Set<String> = emptySet(),
) {
    Column(Modifier.fillMaxWidth()) {
        var row = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        // Rough width budget in characters: chips are 13sp with 14dp side padding
        // on a ~360dp content column, which fits about 26 half-width chars.
        var budget = 0
        for (o in options) {
            val w = o.length * 2 + 6
            if (budget + w > 44 && row.isNotEmpty()) {
                rows.add(row)
                row = mutableListOf()
                budget = 0
            }
            row.add(o)
            budget += w
        }
        if (row.isNotEmpty()) rows.add(row)

        rows.forEachIndexed { index, items ->
            Row(
                Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items.forEach { label ->
                    Chip(
                        label = label,
                        selected = if (multi) label in selectedSet else label == selected,
                        backdrop = backdrop,
                        onClick = { onPick(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, backdrop: Backdrop?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(Ink.Accent)
                else Modifier.glass(
                    backdrop, shape,
                    tint = Color.White.copy(alpha = 0.05f), blurDp = 18f, lensDp = 6f,
                )
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else Ink.TextSecondary,
            maxLines = 1,
        )
    }
}
