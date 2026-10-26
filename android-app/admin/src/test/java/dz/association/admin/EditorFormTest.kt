package dz.association.admin

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dz.association.admin.ui.DateTimeField
import dz.association.app.ui.theme.AssociationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-xxhdpi")
class EditorFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pickingDateAndTimeSetsTheValue() {
        var value by mutableLongStateOf(0L)
        compose.setContent { AssociationTheme { DateTimeField("البداية", value) { value = it } } }
        compose.onNodeWithText("البداية", substring = true).performClick()
        compose.onAllNodesWithText("OK")[0].performClick()   // date
        compose.waitForIdle()
        compose.onAllNodesWithText("OK")[0].performClick()   // time
        compose.waitForIdle()
        assertTrue("value=$value", value > 0)
    }
}
