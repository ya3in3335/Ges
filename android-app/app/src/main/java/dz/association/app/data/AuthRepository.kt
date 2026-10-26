package dz.association.app.data

import dz.association.app.core.auth.SessionManager
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.ChangePasswordRequest
import dz.association.app.core.network.DeleteAccountRequest
import dz.association.app.core.network.EmailCodeRequest
import dz.association.app.core.network.ForgotRequest
import dz.association.app.core.network.LoginRequest
import dz.association.app.core.network.Preferences
import dz.association.app.core.network.RefreshRequest
import dz.association.app.core.network.RegisterRequest
import dz.association.app.core.network.ResetRequest
import dz.association.app.core.network.Result
import dz.association.app.core.network.UpdateMeRequest
import dz.association.app.core.network.User
import dz.association.app.core.network.apiCall
import dz.association.app.core.db.CacheDao
import dz.association.app.core.util.AppLocale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
    private val cache: CacheDao,
) {
    val user = session.user
    val isSignedIn get() = session.isSignedIn

    suspend fun register(name: String, email: String, phone: String, password: String, confirm: String): Result<User> =
        apiCall { api.register(RegisterRequest(name.trim(), email.trim(), phone.trim(), password, confirm, AppLocale.current())) }
            .also { if (it is Result.Ok) { session.saveTokens(it.value.tokens); session.saveUser(it.value.user) } }
            .map { it.user }

    suspend fun login(identifier: String, password: String): Result<User> =
        apiCall { api.login(LoginRequest(identifier.trim(), password)) }
            .also { if (it is Result.Ok) { session.saveTokens(it.value.tokens); session.saveUser(it.value.user) } }
            .map { it.user }

    /** Refreshes the cached profile from the server (e.g. after Telegram verification). */
    suspend fun refreshMe(): Result<User> = apiCall { api.me().user }.also { if (it is Result.Ok) session.saveUser(it.value) }

    suspend fun logout() {
        session.refreshToken?.let { rt -> apiCall { api.logout(RefreshRequest(rt)) } }
        session.clear()
        cache.clear()
    }

    /** Sends a 6-digit code to the account's e-mail. Ok(true) when the address is already verified. */
    suspend fun sendEmailCode(): Result<Boolean> = apiCall { api.sendEmailCode().alreadyVerified }

    suspend fun verifyEmail(code: String): Result<User> = when (val r = apiCall { api.verifyEmail(EmailCodeRequest(code.trim())) }) {
        is Result.Ok -> refreshMe()
        is Result.Err -> r
    }

    suspend fun forgot(identifier: String) = apiCall { api.forgotPassword(ForgotRequest(identifier.trim())) }
    suspend fun reset(identifier: String, code: String, pw: String) = apiCall { api.resetPassword(ResetRequest(identifier.trim(), code.trim(), pw)) }
    suspend fun changePassword(current: String, new: String) = apiCall { api.changePassword(ChangePasswordRequest(current, new)) }

    suspend fun updateProfile(locale: String? = null, prefs: Preferences? = null): Result<User> =
        apiCall { api.updateMe(UpdateMeRequest(locale = locale, preferences = prefs)).user }.also { if (it is Result.Ok) session.saveUser(it.value) }

    suspend fun deleteAccount(password: String): Result<Unit> =
        apiCall { api.deleteMe(DeleteAccountRequest(password)) }.map { }.also { if (it is Result.Ok) { session.clear(); cache.clear() } }
}

inline fun <T, R> Result<T>.map(f: (T) -> R): Result<R> = when (this) {
    is Result.Ok -> Result.Ok(f(value))
    is Result.Err -> this
}
