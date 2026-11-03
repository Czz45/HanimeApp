package com.hanime.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanime.app.core.ReelBucket
import com.hanime.app.core.ReelStats
import com.hanime.app.ui.theme.Ink

/** Slightly brighter than [Ink.TextTertiary]; values here must stay legible. */
private val SecondaryText = Color(0xAAF5F5F7)

/**
 * Histogram of the short-video sampler, drawn by hand rather than pulled from a
 * charting library.
 *
 * Two reasons: the project has no chart dependency in the offline Gradle cache,
 * and a library would want to pick its own type scale — this has to sit inside a
 * glass card next to 10.5sp captions without looking pasted in.
 *
 * The animation is a single grow-in on first composition. It is deliberately not
 * re-run on every data change: the page is refreshed on open, and a bar that
 * re-animates while the user scrolls past reads as jitter, not polish.
 */
@Composable
fun ReelStatsChart(stats: ReelStats, modifier: Modifier = Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(stats.total) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(durationMillis = 620, easing = FastOutSlowInEasing))
    }

    Column(modifier) {
        // Guard rails sit above the chart: if the sampler ever produced an id
        // outside the catalogue this line is the fastest way to see it.
        val emptyShare = if (stats.total > 0) stats.emptyBand * 100f / stats.total else 0f
        Text(
            when {
                stats.total == 0 -> "还没有数据 · 去短视频模式滑几条就有了"
                stats.emptyBand > 0 -> "有 ${stats.emptyBand} 次落在空号段（${"%.1f".format(emptyShare)}%），抽样越过目录了"
                else -> "${stats.total} 次抽样 · ${stats.distinct} 个不同序号 · 全部命中目录"
            },
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = if (stats.emptyBand > 0) Ink.AccentSoft else Ink.TextTertiary,
        )

        Spacer(Modifier.height(14.dp))

        if (stats.total > 0) {
            Bars(buckets = stats.buckets, peak = stats.peak, progress = grow.value)
            Spacer(Modifier.height(6.dp))
            Labels(buckets = stats.buckets, peak = stats.peak)
        } else {
            // Empty state keeps the card's height so the settings page does not
            // jump when the first samples land.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无抽样", fontSize = 10.5.sp, color = Ink.TextTertiary)
            }
        }

        if (stats.top.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "出现最多的序号",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.TextTertiary,
            )
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                stats.top.take(4).forEach { (id, count) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(9.dp))
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("#$id", fontSize = 11.sp, color = Ink.TextPrimary, maxLines = 1)
                        Text(
                            "$count 次",
                            fontSize = 9.5.sp,
                            color = Ink.TextTertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (stats.genres.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "分类分布",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.TextTertiary,
            )
            Spacer(Modifier.height(7.dp))
            stats.genres.take(5).forEach { (name, count) ->
                val share = if (stats.total > 0) count.toFloat() / stats.total else 0f
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        fontSize = 10.5.sp,
                        color = SecondaryText,
                        modifier = Modifier.width(66.dp),
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(3.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(share.coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(listOf(Ink.Accent, Ink.AccentSoft)),
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                    Text(
                        "$count",
                        fontSize = 9.5.sp,
                        color = Ink.TextTertiary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(34.dp),
                    )
                }
            }
        }
    }
}

/** Rounded vertical bars; height is normalised against the tallest bucket. */
@Composable
private fun Bars(buckets: List<ReelBucket>, peak: Int, progress: Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        if (buckets.isEmpty() || peak <= 0) return@Canvas
        val gap = 6.dp.toPx()
        val barWidth = ((size.width - gap * (buckets.size - 1)) / buckets.size).coerceAtLeast(1f)
        val radius = CornerRadius(barWidth / 2.6f, barWidth / 2.6f)
        buckets.forEachIndexed { i, b ->
            val target = (b.count.toFloat() / peak) * size.height * 0.92f * progress
            // A bucket with samples keeps a visible stub even when it is 1/500 of
            // the peak, otherwise "rare but non-zero" is indistinguishable from 0.
            val h = if (b.count > 0) target.coerceAtLeast(2.dp.toPx() * progress) else 0f
            val x = i * (barWidth + gap)
            if (h > 0f) {
                drawRoundRect(
                    brush = barBrush(b.label == "空档"),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            } else {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.05f),
                    topLeft = Offset(x, size.height - 2.dp.toPx()),
                    size = Size(barWidth, 2.dp.toPx()),
                    cornerRadius = radius,
                )
            }
        }
    }
}

private fun DrawScope.barBrush(isGap: Boolean): Brush = if (isGap) {
    // The empty band is coloured as a warning, because a non-zero value there
    // means the sampler is broken rather than merely uneven.
    Brush.verticalGradient(listOf(Ink.Accent, Ink.Accent.copy(alpha = 0.35f)))
} else {
    Brush.verticalGradient(listOf(Ink.Mint, Ink.Mint.copy(alpha = 0.28f)))
}

/** Bucket captions under the bars, skipping labels too cramped to read. */
@Composable
private fun Labels(buckets: List<ReelBucket>, peak: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        buckets.forEach { b ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    b.label,
                    fontSize = 8.5.sp,
                    color = if (b.count > 0) SecondaryText else Ink.TextTertiary,
                    maxLines = 1,
                )
                Text(
                    if (b.count > 0) "${b.count}" else "·",
                    fontSize = 8.5.sp,
                    color = if (b.label == "空档" && b.count > 0) Ink.AccentSoft else Ink.TextTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}
