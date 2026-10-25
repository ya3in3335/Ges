package dz.association.app.ui.intro

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import dz.association.app.R
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

/**
 * Plays the client's existing motion-graphics animation (assets/intro/intro.html, generated from the
 * supplied ZIP by tools/build_intro_html.py). The animation is procedural canvas code that adapts to
 * any screen size, so it runs in a locked-down local WebView:
 *  - content is served only from the APK via WebViewAssetLoader (https://appassets.androidplatform.net)
 *  - all other requests are blocked; network loads are disabled
 *  - no JavaScript interface, no file/content access, no storage
 * The page signals completion by navigating to a sentinel URL; a native timeout is a safety net.
 */
const val INTRO_ORIGIN = "https://appassets.androidplatform.net"
const val INTRO_URL = "$INTRO_ORIGIN/assets/intro/intro.html"
const val INTRO_DONE_URL = "$INTRO_ORIGIN/__intro_done"
// The client animation lasts 9 s (+0.45 s hold); this is only a safety net.
private const val SAFETY_TIMEOUT_MS = 13_000L

/** Pure decision used by the WebViewClient (unit-tested). */
object IntroUrlPolicy {
    fun isDone(url: String) = url == INTRO_DONE_URL
    fun isAllowedAsset(url: String) = url.startsWith("$INTRO_ORIGIN/assets/intro/")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    var done by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }
    val complete = { if (!done) { done = true; finish() } }

    LaunchedEffect(Unit) { delay(1500); showSkip = true }
    LaunchedEffect(Unit) { delay(SAFETY_TIMEOUT_MS); complete() }
    BackHandler { complete() }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
                WebView(ctx).apply {
                    setBackgroundColor(Color.BLACK)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    settings.apply {
                        javaScriptEnabled = true // required: the animation is canvas + JavaScript
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        databaseEnabled = false
                        blockNetworkLoads = true
                        setGeolocationEnabled(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        mediaPlaybackRequiresUserGesture = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (IntroUrlPolicy.isDone(request.url.toString())) post { complete() }
                            return true // never navigate anywhere
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            if (IntroUrlPolicy.isAllowedAsset(url)) return loader.shouldInterceptRequest(request.url)
                            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                            post { complete() }
                            return true
                        }
                    }
                    loadUrl(INTRO_URL)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )
        if (showSkip && !done) {
            TextButton(
                onClick = { complete() },
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp),
            ) { Text(stringResource(R.string.skip), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f)) }
        }
    }
}

