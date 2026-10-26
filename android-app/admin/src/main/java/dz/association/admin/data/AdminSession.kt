package dz.association.admin.data

import dz.association.app.core.auth.SessionManager
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.AppError
import dz.association.app.core.network.LoginRequest
import dz.association.app.core.network.RefreshRequest
import dz.association.app.core.network.Result
import dz.association.app.core.network.User
import dz.association.app.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/** Roles allowed to use the supervisors' app (the server applies the same rule at login). */
object AdminRoles {
    private val RANK = mapOf("USER" to 0, "VOLUNTEER" to 1, "EDITOR" to 2, "ADMIN" to 3, "SUPER_ADMIN" to 4)
    fun atLeast(role: String?, min: String) = (RANK[role] ?: -1) >= (RANK[min] ?: Int.MAX_VALUE)
    fun canUseApp(role: String?) = atLeast(role, "EDITOR")
}

@Singleton
class AdminSession @Inject constructor(private val api: ApiService, private val session: SessionManager) {
    val user = session.user

    /** Signs in with the "admin" client type: the server refuses members and issues short admin sessions. */
    suspend fun login(identifier: String, password: String): Result<User> {
        val r = apiCall { api.login(LoginRequest(identifier.trim(), password, client = "admin")) }
        if (r is Result.Ok) {
            if (!AdminRoles.canUseApp(r.value.user.role)) return Result.Err(AppError.Api(403, "insufficient_role"))
            session.saveTokens(r.value.tokens)
            session.saveUser(r.value.user)
            return Result.Ok(r.value.user)
        }
        return r as Result.Err
    }

    /** Re-reads the role from the server; signs out if the account lost its supervisor role. */
    suspend fun refresh(): Result<User> {
        val r = apiCall { api.me().user }
        if (r is Result.Ok) {
            if (!AdminRoles.canUseApp(r.value.role)) { session.clear(); return Result.Err(AppError.Api(403, "insufficient_role")) }
            session.saveUser(r.value)
        } else if (r is Result.Err && r.error == AppError.Unauthorized) session.clear()
        return r
    }

    suspend fun logout() {
        session.refreshToken?.let { rt -> apiCall { api.logout(RefreshRequest(rt)) } }
        session.clear()
    }
}
