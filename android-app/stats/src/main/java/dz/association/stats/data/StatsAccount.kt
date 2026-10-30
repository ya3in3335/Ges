package dz.association.stats.data

import dz.association.app.core.auth.SessionManager
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.ForgotRequest
import dz.association.app.core.network.LoginRequest
import dz.association.app.core.network.RefreshRequest
import dz.association.app.core.network.RegisterRequest
import dz.association.app.core.network.ResetRequest
import dz.association.app.core.network.Result
import dz.association.app.core.network.User
import dz.association.app.core.network.apiCall
import dz.association.app.core.util.AppLocale
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sign-in for the census apps: exactly the association's accounts, login endpoint, password reset and
 * encrypted session store (shared code in :core). No separate account or profile exists.
 */
@Singleton
class StatsAccount @Inject constructor(private val api: ApiService, private val stats: StatsApi, private val session: SessionManager) {
    /** Null when signed out — including when the server refuses to renew the session. */
    val user: StateFlow<User?> = session.user
    val isSignedIn get() = session.isSignedIn && session.user.value != null

    suspend fun login(identifier: String, password: String): Result<User> =
        apiCall { api.login(LoginRequest(identifier.trim(), password)) }.onOk { session.saveTokens(it.tokens); session.saveUser(it.user) }.map { it.user }

    suspend fun register(name: String, email: String, phone: String, password: String, confirm: String): Result<User> =
        apiCall { api.register(RegisterRequest(name.trim(), email.trim(), phone.trim(), password, confirm, AppLocale.current())) }
            .onOk { session.saveTokens(it.tokens); session.saveUser(it.user) }.map { it.user }

    suspend fun forgot(identifier: String) = apiCall { api.forgotPassword(ForgotRequest(identifier.trim())) }
    suspend fun reset(identifier: String, code: String, pw: String) = apiCall { api.resetPassword(ResetRequest(identifier.trim(), code.trim(), pw)) }

    /** Validates the stored session with the server (renewing it if needed) and refreshes the profile. */
    suspend fun refreshMe(): Result<User> = apiCall { api.me().user }.onOk { session.saveUser(it) }

    /** "manager", "officer" or null — decided by the server. */
    suspend fun accessLevel(): Result<String?> = apiCall { stats.access().level }

    suspend fun logout() {
        session.refreshToken?.let { rt -> apiCall { api.logout(RefreshRequest(rt)) } }
        session.clear()
    }

    /** Local sign-out (e.g. the session was refused by the server). */
    fun forget() = session.clear()
}

inline fun <T, R> Result<T>.map(f: (T) -> R): Result<R> = when (this) {
    is Result.Ok -> Result.Ok(f(value))
    is Result.Err -> this
}

inline fun <T> Result<T>.onOk(f: (T) -> Unit): Result<T> { if (this is Result.Ok) f(value); return this }
