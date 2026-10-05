package app.medicinecabinet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.components.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(snapshot: AppSnapshot, today: LocalDate, showExpiryDays: Boolean, onScan: () -> Unit, onManual: () -> Unit,
    onShopping: () -> Unit, onCabinet: () -> Unit, onNearExpiry: () -> Unit = onCabinet, onExpired: () -> Unit = onCabinet) {
    val alerts = InventoryRules.evaluate(snapshot, today)
    // 状态计数与分类列表复用同一规则，已处理提醒不隐藏尚有库存的批次。
    val near = MedicineListing.select(snapshot, today, MedicineFilter.EXPIRING).size
    val expired = MedicineListing.select(snapshot, today, MedicineFilter.EXPIRED).size
    val low = alerts.count { AlertReason.LOW_STOCK in it.reasons }
    val unknown = snapshot.batches.count { it.quantity > 0 && it.expiryDate == null }
    val largeText = LocalDensity.current.fontScale >= 1.3f
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            PageHeading("家庭药箱", today.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA))) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("本机药箱", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("备药，有数", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("临期早整理，补货不遗漏", color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (!largeText) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) {
                            Icon(Icons.Outlined.MedicalServices, null, Modifier.padding(18.dp).size(36.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${snapshot.medicines.size}", style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("种药品已记入", Modifier.padding(bottom = 7.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    AdaptiveActions(primary = { modifier ->
                        Button(onClick = onScan, modifier = modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("扫码录入")
                        }
                    }, secondary = { modifier ->
                        OutlinedButton(onClick = onManual, modifier = modifier.heightIn(min = 48.dp)) { Text("手动录入") }
                    })
                }
            }
        }
        item {
            if (largeText) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("临期", near, Modifier.fillMaxWidth(), onNearExpiry, horizontal = true)
                StatCard("库存不足", low, Modifier.fillMaxWidth(), onShopping, horizontal = true)
                StatCard("过期", expired, Modifier.fillMaxWidth(), onExpired, horizontal = true)
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("临期", near, Modifier.weight(1f), onNearExpiry)
                StatCard("库存不足", low, Modifier.weight(1f), onShopping)
                StatCard("过期", expired, Modifier.weight(1f), onExpired)
            }
        }
        item {
            Text("默认临期：剩余 0～${snapshot.settings.expiryLeadDays} 天（含到期当天）。可在设置调整；药品单独设置优先。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (unknown > 0) item { InfoBanner("$unknown 个批次尚未确认有效期，请在药箱中补充。它们不计入确认可用库存。", true) }
        if (expired > 0) item { InfoBanner("$expired 种药品含有过期批次。补货后，旧批次也需在实际清理后记为零库存。",
            true, "查看过期批次", onExpired) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("需要留意", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onShopping) { Text("补货清单") }
            }
        }
        if (snapshot.medicines.isEmpty()) item {
            EmptyState(Icons.Outlined.Inventory2, "从第一盒药开始", "扫描药盒商品条码，核对名称和有效期。", "记入药箱", onScan)
        } else if (alerts.isEmpty()) item {
            EmptyState(Icons.Outlined.TaskAlt, "目前没有待补货提醒", "领用后更新数量，新买的药记得添加批次。")
        } else item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    alerts.take(4).forEach { alert ->
                        val medicine = snapshot.medicines.first { it.id == alert.medicineId }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(medicine.name, style = MaterialTheme.typography.titleMedium)
                            val allBatches = snapshot.batches.filter { it.medicineId == medicine.id }
                            val batches = if (alert.expiryBatchIds.isEmpty()) allBatches
                                else allBatches.filter { it.id in alert.expiryBatchIds }
                            ReasonBadges(alert.reasons, batches, today, showExpiryDays)
                            ExpirySummary(batches, today)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, number: Int, modifier: Modifier, onClick: () -> Unit, horizontal: Boolean = false) {
    Card(onClick = onClick, modifier = modifier.semantics { contentDescription = "查看${label}药品" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        if (horizontal) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$number", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$number", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
