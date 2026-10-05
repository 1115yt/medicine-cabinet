package app.medicinecabinet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import app.medicinecabinet.CabinetApplication
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.Medicine
import app.medicinecabinet.domain.StockBatch
import app.medicinecabinet.ui.theme.CabinetTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
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
class UiPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `render main screens light dark and large text`() {
        val repository = (compose.activity.application as CabinetApplication).repository
        val today = LocalDate.now()
        runBlocking {
            listOf(
                Triple("药品示例 A", 2, 12L), Triple("药品示例 B", 0, 180L),
                Triple("药品示例 C", 1, -1L), Triple("药品示例 D", 3, 365L),
            ).forEachIndexed { index, (name, quantity, days) ->
                val id = "preview-$index"
                repository.saveRecord(Medicine(id, name, "包装规格示例", packageUnit = "盒"),
                    StockBatch("batch-$index", id, quantity, today.plusDays(days).toString(), location = "客厅药箱"))
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("4").fetchSemanticsNodes().isNotEmpty() }
        capture("01-overview-light")
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("剩余 12 天").assertIsDisplayed()
        compose.onNodeWithText("我的药箱").assertIsDisplayed()
        capture("02-cabinet-light")
        compose.onNodeWithText("补货").performClick(); capture("03-shopping-light")
        compose.onNodeWithText("设置").performClick(); capture("04-settings-light")
        compose.onNodeWithContentDescription("显示剩余天数").performClick()
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("即将到期").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("显示剩余天数").performClick()
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme(darkTheme = true) { CabinetApp(viewModel) } } }
        compose.waitForIdle()
        compose.onNodeWithText("总览").performClick(); capture("05-overview-dark")
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    CabinetTheme(darkTheme = false) { CabinetApp(viewModel) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("总览").performClick(); capture("06-overview-large-text")
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("保存到药箱").assertIsDisplayed()
        capture("07-editor-large-text")
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            check(view.width > 0 && view.height > 0) { "预览窗口尚未布局。" }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val directory = File("build/ui-preview").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
