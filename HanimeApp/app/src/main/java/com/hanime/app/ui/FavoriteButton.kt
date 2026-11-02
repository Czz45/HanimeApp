package com.hanime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hanime.app.ui.theme.Ink

/** Shared heart button so the grid, reels and detail sheet all look identical. */
@Composable
fun FavoriteButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 34,
) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = if (active) 0.28f else 0.42f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Favorite,
            contentDescription = if (active) "取消收藏" else "收藏",
            tint = if (active) Ink.Accent else Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size((size * 0.48f).dp),
        )
    }
}
