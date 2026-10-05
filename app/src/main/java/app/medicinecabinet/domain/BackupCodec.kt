package app.medicinecabinet.domain

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class LocalBackupV1(
    val schemaVersion: Int = 1,
    val exportedAt: String,
    val medicines: List<Medicine>,
    val batches: List<StockBatch>,
    val shoppingItems: List<ShoppingItem>,
    val settings: ReminderSettings,
    val showExpiryDays: Boolean = true,
    val interfaceSize: InterfaceSize = InterfaceSize.STANDARD,
) {
    fun snapshot() = AppSnapshot(medicines, batches, shoppingItems, settings)
}

object BackupCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val CAPACITY_ERROR = "备份容量不能超过 2 MiB，请检查药箱记录或备份文件。"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun encode(snapshot: AppSnapshot, showExpiryDays: Boolean = true,
        interfaceSize: InterfaceSize = InterfaceSize.STANDARD): String {
        val backup = LocalBackupV1(1, Instant.now().toString(), snapshot.medicines, snapshot.batches,
            snapshot.shoppingItems, snapshot.settings, showExpiryDays, interfaceSize)
        validate(backup)
        val text = json.encodeToString(backup)
        // 在系统创建文件之前核对容量和恢复路径，成功返回的内容必须可按原格式导回。
        require(decode(text) == backup) { "备份校验失败，请重试。" }
        return text
    }

    fun decode(text: String): LocalBackupV1 {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { CAPACITY_ERROR }
        val version = runCatching {
            json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
        require(version == 1) { "无法识别备份版本，请选择本应用导出的备份。" }
        val backup = runCatching { json.decodeFromString<LocalBackupV1>(text) }
            .getOrElse { throw IllegalArgumentException("备份内容不完整或格式有误。", it) }
        validate(backup)
        return backup
    }

    fun validate(backup: LocalBackupV1) {
        require(backup.schemaVersion == 1) { "不支持此备份版本。" }
        require(runCatching { Instant.parse(backup.exportedAt) }.isSuccess) { "备份导出时间不合法。" }
        require(backup.medicines.size <= 10000 && backup.batches.size <= 50000) { "备份记录数量异常。" }
        val medicineIds = backup.medicines.map { it.id }
        require(medicineIds.distinct().size == medicineIds.size && medicineIds.all { it.isNotBlank() }) { "药品记录编号重复或为空。" }
        require(backup.batches.map { it.id }.distinct().size == backup.batches.size) { "批次记录编号重复。" }
        backup.medicines.forEach(::validateMedicine)
        val barcodes = backup.medicines.map { it.barcode }.filter { it.isNotBlank() }
        require(barcodes.distinct().size == barcodes.size) { "备份中存在重复药品条码。" }
        backup.batches.forEach {
            validateBatch(it)
            require(it.medicineId in medicineIds) { "批次缺少对应的药品记录。" }
        }
        require(backup.shoppingItems.map { it.medicineId }.distinct().size == backup.shoppingItems.size) { "补货清单包含重复药品。" }
        backup.shoppingItems.forEach {
            require(it.medicineId in medicineIds && it.requestedQuantity in 1..9999 &&
                it.suggestedQuantity in 1..9999 && it.signature.length <= 10000) { "补货清单数据不合法。" }
        }
        require(backup.settings.expiryLeadDays in 1..365) { "提前提醒天数应为 1 至 365 天。" }
    }

    fun validateMedicine(medicine: Medicine) {
        require(medicine.id.isNotBlank() && medicine.name.isNotBlank() && medicine.name.length <= 80) { "药品名称不能为空，且最多 80 字。" }
        require(medicine.specification.length <= 120 && medicine.barcode.length <= 512) { "药品规格或条码过长。" }
        require(medicine.packageUnit.isNotBlank() && medicine.packageUnit.length <= 8) { "请填写包装单位，如盒、瓶、支。" }
        require(medicine.lowStockThreshold in 0..9999) { "最低库存应为 0 至 9999。" }
        require(medicine.expiryLeadDays == null || medicine.expiryLeadDays in 1..365) { "提前提醒天数应为 1 至 365 天。" }
    }

    fun validateBatch(batch: StockBatch) {
        require(batch.id.isNotBlank() && batch.quantity in 0..9999) { "库存数量应为 0 至 9999。" }
        require(batch.lotNumber.length <= 80 && batch.location.length <= 80) { "批号或存放位置过长。" }
        require(batch.expiryDate == null || ExpiryDates.endDate(batch.expiryDate) != null) {
            "有效期应为 YYYY-MM 或 YYYY-MM-DD。"
        }
    }
}
