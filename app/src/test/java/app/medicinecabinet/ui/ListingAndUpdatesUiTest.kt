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
import app.medicinecabinet.BuildConfig
import app.medicinecabinet.CabinetApplication
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.Medicine
import app.medicinecabinet.domain.MedicineSort
import app.medicinecabinet.domain.StockBatch
import app.medicinecabinet.ui.theme.CabinetTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 全部记录为内存示例；核对分类导航、排序选择与尚未开放的更新入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi", application = TestCabinetApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListingAndUpdatesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    private val today get() = LocalDate.now()
    private val application get() = compose.activity.application as CabinetApplication
    private val viewModel get() = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]

    @Test fun `home opens matching categories expands only matching batches and preserves category after editing`() {
        seedMixedBatches()
        openCategory("临期")
        compose.onNodeWithText("临期药品").assertIsDisplayed()
        compose.onNodeWithText("分类药品示例").assertIsDisplayed()
        compose.onNodeWithContentDescription("药箱排序").assertDoesNotExist()
        capture("33-near-expiry-list")
        val nearDate = today.plusDays(12).toString()
        compose.onNodeWithContentDescription("编辑批次$nearDate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("编辑批次${today.minusDays(1)}").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑批次${today.plusDays(180)}").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑批次$nearDate").performClick()
        compose.onNodeWithText("编辑批次").assertIsDisplayed()
        back()
        compose.onNodeWithText("临期药品").assertIsDisplayed()
        back()
        compose.onNodeWithText("家庭药箱").assertIsDisplayed()
        openCategory("过期")
        compose.onNodeWithText("过期药品").assertIsDisplayed()
        capture("34-expired-list")
        compose.onNodeWithContentDescription("编辑批次${today.minusDays(1)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("编辑批次$nearDate").assertDoesNotExist()
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("我的药箱").assertIsDisplayed()
        compose.onNodeWithText("查看 3 个批次").performScrollTo().assertIsDisplayed()
    }

    @Test fun `low stock still opens shopping and zero count categories have honest empty states`() {
        val medicine = Medicine("empty-stock", "缺货药品示例")
        runBlocking { application.repository.saveRecord(medicine, StockBatch("empty-batch", medicine.id, 0, today.plusDays(180).toString())) }
        awaitMedicineCount(1)
        openCategory("库存不足")
        compose.onNodeWithText("补货清单").assertIsDisplayed()
        compose.onNodeWithText("缺货药品示例").assertIsDisplayed()
        back()
        openCategory("临期")
        compose.onNodeWithText("目前没有临期药品").assertIsDisplayed()
        compose.onNodeWithText("返回总览").performClick()
        openCategory("过期")
        compose.onNodeWithText("目前没有过期库存").assertIsDisplayed()
        compose.onNodeWithText("返回总览").performClick()
        compose.onNodeWithText("家庭药箱").assertIsDisplayed()
    }

    @Test fun `clearing the last expired stock removes it from the category while retaining the medicine`() {
        val medicine = Medicine("clear-category", "清理药品示例")
        runBlocking { application.repository.saveRecord(medicine, StockBatch("clear-batch", medicine.id, 1, today.minusDays(1).toString())) }
        awaitMedicineCount(1)
        openCategory("过期")
        compose.onNodeWithText("已清理").performScrollTo().performClick()
        compose.onNodeWithText("确认已清理此批次？").assertIsDisplayed()
        compose.onNodeWithText("已清理，记为零").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("目前没有过期库存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("目前没有过期库存").assertIsDisplayed()
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("清理药品示例").assertIsDisplayed()
        Assert.assertEquals(0, runBlocking { application.repository.snapshot() }.batches.single().quantity)
    }

    @Test fun `all sort choices change the first medicine and the choice survives tab navigation`() {
        val records = listOf(
            Triple("sort-z", "丙药品示例", 180L),
            Triple("sort-a", "甲药品示例", 90L),
            Triple("sort-b", "乙药品示例", -1L),
        )
        runBlocking { records.forEach { (id, name, days) ->
            application.repository.saveRecord(Medicine(id, name), StockBatch("$id-batch", id, 1, today.plusDays(days).toString()))
        } }
        awaitMedicineCount(3)
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("先添加的在前").assertIsDisplayed()
        compose.onNodeWithText("丙药品示例").assertIsDisplayed()
        listOf(
            MedicineSort.ADDED_LAST to "乙药品示例",
            MedicineSort.EXPIRY_FIRST to "乙药品示例",
            MedicineSort.EXPIRY_LAST to "丙药品示例",
            MedicineSort.NAME to "丙药品示例",
            MedicineSort.ADDED_FIRST to "丙药品示例",
        ).forEach { (sort, firstName) ->
            chooseSort(sort)
            compose.onNodeWithText(firstName).assertIsDisplayed()
            Assert.assertEquals(sort, viewModel.medicineSort.value)
        }
        chooseSort(MedicineSort.EXPIRY_FIRST)
        capture("35-cabinet-expiry-sort")
        compose.onNodeWithText("总览").performClick()
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText(MedicineSort.EXPIRY_FIRST.explanation).assertIsDisplayed()
        compose.onNodeWithText("乙药品示例").assertIsDisplayed()
    }

    @Test fun `update entry displays current preview version and does not claim an online result`() {
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("检查更新"))
        compose.onNodeWithText("预览版暂未开放在线更新").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开作者 GitHub 主页").assertIsDisplayed()
        compose.onNodeWithText("检查更新").performClick()
        compose.onNodeWithText("当前版本：${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("当前使用预览版，暂未开放在线更新。正式版本发布后可在此检查。").assertIsDisplayed()
        compose.onNodeWithText("已是最新版本").assertDoesNotExist()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("当前版本：${BuildConfig.VERSION_NAME}").assertDoesNotExist()
    }

    @Test fun `category and sorting remain readable in dark theme and large text`() {
        seedMixedBatches()
        replaceTheme(dark = true)
        openCategory("临期")
        compose.onNodeWithText("临期药品").assertIsDisplayed()
        capture("36-near-expiry-dark")
        replaceTheme(dark = false, scale = 1.6f)
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithContentDescription("药箱排序").performScrollTo().assertIsDisplayed()
        Assert.assertTrue("排序按钮的实际触控高度应至少 48 dp。",
            compose.onNodeWithContentDescription("药箱排序").fetchSemanticsNode().touchBoundsInRoot.height >= 95.99f)
        chooseSort(MedicineSort.EXPIRY_LAST)
        compose.onNodeWithText(MedicineSort.EXPIRY_LAST.explanation).assertIsDisplayed()
        capture("37-cabinet-sort-large-text")
        compose.onNodeWithText("总览").performClick()
        openCategory("过期")
        compose.onNodeWithText("过期药品").assertIsDisplayed()
        capture("38-expired-large-text")
        compose.onNodeWithContentDescription("编辑批次${today.minusDays(1)}").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w375dp-h812dp-xhdpi")
    fun `sort menu choices and selected control fit a small phone`() {
        compose.onNodeWithText("药箱").performClick()
        chooseSort(MedicineSort.EXPIRY_LAST)
        compose.onNodeWithText("有效期长优先").assertIsDisplayed()
        compose.onNodeWithText("目前没有临期药品").assertDoesNotExist()
        capture("39-cabinet-sort-small-phone")
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xhdpi", shadows = [ReducedMotionSettingsShadow::class])
    fun `sorting and update explanation are usable in short landscape`() {
        compose.onNodeWithText("药箱").performClick()
        chooseSort(MedicineSort.EXPIRY_FIRST)
        compose.onNodeWithText("快到期优先").assertIsDisplayed()
        capture("40-cabinet-sort-landscape")
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("检查更新"))
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("当前版本：${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("知道了").assertIsDisplayed().performClick()
    }

    private fun seedMixedBatches() {
        val mixed = Medicine("mixed-category", "分类药品示例", "包装规格示例")
        val normal = Medicine("normal-category", "常规药品示例")
        runBlocking {
            application.repository.saveRecord(mixed, StockBatch("mixed-old", mixed.id, 1, today.minusDays(1).toString()))
            application.repository.saveRecord(mixed, StockBatch("mixed-near", mixed.id, 2, today.plusDays(12).toString(), expiryHandled = true))
            application.repository.saveRecord(mixed, StockBatch("mixed-new", mixed.id, 1, today.plusDays(180).toString()))
            application.repository.saveRecord(normal, StockBatch("normal-new", normal.id, 1, today.plusDays(180).toString()))
        }
        awaitMedicineCount(2)
    }

    private fun awaitMedicineCount(count: Int) {
        compose.waitUntil(20_000) { viewModel.snapshot.value.medicines.size == count }
        compose.waitForIdle()
    }

    private fun openCategory(label: String) =
        compose.onNodeWithContentDescription("查看${label}药品").performScrollTo().performClick()

    private fun chooseSort(sort: MedicineSort) {
        compose.onNodeWithContentDescription("药箱排序").performScrollTo().performClick()
        // 当前选项同时出现在按钮与菜单；菜单节点在最后，不误点按钮。
        compose.onAllNodesWithText(sort.label).onLast().performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun replaceTheme(dark: Boolean, scale: Float = 1f) {
        val currentModel = viewModel
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                CabinetTheme(darkTheme = dark) { CabinetApp(currentModel) }
            }
        } }
        compose.waitForIdle()
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
