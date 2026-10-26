package dz.association.app.core.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Last network failure, shown in small print under error messages to help diagnose field issues. */
object NetworkDiagnostics {
    @Volatile var lastFailure: String? = null
        private set

    fun record(e: Throwable) {
        lastFailure = when (e) {
            is UnknownHostException -> "DNS"
            is ConnectException, is NoRouteToHostException -> "CONNECT"
            is InterruptedIOException -> "TIMEOUT"
            is SSLException -> "TLS"
            else -> e.javaClass.simpleName.take(30)
        }
    }
}

/**
 * Name resolution that copes with weak or filtered mobile networks:
 *  - IPv4 addresses are tried first (broken IPv6 routes otherwise cost a full connect timeout);
 *  - if the operator's DNS fails, the name is resolved over HTTPS via Cloudflare or Google.
 */
class ResilientDns(bootstrapClient: OkHttpClient) : Dns {
    private val doh: List<Dns> = listOf(
        "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1"),
        "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4"),
    ).map { (url, ips) ->
        DnsOverHttps.Builder().client(bootstrapClient).url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).includeIPv6(true).build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val result = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            doh.firstNotNullOfOrNull { runCatching { it.lookup(hostname) }.getOrNull()?.takeIf { r -> r.isNotEmpty() } } ?: throw e
        }
        return result.sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}

/**
 * Retries requests that never reached the server (DNS / connect failures) up to [maxRetries] times
 * with a short backoff. GET requests are also retried on timeouts. A POST that may have reached the
 * server is never repeated, so actions such as voting or registering are not duplicated.
 */
class RetryInterceptor(private val maxRetries: Int = 2) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            try {
                return chain.proceed(chain.request())
            } catch (e: IOException) {
                NetworkDiagnostics.record(e)
                val neverSent = e is UnknownHostException || e is ConnectException || e is NoRouteToHostException
                val safe = chain.request().method == "GET" && e is InterruptedIOException
                if (attempt >= maxRetries || !(neverSent || safe) || chain.call().isCanceled()) throw e
                attempt++
                try { Thread.sleep(800L * attempt) } catch (_: InterruptedException) { throw e }
            }
        }
    }
}
