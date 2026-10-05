package app.medicinecabinet.ui.forms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.medicinecabinet.domain.Medicine

@Composable
fun MedicineEditor(medicine: Medicine, busy: Boolean, onDismiss: () -> Unit, onSave: (Medicine) -> Unit) {
    var name by rememberSaveable(medicine.id) { mutableStateOf(medicine.name) }
    var specification by rememberSaveable(medicine.id) { mutableStateOf(medicine.specification) }
    var threshold by rememberSaveable(medicine.id) { mutableStateOf(medicine.lowStockThreshold.toString()) }
    var leadDays by rememberSaveable(medicine.id) { mutableStateOf(medicine.expiryLeadDays?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("药品资料") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InputField("药品名称", name, { name = it }, null)
                InputField("规格", specification, { specification = it }, null)
                Text("库存按${medicine.packageUnit}计数", style = MaterialTheme.typography.bodyMedium)
                InputField("最低库存", threshold, { threshold = it }, null, numeric = true)
                InputField("临期提醒天数（留空跟随默认）", leadDays, { leadDays = it }, null, numeric = true)
                Text("填写后仅对该药品生效；默认天数在“设置 → 整理提醒”中调整。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(onClick = {
                val minimum = threshold.toIntOrNull()
                val lead = leadDays.takeIf { it.isNotBlank() }?.toIntOrNull()
                error = when {
                    name.isBlank() || name.length > 80 -> "名称不能为空，最多 80 字"
                    specification.length > 120 -> "规格最多 120 字"
                    minimum !in 0..9999 -> "最低库存应为 0 至 9999"
                    leadDays.isNotBlank() && lead !in 1..365 -> "提醒天数应为 1 至 365"
                    else -> null
                }
                if (error == null) onSave(medicine.copy(name = name.trim(), specification = specification.trim(),
                    lowStockThreshold = minimum!!, expiryLeadDays = lead))
            }, enabled = !busy) { Text("保存资料") }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
