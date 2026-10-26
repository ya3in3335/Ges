package dz.association.app

import dz.association.app.core.network.ApiHosts
import dz.association.app.core.network.FailoverInterceptor
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

class FailoverTest {
    private fun client(hosts: ApiHosts) = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(FailoverInterceptor(hosts))
        .build()

    /** A local port with nothing listening: connections are refused, like a blocked address. */
    private fun deadUrl(): String { val p = ServerSocket(0).use { it.localPort }; return "http://127.0.0.1:$p/" }

    private fun json(body: String) = MockResponse().setHeader("content-type", "application/json").setBody(body)

    @Test fun unreachablePrimaryFallsBackAndIsRemembered() {
        val backup = MockWebServer().apply { start() }
        repeat(2) { backup.enqueue(json("""{"ok":true}""")) }
        val dead = deadUrl()
        val hosts = ApiHosts(listOf(dead, backup.url("/").toString()))
        val c = client(hosts)
        val res = c.newCall(Request.Builder().url(dead + "api/health").build()).execute()
        assertEquals("""{"ok":true}""", res.body!!.string())
        assertEquals("/api/health", backup.takeRequest().path)
        assertEquals(1, hosts.preferred)
        // Next call goes straight to the address that works.
        assertEquals(1, hosts.order().first())
        c.newCall(Request.Builder().url(dead + "api/home").build()).execute().close()
        assertEquals(2, backup.requestCount)
        backup.shutdown()
    }

    @Test fun refusedPostIsSafelySentToTheBackup() {
        val backup = MockWebServer().apply { start() }
        backup.enqueue(json("""{"ok":true}"""))
        val dead = deadUrl()
        val c = client(ApiHosts(listOf(dead, backup.url("/").toString())))
        val res = c.newCall(Request.Builder().url(dead + "api/auth/register").post("{}".toRequestBody()).build()).execute()
        assertEquals(200, res.code)
        assertEquals("POST", backup.takeRequest().method)
        backup.shutdown()
    }

    @Test fun postThatMayHaveReachedTheServerIsNotSentTwice() {
        val primary = MockWebServer().apply { start() }
        val backup = MockWebServer().apply { start() }
        primary.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = client(ApiHosts(listOf(primary.url("/").toString(), backup.url("/").toString())))
        val r = runCatching { c.newCall(Request.Builder().url(primary.url("/api/polls/x/vote")).post("{}".toRequestBody()).build()).execute() }
        assertTrue(r.isFailure)
        assertEquals(0, backup.requestCount)
        primary.shutdown(); backup.shutdown()
    }

    @Test fun filterPageInsteadOfJsonMovesToBackup() {
        val primary = MockWebServer().apply { start() }
        val backup = MockWebServer().apply { start() }
        primary.enqueue(MockResponse().setResponseCode(403).setHeader("content-type", "text/html").setBody("<html>blocked</html>"))
        backup.enqueue(json("""{"settings":{}}"""))
        val c = client(ApiHosts(listOf(primary.url("/").toString(), backup.url("/").toString())))
        val res = c.newCall(Request.Builder().url(primary.url("/api/settings")).build()).execute()
        assertEquals("""{"settings":{}}""", res.body!!.string())
        primary.shutdown(); backup.shutdown()
    }

    @Test fun otherHostsAreUntouched() {
        val other = MockWebServer().apply { start() }
        other.enqueue(MockResponse().setBody("img"))
        val c = client(ApiHosts(listOf(deadUrl(), "https://backup.example/")))
        assertEquals("img", c.newCall(Request.Builder().url(other.url("/thumb.jpg")).build()).execute().body!!.string())
        other.shutdown()
    }
}
