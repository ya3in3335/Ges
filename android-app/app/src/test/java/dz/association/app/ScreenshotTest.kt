package dz.association.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import dz.association.app.core.network.HomeActivity
import dz.association.app.core.network.HomeNews
import dz.association.app.core.network.HomePoll
import dz.association.app.core.network.HomeResponse
import dz.association.app.ui.home.HomeContent
import dz.association.app.ui.theme.AssociationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders real app composables to PNG (build/screenshots) for the download website.
 * Content is illustrative sample data, not association information.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = 86_400_000L
    private val now = 1_790_000_000_000L
    private val sample = HomeResponse(
        announcements = listOf(HomeNews("a1", "مثال: إعلان عن اجتماع الأعضاء", excerptAr = "نص توضيحي يظهر هنا عند نشر الإدارة لإعلان جديد.", category = "announcement", publishedAt = now)),
        recentNews = listOf(HomeNews("n1", "مثال: خبر من نشاطات الجمعية", excerptAr = "تُدار جميع الأخبار من لوحة الإدارة.", publishedAt = now - day)),
        activities = listOf(
            HomeActivity("x1", "مثال: حملة تضامنية", location = "وهران", startsAt = now + 3 * day, endsAt = now + 3 * day + 7200_000),
            HomeActivity("x2", "مثال: يوم تطوعي", location = "وهران", startsAt = now + 9 * day, endsAt = now + 9 * day + 7200_000),
        ),
        activePolls = listOf(HomePoll("p1", "مثال: اختيار نشاط الشهر", endsAt = now + 5 * day)),
    )

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun homeLight() {
        compose.setContent {
            AssociationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface { Column { HomeContent(sample, fromCache = false, user = null, nav = {}) } }
                }
            }
        }
        save("home_light")
    }

    @Test fun homeDark() {
        compose.setContent {
            AssociationTheme(dz.association.app.core.prefs.ThemeMode.DARK) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface { Column { HomeContent(sample, fromCache = true, user = null, nav = {}) } }
                }
            }
        }
        save("home_dark")
    }
}
