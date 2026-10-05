package app.medicinecabinet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi", application = TestCabinetApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSizeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `size controls update small phone layout and preserve touch area`() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("紧凑").performClick()
        compose.onNodeWithText("当前缩放 90%", substring = true).assertIsDisplayed()
        val chip = compose.onNodeWithText("紧凑").fetchSemanticsNode()
        Assert.assertTrue("缩小界面后触控区域仍应至少 48 个实际 dp。",
            chip.touchBoundsInRoot.height >= 95.99f && chip.touchBoundsInRoot.width >= 95.99f)
        capture("08-settings-compact-375")
        compose.onNodeWithText("宽松").performClick()
        compose.onNodeWithText("当前缩放 110%", substring = true).assertIsDisplayed()
        capture("09-settings-comfortable-375")
        compose.onNodeWithText("标准").performClick()
        compose.onNodeWithText("当前缩放 100%", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xhdpi", shadows = [ReducedMotionSettingsShadow::class])
    fun `landscape navigation and recording work with animations disabled`() {
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("手动录入").performScrollTo().assertIsDisplayed()
        capture("10-cabinet-landscape")
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("保存到药箱").assertIsDisplayed()
        capture("11-editor-landscape")
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/ui-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
