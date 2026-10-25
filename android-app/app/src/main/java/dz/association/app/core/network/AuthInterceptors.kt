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

/** Adds the bearer token to API requests. */
class AuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = session.accessToken
        val req = chain.request()
        if (token == null || req.header("Authorization") != null) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * On 401, rotates the refresh token once (serialised so parallel requests share one refresh).
 * If refresh fails the session is cleared and the user must sign in again.
 */
class TokenAuthenticator(
    private val session: SessionManager,
    private val baseUrl: String,
    private val json: Json,
    private val refreshClient: () -> OkHttpClient,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.contains("/api/auth/")) return null
        if (responseCount(response) >= 2) return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        synchronized(lock) {
            val current = session.accessToken
            if (current != null && current != failedToken) {
                return response.request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refresh = session.refreshToken ?: return null
            val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refresh))
                .toRequestBody("application/json".toMediaType())
            val res = runCatching {
                refreshClient().newCall(Request.Builder().url(baseUrl + "api/auth/refresh").post(body).build()).execute()
            }.getOrNull() ?: return null // network error: keep the session, fail this request
            res.use {
                if (!it.isSuccessful) {
                    if (it.code == 401) session.clear()
                    return null
                }
                val tokens = json.decodeFromString(RefreshResponse.serializer(), it.body!!.string()).tokens
                session.saveTokens(tokens)
                return response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
            }
        }
    }

    private fun responseCount(r: Response): Int {
        var n = 1
        var p = r.priorResponse
        while (p != null) { n++; p = p.priorResponse }
        return n
    }
}
