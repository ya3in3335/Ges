package dz.association.app.ui.intro

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dz.association.app.R
import kotlinx.coroutines.delay

/**
 * Plays the client's existing motion graphics (res/raw/intro.mp4). The video is rendered frame by
 * frame from the client's own render(ctx, W, H, t) code by tools/render_intro_video.js, so the motion
 * is unchanged; playing it natively avoids any WebView/JavaScript dependency on the user's phone.
 * The first frame is black (matches the system starting window) and the last frame is the white
 * logo, from which the home screen fades in.
 */
private const val SAFETY_TIMEOUT_MS = 14_000L

@OptIn(UnstableApi::class)
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    var done by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }
    val complete = { if (!done) { done = true; finish() } }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${ctx.packageName}/${R.raw.intro}")))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) complete() }
            override fun onPlayerError(error: PlaybackException) = complete() // never block the app
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    LaunchedEffect(Unit) { delay(1500); showSkip = true }
    LaunchedEffect(Unit) { delay(SAFETY_TIMEOUT_MS); complete() }
    BackHandler { complete() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    // Fill the screen; the logo stays centred with margins on every aspect ratio.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
        )
        if (showSkip && !done) {
            TextButton(onClick = { complete() }, modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp)) {
                Text(stringResource(R.string.skip), color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}
