package dz.association.admin

import androidx.test.core.app.ApplicationProvider
import dz.association.admin.data.AdminRoles
import dz.association.admin.data.AdminSession
import dz.association.app.core.auth.Crypto
import dz.association.app.core.auth.SessionManager
import dz.association.app.core.auth.TokenStore
import dz.association.app.core.network.ApiService
import dz.association.app.core.network.AppError
import dz.association.app.core.network.Result
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
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
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

private class PlainCrypto : Crypto {
    override fun encrypt(plain: String) = plain.reversed()
    override fun decrypt(cipherText: String) = cipherText.reversed()
}

@RunWith(RobolectricTestRunner::class)
class AdminTest {
    private lateinit var server: MockWebServer
    private lateinit var session: SessionManager
    private lateinit var admin: AdminSession
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = SessionManager(TokenStore(ApplicationProvider.getApplicationContext(), PlainCrypto()), json).also { it.clear() }
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        admin = AdminSession(retrofit.create(ApiService::class.java), retrofit.create(dz.association.admin.data.AdminApi::class.java), session)
    }

    @After fun tearDown() = server.shutdown()

    private fun loginBody(role: String) =
        """{"user":{"id":"u1","fullName":"Test User","email":"a@b.dz","phone":"+213550000001","role":"$role"},"tokens":{"accessToken":"A","accessTokenExpiresAt":1,"refreshToken":"R","refreshTokenExpiresAt":2}}"""

    @Test fun roleRanking() {
        assertTrue(AdminRoles.canUseApp("EDITOR"))
        assertTrue(AdminRoles.canUseApp("SUPER_ADMIN"))
        assertFalse(AdminRoles.canUseApp("VOLUNTEER"))
        assertFalse(AdminRoles.canUseApp("USER"))
        assertFalse(AdminRoles.canUseApp(null))
        assertTrue(AdminRoles.atLeast("ADMIN", "EDITOR"))
        assertFalse(AdminRoles.atLeast("EDITOR", "ADMIN"))
        assertFalse(AdminRoles.atLeast("BOGUS", "USER"))
    }

    @Test fun activatesWithACodeAndNoPassword() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(loginBody("EDITOR")))
        val r = admin.activate("1234 5678")
        assertTrue(r is Result.Ok)
        val req = server.takeRequest()
        assertEquals("/api/auth/activate", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"code\":\"12345678\""))
        assertFalse(body.contains("password"))
        assertEquals("R", session.refreshToken)
    }

    @Test fun refusesMemberAccountsAndStoresNothing() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(loginBody("USER")))
        assertEquals(Result.Err(AppError.Api(403, "insufficient_role")), admin.activate("12345678"))
        assertNull(session.refreshToken)
        assertNull(session.user.value)
    }

    @Test fun invalidCodeIsReported() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_code"}"""))
        assertEquals(Result.Err(AppError.Api(400, "invalid_code")), admin.activate("00000000"))
        assertNull(session.refreshToken)
    }

    @Test fun revokedDeviceIsForgotten() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(loginBody("EDITOR")))
        admin.activate("12345678")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        admin.refresh()
        assertNull(session.refreshToken)
    }

    @Test fun demotedAccountIsSignedOutOnRefresh() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(loginBody("EDITOR")))
        admin.activate("12345678")
        server.enqueue(MockResponse().setBody("""{"user":{"id":"u1","fullName":"Test User","email":"a@b.dz","phone":"+213550000001","role":"USER"}}"""))
        admin.refresh()
        assertNull(session.refreshToken)
    }

    @Test fun everyAdminStringIsTranslated() {
        val keys = { p: String -> Regex("<string name=\"([a-zA-Z0-9_]+)\"").findAll(File(p).readText()).map { it.groupValues[1] }.toSet() }
        val ar = keys("src/main/res/values/strings.xml")
        val fr = keys("src/main/res/values-fr/strings.xml")
        assertEquals(emptySet<String>(), ar - fr)
        assertEquals(emptySet<String>(), fr - ar)
    }

    @Test fun adminCodeIsNotInTheMembersApp() {
        // The members' app must not contain any administration endpoints.
        val src = File("../app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(src.contains("api/admin/"))
        assertFalse(src.contains("AdminApi"))
    }
}
