package dz.association.stats.admin

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dz.association.stats.data.CommuneCount
import dz.association.stats.data.GenderSplit
import dz.association.stats.data.KeyCount
import dz.association.stats.data.Meeting
import dz.association.stats.data.MeetingCounts
import dz.association.stats.data.Stats
import dz.association.stats.data.Totals
import dz.association.stats.data.WilayaCount
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

/** Renders the dashboard with illustrative sample numbers (not association data) to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ar-w411dp-h1600dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = 1_790_000_000_000L

    @Test fun dashboard() {
        val st = Stats(
            totals = Totals(registrations = 120, registered = 30, present = 70, late = 12, absent = 8, cancelled = 3, attended = 82),
            attendanceRate = 0.82, gender = GenderSplit(64, 52, 4),
            byWilaya = listOf(WilayaCount(31, "وهران", "Oran", 88, 61), WilayaCount(46, "عين تيموشنت", "Aïn Témouchent", 18, 12), WilayaCount(29, "معسكر", "Mascara", 14, 9)),
            byCommune = listOf(CommuneCount(1, "السانية", "Es Senia", count = 40, attended = 30), CommuneCount(2, "بئر الجير", "Bir El Djir", count = 28, attended = 20)),
            ageGroups = listOf(KeyCount("0-17", 6), KeyCount("18-25", 30), KeyCount("26-35", 41), KeyCount("36-45", 22), KeyCount("46-60", 15), KeyCount("61+", 6)),
            maritalStatus = listOf(KeyCount("single", 50), KeyCount("married", 60), KeyCount("divorced", 6), KeyCount("widowed", 4)),
        )
        val m = Meeting("m1", "مثال: الاجتماع العام", null, "مقر الجمعية", now, now - 1, now + 1, now, true, true, counts = MeetingCounts(30, 70, 12, 8, 3, 120))
        compose.setContent {
            StatsTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LazyColumn(Modifier.fillMaxSize().background(StatsColors.Background), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { MeetingRow(m) {} }
                        statsItems(st)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        val f = File("build/screenshots").apply { mkdirs() }.resolve("admin_dashboard.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 0)
    }
}
