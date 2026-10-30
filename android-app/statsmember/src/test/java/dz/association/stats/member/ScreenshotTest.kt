package dz.association.stats.member

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dz.association.stats.data.GeoData
import dz.association.stats.data.Meeting
import dz.association.stats.data.MeetingRef
import dz.association.stats.data.Registration
import dz.association.stats.ui.CensusForm
import dz.association.stats.ui.CensusFormState
import dz.association.stats.ui.StatsColors
import dz.association.stats.ui.StatsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real composables to PNG (build/screenshots) with illustrative sample data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ar-w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = 1_790_000_000_000L
    private val geo = GeoData.parse(File("../stats/src/main/assets/dz_geo.json").readText())
    private val meeting = Meeting("m1", "مثال: الاجتماع العام للأعضاء", null, "مقر الجمعية", now, now - 3_600_000, now + 10_800_000, now + 900_000, true, true)
    private val reg = Registration(
        "11111111-2222-3333-4444-555555555555", "m1", 7, "أحمد", "بن علي", "male", 31, "وهران", "Oran", 1135, "السانية", "Es Senia", "1995-04-12", "married", "registered",
        meeting = MeetingRef(meeting.title, now, meeting.location, "open"), qr = "AST1.11111111-2222-3333-4444-555555555555.abcdefghijklmnopqrstuvwxyz0",
    )

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            StatsTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(Modifier.fillMaxSize().background(StatsColors.Background).verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        val f = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 0)
    }

    @Test fun cardWaiting() = shot("member_card") { CardContent(reg) }
    @Test fun cardPresent() = shot("member_card_present") { CardContent(reg.copy(status = "present", checkedInAt = now + 60_000)) }

    @Test fun censusForm() = shot("member_census_form") {
        val s = CensusFormState().apply { firstName = "أحمد"; lastName = "بن علي"; wilaya = 31; commune = 1135; day = 12; month = 4; year = 1995; marital = "married"; meetingId = "m1" }
        CensusForm(s, geo, listOf(meeting))
    }

    @Test fun censusFormErrors() = shot("member_census_errors") {
        val s = CensusFormState().apply { showErrors = true; firstName = "أ" }
        CensusForm(s, geo, listOf(meeting))
    }
}
