package app.medicinecabinet.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestCabinetApplication::class)
class CabinetRepositoryTest {
    private lateinit var database: CabinetDatabase
    private lateinit var repository: CabinetRepository
    private val today = LocalDate.now()
    private val medicine = Medicine("m", "测试药品", barcode = "04006381333931")
    private fun batch(id: String, quantity: Int, days: Long) = StockBatch(id, "m", quantity, today.plusDays(days).toString())
    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CabinetDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = CabinetRepository(database)
    }
    @After fun close() { database.close() }

    @Test fun `insertion order survives edits new batches and backup restoration`() = runBlocking {
        val medicines = listOf(Medicine("z", "Z 示例"), Medicine("a", "A 示例"), Medicine("b", "B 示例"))
        medicines.forEach { item ->
            repository.saveRecord(item, StockBatch("batch-${item.id}", item.id, 1, today.plusDays(180).toString()))
        }
        repository.updateMedicine(medicines.first().copy(name = "已改名示例"))
        repository.saveRecord(medicines[1], StockBatch("another", "a", 2, today.plusDays(365).toString()))
        assertEquals(listOf("z", "a", "b"), repository.snapshot().medicines.map { it.id })
        assertEquals(listOf("z", "a", "b"), repository.snapshots.first().medicines.map { it.id })
        val backup = BackupCodec.decode(repository.exportBackup())
        assertEquals(listOf("z", "a", "b"), backup.medicines.map { it.id })
        repository.restore(backup)
        assertEquals(listOf("z", "a", "b"), repository.snapshot().medicines.map { it.id })
    }

    @Test fun `refresh with unchanged shopping writes no replacement rows`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 1, 10))
        database.withTransaction {
            fun changes(): Int = database.openHelper.writableDatabase.query("SELECT total_changes()")
                .use { it.moveToFirst(); it.getInt(0) }
            val before = changes()
            repository.refresh()
            assertEquals(before, changes())
        }
    }

    @Test fun `replenishment acknowledges old expiry and resolves shopping`() = runBlocking {
        repository.saveRecord(medicine, batch("old", 2, -1))
        assertEquals(1, repository.snapshot().shoppingItems.size)
        repository.saveRecord(medicine, batch("new", 2, 180), completeShopping = true)
        val snapshot = repository.snapshot()
        assertTrue(snapshot.batches.first { it.id == "old" }.expiryHandled)
        assertTrue(snapshot.shoppingItems.isEmpty())
        assertEquals(2, InventoryRules.usableQuantity(snapshot.batches, today))
    }
    @Test fun `barcode collision rejects new product without partial writes`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 1, 180))
        try {
            repository.saveRecord(medicine.copy(id = "other"), batch("two", 1, 180).copy(medicineId = "other"))
            fail("应拒绝重复条码")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, repository.snapshot().medicines.size)
        assertEquals(1, repository.snapshot().batches.size)
    }
    @Test fun `negative quantity cannot erase valid inventory`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 1, 180))
        try { repository.adjustQuantity("one", -2); fail("应拒绝负库存") } catch (_: IllegalArgumentException) { }
        assertEquals(1, repository.snapshot().batches.single().quantity)
    }
    @Test fun `invalid restore leaves original data untouched`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 1, 180))
        val before = repository.snapshot()
        val backup = BackupCodec.decode(repository.exportBackup()).copy(batches = listOf(batch("bad", 3, 180).copy(medicineId = "missing")))
        try { repository.restore(backup); fail("应拒绝无对应药品的批次") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.snapshot())
    }
    @Test fun `notification receipts prevent daily duplicates and repeat after a week`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 1, 20))
        val due = repository.dueNotifications(today)
        assertEquals(1, due.size)
        repository.acknowledgeNotifications(due, today)
        assertTrue(repository.dueNotifications(today.plusDays(6)).isEmpty())
        assertEquals(1, repository.dueNotifications(today.plusDays(7)).size)
    }
    @Test fun `valid restore preserves dismissals quantities and settings`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 1, 10))
        repository.setShoppingQuantity("m", 4)
        repository.dismissShopping("m")
        repository.updateSettings(ReminderSettings(45, false))
        val backup = BackupCodec.decode(repository.exportBackup())
        repository.restore(backup)
        assertEquals(backup.snapshot(), repository.snapshot())
        assertTrue(repository.dueNotifications(today).isEmpty())
    }
    @Test fun `resolved shortage can notify again immediately when it recurs`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 0, 180))
        repository.acknowledgeNotifications(repository.dueNotifications(today), today)
        repository.adjustQuantity("one", 1)
        assertTrue(repository.dueNotifications(today).isEmpty())
        repository.adjustQuantity("one", -1)
        assertEquals(1, repository.dueNotifications(today).size)
    }

    @Test fun `deleting a medicine removes its batches shopping and receipts and preserves other medicines`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 2, -1))
        repository.saveRecord(medicine, batch("two", 1, 10))
        val other = Medicine("other", "其他示例")
        repository.saveRecord(other, StockBatch("other-batch", other.id, 3, today.plusDays(180).toString()))
        repository.acknowledgeNotifications(repository.dueNotifications(today), today)
        assertTrue(database.dao().receipts().any { it.medicineId == medicine.id })
        repository.deleteMedicine(medicine.id)
        val result = repository.snapshot()
        assertEquals(listOf(other), result.medicines)
        assertEquals(listOf("other-batch"), result.batches.map { it.id })
        assertTrue(result.shoppingItems.isEmpty())
        assertTrue(database.dao().receipts().isEmpty())
        assertEquals(1, database.openHelper.readableDatabase.version)
    }

    @Test fun `deleting one batch preserves medicine other batches and recalculates alerts`() = runBlocking {
        repository.saveRecord(medicine, batch("expired", 1, -1))
        repository.saveRecord(medicine, batch("valid", 2, 180))
        repository.deleteBatch("expired")
        val result = repository.snapshot()
        assertEquals(listOf(medicine), result.medicines)
        assertEquals(listOf("valid"), result.batches.map { it.id })
        assertTrue(result.shoppingItems.isEmpty())
        assertEquals(2, InventoryRules.usableQuantity(result.batches, today))
    }

    @Test fun `deleting the last batch retains medicine and produces shortage when threshold enabled`() = runBlocking {
        repository.saveRecord(medicine, batch("last", 1, 180))
        repository.deleteBatch("last")
        assertEquals(listOf(medicine), repository.snapshot().medicines)
        assertTrue(repository.snapshot().batches.isEmpty())
        assertEquals(listOf(AlertReason.LOW_STOCK), repository.snapshot().shoppingItems.single().reasons)
    }

    @Test fun `deleting unknown records fails without changing existing data`() = runBlocking {
        repository.saveRecord(medicine, batch("one", 1, 180))
        val original = repository.snapshot()
        try { repository.deleteMedicine("missing"); fail("未知药品不能删除其他记录") } catch (_: IllegalArgumentException) { }
        try { repository.deleteBatch("missing"); fail("未知批次不能删除其他记录") } catch (_: IllegalArgumentException) { }
        assertEquals(original, repository.snapshot())
    }

    @Test fun `stale notification completion cannot recreate receipts for deleted medicine`() = runBlocking {
        repository.saveRecord(medicine, batch("expired", 1, -1))
        val stale = repository.dueNotifications(today)
        repository.deleteMedicine(medicine.id)
        repository.acknowledgeNotifications(stale, today)
        assertTrue(database.dao().receipts().isEmpty())
    }
}
