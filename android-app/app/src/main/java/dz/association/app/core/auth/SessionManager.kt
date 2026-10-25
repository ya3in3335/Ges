package dz.association.app.core.auth

import dz.association.app.core.network.Tokens
import dz.association.app.core.network.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the signed-in session. Tokens live encrypted in [TokenStore];
 * the cached user is used for offline display only — the server remains authoritative.
 */
@Singleton
class SessionManager @Inject constructor(private val store: TokenStore, private val json: Json) {
    private val _user = MutableStateFlow(store.read(K_USER)?.let { runCatching { json.decodeFromString(User.serializer(), it) }.getOrNull() })
    val user: StateFlow<User?> = _user.asStateFlow()

    val isSignedIn: Boolean get() = refreshToken != null

    @Volatile var accessToken: String? = store.read(K_ACCESS); private set
    @Volatile var refreshToken: String? = store.read(K_REFRESH); private set

    @Synchronized
    fun saveTokens(t: Tokens) {
        accessToken = t.accessToken
        refreshToken = t.refreshToken
        store.write(mapOf(K_ACCESS to t.accessToken, K_REFRESH to t.refreshToken))
    }

    fun saveUser(u: User) {
        _user.value = u
        store.write(mapOf(K_USER to json.encodeToString(User.serializer(), u)))
    }

    @Synchronized
    fun clear() {
        accessToken = null
        refreshToken = null
        _user.value = null
        store.clear()
    }

    private companion object {
        const val K_ACCESS = "access"
        const val K_REFRESH = "refresh"
        const val K_USER = "user"
    }
}
