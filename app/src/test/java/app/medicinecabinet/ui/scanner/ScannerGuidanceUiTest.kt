package app.medicinecabinet.ui.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.CodeFormat
import app.medicinecabinet.ui.ReducedMotionSettingsShadow
import app.medicinecabinet.ui.assertUiUsesCurrentApplication
import app.medicinecabinet.ui.releaseUiTestResources
import app.medicinecabinet.ui.theme.CabinetTheme
import java.io.File
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 只验证本地错误说明与会话控制；不代替真实摄像头识别验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi", application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScannerGuidanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)
    private val error = "该条码为空或过长，请换用药盒商品条码继续扫描。"

    private fun render(error: String?, fontScale: Float = 1f, dark: Boolean = false, onManual: () -> Unit = {}) {
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CabinetTheme(darkTheme = dark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Surface(color = MaterialTheme.colorScheme.surface) { ScannerGuidance(error, onManual) }
                        }
                    }
                }
            }
        } }
    }

    @Test fun `rejected result is visible on scanner and later valid result is accepted`() {
        val visibleError = mutableStateOf<String?>(null)
        var acceptedCount = 0
        val session = ScannerSession({ acceptedCount++ }, { visibleError.value = it })
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(color = MaterialTheme.colorScheme.surface) { ScannerGuidance(visibleError.value, {}) }
                }
            }
        } } }
        compose.runOnUiThread { session.submit("q".repeat(513), CodeFormat.OTHER) }
        compose.onNodeWithText(error).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        capture("48-scanner-rejected")
        compose.runOnUiThread {
            assertTrue(session.submit("4006381333931", CodeFormat.RETAIL))
            assertFalse(session.submit("4006381333931", CodeFormat.RETAIL))
            session.reportError("迟到错误")
        }
        assertEquals(1, acceptedCount)
        compose.onNodeWithText("迟到错误").assertDoesNotExist()
    }

    @Test fun `large text error preserves reachable manual entry`() {
        var manualCount = 0
        render(error, fontScale = 1.6f, onManual = { manualCount++ })
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        capture("49-scanner-rejected-large")
        compose.onNodeWithText("改用手动录入").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, manualCount)
    }

    @Test fun `dark large text error uses readable existing theme and recovery action`() {
        render(error, fontScale = 1.6f, dark = true)
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        capture("50-scanner-rejected-dark-large")
        compose.onNodeWithText("改用手动录入").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w812dp-h375dp-land-xhdpi")
    fun `landscape large text guidance scrolls to manual entry`() {
        render(error, fontScale = 1.6f)
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        capture("51-scanner-rejected-landscape")
        compose.onNodeWithText("改用手动录入").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
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
