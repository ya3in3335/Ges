package dz.association.admin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dz.association.admin.data.AdminApi
import dz.association.admin.ui.screens.LecturesScreen
import dz.association.admin.ui.screens.LecturesVm
import dz.association.app.ui.theme.AssociationTheme
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-xxhdpi")
class LectureEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun filledFormIsSent() {
        val posted = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "POST") { posted += request.body.readUtf8(); MockResponse().setResponseCode(201).setBody("""{"id":"x"}""") }
                    else MockResponse().setBody("""{"items":[]}""")
            }
            start()
        }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AdminApi::class.java)
        val vm = LecturesVm(api)
        compose.setContent { AssociationTheme { LecturesScreen({}, "SUPER_ADMIN", vm) } }
        compose.onNodeWithText("إضافة", useUnmergedTree = true).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("محاضرة تجريبية")
        compose.onNode(hasText("البداية", substring = true)).performScrollTo().performClick()
        compose.onAllNodesWithText("OK")[0].performClick(); compose.waitForIdle()
        compose.onAllNodesWithText("OK")[0].performClick(); compose.waitForIdle()
        compose.onNode(hasText("حفظ")).performScrollTo().performClick()
        compose.waitUntil(5000) { posted.isNotEmpty() || compose.onAllNodesWithText("يرجى", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val err = compose.onAllNodesWithText("يرجى", substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue("posted=$posted error=$err", posted.isNotEmpty())
        server.shutdown()
    }

    @Test fun shortTitleIsNamedInsteadOfAGenericError() {
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"items":[]}""") }; start() }
        val json = Json { ignoreUnknownKeys = true }
        val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AdminApi::class.java)
        compose.setContent { AssociationTheme { LecturesScreen({}, "SUPER_ADMIN", LecturesVm(api)) } }
        compose.onNodeWithText("إضافة", useUnmergedTree = true).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("تس")
        compose.onNode(hasText("حفظ")).performScrollTo().performClick()
        compose.onNode(hasText("3 أحرف", substring = true)).performScrollTo()
        compose.onNode(hasText("تاريخ ووقت البداية", substring = true)).performScrollTo()
        server.shutdown()
    }
}
