package dz.association.admin.data

import android.os.Build
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.auth.TokenStore
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
    private val store: TokenStore,
) {
    val user = session.user
    private val device get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)

    /** A pending approval request survives app restarts (kept encrypted with the session store). */
    val pendingRequestId: String? get() = store.read(K_REQ_ID)

    /** Sends an activation request; the owner accepts or rejects it from the Telegram bot. */
    suspend fun requestActivation(name: String): Result<Unit> {
        val r = apiCall { adminApi.requestActivation(DeviceRequestBody(name.trim(), device)) }
        if (r is Result.Ok) store.write(mapOf(K_REQ_ID to r.value.requestId, K_REQ_SECRET to r.value.secret))
        return when (r) { is Result.Ok -> Result.Ok(Unit); is Result.Err -> r }
    }

    /** Returns "pending", "approved" (device now signed in), "rejected", "expired" or "claimed". */
    suspend fun checkActivation(): Result<String> {
        val id = store.read(K_REQ_ID) ?: return Result.Ok("none")
        val secret = store.read(K_REQ_SECRET) ?: return Result.Ok("none")
        val r = apiCall { adminApi.pollActivation(id, secret) }
        if (r is Result.Err) {
            if ((r.error as? AppError.Api)?.status == 404) cancelRequest()
            return r
        }
        val st = (r as Result.Ok).value
        if (st.status == "approved" && st.user != null && st.tokens != null) {
            if (!AdminRoles.canUseApp(st.user.role)) return Result.Err(AppError.Api(403, "insufficient_role"))
            store.write(mapOf(K_REQ_ID to null, K_REQ_SECRET to null))
            session.saveTokens(st.tokens)
            session.saveUser(st.user)
        } else if (st.status != "pending") cancelRequest()
        return Result.Ok(st.status)
    }

    fun cancelRequest() = store.write(mapOf(K_REQ_ID to null, K_REQ_SECRET to null))

    suspend fun activate(code: String): Result<User> {
        val digits = code.filter(Char::isDigit)
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

    private companion object {
        const val K_REQ_ID = "activation_request_id"
        const val K_REQ_SECRET = "activation_request_secret"
    }
}
