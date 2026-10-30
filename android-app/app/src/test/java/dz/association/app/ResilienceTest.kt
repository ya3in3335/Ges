package dz.association.app

import dz.association.app.core.network.NetworkDiagnostics
import dz.association.app.core.network.RetryInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ResilienceTest {
    private fun client() = OkHttpClient.Builder()
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(RetryInterceptor(maxRetries = 2))
        .build()

    @Test fun getIsRetriedOnTimeoutThenSucceeds() {
        val server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("ok"))
        val res = client().newCall(Request.Builder().url(server.url("/api/home").newBuilder().host("127.0.0.1").build()).build()).execute()
        assertEquals("ok", res.body!!.string())
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun postThatMayHaveReachedTheServerIsNeverRepeated() {
        val server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("second"))
        val call = client().newCall(Request.Builder().url(server.url("/api/polls/x/vote").newBuilder().host("127.0.0.1").build()).post("{}".toRequestBody()).build())
        runCatching { call.execute() }
        assertEquals(1, server.requestCount)
        assertEquals("TIMEOUT", NetworkDiagnostics.lastFailure)
        server.shutdown()
    }

    @Test fun connectFailureIsRetriedAndReported() {
        val port = ServerSocket(0).use { it.localPort } // nothing listens here
        val started = System.nanoTime()
        val r = runCatching { client().newCall(Request.Builder().url("http://127.0.0.1:$port/").post("{}".toRequestBody()).build()).execute() }
        assertTrue(r.isFailure)
        assertEquals("CONNECT", NetworkDiagnostics.lastFailure)
        // two backoff waits (0.8 s + 1.6 s) prove the request was retried twice
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 2_300)
    }
}
