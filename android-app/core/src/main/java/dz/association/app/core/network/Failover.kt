package dz.association.app.core.network

import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The API is reachable through several equivalent addresses on different domains (the Worker on
 * workers.dev and a gateway on pages.dev). The address that last worked is remembered and tried
 * first, so a user whose network filters or breaks one domain only pays the cost once.
 */
class ApiHosts(urls: List<String>, private val prefs: SharedPreferences? = null) {
    val bases: List<HttpUrl> = urls.distinct().map { it.toHttpUrl() }

    @Volatile var preferred: Int = prefs?.getInt(KEY, 0)?.takeIf { it in bases.indices } ?: 0
        private set

    fun indexOf(url: HttpUrl): Int = bases.indexOfFirst {
        it.host == url.host && it.port == url.port && url.encodedPath.startsWith(it.encodedPath)
    }

    /** Hosts in the order to try them: the preferred one first, then the others. */
    fun order(): List<Int> = listOf(preferred) + bases.indices.filter { it != preferred }

    fun markWorking(i: Int) {
        if (i == preferred) return
        preferred = i
        prefs?.edit()?.putInt(KEY, i)?.apply()
    }

    /** Same request, sent to base [i] (the path below the base is kept). */
    fun rewrite(url: HttpUrl, from: Int, to: Int): HttpUrl {
        if (from == to) return url
        val rest = url.encodedPath.removePrefix(bases[from].encodedPath)
        return url.newBuilder().scheme(bases[to].scheme).host(bases[to].host).port(bases[to].port)
            .encodedPath(bases[to].encodedPath + rest).build()
    }

    private companion object { const val KEY = "preferred_api_host" }
}

/**
 * Sends API requests to the preferred address and, when it cannot be reached, immediately to the
 * next one. Requests to other hosts (e.g. YouTube thumbnails) pass through untouched.
 *
 * A request is moved to another address only when it is safe: GET requests always, other methods
 * only when the failure proves the request never reached the API (DNS, refused connection, TLS
 * handshake, or a filtering proxy's page instead of the API's JSON), so votes are never doubled.
 */
class FailoverInterceptor(private val hosts: ApiHosts) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val origin = hosts.indexOf(req.url)
        if (origin < 0 || hosts.bases.size < 2) return chain.proceed(req)
        val idempotent = req.method == "GET" || req.method == "HEAD"
        var lastError: IOException? = null
        val order = hosts.order()
        for ((n, i) in order.withIndex()) {
            val isLast = n == order.lastIndex
            val attempt = req.newBuilder().url(hosts.rewrite(req.url, origin, i)).build()
            try {
                val res = chain.proceed(attempt)
                // A 5xx page could follow a partially run POST, so only safe requests move on for those.
                if (!isLast && looksFiltered(attempt, res) && (idempotent || res.code < 500)) {
                    res.close()
                    NetworkDiagnostics.recordCode("BLOCKED")
                    continue
                }
                hosts.markWorking(i)
                return res
            } catch (e: IOException) {
                NetworkDiagnostics.record(e)
                lastError = e
                if (isLast || chain.call().isCanceled() || !(idempotent || neverReachedServer(e))) throw e
            }
        }
        throw lastError ?: IOException("no API host reachable")
    }

    companion object {
        fun neverReachedServer(e: IOException): Boolean = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException,
            is SSLHandshakeException, is SSLPeerUnverifiedException -> true
            is InterruptedIOException -> e.message?.contains("connect", ignoreCase = true) == true
            else -> false
        }

        /** The API always answers /api/ with JSON; an HTML page in its place comes from a filter or captive portal. */
        fun looksFiltered(req: Request, res: Response): Boolean {
            if (!req.url.encodedPath.contains("/api/")) return false
            val type = res.header("content-type").orEmpty()
            return !type.contains("json", ignoreCase = true) && res.code != 204 && res.code != 304
        }
    }
}
