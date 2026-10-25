package dz.association.app

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dz.association.app.ui.navigation.Routes
import dz.association.app.ui.theme.AssociationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class ResourcesTest {
    @get:Rule val compose = createComposeRule()

    private fun keys(path: String) = Regex("<string name=\"([a-zA-Z0-9_]+)\"").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    @Test fun everyStringIsTranslatedToFrench() {
        val ar = keys("src/main/res/values/strings.xml")
        val fr = keys("src/main/res/values-fr/strings.xml")
        assertTrue(ar.size > 100)
        assertEquals("missing in French", emptySet<String>(), ar - fr)
        assertEquals("missing in Arabic", emptySet<String>(), fr - ar)
    }

    @Test fun arabicIsTheDefaultLocaleAndLaysOutRightToLeft() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Default (unqualified) resources are Arabic.
        assertEquals("الرئيسية", ctx.getString(R.string.nav_home))
        val conf = Configuration(ctx.resources.configuration).apply { setLocale(Locale("ar", "DZ")) }
        assertEquals(View.LAYOUT_DIRECTION_RTL, conf.layoutDirection)
        val fr = ctx.createConfigurationContext(Configuration(conf).apply { setLocale(Locale.FRANCE) })
        assertEquals("Accueil", fr.getString(R.string.nav_home))
        assertEquals(View.LAYOUT_DIRECTION_LTR, fr.resources.configuration.layoutDirection)
    }

    @Test fun bottomNavigationHasTheFiveRequiredTabsInOrder() {
        assertEquals(listOf(Routes.HOME, Routes.ACTIVITIES, Routes.POLLS, Routes.VOLUNTEER, Routes.ACCOUNT), Routes.TABS)
    }

    @Test fun rtlLayoutPlacesFirstItemOnTheRight() {
        compose.setContent {
            AssociationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(Modifier.size(300.dp, 50.dp)) {
                        Box(Modifier.size(50.dp).testTag("first"))
                        Box(Modifier.size(50.dp).testTag("second"))
                    }
                    Text("الرئيسية")
                }
            }
        }
        val first = compose.onNodeWithTag("first").getBoundsInRoot()
        val second = compose.onNodeWithTag("second").getBoundsInRoot()
        assertTrue("first item must be right of second in RTL", first.left > second.left)
        compose.onNodeWithText("الرئيسية").assertIsDisplayed()
    }

    @Test fun introAssetIsTheClientAnimationAndIsSelfContained() {
        val html = File("src/main/assets/intro/intro.html").readText()
        assertTrue(html.contains("function render(ctx,W,H,t)"))
        assertTrue(html.contains("const TL={"))
        assertTrue(html.contains("Content-Security-Policy"))
        // No remote resources: only data: images and the completion sentinel.
        val urls = Regex("https?://[^\"'\\s)]+").findAll(html).map { it.value }.toSet()
        assertTrue("unexpected URLs: $urls", urls.all { it.startsWith("https://appassets.androidplatform.net/__intro_done") })
    }
}

