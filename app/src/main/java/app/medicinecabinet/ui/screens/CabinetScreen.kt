package app.medicinecabinet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.components.*
import java.time.LocalDate

@Composable
fun CabinetScreen(snapshot: AppSnapshot, today: LocalDate, showExpiryDays: Boolean, busy: Boolean, onAdd: (Medicine?) -> Unit,
    onEditMedicine: (Medicine) -> Unit, onEditBatch: (StockBatch) -> Unit,
    onAdjust: (StockBatch, Int) -> Unit, onClear: (StockBatch) -> Unit,
    onDeleteMedicine: (Medicine) -> Unit = {}, onDeleteBatch: (StockBatch) -> Unit = {},
    filter: MedicineFilter = MedicineFilter.ALL, sort: MedicineSort = MedicineSort.ADDED_FIRST,
    onSortChange: (MedicineSort) -> Unit = {}, onBack: () -> Unit = {}) {
    var query by rememberSaveable(filter) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val appliedSort = if (filter == MedicineFilter.ALL) sort else MedicineSort.EXPIRY_FIRST
    val batchesByMedicine = remember(snapshot.batches) {
        snapshot.batches.groupBy { it.medicineId }.mapValues { (_, batches) ->
            batches.sortedWith(compareBy<StockBatch> { InventoryRules.dateOf(it) ?: LocalDate.MAX }.thenBy { it.id })
        }
    }
    val visible = remember(snapshot, today, filter, appliedSort, query) {
        MedicineListing.select(snapshot, today, filter, appliedSort, query)
    }
    LaunchedEffect(filter, appliedSort, query) { listState.scrollToItem(0) }
    val alerts = remember(snapshot, today) { InventoryRules.evaluate(snapshot, today).associateBy { it.medicineId } }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (filter != MedicineFilter.ALL) item {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text("返回总览")
            }
        }
        item { PageHeading(filter.title, "${visible.size} 种药品 · ${if (filter == MedicineFilter.ALL) "按批次整理" else "查看对应的在库批次"}") }
        if (filter != MedicineFilter.ALL) item {
            Text(if (filter == MedicineFilter.EXPIRING)
                "默认剩余 0～${snapshot.settings.expiryLeadDays} 天，含到期当天；药品单独设置优先。已补货但仍有库存的临期批次也会显示。"
                else "仅显示仍有库存的过期批次，实际清理后记为零库存。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("搜索药名、规格或位置") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                shape = MaterialTheme.shapes.medium)
        }
        if (filter == MedicineFilter.ALL) item { MedicineSortControl(sort, onSortChange) }
        if (visible.isEmpty()) item {
            val emptyTitle = when {
                query.isNotBlank() -> "没有找到药品"
                filter == MedicineFilter.EXPIRING -> "目前没有临期药品"
                filter == MedicineFilter.EXPIRED -> "目前没有过期库存"
                else -> "药箱还是空的"
            }
            EmptyState(Icons.Outlined.Inventory2, emptyTitle,
                when {
                    query.isNotBlank() -> "换个药名、规格或位置试试。"
                    filter == MedicineFilter.EXPIRING -> "有库存的批次进入提醒范围后，会显示在这里。"
                    filter == MedicineFilter.EXPIRED -> "仍有库存的批次过期后，会显示在这里。"
                    else -> "把药盒信息记下来，下次补货更从容。"
                }, if (query.isBlank() && filter == MedicineFilter.ALL) "手动录入" else null, { onAdd(null) })
        }
        items(visible, key = { it.id }) { medicine ->
            MedicineCard(medicine, batchesByMedicine[medicine.id].orEmpty(), alerts[medicine.id],
                today, showExpiryDays, busy, { onAdd(medicine) }, { onEditMedicine(medicine) }, onEditBatch, onAdjust, onClear,
                { onDeleteMedicine(medicine) }, onDeleteBatch, filter, snapshot.settings)
        }
    }
}

@Composable
private fun MedicineCard(medicine: Medicine, batches: List<StockBatch>, alert: InventoryAlert?, today: LocalDate, showExpiryDays: Boolean,
    busy: Boolean, onAdd: () -> Unit, onEdit: () -> Unit, onEditBatch: (StockBatch) -> Unit,
    onAdjust: (StockBatch, Int) -> Unit, onClear: (StockBatch) -> Unit,
    onDelete: () -> Unit, onDeleteBatch: (StockBatch) -> Unit, filter: MedicineFilter, settings: ReminderSettings) {
    var expanded by rememberSaveable(medicine.id, filter) { mutableStateOf(filter != MedicineFilter.ALL) }
    val displayedBatches = MedicineListing.matchingBatches(medicine, batches, settings, today, filter)
    val usable = InventoryRules.usableQuantity(batches, today)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Outlined.Medication)
                Column(Modifier.weight(1f)) {
                    Text(medicine.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (medicine.specification.isNotBlank()) Text(medicine.specification,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Outlined.Edit, "编辑${medicine.name}资料") }
                DeleteMenu("更多${medicine.name}操作", "删除药品", busy, onDelete)
            }
            val reasons = when (filter) {
                MedicineFilter.EXPIRING -> listOf(AlertReason.EXPIRING)
                MedicineFilter.EXPIRED -> listOf(AlertReason.EXPIRED)
                MedicineFilter.ALL -> ((alert?.reasons ?: emptyList()) +
                    if (batches.any { it.quantity > 0 && InventoryRules.dateOf(it)?.isBefore(today) == true })
                        listOf(AlertReason.EXPIRED) else emptyList()).distinct()
            }
            if (reasons.isNotEmpty()) ReasonBadges(reasons, displayedBatches, today, showExpiryDays)
            ExpirySummary(displayedBatches, today, showDays = showExpiryDays && reasons.none { it != AlertReason.LOW_STOCK })
            QuantitySummary(if (filter == MedicineFilter.ALL) "确认可用" else "全部批次可用", usable, medicine.packageUnit, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            AdaptiveActions(primary = { modifier ->
                FilledTonalButton(onClick = onAdd, enabled = !busy, modifier = modifier.heightIn(min = 48.dp)) { Text("添加批次") }
            }, secondary = { modifier ->
                TextButton(onClick = { expanded = !expanded }, modifier = modifier.heightIn(min = 48.dp)) {
                    Text(if (expanded) "收起批次" else "查看 ${displayedBatches.size} 个批次")
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            })
            // 批次展开使用短淡入淡出，避免持续改变卡片高度而反复测量整个列表。
            AnimatedVisibility(expanded, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    displayedBatches.forEach { batch ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BatchRow(batch, medicine.packageUnit, today, showExpiryDays, busy, { onEditBatch(batch) },
                            { onAdjust(batch, it) }, { onClear(batch) }, { onDeleteBatch(batch) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchRow(batch: StockBatch, unit: String, today: LocalDate, showExpiryDays: Boolean, busy: Boolean,
    onEdit: () -> Unit, onAdjust: (Int) -> Unit, onClear: () -> Unit, onDelete: () -> Unit) {
    val date = InventoryRules.dateOf(batch)
    val expired = date?.isBefore(today) == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(batch.expiryDate?.let { "有效期至 ${ExpiryDates.display(it)}" } ?: "有效期未确认",
                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge)
                val status = when {
                    batch.quantity == 0 -> "已用完或已清理 · 历史记录"
                    expired -> "${if (showExpiryDays) ExpiryPresentation.remainingLabel(date!!, today) else "已过期"} · 不计入可用库存"
                    date == null -> "请补充日期 · 不计入可用库存"
                    batch.expiryHandled -> if (showExpiryDays) "${ExpiryPresentation.remainingLabel(date, today)} · 已处理临期提醒" else "已处理本批次的临期提醒"
                    else -> if (showExpiryDays) ExpiryPresentation.remainingLabel(date, today) else "尚未到期"
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Outlined.EditCalendar, "编辑批次${batch.expiryDate ?: "未知日期"}") }
            DeleteMenu("更多批次${batch.expiryDate ?: "未知日期"}操作", "删除批次", busy, onDelete)
        }
        if (batch.location.isNotBlank() || batch.lotNumber.isNotBlank()) {
            Text(listOfNotNull(batch.location.takeIf { it.isNotBlank() }, batch.lotNumber.takeIf { it.isNotBlank() }?.let { "批号 $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            QuantityControls(batch.quantity, unit, "本批次库存", !busy, onChange = onAdjust)
            if (expired && batch.quantity > 0) TextButton(onClick = onClear, enabled = !busy) { Text("已清理") }
        }
    }
}

@Composable
private fun DeleteMenu(description: String, label: String, busy: Boolean, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, description) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(label, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() }, enabled = !busy)
        }
    }
}
