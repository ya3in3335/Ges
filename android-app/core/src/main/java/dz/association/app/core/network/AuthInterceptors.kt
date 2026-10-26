package dz.association.app.core.network

import dz.association.app.core.auth.SessionManager
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okio.ByteString.Companion.decodeBase64

/**
 * Adds the bearer token to API requests, renewing it first when it has expired. Public endpoints
 * (poll details, home…) answer an expired token as if the visitor were anonymous rather than with
 * 401, so waiting for a 401 would leave a signed-in member looking "not eligible" to vote.
 */
class AuthInterceptor(private val session: SessionManager, private val refresher: TokenRefresher? = null) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Authorization") != null || req.url.encodedPath.endsWith("/api/auth/refresh")) return chain.proceed(req)
        var token = session.accessToken
        if (refresher != null && session.refreshToken != null && (token == null || isExpired(token))) {
            token = refresher.refresh(failedToken = token) ?: session.accessToken
        }
        if (token == null) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }

    companion object {
        /** Reads the JWT `exp` claim; renews 30 s early to absorb clock drift and request time. */
        fun isExpired(jwt: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            val payload = jwt.split('.').getOrNull(1) ?: return true
            val exp = runCatching {
                val text = payload.decodeBase64()!!.utf8()
                Regex("\"exp\"\\s*:\\s*(\\d+)").find(text)!!.groupValues[1].toLong()
            }.getOrNull() ?: return true
            return exp * 1000 - 30_000 <= nowMs
        }
    }
}

/** Rotates the refresh token once at a time (parallel requests share one refresh). */
class TokenRefresher(
    private val session: SessionManager,
    private val baseUrl: String,
    private val json: Json,
    private val client: () -> OkHttpClient,
) {
    private val lock = Any()

    /**
     * Returns a fresh access token, or null when it could not be renewed. The session is cleared
     * only when the server rejects the refresh token, never on a network failure.
     */
    fun refresh(failedToken: String?): String? = synchronized(lock) {
        val current = session.accessToken
        if (current != null && current != failedToken && !AuthInterceptor.isExpired(current)) return current
        val refresh = session.refreshToken ?: return null
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refresh))
            .toRequestBody("application/json".toMediaType())
        val res = runCatching {
            client().newCall(Request.Builder().url(baseUrl + "api/auth/refresh").post(body).build()).execute()
        }.getOrNull() ?: return null
        res.use {
            if (!it.isSuccessful) {
                if (it.code == 401) session.clear()
                return null
            }
            val tokens = json.decodeFromString(RefreshResponse.serializer(), it.body!!.string()).tokens
            session.saveTokens(tokens)
            tokens.accessToken
        }
    }
}

/** On 401, renews the access token once and repeats the request; if renewal fails the request fails. */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.contains("/api/auth/")) return null
        if (responseCount(response) >= 2) return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val token = refresher.refresh(failedToken) ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $token").build()
    }

    private fun responseCount(r: Response): Int {
        var n = 1
        var p = r.priorResponse
        while (p != null) { n++; p = p.priorResponse }
        return n
    }
}
