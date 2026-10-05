package app.medicinecabinet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import app.medicinecabinet.CabinetApplication
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.data.LookupSettings
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.components.*
import app.medicinecabinet.ui.theme.CabinetTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi", application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `quantity is centered between buttons at all interface sizes and large text`() {
        for (size in InterfaceSize.entries) for (fontScale in listOf(1f, 1.6f)) {
            compose.runOnUiThread { compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    CabinetTheme(interfaceSize = size) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            QuantityControls(1, "盒", "示例库存", true) {}
                        }
                    }
                }
            } }
            val quantity = compose.onNodeWithText("1 盒").fetchSemanticsNode().boundsInRoot
            val minus = compose.onNodeWithContentDescription("减少示例库存").fetchSemanticsNode()
            val plus = compose.onNodeWithContentDescription("增加示例库存").fetchSemanticsNode()
            val center = (minus.boundsInRoot.center.x + plus.boundsInRoot.center.x) / 2f
            Assert.assertEquals("数量应水平居中：$size / $fontScale", center, quantity.center.x, 1f)
            Assert.assertEquals("数量与按钮应垂直居中。", minus.boundsInRoot.center.y, quantity.center.y, 1f)
            Assert.assertEquals(minus.boundsInRoot.center.y, plus.boundsInRoot.center.y, 1f)
            Assert.assertTrue("缩放后减少按钮的实际触控区域应至少 48 dp。", minus.touchBoundsInRoot.width >= 95.99f)
            Assert.assertTrue("缩放后增加按钮的实际触控区域应至少 48 dp。", plus.touchBoundsInRoot.width >= 95.99f)
        }
        capture("15-quantity-large-text")
    }

    @Test fun `quantity summary label and value share a text baseline`() {
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            QuantitySummary("确认可用", 0, "盒", MaterialTheme.typography.titleMedium)
        } } }
        fun baseline(text: String): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText(text)
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        Assert.assertEquals("说明文字与数量应使用同一基线。", baseline("确认可用"), baseline("0 盒"), 1f)
        val label = compose.onNodeWithText("确认可用").fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("0 盒").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals("两段文字使用 8 dp 间距。", 16f, value.left - label.right, 1f)
    }

    @Test fun `quantity buttons keep minimum maximum and disabled states`() {
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            var quantity by remember { mutableStateOf(1) }
            Column {
                QuantityControls(quantity, "盒", "补货数量", true, 1) { quantity += it }
                QuantityControls(9999, "瓶", "上限库存", true) {}
                QuantityControls(0, "支", "保存中库存", false) {}
            }
        } } }
        compose.onNodeWithContentDescription("减少补货数量").assertIsNotEnabled()
        compose.onNodeWithContentDescription("增加补货数量").performClick()
        compose.onNodeWithText("2 盒").assertIsDisplayed()
        compose.onNodeWithContentDescription("减少补货数量").performClick()
        compose.onNodeWithText("1 盒").assertIsDisplayed()
        compose.onNodeWithContentDescription("增加上限库存").assertIsNotEnabled()
        compose.onNodeWithText("9999 瓶").assertIsDisplayed()
        compose.onNodeWithContentDescription("减少保存中库存").assertIsNotEnabled()
        compose.onNodeWithContentDescription("增加保存中库存").assertIsNotEnabled()
    }

    @Test fun `scan guidance and supplemental providers use independent cards without catalog metadata`() {
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ScanInfoCard()
                LookupSettingsCard(LookupSettings(), false, {}, {})
                MxnzpSettingsCard(LookupSettings(), false, {}, onSave = { _, _ -> })
            }
        } } }
        compose.onNodeWithText("扫码资料").assertIsDisplayed()
        compose.onNodeWithText("47,256", substring = true).assertDoesNotExist()
        compose.onNodeWithText("2023-09", substring = true).assertDoesNotExist()
        compose.onNodeWithText("内置资料导出", substring = true).assertDoesNotExist()
        compose.onNodeWithText("阿里云补充查询").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("使用阿里云").assertIsNotEnabled()
        compose.onNodeWithText("保存阿里云认证并启用").assertIsNotEnabled()
        capture("16-scan-and-aliyun-cards")
        compose.onNodeWithText("MXNZP 补充查询").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("使用 MXNZP").assertIsNotEnabled()
        compose.onNodeWithText("保存 MXNZP 认证并启用").performScrollTo().assertIsNotEnabled()
        capture("17-mxnzp-card")
    }

    @Test fun `cabinet shopping and matching local scan use the simplified presentation`() {
        val application = compose.activity.application as CabinetApplication
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        val today = LocalDate.now()
        runBlocking {
            application.repository.saveRecord(Medicine("polish-example", "药品示例", barcode = "04006381333931", packageUnit = "盒"),
                StockBatch("polish-batch", "polish-example", 1, today.minusDays(1).toString()))
        }
        // 总览的提醒卡位于折叠区域之外；先进入药箱，再等待可见药名与状态流更新。
        compose.onNodeWithText("药箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("药品示例").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看 1 个批次").performClick()
        compose.onNodeWithText("0 盒").assertIsDisplayed()
        compose.onNodeWithText("1 盒").assertIsDisplayed()
        capture("18-cabinet-aligned-quantity")
        compose.onNodeWithText("补货").performClick()
        compose.onNodeWithText("记入补货").assertIsDisplayed()
        capture("19-shopping-aligned-quantity")
        compose.runOnUiThread { viewModel.lookupScan(ParsedBarcode("04006381333931")) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("本批次信息").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("资料来源", substring = true).assertDoesNotExist()
        compose.onNodeWithText("本机已核对记录", substring = true).assertDoesNotExist()
        capture("20-local-scan-review")
    }

    @Test fun `settings keep API cards and shared identity explanation without server credentials`() {
        compose.onNodeWithText("设置").performClick()
        scrollToSettingsText("扫码资料")
        scrollToSettingsText("共享条码资料")
        compose.onNodeWithText("服务器 HTTPS 地址").assertDoesNotExist()
        compose.onNodeWithText("服务器访问令牌").assertDoesNotExist()
        capture("21-settings-shared-identity")
        scrollToSettingsText("阿里云补充查询")
        scrollToSettingsText("MXNZP 补充查询")
        scrollToSettingsText("备份与恢复")
        compose.onNodeWithText("条码共享缓存").assertDoesNotExist()
    }

    private fun scrollToSettingsText(text: String) {
        // LazyColumn 屏幕外的项可能尚未组合，应通过列表滚动寻找节点。
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
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
