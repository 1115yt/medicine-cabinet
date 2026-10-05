package app.medicinecabinet.ui

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.data.entity
import app.medicinecabinet.domain.BackupCodec
import app.medicinecabinet.domain.Medicine
import app.medicinecabinet.domain.StockBatch
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 使用虚构内存药箱和本机文件，核对创建文件前的预检及固定快照。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
class BackupExportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)
    private val app get() = compose.activity.application as TestCabinetApplication
    private val viewModel get() = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]

    private fun outputFile(): File {
        val folder = File(System.getProperty("user.dir"), ".local/review-export-tests").apply { mkdirs() }
        return File.createTempFile("synthetic-backup-", ".json", folder)
    }

    private fun awaitNotBusy() {
        compose.waitUntil(20_000) { !viewModel.busy.value }
        compose.waitForIdle()
    }

    @Test fun `export writes the exact preflight snapshot even if cabinet changes in file picker`() {
        val medicine = Medicine("fixture-medicine", "预检时的虚构药品", lowStockThreshold = 0)
        runBlocking { app.repository.saveRecord(medicine, StockBatch("fixture-batch", medicine.id, 1, "2030-06")) }
        var pickerLaunches = 0
        compose.runOnIdle { viewModel.prepareExport { pickerLaunches++ } }
        awaitNotBusy()
        assertEquals(1, pickerLaunches)
        runBlocking { app.repository.updateMedicine(medicine.copy(name = "选择位置后的虚构修改")) }
        val file = outputFile()
        compose.runOnIdle { viewModel.exportTo(Uri.fromFile(file)) }
        awaitNotBusy()
        val restored = BackupCodec.decode(file.readText(Charsets.UTF_8))
        assertEquals(medicine.name, restored.medicines.single().name)
        assertEquals("2030-06", restored.batches.single().expiryDate)
        assertEquals("选择位置后的虚构修改", runBlocking { app.repository.snapshot() }.medicines.single().name)
    }

    @Test fun `cancelled preflight cannot truncate a selected file and a fresh preflight is allowed`() {
        var pickerLaunches = 0
        compose.runOnIdle { viewModel.prepareExport { pickerLaunches++ } }
        awaitNotBusy()
        assertEquals(1, pickerLaunches)
        val file = outputFile().apply { writeText("synthetic-existing-content") }
        compose.runOnIdle { viewModel.cancelExport(); viewModel.exportTo(Uri.fromFile(file)) }
        awaitNotBusy()
        assertEquals("synthetic-existing-content", file.readText())
        compose.runOnIdle { viewModel.prepareExport { pickerLaunches++ } }
        awaitNotBusy()
        assertEquals(2, pickerLaunches)
    }

    @Test fun `oversized valid records reject export before launching file creation and preserve cabinet`() {
        val medicine = Medicine("fixture-large", "大型虚构药箱", lowStockThreshold = 0)
        val database = app.database
        runBlocking {
            database.withTransaction {
                val dao = database.dao()
                dao.saveMedicine(medicine.entity())
                repeat(4000) { index ->
                    dao.saveBatch(StockBatch("fixture-batch-$index", medicine.id, 1, "2030-06",
                        lotNumber = "批".repeat(80), location = "位".repeat(80)).entity())
                }
            }
        }
        var pickerLaunches = 0
        compose.runOnIdle { viewModel.prepareExport { pickerLaunches++ } }
        awaitNotBusy()
        assertEquals(0, pickerLaunches)
        val current = runBlocking { app.repository.snapshot() }
        assertEquals(1, current.medicines.size)
        assertEquals(4000, current.batches.size)
        assertTrue(current.batches.all { it.quantity == 1 })
    }
}
