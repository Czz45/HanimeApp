package com.hanime.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.abs

/**
 * Full-screen playback.
 *
 * media3-ui is not available here, so the surface is a raw [SurfaceView] and the
 * transport controls are Compose. The surface is letterboxed to the real video
 * aspect ratio instead of being stretched: the site serves both 16:9 and portrait
 * clips, and filling the screen made the portrait ones look zoomed-in and cropped.
 *
 * Layer order matters — the gesture layer sits under the control chrome, so the
 * back button and slider receive taps before the tap-to-toggle handler does.
 */
@Composable
fun PlayerScreen(
    detail: Detail,
    source: Source,
    onBack: () -> Unit,
    startAt: Long = 0L,
    scaleMode: Int = 0,
    onScaleChange: (Int) -> Unit = {},
    onPositionChange: (Long) -> Unit = {},
    seekSeconds: Int = 90,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var videoRatio by remember { mutableStateOf(0f) }
    var speed by remember { mutableStateOf(1f) }
    var locked by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var lastTapAt by remember { mutableStateOf(0L) }
    var scaleMenu by remember { mutableStateOf(false) }

    val player = remember {
        com.hanime.app.Players.build(context, source.url, com.hanime.app.Ua.BROWSER)
            .apply { if (startAt > 0) seekTo(startAt) }
    }

    // The rotate button pins the activity to an orientation, and nothing else in
    // the app ever clears it — backing out of the player used to leave the whole
    // UI stuck in landscape. Remember what the manifest asked for on the way in
    // and hand it back on the way out.
    val initialOrientation = remember {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Entering full screen means "show me the video", so rotate on arrival instead
    // of making the user hunt for the rotate button every time. Only once, and only
    // for landscape clips — forcing a portrait clip sideways would be worse than
    // leaving it upright.
    var autoRotated by remember { mutableStateOf(false) }
    LaunchedEffect(videoRatio) {
        if (!autoRotated && videoRatio >= 1f) {
            autoRotated = true
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Hand the playhead back before the player is released so the inline
            // player on the detail sheet resumes at the same frame instead of 0.
            onPositionChange(player.currentPosition.coerceAtLeast(0L))
            activity?.requestedOrientation = initialOrientation
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controlsVisible = true
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

    // Poll the clock rather than listening to every frame: 120 ms is smooth for a
    // progress bar and costs nothing compared with a per-frame callback.
    LaunchedEffect(Unit) {
        var sinceSync = 0
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            // Publish the playhead about once a second, not on every tick. The
            // detail sheet composes (and reads the position) before this screen's
            // onDispose runs, so a dispose-only hand-off would be too late.
            if (++sinceSync >= 8) {
                sinceSync = 0
                onPositionChange(position)
            }
            delay(120)
        }
    }

    // Auto-hide the chrome after a short idle while playing.
    LaunchedEffect(controlsVisible, playing, locked) {
        if (controlsVisible && playing && !locked) {
            delay(3200)
            controlsVisible = false
        }
    }

    LaunchedEffect(hud) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- video surface -------------------------------------------------
        // A raw SurfaceView stretches the video to whatever size it is given, so the
        // fit mode is expressed purely by the surface's dimensions, measured against
        // the container's real aspect ratio:
        //   fit   — smaller or equal on both axes, letterboxed
        //   fill  — cover: one axis matches the screen, the other overflows and is
        //           clipped by the parent, so the picture reaches the edges uncropped
        //           in the middle rather than being squashed
        //   stretch — exactly the screen, distortion included
        // Fill matters for subtitled frames: letterboxing shrinks the picture until
        // the bottom line of text is unreadable, and stretching deforms everyone.
        BoxWithConstraints(
            Modifier.fillMaxSize().clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val boxRatio = maxWidth / maxHeight
            val surfaceModifier = when {
                scaleMode == 2 -> Modifier.fillMaxSize()
                videoRatio <= 0f -> Modifier.fillMaxSize()
                scaleMode == 1 -> {
                    if (videoRatio >= boxRatio) {
                        Modifier.fillMaxHeight().aspectRatio(videoRatio)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(videoRatio)
                    }
                }
                else -> {
                    if (videoRatio >= boxRatio) {
                        Modifier.fillMaxWidth().aspectRatio(videoRatio)
                    } else {
                        Modifier.fillMaxHeight().aspectRatio(videoRatio)
                    }
                }
            }
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
                modifier = surfaceModifier,
            )
        }

        // ---- gesture layer ---------------------------------------------
        if (!locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(videoRatio, duration, seekSeconds) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dx = 0f
                            var dy = 0f
                            var mode = 0 // 0 tap, 1 seek, 2 volume, 3 brightness
                            var seekTo = 0L
                            var volStart = 0
                            var brightStart = 0f
                            val slop = 14.dp.toPx()
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                                    mode = when {
                                        abs(dx) > abs(dy) -> 1
                                        down.position.x < w / 2 -> 3
                                        else -> 2
                                    }
                                    if (mode == 2) {
                                        volStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    }
                                    if (mode == 3) {
                                        brightStart = activity?.window?.attributes?.screenBrightness
                                            ?.takeIf { it >= 0f } ?: 0.5f
                                    }
                                }
                                when (mode) {
                                    1 -> {
                                        // Seconds-per-full-width instead of a fraction
                                        // of the duration: on a 25-minute episode the
                                        // old proportional mapping jumped minutes per
                                        // swipe, which made fine scrubbing impossible.
                                        val base = if (duration > 0) position else 0L
                                        val span = seekSeconds * 1000f
                                        val delta = (dx / w * span).toLong()
                                        seekTo = (base + delta).coerceIn(0L, duration.takeIf { it > 0 } ?: 0L)
                                        scrubTarget = seekTo
                                    }
                                    2 -> {
                                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        val level = (volStart - dy / h * max * 1.2f).toInt().coerceIn(0, max)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                                        hud = "音量 ${(level * 100 / max)}%"
                                    }
                                    3 -> {
                                        val level = (brightStart - dy / h * 1.2f).coerceIn(0.05f, 1f)
                                        activity?.window?.let { win ->
                                            win.attributes = win.attributes.apply { screenBrightness = level }
                                        }
                                        hud = "亮度 ${(level * 100).toInt()}%"
                                    }
                                }
                                change.consume()
                            }
                            when (mode) {
                                0 -> {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapAt < 260) {
                                        if (player.isPlaying) player.pause() else player.play()
                                        lastTapAt = 0L
                                        controlsVisible = true
                                    } else {
                                        lastTapAt = now
                                        controlsVisible = !controlsVisible
                                    }
                                }
                                1 -> {
                                    if (duration > 0) player.seekTo(seekTo)
                                    scrubTarget = null
                                    controlsVisible = true
                                }
                                else -> Unit
                            }
                        }
                    }
            )
        }

        // ---- scrubbing / level indicator -------------------------------
        scrubTarget?.let { target ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "${fmt(target)} / ${fmt(duration)}",
                    fontSize = 12.sp,
                    color = Color.White,
                )
            }
        }
        hud?.let { text ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.60f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(text, fontSize = 12.sp, color = Color.White)
            }
        }

        // ---- picture-fit menu -------------------------------------------
        if (scaleMenu && !locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { scaleMenu = false }
            )
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 84.dp, end = 62.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.86f))
                    .padding(vertical = 4.dp),
            ) {
                listOf(
                    0 to "适应画面",
                    1 to "铺满屏幕",
                    2 to "拉伸填满",
                ).forEach { (mode, label) ->
                    Row(
                        Modifier
                            .clickable {
                                onScaleChange(mode)
                                scaleMenu = false
                                controlsVisible = true
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (scaleMode == mode) Ink.AccentSoft else Color.White,
                            fontWeight = if (scaleMode == mode) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (scaleMode == mode) {
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier
                                    .size(4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Ink.Accent)
                            )
                        }
                    }
                }
            }
        }

        // ---- chrome (drawn above the gesture layer) ---------------------
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.4f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f),
                        )
                    )
            ) {
                if (locked) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 18.dp)
                            .size(38.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable { locked = false; controlsVisible = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.LockOpen,
                            contentDescription = "解锁",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    // top bar
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, top = 38.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { onBack() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(
                                text = detail.cnTitle.ifBlank { detail.title },
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${source.quality}p · ${if (speed == 1f) "原速" else "${speed}x"}",
                                fontSize = 10.5.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable {
                                    // 以当前屏幕方向为准：竖屏视频在竖屏下点旋转还是要横过去，
                                    // 之前用 videoRatio 判断会让横版视频在竖屏里“点了没反应”。
                                    val landscape = context.resources.configuration.orientation ==
                                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                    activity?.requestedOrientation = if (landscape) {
                                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Fullscreen,
                                contentDescription = "旋转",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        // Picture fit. The label is the current mode so the button
                        // doubles as the indicator — a separate badge would compete
                        // with the speed chip already in the bottom bar.
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { scaleMenu = !scaleMenu }
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when (scaleMode) {
                                    1 -> "铺满"
                                    2 -> "拉伸"
                                    else -> "适应"
                                },
                                fontSize = 10.5.sp,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { locked = true; controlsVisible = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = "锁定",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }

                    // centre play / pause
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (buffering) {
                            LoadingDots()
                        } else {
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }

                    // bottom bar
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 34.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(fmt(position), fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                            Text(fmt(duration), fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                        }
                        Slider(
                            value = if (duration > 0) position.toFloat() / duration else 0f,
                            onValueChange = { frac ->
                                if (duration > 0) {
                                    player.seekTo((frac * duration).toLong())
                                    position = (frac * duration).toLong()
                                }
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Ink.Accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                            ),
                            modifier = Modifier.fillMaxWidth().height(26.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                                val on = speed == v
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (on) Ink.Accent else Color.White.copy(alpha = 0.14f)
                                        )
                                        .clickable {
                                            speed = v
                                            player.setPlaybackSpeed(v)
                                            controlsVisible = true
                                        }
                                        .padding(horizontal = 11.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        if (v == 1f) "1x" else "${v}x",
                                        fontSize = 10.5.sp,
                                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (on) Color.White else Color.White.copy(alpha = 0.85f),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "已加入观看记录",
                                fontSize = 10.sp,
                                color = Ink.Mint.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
