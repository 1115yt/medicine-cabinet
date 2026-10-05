package app.medicinecabinet.ui.forms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.components.InfoBanner
import app.medicinecabinet.ui.components.ApiResultCard
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class EditorRequest(
    val medicineId: String? = null,
    val batchId: String? = null,
    val barcode: String = "",
    val lotNumber: String = "",
    val expiryDate: String = "",
    val quantity: Int = 1,
    val replenish: Boolean = false,
    val fromScan: Boolean = false,
    val suggestedName: String = "",
    val suggestedSpecification: String = "",
    val suggestedUnit: String = "盒",
    val sourceNote: String = "",
    val apiAttempts: List<ApiAttempt> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditor(request: EditorRequest, snapshot: AppSnapshot, busy: Boolean, onClose: () -> Unit,
    onSave: (Medicine, StockBatch, Boolean) -> Unit) {
    BackHandler { if (!busy) onClose() }
    val medicine = snapshot.medicines.find { it.id == request.medicineId }
    val batch = snapshot.batches.find { it.id == request.batchId }
    val key = request.batchId ?: request.medicineId ?: request.barcode
    val medicineId = rememberSaveable(key) { medicine?.id ?: UUID.randomUUID().toString() }
    val batchId = rememberSaveable(key) { batch?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable(key) { mutableStateOf(medicine?.name ?: request.suggestedName) }
    var specification by rememberSaveable(key) { mutableStateOf(medicine?.specification ?: request.suggestedSpecification) }
    var unit by rememberSaveable(key) { mutableStateOf(medicine?.packageUnit ?: request.suggestedUnit) }
    var threshold by rememberSaveable(key) { mutableStateOf((medicine?.lowStockThreshold ?: 1).toString()) }
    var quantity by rememberSaveable(key) { mutableStateOf((batch?.quantity ?: request.quantity).toString()) }
    var expiry by rememberSaveable(key) { mutableStateOf(batch?.expiryDate ?: request.expiryDate) }
    var unknownDate by rememberSaveable(key) { mutableStateOf(batch != null && batch.expiryDate == null) }
    var lot by rememberSaveable(key) { mutableStateOf(batch?.lotNumber ?: request.lotNumber) }
    var location by rememberSaveable(key) { mutableStateOf(batch?.location ?: "") }
    var advanced by rememberSaveable(key) { mutableStateOf(lot.isNotEmpty() || location.isNotEmpty()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val title = when { batch != null -> "编辑批次"; request.replenish -> "记入补货"; medicine != null -> "添加批次"; else -> "记入药箱" }

    fun submit() {
        val problems = buildMap {
            if (name.isBlank() || name.length > 80) put("name", "填写药品名称，最多 80 字")
            if (specification.length > 120) put("specification", "规格最多 120 字")
            if (unit.isBlank() || unit.length > 8) put("unit", "填写包装单位，最多 8 字")
            if (threshold.toIntOrNull() !in 0..9999) put("threshold", "最低库存应为 0 至 9999")
            if (quantity.toIntOrNull() !in (if (batch != null) 0 else 1)..9999) put("quantity", "请填写合法的包装数量")
            if (!unknownDate && ExpiryDates.normalizeInput(expiry) == null) put("expiry", "请输入有效年月或日期，如 2028-06 或 2028-06-30")
            if (lot.length > 80) put("lot", "批号最多 80 字")
            if (location.length > 80) put("location", "位置最多 80 字")
        }
        errors = problems
        if (problems.isNotEmpty()) return
        val record = medicine ?: Medicine(medicineId, name.trim(), specification.trim(), request.barcode,
            unit.trim(), threshold.toInt())
        onSave(record, StockBatch(batchId, medicineId, quantity.toInt(),
            if (unknownDate) null else ExpiryDates.normalizeInput(expiry), lot.trim(), location.trim(), batch?.expiryHandled ?: false), request.replenish)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回药箱") }
        })
    }, bottomBar = {
        Surface(shadowElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding().imePadding().padding(20.dp, 12.dp)) {
                Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (busy) "正在保存…" else "保存到药箱")
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (request.fromScan) InfoBanner("条码已识别，请核对药品名称、规格和包装有效期后保存。")
            ApiResultCard(request.apiAttempts)
            if (request.sourceNote.isNotBlank()) Text(request.sourceNote, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (medicine != null) {
                Text(medicine.name, style = MaterialTheme.typography.headlineSmall)
                Text("${medicine.specification.ifBlank { "未填规格" }} · 按${medicine.packageUnit}计数",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                InputField("药品名称", name, { name = it }, errors["name"])
                InputField("规格（可选）", specification, { specification = it }, errors["specification"])
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InputField("包装单位", unit, { unit = it }, errors["unit"], Modifier.weight(1f))
                    InputField("最低库存", threshold, { threshold = it }, errors["threshold"], Modifier.weight(1f), true)
                }
                Text("最低库存为 0 时关闭低库存提醒。已有药品可从“我的药箱”添加批次。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("本批次信息", style = MaterialTheme.typography.titleMedium)
            InputField("数量（$unit）", quantity, { quantity = it }, errors["quantity"], numeric = true)
            DateField(expiry, { expiry = it; errors = errors - "expiry" }, !unknownDate, errors["expiry"])
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(unknownDate, role = Role.Checkbox,
                onValueChange = { unknownDate = it; errors = errors - "expiry" })) {
                Checkbox(checked = unknownDate, onCheckedChange = null)
                Text("有效期暂未确认", Modifier.padding(start = 12.dp, top = 12.dp))
            }
            if (unknownDate) InfoBanner("此批次会提示补充日期，并暂不计入确认可用库存。", true)
            else if (ExpiryDates.normalizeInput(expiry)?.let(ExpiryDates::endDate)?.isBefore(LocalDate.now()) == true) {
                InfoBanner("此日期已过，将记录为过期库存。", true)
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text("批号和存放位置（可选）")
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (advanced) {
                InputField("批号", lot, { lot = it }, errors["lot"])
                InputField("存放位置", location, { location = it }, errors["location"])
            }
            if (request.barcode.isNotBlank() || medicine?.barcode?.isNotBlank() == true) {
                Text("条码：${medicine?.barcode ?: request.barcode}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun InputField(label: String, value: String, onChange: (String) -> Unit, error: String?,
    modifier: Modifier = Modifier, numeric: Boolean = false) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = if (error == null) null else ({ Text(error) }),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        shape = MaterialTheme.shapes.medium)
}
