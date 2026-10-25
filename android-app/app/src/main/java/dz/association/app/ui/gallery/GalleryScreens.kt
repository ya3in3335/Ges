package dz.association.app.ui.gallery

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dagger.hilt.android.lifecycle.HiltViewModel
import dz.association.app.BuildConfig
import dz.association.app.R
import dz.association.app.core.network.AppError
import dz.association.app.core.network.MediaItem as AppMedia
import dz.association.app.core.network.Result
import dz.association.app.core.util.absoluteUrl
import dz.association.app.core.util.locOrNull
import dz.association.app.data.ContentRepository
import dz.association.app.ui.components.AppCard
import dz.association.app.ui.components.AppTopBar
import dz.association.app.ui.components.EmptyState
import dz.association.app.ui.components.ErrorState
import dz.association.app.ui.components.FullScreenLoading
import dz.association.app.ui.components.OfflineBanner
import dz.association.app.ui.components.RemoteImage
import dz.association.app.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GalleryState(
    val kind: String = "photo",
    val category: String? = null,
    val items: List<AppMedia> = emptyList(),
    val categories: List<String> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val error: AppError? = null,
    val fromCache: Boolean = false,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(private val repo: ContentRepository) : ViewModel() {
    val state = MutableStateFlow(GalleryState())
    init { loadMore() }

    fun setKind(k: String) { state.value = GalleryState(kind = k, category = null); loadMore() }
    fun setCategory(c: String?) { state.value = GalleryState(kind = state.value.kind, category = c, categories = state.value.categories); loadMore() }

    fun loadMore() = viewModelScope.launch {
        val s = state.value
        state.value = s.copy(loading = true, error = null)
        when (val r = repo.media(s.kind, s.category, s.page + 1)) {
            is Result.Ok -> state.value = state.value.copy(
                items = s.items + r.value.data.items, categories = r.value.data.categories, page = s.page + 1,
                hasMore = r.value.data.hasMore, loading = false, fromCache = r.value.fromCache,
            )
            is Result.Err -> state.value = state.value.copy(loading = false, error = r.error)
        }
    }
}

@Composable
fun GalleryScreen(nav: (String) -> Unit, back: () -> Unit, vm: GalleryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.gallery_title), back) }) { pad ->
        Column(Modifier.padding(pad)) {
            PrimaryTabRow(selectedTabIndex = if (s.kind == "photo") 0 else 1) {
                Tab(s.kind == "photo", { vm.setKind("photo") }, text = { Text(stringResource(R.string.gallery_photos)) })
                Tab(s.kind == "video", { vm.setKind("video") }, text = { Text(stringResource(R.string.gallery_videos)) })
            }
            OfflineBanner(s.fromCache)
            if (s.categories.size > 1) {
                LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(s.category == null, { vm.setCategory(null) }, { Text(stringResource(R.string.cat_all)) }) }
                    items(s.categories) { c -> FilterChip(s.category == c, { vm.setCategory(c) }, { Text(c) }) }
                }
            }
            when {
                s.items.isEmpty() && s.loading -> FullScreenLoading()
                s.items.isEmpty() && s.error != null -> ErrorState(s.error!!, { vm.setKind(s.kind) })
                s.items.isEmpty() -> EmptyState()
                else -> LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(s.items, key = { it.id }) { m ->
                        AppCard(Modifier.aspectRatio(1f), onClick = { nav(if (m.kind == "video") Routes.video(m.url) else Routes.photo(m.url)) }) {
                            Box(Modifier.fillMaxSize()) {
                                RemoteImage(m.thumbnailUrl ?: m.url.takeIf { m.kind == "photo" }, locOrNull(m.titleAr, m.titleFr), Modifier.fillMaxSize())
                                if (m.kind == "video") Icon(Icons.Outlined.PlayCircle, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
                            }
                        }
                    }
                    if (s.hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                        LaunchedEffect(s.page) { vm.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

private fun shareUrl(ctx: android.content.Context, url: String, label: String) =
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), label))

/** Fullscreen photo with pinch-to-zoom. */
@Composable
fun PhotoScreen(url: String, back: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current
    val shareLabel = stringResource(R.string.share)
    val full = absoluteUrl(url, BuildConfig.API_BASE_URL)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RemoteImage(
            url, null,
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit,
        )
        IconButton(onClick = back, modifier = Modifier.systemBarsPadding().align(Alignment.TopStart)) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White) }
        if (full != null) IconButton(onClick = { shareUrl(ctx, full, shareLabel) }, modifier = Modifier.systemBarsPadding().align(Alignment.TopEnd)) {
            Icon(Icons.Outlined.Share, shareLabel, tint = Color.White)
        }
    }
}

/** True for direct video files that ExoPlayer can stream; other links (e.g. YouTube) open externally. */
fun isDirectVideo(url: String): Boolean {
    val path = Uri.parse(url).path?.lowercase() ?: return false
    return path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm")
}

@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(url: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val full = absoluteUrl(url, BuildConfig.API_BASE_URL)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (full != null && isDirectVideo(full)) {
            val player = remember { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(full)); prepare(); playWhenReady = true } }
            DisposableEffect(Unit) { onDispose { player.release() } }
            AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS) } }, modifier = Modifier.fillMaxSize())
        } else if (full != null) {
            // External platforms (YouTube, Facebook…) are opened in their app/browser: no embedded tracking.
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full))) } }, modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Outlined.OpenInNew, null, tint = Color.White)
                Text(stringResource(R.string.video_open_external), color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
        }
        IconButton(onClick = back, modifier = Modifier.systemBarsPadding().align(Alignment.TopStart)) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White) }
    }
}
