package app.medicinecabinet.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import org.junit.Rule
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi", application = TestCabinetApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `manual recording decrement and shopping flow works`() {
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("药品名称").performTextInput("演示药品")
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("2030-12-31")
        compose.onNodeWithText("保存到药箱").performClick()
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("总览").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw error
        }
        compose.onNodeWithText("药箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("演示药品").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("演示药品").assertIsDisplayed()
        compose.onNodeWithText("查看 1 个批次").performClick()
        compose.onNodeWithContentDescription("减少本批次库存").performScrollTo().performClick()
        compose.onNodeWithText("补货").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("库存不足").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("库存不足").assertIsDisplayed()
        compose.onNodeWithText("记入补货").assertIsDisplayed()
    }
}
