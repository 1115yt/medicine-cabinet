package app.medicinecabinet.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.medicinecabinet.domain.*

@Entity(tableName = "medicines")
data class MedicineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val specification: String,
    val barcode: String,
    val packageUnit: String,
    val lowStockThreshold: Int,
    val expiryLeadDays: Int?,
) {
    fun model() = Medicine(id, name, specification, barcode, packageUnit, lowStockThreshold, expiryLeadDays)
}

@Entity(
    tableName = "batches",
    foreignKeys = [ForeignKey(entity = MedicineEntity::class, parentColumns = ["id"],
        childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicineId")],
)
data class BatchEntity(
    @PrimaryKey val id: String,
    val medicineId: String,
    val quantity: Int,
    val expiryDate: String?,
    val lotNumber: String,
    val location: String,
    val expiryHandled: Boolean,
) {
    fun model() = StockBatch(id, medicineId, quantity, expiryDate, lotNumber, location, expiryHandled)
}

@Entity(tableName = "shopping")
data class ShoppingEntity(
    @PrimaryKey val medicineId: String,
    val reasons: String,
    val suggestedQuantity: Int,
    val requestedQuantity: Int,
    val signature: String,
    val status: String,
    val customQuantity: Boolean,
) {
    fun model() = ShoppingItem(medicineId, reasons.split(',').map(AlertReason::valueOf),
        suggestedQuantity, requestedQuantity, signature, ShoppingStatus.valueOf(status), customQuantity)
}

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val expiryLeadDays: Int = 30,
    val enabled: Boolean = true,
) {
    fun model() = ReminderSettings(expiryLeadDays, enabled)
}

@Entity(tableName = "notification_receipts")
data class NotificationReceipt(
    @PrimaryKey val medicineId: String,
    val signature: String,
    val lastNotifiedEpochDay: Long,
)

fun Medicine.entity() = MedicineEntity(id, name, specification, barcode, packageUnit, lowStockThreshold, expiryLeadDays)
fun StockBatch.entity() = BatchEntity(id, medicineId, quantity, expiryDate, lotNumber, location, expiryHandled)
fun ShoppingItem.entity() = ShoppingEntity(medicineId, reasons.joinToString(",") { it.name },
    suggestedQuantity, requestedQuantity, signature, status.name, customQuantity)
fun ReminderSettings.entity() = SettingsEntity(expiryLeadDays = expiryLeadDays, enabled = enabled)

