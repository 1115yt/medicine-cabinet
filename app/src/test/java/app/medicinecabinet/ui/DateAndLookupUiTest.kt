package app.medicinecabinet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.forms.EditorRequest
import app.medicinecabinet.ui.forms.RecordEditor
import app.medicinecabinet.ui.theme.CabinetTheme
import app.medicinecabinet.ui.components.ServerCacheSettingsCard
import app.medicinecabinet.data.ServerCacheSettings
import app.medicinecabinet.data.CacheSyncStatus
import app.medicinecabinet.data.ServerConnectionState
import app.medicinecabinet.data.ServerHealthResult
import app.medicinecabinet.data.ServerHealthReason
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi", application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DateAndLookupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `direct numeric month shows separators and appending a day saves the complete date`() {
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("药品名称").performTextInput("数字日期示例")
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("203006")
        compose.onNodeWithText("2030-06").assertIsDisplayed()
        compose.onNodeWithText("有效期至").performTextInput("30")
        compose.onNodeWithText("2030-06-30").assertIsDisplayed()
        compose.onNodeWithText("保存到药箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("总览").fetchSemanticsNodes().isNotEmpty() }
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        Assert.assertEquals("2030-06-30", viewModel.snapshot.value.batches.single().expiryDate)
    }

    @Test fun `numeric month in unified dialog preserves precision and incomplete input is kept for correction`() {
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("药品名称").performTextInput("数字年月示例")
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("2027060")
        compose.onNodeWithContentDescription("填写包装有效期").performClick()
        // 主字段和弹窗保留同一输入，使用弹窗标签定位，避免匹配两个相同日期。
        compose.onNodeWithText("包装有效期").assertTextContains("2027-06-0").assertIsDisplayed()
        compose.onNodeWithText("确认有效期").assertIsNotEnabled()
        compose.onNodeWithText("包装有效期").performTextReplacement("20270229")
        compose.onNodeWithText("确认有效期").assertIsNotEnabled()
        compose.onNodeWithText("包装有效期").performTextReplacement("202806")
        compose.onNodeWithText("2028-06").assertIsDisplayed()
        captureDialog("29-numeric-expiry-month")
        compose.onNodeWithText("确认有效期").performClick()
        compose.onNodeWithText("保存到药箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("总览").fetchSemanticsNodes().isNotEmpty() }
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        Assert.assertEquals("2028-06", viewModel.snapshot.value.batches.single().expiryDate)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `a prefilled month can be extended to a day without manually adding separators`() {
        var savedDate: String? = null
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            RecordEditor(EditorRequest(suggestedName = "预填年月示例", expiryDate = "2030-06"),
                AppSnapshot(), false, {}) { _, batch, _ -> savedDate = batch.expiryDate }
        } } }
        val field = compose.onNodeWithText("有效期至").performScrollTo()
        field.performTextInputSelection(TextRange(6))
        field.performTextInput("30")
        compose.onNodeWithText("2030-06-30").assertIsDisplayed()
        compose.onNodeWithText("保存到药箱").performClick()
        Assert.assertEquals("2030-06-30", savedDate)
    }

    @Test fun `manual month date can be saved and shown at original precision`() {
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("药品名称").performTextInput("年月示例")
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("2030年6月")
        compose.onNodeWithText("保存到药箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("总览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("2030-06（按月末计算）", substring = true).assertIsDisplayed()
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        Assert.assertEquals("2030-06", viewModel.snapshot.value.batches.single().expiryDate)
    }

    @Test fun `unified expiry dialog title and confirmation fit every interface scale`() {
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("2030-06-30")
        for (size in InterfaceSize.entries) {
            compose.runOnUiThread { viewModel.setInterfaceSize(size) }
            compose.onNodeWithContentDescription("填写包装有效期").performScrollTo().performClick()
            compose.onNodeWithText("填写包装有效期").assertIsDisplayed()
            compose.onNodeWithText("确认有效期").assertIsDisplayed()
            compose.onNodeWithText("包装有效期").assertIsDisplayed()
            Assert.assertTrue("标题必须与弹窗上边缘留有间距。",
                compose.onNodeWithText("填写包装有效期").fetchSemanticsNode().boundsInRoot.top >= 20f)
            captureDialog("26-unified-expiry-${size.name.lowercase()}")
            compose.onNodeWithText("取消").performClick()
        }
    }

    @Test fun `large text unified dialog accepts both month and day without format selection`() {
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    CabinetTheme(interfaceSize = InterfaceSize.COMFORTABLE) { CabinetApp(viewModel) }
                }
            }
        }
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("仅年月").assertDoesNotExist()
        compose.onNodeWithText("年月日").assertDoesNotExist()
        compose.onNodeWithContentDescription("填写包装有效期").performScrollTo().performClick()
        compose.onNodeWithText("包装有效期").performTextInput("2030-06-30")
        compose.onNodeWithText("确认有效期").assertIsDisplayed().performClick()
        compose.onNodeWithText("2030-06-30").assertIsDisplayed()
        compose.onNodeWithContentDescription("填写包装有效期").performClick()
        compose.onNodeWithText("包装有效期").performTextReplacement("2030年12月")
        compose.onNodeWithText("按 2030-12-31 计算到期", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("确认有效期").assertIsDisplayed()
        captureDialog("27-unified-expiry-large-text")
        compose.onNodeWithText("确认有效期").performClick()
        compose.onNodeWithText("2030-12").assertIsDisplayed()
    }

    @Test fun `prefilled identity is editable and is not saved before confirmation`() {
        var saved: Medicine? = null
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            RecordEditor(EditorRequest(barcode = "04006381333931", fromScan = true,
                suggestedName = "预填示例", suggestedSpecification = "10片", suggestedUnit = "瓶",
                sourceNote = "厂家：示例厂家\n批准文号：示例文号"), AppSnapshot(), false, {}) { medicine, _, _ -> saved = medicine }
        } } }
        Assert.assertNull(saved)
        compose.onNodeWithText("预填示例").performTextReplacement("用户核对名称")
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("2030-06")
        compose.onNodeWithText("保存到药箱").performClick()
        Assert.assertEquals("用户核对名称", saved?.name)
        Assert.assertEquals("10片", saved?.specification)
        Assert.assertEquals("瓶", saved?.packageUnit)
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xhdpi", shadows = [ReducedMotionSettingsShadow::class])
    fun `short landscape date dialog keeps actions visible and confirms a date`() {
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithContentDescription("填写包装有效期").performScrollTo().performClick()
        compose.onNodeWithText("包装有效期").performTextInput("2030-06-30")
        compose.onNodeWithText("填写包装有效期").assertIsDisplayed()
        compose.onNodeWithText("确认有效期").assertIsDisplayed()
        captureDialog("28-unified-expiry-landscape")
        compose.onNodeWithText("确认有效期").performClick()
        compose.onNodeWithText("2030-06-30").assertIsDisplayed()
    }

    @Test fun `unified dialog rejects impossible dates preserves month precision and cancel leaves field unchanged`() {
        compose.onNodeWithText("手动录入").performClick()
        compose.onNodeWithText("药品名称").performTextInput("统一日期示例")
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("2028-02")
        compose.onNodeWithContentDescription("填写包装有效期").performClick()
        compose.onNodeWithText("包装有效期").performTextReplacement("2027-02-29")
        compose.onNodeWithText("确认有效期").assertIsNotEnabled()
        compose.onNodeWithText("请输入有效年月或日期", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("2028-02").assertIsDisplayed()
        compose.onNodeWithContentDescription("填写包装有效期").performClick()
        compose.onNodeWithText("包装有效期").performTextReplacement("2028/6")
        compose.onNodeWithText("确认有效期").assertIsEnabled().performClick()
        compose.onNodeWithText("保存到药箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("总览").fetchSemanticsNodes().isNotEmpty() }
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        Assert.assertEquals("2028-06", viewModel.snapshot.value.batches.single().expiryDate)
    }

    @Test fun `scan resolves bundled record into review without writing inventory`() {
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        compose.runOnUiThread { viewModel.lookupScan(ParsedBarcode("06902401045076")) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("50mg*10片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("50mg*10片").assertIsDisplayed()
        compose.onNodeWithText("资料来源", substring = true).assertDoesNotExist()
        compose.onNodeWithText("2023-09", substring = true).assertDoesNotExist()
        compose.onNodeWithText("厂家：", substring = true).assertIsDisplayed()
        Assert.assertTrue((viewModel.scanLookup.value as? ScanLookupState.Ready)?.request?.sourceNote.orEmpty().contains("资料来源").not())
        Assert.assertTrue(viewModel.snapshot.value.medicines.isEmpty())
        compose.onNodeWithText("药品名称").performTextReplacement("核对后的示例药品")
        compose.onNodeWithText("有效期至").performScrollTo().performTextInput("2030-06")
        compose.onNodeWithText("保存到药箱").performClick()
        // 语义查询会推动模拟主线程，等待保存完成后返回药箱，再核对实际持久化内容。
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("总览").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw error
        }
        Assert.assertEquals("核对后的示例药品", viewModel.snapshot.value.medicines.single().name)
        Assert.assertEquals("2030-06", viewModel.snapshot.value.batches.single().expiryDate)
    }

    private fun captureDialog(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            Assert.assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/ui-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun `shared cache settings require no address or server credential entry`() {
        var enabled: Boolean? = null
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            ServerCacheSettingsCard(ServerCacheSettings(enabled = true, available = true),
                CacheSyncStatus(1, "待上传资料已保存在本机。"), false, { enabled = it }, {})
        } } }
        compose.onNodeWithText("共享条码资料").assertIsDisplayed()
        compose.onNodeWithText("服务器 HTTPS 地址").assertDoesNotExist()
        compose.onNodeWithText("服务器访问令牌").assertDoesNotExist()
        compose.onNodeWithText("待上传 1 条基础资料").assertIsDisplayed()
        compose.onNodeWithContentDescription("使用共享资料").performClick()
        Assert.assertEquals(false, enabled)
    }

    @Test fun `shared connection button shows pending then success and blocks repeated checks`() {
        var checks = 0
        val state = mutableStateOf(ServerConnectionState())
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ServerCacheSettingsCard(ServerCacheSettings(enabled = true, available = true), CacheSyncStatus(), false, {}, {},
                    state.value, { checks++; state.value = ServerConnectionState(checking = true) })
            }
        } } }
        compose.onNodeWithText("尚未检测连接").assertIsDisplayed()
        compose.onNodeWithText("检测连接").performClick()
        compose.onNodeWithText("检测中…").assertIsNotEnabled()
        compose.onNodeWithText("正在检测共享服务及数据库…").assertIsDisplayed()
        Assert.assertEquals(1, checks)
        compose.runOnUiThread { state.value = ServerConnectionState(
            result = ServerHealthResult(ServerHealthReason.CONNECTED, 200), checkedAt = 1234L) }
        compose.onNodeWithText("连接成功：共享服务及数据库可用", substring = true).assertIsDisplayed()
        compose.onNodeWithText("HTTP 200", substring = true).assertIsDisplayed()
        compose.onNodeWithText("上次检测：", substring = true).assertIsDisplayed()
        compose.onNodeWithText("检测连接").assertIsEnabled()
    }

    @Test fun `shared connection failure remains readable at large text and does not expose credentials`() {
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.6f)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ServerCacheSettingsCard(ServerCacheSettings(enabled = true, available = true), CacheSyncStatus(), false, {}, {},
                        ServerConnectionState(result = ServerHealthResult(ServerHealthReason.SERVICE_UNAVAILABLE, 503)), {})
                }
            }
        } } }
        compose.onNodeWithText("共享服务或数据库暂不可用", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HTTP 503", substring = true).assertIsDisplayed()
        compose.onNodeWithText("检测连接").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("服务器访问令牌").assertDoesNotExist()
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/ui-preview").apply { mkdirs() }
            File("build/ui-preview/32-shared-connection-large-text.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun `disabled sharing never displays a stale connection success`() {
        compose.runOnUiThread { compose.activity.setContent { CabinetTheme {
            ServerCacheSettingsCard(ServerCacheSettings(enabled = false, available = true), CacheSyncStatus(), false, {}, {},
                ServerConnectionState(result = ServerHealthResult(ServerHealthReason.CONNECTED, 200), checkedAt = 1234L), {})
        } } }
        compose.onNodeWithText("共享已关闭，开启后可检测连接").assertIsDisplayed()
        compose.onNodeWithText("检测连接").assertIsNotEnabled()
        compose.onNodeWithText("连接成功", substring = true).assertDoesNotExist()
        compose.onNodeWithText("上次检测：", substring = true).assertDoesNotExist()
    }
}
