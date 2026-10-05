package app.medicinecabinet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.components.*

@Composable
fun ShoppingScreen(snapshot: AppSnapshot, today: LocalDate, showExpiryDays: Boolean, busy: Boolean, onReplenish: (Medicine, ShoppingItem) -> Unit,
    onQuantity: (ShoppingItem, Int) -> Unit, onDismiss: (ShoppingItem) -> Unit,
    onReactivate: (ShoppingItem) -> Unit) {
    val pending = snapshot.shoppingItems.filter { it.status == ShoppingStatus.PENDING }
    val dismissed = snapshot.shoppingItems.count { it.status == ShoppingStatus.DISMISSED }
    val alerts = InventoryRules.evaluate(snapshot, today).associateBy { it.medicineId }
    var showDismissed by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("补货清单", "${pending.size} 种待补货 · 数量可调整") }
        if (pending.isEmpty()) item {
            EmptyState(Icons.Outlined.ShoppingBag, "暂时不用补货", "临期或库存不足的药品会自动出现在这里。")
        } else item { InfoBanner("买到新药后点“记入补货”，填写新批次的有效期，药箱和清单会一起更新。") }
        items(pending, key = { it.medicineId }) { item ->
            val medicine = snapshot.medicines.find { it.id == item.medicineId } ?: return@items
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(medicine.name, style = MaterialTheme.typography.titleLarge)
                    if (medicine.specification.isNotBlank()) Text(medicine.specification,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val allBatches = snapshot.batches.filter { it.medicineId == medicine.id }
                    val expiryIds = alerts[medicine.id]?.expiryBatchIds ?: emptySet()
                    val batches = if (expiryIds.isEmpty()) allBatches else allBatches.filter { it.id in expiryIds }
                    ReasonBadges(item.reasons, batches, today, showExpiryDays)
                    ExpirySummary(batches, today, showDays = showExpiryDays && item.reasons.all { it == AlertReason.LOW_STOCK })
                    QuantitySummary("建议补货", item.suggestedQuantity, medicine.packageUnit,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    QuantityControls(item.requestedQuantity, medicine.packageUnit, "${medicine.name}补货数量", !busy, 1) {
                        onQuantity(item, item.requestedQuantity + it)
                    }
                    AdaptiveActions(primary = { modifier ->
                        Button(onClick = { onReplenish(medicine, item) }, enabled = !busy, modifier = modifier.heightIn(min = 48.dp)) {
                            Text("记入补货")
                        }
                    }, secondary = { modifier ->
                        OutlinedButton(onClick = { onDismiss(item) }, enabled = !busy, modifier = modifier.heightIn(min = 48.dp)) { Text("忽略本次") }
                    })
                }
            }
        }
        if (dismissed > 0) item {
            TextButton(onClick = { showDismissed = !showDismissed }) {
                Text(if (showDismissed) "收起已忽略记录" else "查看已忽略的 $dismissed 种药品")
            }
            if (showDismissed) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.shoppingItems.filter { it.status == ShoppingStatus.DISMISSED }.forEach { item ->
                    val medicine = snapshot.medicines.find { it.id == item.medicineId }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(medicine?.name ?: "药品记录", Modifier.weight(1f))
                        TextButton(onClick = { onReactivate(item) }, enabled = !busy) { Text("重新提醒") }
                    }
                }
            }
        }
    }
}
