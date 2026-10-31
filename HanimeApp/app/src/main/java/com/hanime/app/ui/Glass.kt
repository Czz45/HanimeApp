package com.hanime.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Whether this device can afford real backdrop refraction.
 *
 * Disabled everywhere: `drawBackdrop` records the surrounding content from inside
 * the same subtree it is recorded into, and on a GPU render thread that recursion
 * builds an unbounded display list until hwui blows its stack (SIGSEGV in
 * RenderNode::prepareTreeImpl). A device-independent capability check cannot
 * predict it, so the live path stays off and every surface uses the painted frost
 * below, which is what all shipped screenshots were taken from.
 */
object Glass {
    const val live: Boolean = false
}

/** The one glass recipe used everywhere. */
@Composable
fun rememberGlassBackdrop(): com.kyant.backdrop.backdrops.LayerBackdrop? =
    if (Glass.live) rememberLayerBackdrop() else null

@Composable
fun Modifier.glass(
    backdrop: Backdrop?,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = Color.White.copy(alpha = 0.08f),
    blurDp: Float = 26f,
    lensDp: Float = 12f,
): Modifier {
    val base = this
    val surface = if (backdrop != null && Glass.live) {
        base.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurDp.dp.toPx())
                lens(lensDp.dp.toPx(), lensDp.dp.toPx())
            },
            onDrawSurface = { drawRect(Color(0xFF12121A).copy(alpha = 0.55f)) },
        )
    } else {
        // Painted frost: a soft vertical sheen over a near-opaque dark base reads as
        // glass at a glance and costs one draw call.
        base.background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF20202B).copy(alpha = 0.94f),
                    Color(0xFF12121A).copy(alpha = 0.96f),
                )
            ),
            shape,
        )
    }
    return surface
        .background(tint, shape)
        .border(
            0.7.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.22f),
                    Color.White.copy(alpha = 0.06f),
                )
            ),
            shape,
        )
}

/** Pill chip used for genres and sort modes. */
@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    backdrop: Backdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(
                if (selected) {
                    Modifier
                        .background(Ink.TextPrimary, shape)
                        .border(0.7.dp, Color.White.copy(alpha = 0.5f), shape)
                } else {
                    Modifier.glass(backdrop, shape, tint = Color.White.copy(alpha = 0.05f), blurDp = 18f, lensDp = 6f)
                }
            )
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color(0xFF0B0B10) else Ink.TextSecondary,
            maxLines = 1,
        )
    }
}

/** Small translucent label such as the duration badge on a cover. */
@Composable
fun GlassTag(text: String, modifier: Modifier = Modifier, backdrop: Backdrop? = null) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .then(
                if (backdrop != null && Glass.live) Modifier.glass(backdrop, shape, Color.Transparent, 12f, 4f)
                else Modifier.background(Color.Black.copy(alpha = 0.55f), shape)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

/** Row wrapper so callers can lay out glass chrome without repeating shape setup. */
@Composable
fun GlassRow(
    backdrop: Backdrop?,
    shape: Shape = RoundedCornerShape(28.dp),
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .glass(backdrop, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun GlassBox(
    backdrop: Backdrop?,
    shape: Shape = RoundedCornerShape(22.dp),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(backdrop, shape), content = content)
}
