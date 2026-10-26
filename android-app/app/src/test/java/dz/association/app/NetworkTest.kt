package dz.association.app

import androidx.test.core.app.ApplicationProvider
import dz.association.app.core.auth.Crypto
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.auth.TokenStore
import dz.association.app.core.network.AppError
import dz.association.app.core.network.AuthInterceptor
import dz.association.app.core.network.Result
import dz.association.app.core.network.TokenAuthenticator
import dz.association.app.core.network.TokenRefresher
import dz.association.app.core.network.Tokens
import dz.association.app.core.network.apiCall
import dz.association.app.core.network.toAppError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** Reversible fake: the Android Keystore is not available under Robolectric. */
private class FakeCrypto : Crypto {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(cipherText: String) = cipherText.removePrefix("enc:").reversed()
}

@RunWith(RobolectricTestRunner::class)
class NetworkTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var session: SessionManager

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = SessionManager(TokenStore(ApplicationProvider.getApplicationContext(), FakeCrypto()), json)
        session.clear()
    }

    @After fun tearDown() = server.shutdown()

    private fun client(): OkHttpClient {
        val base = OkHttpClient()
        val refresher = TokenRefresher(session, server.url("/").toString(), json) { base }
        return base.newBuilder()
            .addInterceptor(AuthInterceptor(session, refresher))
            .authenticator(TokenAuthenticator(refresher))
            .build()
    }

    private fun tokens(a: String, r: String) = Tokens(a, 0, r, 0)

    /** A JWT-shaped access token whose `exp` is [inSeconds] from now. */
    private fun jwt(name: String, inSeconds: Long): String {
        val exp = System.currentTimeMillis() / 1000 + inSeconds
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$name","exp":$exp}""".toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.sig-$name"
    }

    private val refreshed = """{"tokens":{"accessToken":"NEW","accessTokenExpiresAt":1,"refreshToken":"refresh-2","refreshTokenExpiresAt":2}}"""

    @Test fun expiredTokenIsRenewedBeforeCallingPublicEndpoint() {
        // Public endpoints answer an expired token as anonymous (200, "not eligible"), never 401.
        session.saveTokens(tokens(jwt("old", -60), "refresh-1"))
        server.enqueue(MockResponse().setBody(refreshed))
        server.enqueue(MockResponse().setBody("""{"eligible":true}"""))
        val res = client().newCall(Request.Builder().url(server.url("/api/polls/p1")).build()).execute()
        assertEquals(200, res.code)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer NEW", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-2", session.refreshToken)
    }

    @Test fun validTokenIsUsedAsIs() {
        val valid = jwt("ok", 600)
        session.saveTokens(tokens(valid, "refresh-1"))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        client().newCall(Request.Builder().url(server.url("/api/polls/p1")).build()).execute().close()
        assertEquals("Bearer $valid", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun expiryIsReadFromTheToken() {
        assertTrue(AuthInterceptor.isExpired(jwt("a", -1)))
        assertTrue(AuthInterceptor.isExpired(jwt("b", 10))) // inside the 30 s safety margin
        assertFalse(AuthInterceptor.isExpired(jwt("c", 600)))
        assertTrue(AuthInterceptor.isExpired("not-a-jwt"))
    }

    @Test fun tokensAreStoredEncrypted() {
        session.saveTokens(tokens("access-1", "refresh-1"))
        val raw = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("secure_session", 0).all.values.joinToString()
        assertFalse(raw.contains("refresh-1"))
        assertEquals("refresh-1", session.refreshToken)
    }

    @Test fun refreshesOnceOn401AndRetries() {
        val old = jwt("old", 600) // looks valid locally, but the server has revoked/rotated it
        session.saveTokens(tokens(old, "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setBody("""{"tokens":{"accessToken":"new","accessTokenExpiresAt":1,"refreshToken":"refresh-2","refreshTokenExpiresAt":2}}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val res = client().newCall(Request.Builder().url(server.url("/api/me")).build()).execute()
        assertEquals(200, res.code)
        assertEquals("Bearer $old", server.takeRequest().getHeader("Authorization"))
        val refreshReq = server.takeRequest()
        assertEquals("/api/auth/refresh", refreshReq.path)
        assertTrue(refreshReq.body.readUtf8().contains("refresh-1"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-2", session.refreshToken)
    }

    @Test fun failedRefreshClearsSession() {
        session.saveTokens(tokens(jwt("old", 600), "stolen-or-reused"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"refresh_token_reused"}"""))
        val res = client().newCall(Request.Builder().url(server.url("/api/me")).build()).execute()
        assertEquals(401, res.code)
        assertNull(session.refreshToken)
        assertFalse(session.isSignedIn)
    }

    private fun http(code: Int, body: String, headers: Map<String, String> = emptyMap()): HttpException {
        val raw = okhttp3.Response.Builder().code(code).message("x").protocol(okhttp3.Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://x.test/").build()).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return HttpException(Response.error<Any>(body.toResponseBody(), raw))
    }

    @Test fun errorsAreMappedToTypedErrors() {
        assertEquals(AppError.Api(401, "invalid_credentials"), http(401, """{"error":"invalid_credentials"}""").toAppError())
        assertEquals(AppError.Unauthorized, http(401, """{"error":"unauthorized"}""").toAppError())
        assertEquals(AppError.RateLimited(30), http(429, """{"error":"rate_limited"}""", mapOf("retry-after" to "30")).toAppError())
        assertEquals(AppError.Api(409, "already_voted"), http(409, """{"error":"already_voted"}""").toAppError())
        assertEquals(AppError.Network, IOException().toAppError())
    }

    @Test fun apiCallWrapsFailures() = runTest {
        val r = apiCall<Unit> { throw IOException("offline") }
        assertEquals(Result.Err(AppError.Network), r)
    }
}
