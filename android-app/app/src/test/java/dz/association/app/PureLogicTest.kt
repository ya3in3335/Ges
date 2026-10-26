package dz.association.app

import dz.association.app.core.network.VerificationStart
import dz.association.app.ui.account.AuthValidation
import dz.association.app.ui.account.VerifyUi
import dz.association.app.ui.account.mapVerificationStatus
import dz.association.app.ui.gallery.isDirectVideo
import dz.association.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class) // android.net.Uri is used by Routes
class PureLogicTest {
    @Test fun deepLinksMapToRoutes() {
        assertEquals("news/abc-123", Routes.fromDeepLink("app://news/abc-123"))
        assertEquals("poll/p1", Routes.fromDeepLink("app://polls/p1"))
        assertEquals("activity/a1", Routes.fromDeepLink("app://activities/a1"))
        assertEquals(Routes.VOLUNTEER, Routes.fromDeepLink("app://volunteer"))
        assertEquals(Routes.NEWS, Routes.fromDeepLink("app://news"))
    }

    @Test fun maliciousOrForeignDeepLinksAreIgnored() {
        assertNull(Routes.fromDeepLink("https://evil.example/news/1"))
        assertNull(Routes.fromDeepLink("app://settings/delete"))
        assertNull(Routes.fromDeepLink(null))
        // invalid ids fall back to the list instead of being injected into a route
        assertEquals(Routes.NEWS, Routes.fromDeepLink("app://news/..%2F..%2Fsettings"))
    }

    @Test fun phoneNormalisationMatchesBackendRules() {
        assertEquals("+213550123456", AuthValidation.normalizePhone("0550 12 34 56"))
        assertEquals("+213661234567", AuthValidation.normalizePhone("+213 661 23 45 67"))
        assertEquals("+213770123456", AuthValidation.normalizePhone("00213770123456"))
        assertNull(AuthValidation.normalizePhone("041234567")) // landline not accepted for accounts
        assertNull(AuthValidation.normalizePhone("+33612345678"))
        assertNull(AuthValidation.normalizePhone("055012"))
    }

    @Test fun passwordAndEmailValidation() {
        assertTrue(AuthValidation.passwordValid("Secret123x"))
        assertFalse(AuthValidation.passwordValid("short1"))
        assertFalse(AuthValidation.passwordValid("onlyletters"))
        assertTrue(AuthValidation.emailValid("a@b.dz"))
        assertFalse(AuthValidation.emailValid("a@b"))
    }

    private val waiting = VerifyUi.Waiting(VerificationStart("v", "https://t.me/x?start=t", "tg://resolve?domain=x&start=t", 0))

    @Test fun verificationStatusMapping() {
        assertEquals(VerifyUi.Verified, mapVerificationStatus(waiting, "verified", null, 3))
        assertEquals(VerifyUi.Failed("expired"), mapVerificationStatus(waiting, "expired", null, 3))
        assertEquals(VerifyUi.Failed("phone_mismatch"), mapVerificationStatus(waiting, "failed", "phone_mismatch", 0))
        val w = mapVerificationStatus(waiting, "awaiting_contact", "phone_mismatch", 2) as VerifyUi.Waiting
        assertEquals("phone_mismatch", w.failureReason)
        assertEquals(2, w.attemptsLeft)
    }

    @Test fun onlyDirectVideoFilesUseExoPlayer() {
        assertTrue(isDirectVideo("https://api.example.org/media/x.mp4"))
        assertFalse(isDirectVideo("https://www.youtube.com/watch?v=abc"))
    }
}
