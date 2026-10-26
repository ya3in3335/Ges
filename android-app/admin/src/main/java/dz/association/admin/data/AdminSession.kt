package dz.association.admin.data

import android.os.Build
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.AppError
import dz.association.app.core.network.RefreshRequest
import dz.association.app.core.network.Result
import dz.association.app.core.network.User
import dz.association.app.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/** Roles allowed to use the supervisors' app (the server applies the same rule). */
object AdminRoles {
    private val RANK = mapOf("USER" to 0, "VOLUNTEER" to 1, "EDITOR" to 2, "ADMIN" to 3, "SUPER_ADMIN" to 4)
    fun atLeast(role: String?, min: String) = (RANK[role] ?: -1) >= (RANK[min] ?: Int.MAX_VALUE)
    fun canUseApp(role: String?) = atLeast(role, "EDITOR")
}

/**
 * Device session for the supervisors' app. There is no username/password: a supervisor enters a
 * single-use activation code once, and the device keeps a long-lived session that an
 * administrator can revoke at any time.
 */
@Singleton
class AdminSession @Inject constructor(
    private val api: ApiService,
    private val adminApi: AdminApi,
    private val session: SessionManager,
) {
    val user = session.user

    suspend fun activate(code: String): Result<User> {
        val digits = code.filter(Char::isDigit)
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)
        val r = apiCall { adminApi.activate(ActivateRequest(digits, device)) }
        if (r is Result.Ok) {
            if (!AdminRoles.canUseApp(r.value.user.role)) return Result.Err(AppError.Api(403, "insufficient_role"))
            session.saveTokens(r.value.tokens)
            session.saveUser(r.value.user)
            return Result.Ok(r.value.user)
        }
        return r as Result.Err
    }

    /** Re-reads the role from the server; forgets the device if it was revoked or demoted. */
    suspend fun refresh(): Result<User> {
        val r = apiCall { api.me().user }
        if (r is Result.Ok) {
            if (!AdminRoles.canUseApp(r.value.role)) { session.clear(); return Result.Err(AppError.Api(403, "insufficient_role")) }
            session.saveUser(r.value)
        } else if (r is Result.Err && r.error == AppError.Unauthorized) session.clear()
        return r
    }

    /** Disconnects this device (its session is revoked on the server). */
    suspend fun disconnect() {
        session.refreshToken?.let { rt -> apiCall { api.logout(RefreshRequest(rt)) } }
        session.clear()
    }
}
