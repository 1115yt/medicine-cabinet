package app.medicinecabinet.data

import androidx.room.withTransaction
import app.medicinecabinet.domain.*
import java.time.LocalDate
import kotlinx.coroutines.flow.combine

class CabinetRepository(private val database: CabinetDatabase) {
    private val dao = database.dao()
    val snapshots = combine(dao.observeMedicines(), dao.observeBatches(), dao.observeShopping(),
        dao.observeSettings()) { medicines, batches, shopping, settings ->
        AppSnapshot(medicines.map { it.model() }, batches.map { it.model() },
            shopping.map { it.model() }, settings?.model() ?: ReminderSettings())
    }

    private suspend fun readSnapshot() = AppSnapshot(
        dao.medicines().map { it.model() }, dao.batches().map { it.model() },
        dao.shopping().map { it.model() }, dao.settings()?.model() ?: ReminderSettings(),
    )

    suspend fun snapshot(): AppSnapshot = database.withTransaction { readSnapshot() }

    private suspend fun reconcile(today: LocalDate = LocalDate.now()) {
        val snapshot = readSnapshot()
        val shopping = InventoryRules.reconcileShopping(InventoryRules.evaluate(snapshot, today), snapshot.shoppingItems)
        val previousByMedicine = snapshot.shoppingItems.associateBy { it.medicineId }
        // 条件未变化时保留现有行，减少 Room 通知、列表重组和重复写入。
        if (shopping.size != snapshot.shoppingItems.size || shopping.any { previousByMedicine[it.medicineId] != it }) {
            dao.clearShopping()
            dao.saveShopping(shopping.map { it.entity() })
        }
        if (shopping.isEmpty()) dao.clearReceipts()
        else dao.clearInactiveReceipts(shopping.map { it.medicineId })
    }

    suspend fun refresh(today: LocalDate = LocalDate.now()) = database.withTransaction { reconcile(today) }

    /** 添药和完成补货使用同一事务，避免数量已增加而旧提醒未处理。 */
    suspend fun saveRecord(medicine: Medicine, batch: StockBatch, completeShopping: Boolean = false) {
        BackupCodec.validateMedicine(medicine)
        BackupCodec.validateBatch(batch)
        require(batch.medicineId == medicine.id) { "批次与药品不匹配。" }
        database.withTransaction {
            checkBarcodeConflict(medicine)
            if (completeShopping) {
                val previous = readSnapshot()
                val oldAlert = InventoryRules.evaluate(previous, LocalDate.now()).find { it.medicineId == medicine.id }
                previous.batches.filter { it.id in (oldAlert?.expiryBatchIds ?: emptySet()) }.forEach {
                    dao.saveBatch(it.copy(expiryHandled = true).entity())
                }
            }
            dao.saveMedicine(medicine.entity())
            dao.saveBatch(batch.entity())
            reconcile()
        }
    }

    private suspend fun checkBarcodeConflict(medicine: Medicine) {
        require(medicine.barcode.isEmpty() || dao.medicines().none {
            it.id != medicine.id && it.barcode == medicine.barcode
        }) { "此条码已有药品记录，请选择已有药品添加批次。" }
    }

    suspend fun updateMedicine(medicine: Medicine) {
        BackupCodec.validateMedicine(medicine)
        database.withTransaction {
            val old = dao.medicines().find { it.id == medicine.id } ?: error("找不到药品记录。")
            require(old.packageUnit == medicine.packageUnit || dao.batches().none {
                it.medicineId == medicine.id && it.quantity > 0
            }) { "还有库存时不能更换计数单位，请先盘点原有库存。" }
            checkBarcodeConflict(medicine)
            dao.saveMedicine(medicine.entity())
            reconcile()
        }
    }

    suspend fun updateBatch(batch: StockBatch) {
        BackupCodec.validateBatch(batch)
        database.withTransaction {
            val old = dao.batches().find { it.id == batch.id } ?: error("找不到批次记录。")
            require(old.medicineId == batch.medicineId) { "不能将批次移动到其他药品。" }
            dao.saveBatch(batch.copy(expiryHandled = if (old.expiryDate == batch.expiryDate) old.expiryHandled else false).entity())
            reconcile()
        }
    }

    suspend fun adjustQuantity(batchId: String, delta: Int) = database.withTransaction {
        val batch = dao.batches().find { it.id == batchId } ?: error("找不到批次记录。")
        val quantity = batch.quantity.toLong() + delta
        require(quantity in 0..9999) { "库存数量应为 0 至 9999。" }
        dao.saveBatch(batch.copy(quantity = quantity.toInt()))
        reconcile()
    }

    suspend fun clearBatch(batchId: String) = database.withTransaction {
        val batch = dao.batches().find { it.id == batchId } ?: error("找不到批次记录。")
        dao.saveBatch(batch.copy(quantity = 0, expiryHandled = true))
        reconcile()
    }

    /** 确认删除整种药品后，批次由现有外键级联删除，清单与提醒在同一事务中清理。 */
    suspend fun deleteMedicine(medicineId: String) = database.withTransaction {
        require(dao.medicines().any { it.id == medicineId }) { "药品记录已不存在，请刷新药箱。" }
        dao.deleteShopping(medicineId)
        dao.clearReceipt(medicineId)
        dao.deleteMedicine(medicineId)
        reconcile()
    }

    /** 删除一个批次后保留药品资料，重新计算库存与补货条件。 */
    suspend fun deleteBatch(batchId: String) = database.withTransaction {
        require(dao.batches().any { it.id == batchId }) { "批次记录已不存在，请刷新药箱。" }
        dao.deleteBatch(batchId)
        reconcile()
    }

    suspend fun setShoppingQuantity(medicineId: String, quantity: Int) = database.withTransaction {
        require(quantity in 1..9999) { "补货数量应为 1 至 9999。" }
        val item = dao.shopping().find { it.medicineId == medicineId } ?: error("补货状态已改变，请刷新。")
        dao.saveShopping(listOf(item.copy(requestedQuantity = quantity, customQuantity = true)))
    }

    suspend fun dismissShopping(medicineId: String) = database.withTransaction {
        val item = dao.shopping().find { it.medicineId == medicineId } ?: return@withTransaction
        dao.saveShopping(listOf(item.copy(status = ShoppingStatus.DISMISSED.name)))
    }

    suspend fun reactivateShopping(medicineId: String) = database.withTransaction {
        val item = dao.shopping().find { it.medicineId == medicineId } ?: return@withTransaction
        dao.saveShopping(listOf(item.copy(status = ShoppingStatus.PENDING.name)))
        dao.clearReceipt(medicineId)
    }

    suspend fun updateSettings(settings: ReminderSettings) = database.withTransaction {
        require(settings.expiryLeadDays in 1..365) { "提前提醒天数应为 1 至 365 天。" }
        dao.saveSettings(settings.entity())
        reconcile()
    }

    suspend fun exportBackup(showExpiryDays: Boolean = true,
        interfaceSize: InterfaceSize = InterfaceSize.STANDARD): String = database.withTransaction {
        reconcile()
        BackupCodec.encode(readSnapshot(), showExpiryDays, interfaceSize)
    }

    suspend fun restore(backup: LocalBackupV1) {
        BackupCodec.validate(backup)
        // 验证在写入前完成；事务失败时旧药箱仍完整保留。
        database.withTransaction {
            dao.clearReceipts()
            dao.clearShopping()
            dao.clearBatches()
            dao.clearMedicines()
            backup.medicines.forEach { dao.saveMedicine(it.entity()) }
            backup.batches.forEach { dao.saveBatch(it.entity()) }
            dao.saveShopping(backup.shoppingItems.map { it.entity() })
            dao.saveSettings(backup.settings.entity())
            reconcile()
        }
    }

    suspend fun dueNotifications(today: LocalDate): List<ShoppingItem> = database.withTransaction {
        reconcile(today)
        val receipts = dao.receipts().associateBy { it.medicineId }
        dao.shopping().map { it.model() }.filter { item ->
            val receipt = receipts[item.medicineId]
            item.status == ShoppingStatus.PENDING && (receipt == null || receipt.signature != item.signature ||
                today.toEpochDay() - receipt.lastNotifiedEpochDay >= 7)
        }
    }

    suspend fun acknowledgeNotifications(items: List<ShoppingItem>, today: LocalDate) = database.withTransaction {
        // 提醒检查与用户删除可能交错；只确认仍存在且条件未变的提醒，避免重新写入已删除药品的记录。
        val current = dao.shopping().associateBy { it.medicineId }
        dao.saveReceipts(items.filter {
            current[it.medicineId]?.let { row -> row.signature == it.signature && row.status == ShoppingStatus.PENDING.name } == true
        }.map { NotificationReceipt(it.medicineId, it.signature, today.toEpochDay()) })
    }
}
