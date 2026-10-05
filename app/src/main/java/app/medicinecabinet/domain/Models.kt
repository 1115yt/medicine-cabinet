package app.medicinecabinet.domain

import kotlinx.serialization.Serializable

@Serializable
data class Medicine(
    val id: String,
    val name: String,
    val specification: String = "",
    val barcode: String = "",
    val packageUnit: String = "盒",
    val lowStockThreshold: Int = 1,
    val expiryLeadDays: Int? = null,
)

@Serializable
data class StockBatch(
    val id: String,
    val medicineId: String,
    val quantity: Int,
    // 包装仅标注年月时保存 YYYY-MM；有具体日期时保存 YYYY-MM-DD。
    val expiryDate: String? = null,
    val lotNumber: String = "",
    val location: String = "",
    val expiryHandled: Boolean = false,
)

@Serializable
enum class AlertReason { EXPIRING, EXPIRED, LOW_STOCK }

@Serializable
enum class ShoppingStatus { PENDING, DISMISSED }

@Serializable
data class ShoppingItem(
    val medicineId: String,
    val reasons: List<AlertReason>,
    val suggestedQuantity: Int,
    val requestedQuantity: Int,
    val signature: String,
    val status: ShoppingStatus = ShoppingStatus.PENDING,
    val customQuantity: Boolean = false,
)

@Serializable
data class ReminderSettings(
    val expiryLeadDays: Int = 30,
    val enabled: Boolean = true,
)

data class AppSnapshot(
    val medicines: List<Medicine> = emptyList(),
    val batches: List<StockBatch> = emptyList(),
    val shoppingItems: List<ShoppingItem> = emptyList(),
    val settings: ReminderSettings = ReminderSettings(),
)

data class InventoryAlert(
    val medicineId: String,
    val reasons: List<AlertReason>,
    val suggestedQuantity: Int,
    val expiryBatchIds: Set<String>,
    val signature: String,
)

fun AlertReason.displayName(): String = when (this) {
    AlertReason.EXPIRING -> "即将到期"
    AlertReason.EXPIRED -> "已有过期批次"
    AlertReason.LOW_STOCK -> "库存不足"
}
