package dz.association.app.core.network

import android.content.Context
import coil.ImageLoader
import okhttp3.OkHttpClient

/** Image loader sharing the app's resilient network stack (DNS fallback, retries, API failover). */
object Images {
    fun loader(ctx: Context, client: OkHttpClient): ImageLoader =
        ImageLoader.Builder(ctx).okHttpClient(client).crossfade(true).respectCacheHeaders(false).build()
}
