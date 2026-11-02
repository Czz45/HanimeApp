package com.hanime.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hanime.app.core.Card
import com.hanime.app.ui.theme.Ink

/**
 * Cover with a scrim and a duration pill.
 *
 * The scrim exists because titles are drawn on top of the artwork for the grid
 * variant and thumbnails are unpredictable in brightness.
 */
@Composable
fun Cover(
    url: String,
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 16.dp,
    duration: String = "",
    watched: Boolean = false,
    wide: Boolean = true,
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Ink.SurfaceHigh)
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            // The site serves both 640x360 stills and 268x394 DVD covers, so the
            // slot aspect ratio is decided by the caller; `Fit` keeps a mismatched
            // image uncropped instead of beheading the artwork.
            contentScale = if (wide) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // Bottom scrim keeps the title legible over bright artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                )
        )
        if (duration.isNotBlank()) {
            GlassTag(
                text = duration,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
        if (favorite != null && onToggleFavorite != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = if (favorite) 0.30f else 0.45f))
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) Ink.Accent else Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (watched) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text("已看", fontSize = 9.sp, color = Ink.Mint, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Wide card used inside horizontally scrolling home rows. */
@Composable
fun RowCard(
    card: Card,
    watched: Boolean,
    onClick: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 158.dp,
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Cover(
            url = card.cover,
            duration = card.dur,
            watched = watched,
            wide = card.wide,
            favorite = favorite,
            onToggleFavorite = onToggleFavorite,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (card.wide) 16f / 9f else 0.68f),
            corner = 14.dp,
        )
        Text(
            text = card.title,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            color = Ink.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp, start = 1.dp, end = 1.dp),
        )
        val meta = listOf(card.timeText, card.views).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                fontSize = 10.5.sp,
                color = Ink.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 1.dp),
            )
        }
    }
}

/** Poster card for the paged grid (search / genre lists). */
@Composable
fun GridCard(
    card: Card,
    watched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Cover(
            url = card.cover,
            duration = card.dur,
            watched = watched,
            wide = card.wide,
            favorite = favorite,
            onToggleFavorite = onToggleFavorite,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (card.wide) 16f / 9f else 0.68f),
        )
        Text(
            text = card.title,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            color = Ink.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp, start = 1.dp, end = 1.dp),
        )
    }
}

/** Full-width list row used by downloads and search results. */
@Composable
fun ListRow(
    card: Card,
    watched: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed box for the thumb: an unconstrained fillMaxSize sibling here used to
        // eat the whole row width and squeeze the title column down to zero.
        Box(
            Modifier
                .width(120.dp)
                .height(70.dp)
        ) {
            Cover(
                url = card.cover,
                duration = card.dur,
                watched = watched,
                modifier = Modifier.fillMaxSize(),
                corner = 12.dp,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = card.title,
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                color = Ink.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(card.timeText, card.views).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    fontSize = 11.sp,
                    color = Ink.TextTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/** Row title with a fade-in "查看更多" affordance. */
@Composable
fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.TextPrimary,
        )
        if (onMore != null) {
            val interaction = remember { MutableInteractionSource() }
            Text(
                text = "全部",
                fontSize = 11.5.sp,
                color = Ink.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(interactionSource = interaction, indication = null, onClick = onMore)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Throbber that fades instead of popping when a page finishes loading. */
@Composable
fun LoadingBar(visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "loading",
    )
    if (alpha > 0.01f) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Ink.Accent, Ink.AccentSoft, Color.Transparent)
                    )
                )
        )
    }
}
