package com.hanime.app.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.hanime.app.core.Detail
import com.hanime.app.core.Source
import com.hanime.app.ui.theme.Ink
import kotlinx.coroutines.delay

/**
 * Player embedded in the detail sheet.
 *
 * The sheet used to hand playback over to a separate full-screen route, which
 * meant leaving the page, losing scroll position and coming back through two
 * transitions before the user could pick another episode. Here the video is just
 * another row in the page: tapping a quality swaps the stream in place, and the
 * full-screen route stays available for immersive viewing.
 *
 * The surface is letterboxed to the decoded aspect ratio rather than stretched,
 * because the site serves both 16:9 and portrait clips.
 */
@Composable
fun InlinePlayer(
    detail: Detail,
    source: Source,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    var videoRatio by remember { mutableStateOf(0f) }

    // Keyed on the URL: changing quality releases the old player and builds a new
    // one, which is cheaper and far less stateful than swapping a MediaItem.
    val player = remember(source.url) {
        com.hanime.app.Players.build(context, source.url, com.hanime.app.Ua.BROWSER)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoRatio = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 250 ms is fast enough for a scrub bar and far cheaper than per-frame callbacks.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(if (videoRatio > 0f) videoRatio else 16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap toggles transport controls; they start visible so the player reads as
        // an object on the page rather than a video that happens to be paused.
        var chrome by remember { mutableStateOf(true) }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { chrome = !chrome }
        )

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.4f),
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.62f),
                            )
                        )
                )

                if (buffering) {
                    LoadingDots(Modifier.align(Alignment.Center))
                } else {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(52.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (player.isPlaying) player.pause() else player.play()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${fmtClock(position)} / ${fmtClock(duration)}",
                        fontSize = 10.5.sp,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${source.quality}p",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.size(10.dp))
                    // The inline player has no transport bar worth cramming into a
                    // 200dp box, so full screen is the only extra control it needs.
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onFullscreen,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Fullscreen,
                            contentDescription = "全屏",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Thin progress hairline along the bottom edge.
                if (duration > 0) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Color.White.copy(alpha = 0.18f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(Ink.Accent)
                        )
                    }
                }
            }
        }
    }
}

private fun fmtClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
