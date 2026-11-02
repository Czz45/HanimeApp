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
    val pager = rememberPagerState(
        initialPage = 0,
        pageCount = { cards.size },
    )
    var muted by remember { mutableStateOf(false) }

    // Warm the two pages below: the next swipe should start instantly, and fetching
    // further ahead would compete with the clip that is playing now. The last two
    // pages also pull the next batch so the feed never dead-ends.
    LaunchedEffect(pager.currentPage, cards.size) {
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
            Text(
                if (cards.isEmpty()) "" else "${pager.currentPage + 1} / ${cards.size}",
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
                onClick = { showChrome = !showChrome },
            )
        }

        if (failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载失败", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
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

        // Bookmark + mute, stacked on the right so they never cover the frame centre.
        AnimatedVisibility(visible = showChrome, enter = fadeIn(), exit = fadeOut()) {
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
    onClick: () -> Unit,
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
        // Tap anywhere toggles transport; a very small target would fight the pager.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (player.isPlaying) player.pause() else player.play()
                    onClick()
                }
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
