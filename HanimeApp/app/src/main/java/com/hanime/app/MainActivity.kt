package com.hanime.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanime.app.core.Card
import com.hanime.app.core.DownloadTask
import com.hanime.app.ui.AppViewModel
import com.hanime.app.ui.CollectionScreen
import com.hanime.app.ui.DetailSheet
import com.hanime.app.ui.DownloadsScreen
import com.hanime.app.ui.ErrorPill
import com.hanime.app.ui.HomeScreen
import com.hanime.app.ui.PlayerScreen
import com.hanime.app.ui.Route
import com.hanime.app.ui.SearchScreen
import com.hanime.app.ui.SettingsScreen
import com.hanime.app.ui.glass
import com.hanime.app.ui.rememberGlassBackdrop
import com.hanime.app.ui.theme.HanimeTheme
import com.hanime.app.ui.theme.Ink
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HanimeTheme { Root() } }
    }
}

@Composable
private fun Root(vm: AppViewModel = viewModel()) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val watched by Graph.repo.watched.collectAsStateWithLifecycle()

    // System back must walk the overlay stack (player -> detail -> tabs) instead of
    // finishing the activity, which is what the platform default would do.
    BackHandler(enabled = vm.route !is Route.Tabs) { vm.back() }

    // One backdrop for the whole app: the tab content is recorded into it and every
    // floating surface (tab bar, chips, detail header) samples that live recording.
    val backdrop = rememberGlassBackdrop()
    val topInset = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp().value.toInt()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Bg)
    ) {
        when (val r = vm.route) {
            is Route.Player -> PlayerScreen(
                detail = r.detail,
                source = r.source,
                onBack = { vm.back() },
            )

            else -> {
                // Tab content. When live glass is available it is recorded into the
                // backdrop layer; otherwise (software GPU) it is just painted.
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                ) {
                    when (vm.tab) {
                        0 -> HomeScreen(
                            vm = vm,
                            backdrop = backdrop,
                            watched = if (settings.hideWatched) emptySet() else watched,
                            topInset = topInset,
                            hideWatched = settings.hideWatched,
                            onToggleHideWatched = {
                                vm.saveSettings { it.copy(hideWatched = !it.hideWatched) }
                            },
                            onOpenSearch = { vm.openSearch() },
                            onOpen = { c: Card -> vm.openDetail(c.id, c) },
                        )

                        1 -> DownloadsScreen(
                            vm = vm,
                            downloads = downloads,
                            topInset = topInset,
                            onPlayLocal = { t: DownloadTask -> vm.playLocal(t) },
                            onOpenDetail = { id -> vm.openDetail(id) },
                        )

                        else -> SettingsScreen(
                            vm = vm,
                            settings = settings,
                            topInset = topInset,
                            backdrop = backdrop,
                            onHideWatched = { v -> vm.saveSettings { it.copy(hideWatched = v) } },
                            onQuality = { q -> vm.saveSettings { it.copy(preferredQuality = q) } },
                            onAutoPlay = { v -> vm.saveSettings { it.copy(autoPlay = v) } },
                            onProxy = { p -> vm.saveSettings { it.copy(proxy = p) } },
                            onClearWatched = { vm.clearWatched() },
                            downloadDir = vm.downloadDir(),
                            watchedCount = watched.size,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    }
                }

                // Detail sheet slides over the tabs.
                AnimatedContent(
                    targetState = vm.route is Route.Info,
                    transitionSpec = {
                        (fadeIn(tween(240)) + slideInVertically(tween(300)) { it / 12 })
                            .togetherWith(fadeOut(tween(180)))
                    },
                    label = "detail",
                ) { showing ->
                    if (showing) {
                        val r = vm.route as? Route.Info
                        DetailSheet(
                            vm = vm,
                            detail = vm.detail,
                            loading = vm.detailLoading,
                            error = vm.detailError,
                            seed = r?.seed,
                            topInset = topInset,
                            backdrop = backdrop,
                            downloads = downloads,
                            preferredQuality = settings.preferredQuality,
                            autoPlay = settings.autoPlay,
                            onBack = { vm.closeOverlay() },
                            onOpenRelated = { c -> vm.openDetail(c.id, c) },
                            onOpenCollection = { id, title -> vm.openCollection(id, title) },
                            onFullscreen = { s -> vm.playFullscreen(s) },
                        )
                    }
                }

                // Search and collection are full pages, so they sit above the tabs
                // instead of inside the overlay slot the sheet uses.
                AnimatedVisibility(
                    visible = vm.route is Route.Search,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 8 },
                    exit = fadeOut(tween(140)),
                ) {
                    if (vm.route is Route.Search) {
                        SearchScreen(
                            vm = vm,
                            backdrop = backdrop,
                            watched = watched,
                            topInset = topInset,
                            onOpen = { c -> vm.openDetail(c.id, c) },
                            onBack = { vm.closeOverlay() },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = vm.route is Route.Collection,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 8 },
                    exit = fadeOut(tween(140)),
                ) {
                    if (vm.route is Route.Collection) {
                        CollectionScreen(
                            vm = vm,
                            backdrop = backdrop,
                            watched = watched,
                            topInset = topInset,
                            onOpen = { c -> vm.openDetail(c.id, c) },
                            onDownloadAll = { _, q -> vm.downloadCollection(q) },
                            onBack = { vm.back() },
                        )
                    }
                }

                // Action failures surface as a floating toast above the bar rather than
                // reusing the detail-load error slot, which carries retry semantics.
                vm.notice?.let { text ->
                    LaunchedEffect(text) {
                        delay(2600)
                        vm.clearNotice()
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)) {
                        ErrorPill(text, onRetry = null)
                    }
                }

                // The bar hides while the sheet is open: the sheet owns the screen and
                // switching tabs behind it would silently change what "back" returns to.
                AnimatedVisibility(
                    visible = vm.route !is Route.Info,
                    enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 3 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 3 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    GlassTabBar(
                        selected = vm.tab,
                        backdrop = backdrop,
                        onSelect = { vm.selectTab(it) },
                    )
                }
            }
        }
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("首页", Icons.Rounded.Home),
    TabItem("下载", Icons.Rounded.Download),
    TabItem("设置", Icons.Rounded.Settings),
)

/**
 * Floating glass tab bar.
 *
 * A moving pill highlights the active tab instead of swapping icons, which keeps
 * the bar a single continuous object while switching pages.
 */
@Composable
private fun GlassTabBar(
    selected: Int,
    backdrop: com.kyant.backdrop.Backdrop?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    val indicator by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "tab",
    )

    Box(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 14.dp, start = 26.dp, end = 26.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .glass(backdrop, shape, tint = Color.White.copy(alpha = 0.05f), blurDp = 30f, lensDp = 16f)
        ) {
            // Sliding highlight behind the labels.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                val itemWidth = 1f / TABS.size
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(itemWidth)
                        .graphicsLayer {
                            translationX = indicator * size.width
                        }
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }

            Row(Modifier.fillMaxSize()) {
                TABS.forEachIndexed { i, tab ->
                    val active = i == selected
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(interactionSource = interaction, indication = null) { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (active) Ink.TextPrimary else Ink.TextTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            tab.label,
                            fontSize = 9.5.sp,
                            color = if (active) Ink.TextPrimary else Ink.TextTertiary,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
