package com.hanime.app.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.hanime.app.core.Card
import com.hanime.app.core.Detail
import com.hanime.app.core.Source
import com.hanime.app.ui.theme.Ink
import kotlinx.coroutines.delay

/**
 * Short-video feed: one title per screen, swipe up for the next.
 *
 * The whole point is that the next clip is already playable when it arrives, so
 * the detail pages are resolved ahead of time through [AppViewModel.reelDetail]
 * (which also memoises) and only the visible page owns an ExoPlayer. Building one
 * player per page would hold dozens of decoders open.
 *
 * VerticalPager rather than a LazyColumn: it gives snap-to-page physics and
 * offscreen pre-composition for free, which is exactly the behaviour a swipe feed
 * needs, and it keeps the player count bounded because only pages inside the
 * pager's prefetch window exist.
 */
@Composable
fun ReelsScreen(
    vm: AppViewModel,
    watched: Set<Long>,
    onOpen: (Card) -> Unit,
    onBack: () -> Unit,
) {
    val cards = vm.reelCards
    // Resume where the user left off: a detail sheet tears this screen down, and
    // the VM holds the page so coming back does not restart at clip one.
    val pager = rememberPagerState(
        initialPage = vm.reelIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0)),
        pageCount = { cards.size },
    )
    var muted by remember { mutableStateOf(false) }

    // Warm the two pages below: the next swipe should start instantly, and fetching
    // further ahead would compete with the clip that is playing now. The last two
    // pages also pull the next batch so the feed never dead-ends.
    LaunchedEffect(pager.currentPage, cards.size) {
        vm.rememberReelIndex(pager.currentPage)
        if (cards.isEmpty()) return@LaunchedEffect
        val from = (pager.currentPage + 1).coerceAtMost(cards.lastIndex)
        val to = (pager.currentPage + 2).coerceAtMost(cards.lastIndex)
        if (from <= to) vm.prefetchReels((from..to).map { cards[it].id })
        // Mark as watched on arrival, same as opening the detail page does.
        cards.getOrNull(pager.currentPage)?.let { vm.markReelWatched(it.id) }
        if (pager.currentPage >= cards.size - 3) vm.loadReels(reset = false)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (vm.reelLoading) {
                    LoadingDots()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            vm.reelError ?: "这个分类暂时没有内容",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Text(
                            "重试",
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.16f))
                                .clickable { vm.loadReels(reset = true) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        } else VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            val card = cards.getOrNull(page) ?: return@VerticalPager
            ReelPage(
                vm = vm,
                card = card,
                active = page == pager.currentPage,
                watched = card.id in watched,
                muted = muted,
                onToggleMute = { muted = !muted },
                onOpen = { onOpen(card) },
            )
        }

        // Floating chrome: back at the top left, so it never fights the video for
        // the centre of the screen.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 12.dp, end = 16.dp, top = 6.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // Random toggle sits with the counter: it changes what the whole feed
            // contains, so it belongs with the feed-level controls, not per item.
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable { vm.toggleReelRandom() }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = if (vm.reelRandom) "关闭随机" else "随机",
                    tint = if (vm.reelRandom) Ink.Accent else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    if (vm.reelRandom) "随机" else "顺序",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (vm.reelRandom) Ink.Accent else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            // The catalogue id is the actual proof of randomness: random mode jumps
            // all over this number, sequential mode walks it down.
            Text(
                if (cards.isEmpty()) {
                    ""
                } else {
                    val id = cards.getOrNull(pager.currentPage)?.id
                    "${pager.currentPage + 1}/${cards.size} · #$id"
                },
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // Genre selector along the top. The feed reloads in place and the pager
        // resets to the first item, which is what picking a new genre should mean.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(top = 50.dp),
        ) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(GENRES, key = { it }) { g ->
                    val on = vm.reelGenre == g
                    Text(
                        g,
                        fontSize = 11.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color(0xFF0B0B10) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Color.White else Color.Black.copy(alpha = 0.42f))
                            .clickable { vm.chooseReelGenre(g) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Bridge between the player and the timeline drawn by [ReelPage].
 *
 * The scrubber has to sit above the title block, but the player lives inside the
 * video layer, so the two communicate through this holder instead of lifting the
 * whole ExoPlayer up into the page composable.
 */
private class ReelControls {
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var playing by mutableStateOf(false)
    var seekTo: ((Long) -> Unit)? = null
    var togglePlay: (() -> Unit)? = null
}

@Composable
private fun ReelPage(
    vm: AppViewModel,
    card: Card,
    active: Boolean,
    watched: Boolean,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onOpen: () -> Unit,
) {
    var detail by remember(card.id) { mutableStateOf<Detail?>(null) }
    var failed by remember(card.id) { mutableStateOf(false) }
    var showChrome by remember(card.id) { mutableStateOf(true) }
    val controls = remember(card.id) { ReelControls() }

    LaunchedEffect(card.id) {
        // Usually already warm from the prefetch; this await resolves instantly then.
        runCatching { vm.reelDetail(card.id) }
            .onSuccess { detail = it }
            .onFailure { failed = true }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // Poster covers the frame only while the metadata resolves, so a fast swipe
        // never flashes a black rectangle. Once the video exists the backdrop turns
        // black: a poster behind letterbox bars looks like a stretched video.
        if (detail == null) {
            if (card.cover.isNotBlank()) {
                com.hanime.app.ui.Cover(
                    url = card.cover,
                    modifier = Modifier.fillMaxSize(),
                    corner = 0.dp,
                    wide = card.wide,
                )
            } else {
                Box(Modifier.fillMaxSize().background(Ink.BgElevated))
            }
        }

        val source = remember(detail) { detail?.sources?.maxByOrNull { it.quality } }
        if (source != null) {
            ReelVideo(
                source = source,
                active = active,
                muted = muted,
                controls = controls,
            )
        }

        if (failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载失败", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }

        // Gesture layer above the video but under everything interactive.
        //
        // Vertical swipes are deliberately left to the pager — brightness/volume on
        // the vertical axis would fight page changes. Horizontal drag seeks, which
        // nothing else claims, and the scrubber above reflects it live.
        val seekSpan = (vm.settings.value.seekSeconds * 1000L).coerceAtLeast(10_000L)
        var dragTarget by remember(card.id) { mutableStateOf<Long?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(card.id, controls.duration) {
                    detectTapGestures(
                        onTap = { showChrome = !showChrome },
                        onDoubleTap = { controls.togglePlay?.invoke() },
                    )
                }
                .pointerInput(card.id, controls.duration, seekSpan) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragTarget = controls.position },
                        onDragEnd = {
                            dragTarget?.let { controls.seekTo?.invoke(it) }
                            dragTarget = null
                        },
                        onDragCancel = { dragTarget = null },
                    ) { change, delta ->
                        change.consume()
                        val base = dragTarget ?: controls.position
                        val d = controls.duration
                        if (d > 0) {
                            // Same mapping as the full-screen player: one full width
                            // covers `seekSeconds`, so the feel is consistent.
                            val step = (delta / size.width.toFloat() * seekSpan).toLong()
                            dragTarget = (base + step).coerceIn(0L, d)
                        }
                    }
                },
        ) {
            dragTarget?.let { target ->
                if (controls.duration > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 96.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            "${fmtReel(target)} / ${fmtReel(controls.duration)}",
                            fontSize = 11.sp,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Bottom gradient carries the title no matter how bright the frame is.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(210.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 16.dp, end = 78.dp, bottom = 18.dp),
        ) {
            if (controls.duration > 0) {
                ReelScrubber(
                    position = controls.position,
                    duration = controls.duration,
                    onSeek = { controls.seekTo?.invoke(it) },
                )
            }
            Text(
                detail?.cnTitle?.takeIf { it.isNotBlank() && it != detail?.title } ?: card.title,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                detail?.author?.ifBlank { null },
                detail?.genre?.ifBlank { null },
                source?.let { "${it.quality}p" },
                detail?.views?.ifBlank { null },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    fontSize = 10.5.sp,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Row(
                Modifier.padding(top = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "查看详情",
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (watched) {
                    Text(
                        "已看",
                        fontSize = 9.5.sp,
                        color = Ink.Mint,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Bookmark + mute stay put while the caption toggles: they are actions, not
        // decoration, and hiding the only way to save a video is a bug, not chrome.
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FavoriteButton(
                active = vm.isFavorite(card.id),
                onClick = { vm.toggleFavorite(card) },
                size = 38,
            )
            MuteButton(muted = muted, onClick = onToggleMute)
        }

    }
}

@Composable
private fun MuteButton(muted: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
            contentDescription = if (muted) "取消静音" else "静音",
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}


/**
 * Thin draggable timeline.
 *
 * A Material Slider would be 40dp tall and cover the title; this is a 14dp touch
 * band with a 2.5dp track so it can sit directly above the caption. Drag anywhere
 * on the band, the thumb follows the finger and the seek is committed on release.
 */
@Composable
private fun ReelScrubber(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val fraction = if (dragging) {
        dragFraction
    } else if (duration > 0) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragFraction = (it.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging = false
                            onSeek((dragFraction * duration).toLong())
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((f * duration).toLong())
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (dragging) 4.dp else 2.5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.24f))
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(if (dragging) 4.dp else 2.5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.92f))
            )
            if (dragging) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(14.dp),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(9.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

/**
 * The one player a page owns.
 *
 * Built only while the page is the current one: [active] flips to false as soon as
 * the pager settles on a neighbour, and releasing then keeps exactly one decoder
 * alive instead of one per pre-composed page.
 */
@Composable
private fun ReelVideo(
    source: Source,
    active: Boolean,
    muted: Boolean,
    controls: ReelControls,
) {
    val context = LocalContext.current
    var videoRatio by remember(source.url) { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }

    val player = remember(source.url) {
        com.hanime.app.Players.build(context, source.url, com.hanime.app.Ua.BROWSER, playWhenReady = false)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoRatio = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Pause the moment the page stops being current: a released-then-rebuilt player
    // would otherwise keep decoding audio offscreen.
    LaunchedEffect(active) {
        if (active) {
            player.prepare()
            player.playWhenReady = true
        } else {
            player.playWhenReady = false
            player.pause()
        }
    }

    // Expose the transport to the page so its scrubber can drive this player.
    DisposableEffect(player) {
        controls.seekTo = { ms ->
            player.seekTo(ms.coerceIn(0L, player.duration.coerceAtLeast(0L)))
            player.play()
        }
        controls.togglePlay = { if (player.isPlaying) player.pause() else player.play() }
        onDispose {
            controls.seekTo = null
            controls.togglePlay = null
        }
    }

    // 200 ms is smooth enough for a thin progress bar and cheap next to per-frame
    // callbacks; only the visible page polls, so the cost stays bounded.
    LaunchedEffect(player, active) {
        while (true) {
            if (active) {
                controls.position = player.currentPosition.coerceAtLeast(0L)
                controls.duration = player.duration.coerceAtLeast(0L)
                controls.playing = player.isPlaying
            }
            delay(200)
        }
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    // Loop, because a feed that stops on a 90-second clip reads as broken.
    LaunchedEffect(player, active) {
        while (true) {
            if (active) {
                val d = player.duration
                if (d > 0 && player.currentPosition >= d - 250) {
                    player.seekTo(0)
                    player.play()
                }
            }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
            modifier = if (videoRatio <= 0f) {
                Modifier.fillMaxSize()
            } else {
                // Letterboxed so a portrait clip is not cut, and a landscape one is
                // not zoomed past its edges.
                Modifier.fillMaxWidth().aspectRatio(videoRatio)
            },
        )
        if (buffering && active) {
            LoadingDots(Modifier.align(Alignment.Center))
        } else if (!playing && active) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

/** Short clock for the feed; reels are minutes long, so hours never show. */
private fun fmtReel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
