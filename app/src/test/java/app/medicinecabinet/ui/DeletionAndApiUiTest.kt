package app.medicinecabinet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import app.medicinecabinet.CabinetApplication
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.data.ApiUsage
import app.medicinecabinet.data.ApiUsageStore
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.components.*
import app.medicinecabinet.ui.forms.EditorRequest
import app.medicinecabinet.ui.forms.RecordEditor
import app.medicinecabinet.ui.theme.CabinetTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi", application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeletionAndApiUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)
    private val medicine = Medicine("delete-demo", "药品示例")
    private val today = LocalDate.now()

    @Test fun `Ali gateway cause and code fit the record review screen`() {
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            RecordEditor(EditorRequest(barcode = "04006381333931", fromScan = true, apiAttempts = listOf(
                ApiAttempt(BarcodeService.MXNZP, ApiOutcome.NOT_FOUND, 200, "10036"),
                ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.RATE_LIMIT, 403, "B403MQ", AliGatewayReason.QUOTA_EXHAUSTED))),
                AppSnapshot(), false, {}) { _, _, _ -> }
        } } }
        compose.onNodeWithText("阿里云：已订购的 API 调用次数耗尽", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("错误码 B403MQ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("本次发起 2 次请求", substring = true).performScrollTo().assertIsDisplayed()
        capture("30-ali-gateway-result")
    }

    @Test fun `Ali HTTP 450 business cause is visible on the large text review screen`() {
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                CabinetTheme(interfaceSize = InterfaceSize.COMFORTABLE) {
                    RecordEditor(EditorRequest(barcode = "04006381333931", fromScan = true,
                        apiAttempts = listOf(ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.NOT_FOUND, 450, "-1"))),
                        AppSnapshot(), false, {}) { _, _, _ -> }
                }
            }
        } }
        compose.onNodeWithText("阿里云：未查到此条码", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HTTP 450 · 错误码 -1", substring = true).assertIsDisplayed()
        capture("31-ali-450-large-text")
    }

    private fun openCabinet(withTwoBatches: Boolean = false): CabinetViewModel {
        val application = compose.activity.application as CabinetApplication
        runBlocking {
            application.repository.saveRecord(medicine, StockBatch("expired", medicine.id, 1, today.minusDays(1).toString()))
            if (withTwoBatches) application.repository.saveRecord(medicine,
                StockBatch("valid", medicine.id, 2, today.plusDays(180).toString()))
        }
        compose.onNodeWithText("药箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(medicine.name).fetchSemanticsNodes().isNotEmpty() }
        return ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
    }

    @Test fun `medicine delete requires confirmation cancel preserves records and confirm removes associations`() {
        val viewModel = openCabinet()
        compose.onNodeWithContentDescription("更多药品示例操作").performClick()
        compose.onNodeWithText("删除药品").performClick()
        compose.onNodeWithText("删除药品示例？").assertIsDisplayed()
        compose.onNodeWithText("关联的 1 个批次", substring = true).assertIsDisplayed()
        capture("22-delete-medicine-confirmation", dialog = true)
        Assert.assertEquals(1, viewModel.snapshot.value.medicines.size)
        compose.onNodeWithText("取消").performClick()
        Assert.assertEquals(1, viewModel.snapshot.value.batches.size)
        compose.onNodeWithContentDescription("更多药品示例操作").performClick()
        compose.onNodeWithText("删除药品").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("药箱还是空的").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertTrue(viewModel.snapshot.value.medicines.isEmpty())
        Assert.assertTrue(viewModel.snapshot.value.batches.isEmpty())
        Assert.assertTrue(viewModel.snapshot.value.shoppingItems.isEmpty())
    }

    @Test fun `deleting a batch leaves medicine and other batch usable`() {
        val viewModel = openCabinet(withTwoBatches = true)
        compose.onNodeWithText("查看 2 个批次").performClick()
        val description = "更多批次${today.minusDays(1)}操作"
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.onNodeWithText("删除批次").performClick()
        compose.onNodeWithText("仅删除此批次", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        Assert.assertEquals(2, viewModel.snapshot.value.batches.size)
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText("删除批次").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("有效期至 ${today.minusDays(1)}").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        Assert.assertEquals(listOf(medicine), viewModel.snapshot.value.medicines)
        Assert.assertEquals("valid", viewModel.snapshot.value.batches.single().id)
        Assert.assertTrue(viewModel.snapshot.value.shoppingItems.isEmpty())
    }

    @Test fun `connection test explains one request validates input and never runs without confirmation`() {
        val requests = mutableListOf<String>()
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            Box(Modifier.fillMaxSize()) {
                ApiDiagnostics(BarcodeService.ALIYUN,
                    ApiUsage(3, 1, connectionTest = ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.AUTH_ERROR, 401)),
                    configured = true, busy = false, checking = false, onTest = { requests.add(it) })
            }
        } } }
        compose.onNodeWithText("检测阿里云连接").performClick()
        compose.onNodeWithText("发送 1 次真实查询", substring = true).assertIsDisplayed()
        compose.onNodeWithText("测试条码：6902538005141").assertIsDisplayed()
        Assert.assertTrue(requests.isEmpty())
        capture("23-api-connection-confirmation", dialog = true)
        compose.onNodeWithText("取消").performClick()
        Assert.assertTrue(requests.isEmpty())
        compose.onNodeWithText("检测阿里云连接").performClick()
        compose.onNodeWithText("确认发送 1 次查询").performClick()
        Assert.assertEquals(listOf("6902538005141"), requests)
    }

    @Test fun `scan displays each real API result and no catalog metadata at large text`() {
        val attempts = listOf(ApiAttempt(BarcodeService.MXNZP, ApiOutcome.NOT_FOUND, 200, "10036"),
            ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.FOUND, 200))
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                CabinetTheme(interfaceSize = InterfaceSize.COMFORTABLE) {
                    RecordEditor(EditorRequest(fromScan = true, suggestedName = "联网预填示例", apiAttempts = attempts),
                        AppSnapshot(), false, {}) { _, _, _ -> }
                }
            }
        } }
        compose.onNodeWithText("本次 API 查询").assertIsDisplayed()
        compose.onNodeWithText("MXNZP：", substring = true).assertIsDisplayed()
        compose.onNodeWithText("阿里云：查询成功", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("错误码 10036", substring = true).assertIsDisplayed()
        compose.onNodeWithText("本次发起 2 次请求", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MXNZP +1 次 · 阿里云 +1 次").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("资料来源", substring = true).assertDoesNotExist()
        compose.onNodeWithText("2023-09", substring = true).assertDoesNotExist()
        capture("24-api-result-large-text")
        compose.onNodeWithText("联网预填示例").performScrollTo().assertIsDisplayed()
    }

    @Test fun `settings bind unequal persisted API counters to the correct provider cards`() {
        var day = today.minusDays(1)
        val usage = ApiUsageStore(compose.activity, { day })
        repeat(8) { usage.requestStarted(BarcodeService.ALIYUN) }
        repeat(5) { usage.requestStarted(BarcodeService.MXNZP) }
        day = today
        repeat(3) { usage.requestStarted(BarcodeService.ALIYUN) }
        repeat(2) { usage.requestStarted(BarcodeService.MXNZP) }
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        compose.runOnUiThread { viewModel.refresh() }
        compose.onNodeWithText("设置").performClick()
        fun checkCount(text: String) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        checkCount("阿里云 · 本机今日 3 次 · 累计 11 次")
        checkCount("MXNZP · 本机今日 2 次 · 累计 7 次")
        usage.requestStarted(BarcodeService.MXNZP)
        compose.runOnUiThread { viewModel.refresh() }
        checkCount("MXNZP · 本机今日 3 次 · 累计 8 次")
        checkCount("阿里云 · 本机今日 3 次 · 累计 11 次")
    }

    @Test fun `GitHub mark opens the user specified profile and preserves version card`() {
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("打开作者 GitHub 主页"))
        compose.onNodeWithText("家庭药箱 ·", substring = true).assertIsDisplayed()
        capture("25-about-github")
        val mark = compose.onNodeWithContentDescription("打开作者 GitHub 主页").fetchSemanticsNode()
        Assert.assertTrue("GitHub 入口保留 48 dp 的实际触控范围。", mark.touchBoundsInRoot.width >= 95.99f)
        compose.onNodeWithContentDescription("打开作者 GitHub 主页").performClick()
        Assert.assertEquals(AUTHOR_GITHUB_URL, Shadows.shadowOf(compose.activity).nextStartedActivity.data.toString())
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/ui-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
